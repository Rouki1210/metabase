(ns metabase.metabot.tools.feature-store
  "The `query_feature_store` tool: delegates a business question to an external Feature Store Query
  Agent service.

  That service owns the whole decision pipeline — routing, semantic retrieval over an approved
  feature catalog, join planning, SQL generation, AST-level validation, execution under a restricted
  role, and audit logging. Metabase deliberately does none of it: this tool is a thin, permission-gated
  bridge that hands the question over verbatim and renders whatever comes back.

  The service answers in exactly one of three shapes — a result (SQL + rows), a clarifying question, or
  a refusal with a code — and this tool maps each onto `:structured-output`. That matters beyond
  bookkeeping: [[metabase.metabot.agent.core]] treats the *presence* of `:structured-output` as the
  success signal for a tool call, so infrastructure failures (service down, timeout) must return only
  `:output`, leaving the model free to retry or explain."
  (:require
   [clj-http.client :as http]
   [metabase.api.common :as api]
   [metabase.metabot.agent.streaming :as streaming]
   [metabase.metabot.scope :as scope]
   [metabase.metabot.settings :as metabot.settings]
   [metabase.metabot.tmpl :as te]
   [metabase.metabot.tools.shared :as shared]
   [metabase.metabot.tools.sql.create :as create-sql-query-tools]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.o11y :refer [with-span]]))

(set! *warn-on-reflection* true)

(def ^:private answer-instructions
  (te/lines
   "Relay the <result> content to the user VERBATIM."
   "Do NOT rephrase it, do NOT add or recompute any numbers, do NOT paste SQL."
   "The rows are already rendered as an interactive card — just point the user at it."))

(def ^:private clarification-instructions
  (te/lines
   "Ask the user this exact question and stop."
   "Do NOT guess the missing detail and do NOT try to answer around it with another tool."))

(def ^:private refusal-instructions
  (te/lines
   "Tell the user this request is out of scope and why."
   "Do NOT attempt the request another way — `read_resource` is not a workaround for a refusal."))

(def ^:private stage-cost-keys
  "The `pipeline_trace` fields worth logging. An allowlist rather than \"everything but `:stage`\":
  stages also carry the retrieved features and the generated SQL, and spans reach the logs."
  [:duration_ms :tokens_in :tokens_out])

(defn- stage-stats
  "Per-stage cost pulled out of the service's `pipeline_trace`, as {stage-name {field value}}.
  The service already times every stage for its own audit log, so the 9-stage latency breakdown is
  free; `tokens_in`/`tokens_out` are the same idea for spend, and cost the service one field per stage
  since it has the numbers from its own LLM calls anyway. Fields it omits are simply absent, and a
  stage reporting none of them is dropped."
  [pipeline-trace]
  (into {} (keep (fn [{:keys [stage] :as s}]
                   (let [cost (select-keys s stage-cost-keys)]
                     (when (seq cost) [stage cost]))))
        pipeline-trace))

(defn- config-error
  "Message naming the missing setting, or nil when the tool is fully configured."
  [url db-id]
  (cond
    (nil? db-id)     "The feature store is not configured: `feature-store-database-id` is unset."
    (empty? url)     "The feature store is not configured: `feature-store-agent-url` is unset."))

(defn- call-agent!
  "POST the question to the Feature Store Query Agent and return its parsed body.

  Wrapped in its own span so the external round-trip is measurable separately from the surrounding
  tool call (which also covers building the card), and so the service's own per-stage `timings` land
  next to the wall-clock duration.

  `outcome` must be a `promise`, not a `delay`: [[metabase.util.o11y/with-span]] realizes derefables
  with the three-arity `deref`, which needs an `IBlockingDeref`. When the request throws, the promise
  is simply never delivered and the span is dropped along with the failed call."
  [url question session-id]
  (let [outcome (promise)
        timeout (metabot.settings/feature-store-agent-timeout-ms)]
    (with-span :info {:name            :metabot.feature-store/request
                      ;; The question is user data and spans reach the logs — length only.
                      :question-length (count question)
                      :outcome         outcome}
      (let [body (:body (http/post (str url "/ask")
                                   {:form-params        {:question   question
                                                         :session_id session-id}
                                    :content-type       :json
                                    :as                 :json
                                    :socket-timeout     timeout
                                    :connection-timeout timeout}))]
        ;; One string attribute rather than a nested map: span attributes are only reliably primitives,
        ;; and this lands in the log line either way. `stages` is the service's own per-stage
        ;; latency + token breakdown — where the turn's real spend is, since Metabase's own two LLM
        ;; calls never see the rows or the SQL.
        (deliver outcome (pr-str {:status       (:status body)
                                  :refusal-code (:refusal_code body)
                                  :has-sql      (boolean (:sql body))
                                  :coverage     (:coverage body)
                                  :repairs      (:repairs body)
                                  :error        (:error body)
                                  :stages       (stage-stats (:pipeline_trace body))}))
        body))))

(defn- refusal-result
  [{:keys [answer_vi refusal_code]}]
  {:output            (te/lines "<result>"
                                (not-empty answer_vi)
                                (str "refusal_code: " refusal_code)
                                "</result>"
                                "<instructions>" refusal-instructions "</instructions>")
   :structured-output {:result-type  :refusal
                       :refusal-code refusal_code}})

(defn- clarification-result
  [{:keys [clarifying_question clarification_options missing_slots]}]
  {:output            (te/lines "<result>"
                                clarifying_question
                                (when (seq clarification_options)
                                  (te/lines "Options:" (map #(str "- " %) clarification_options)))
                                "</result>"
                                "<instructions>" clarification-instructions "</instructions>")
   :structured-output {:result-type   :clarification
                       :question      clarifying_question
                       :options       (vec clarification_options)
                       ;; Which slot the service is missing — useful for evaluating clarification
                       ;; quality later, and free to carry here.
                       :missing-slots (vec missing_slots)}})

(defn- coverage-percent
  "The service reports coverage as a 0..1 ratio; render it as a percentage for display."
  [coverage]
  (when (number? coverage)
    (Math/round (* 100.0 (double coverage)))))

(defn- query-result
  "Turn a SQL-bearing response into a card. Reuses [[create-sql-query-tools/create-sql-query]], which
  re-checks database access, validates the SQL against the database's dialect, and builds the legacy
  MBQL native query the frontend needs — so a query the service considered fine but Metabase cannot
  run comes back as a failure (no `:structured-output`) rather than a broken card."
  [db-id {:keys [answer_vi sql title coverage]}]
  (let [{:keys [validation-result action-result]}
        (create-sql-query-tools/create-sql-query {:database-id db-id :sql sql})
        {:keys [query-id query]} action-result
        pct (coverage-percent coverage)]
    (if (:valid? validation-result)
      {:output            (te/lines "<result>"
                                    answer_vi
                                    (when pct (str "Coverage: " pct "%"))
                                    "</result>"
                                    "<instructions>" answer-instructions "</instructions>")
       :structured-output {:result-type :query
                           :query-id    query-id
                           :query       query}
       :data-parts        [(streaming/viz-part
                            {:entity-id   (str (random-uuid))
                             :query-id    query-id
                             :query       query
                             :title       (or title "Feature store query")
                             :description (when pct (str "Coverage " pct "%"))})]}
      {:output (str "The feature store returned SQL that Metabase could not validate: "
                    (:error-message validation-result))})))

(defn- handle-response
  "Map the service's response onto a tool result.

  A refusal and a clarification are *answers*, so they're checked before `error` — those come back
  with `status` set to something other than success but are exactly what the pipeline is supposed to
  produce. Only a genuine pipeline failure (`error`) falls through to the no-`:structured-output`
  path, which is what lets the agent loop tell the model the call failed."
  [db-id {:keys [refusal_code clarifying_question sql error] :as body}]
  (cond
    refusal_code        (refusal-result body)
    clarifying_question (clarification-result body)
    sql                 (query-result db-id body)
    error               {:output (str "The feature store could not answer: " error)}
    :else               {:output (str "The feature store returned no SQL, question, refusal code, or "
                                      "error. Tell the user the service gave an unusable response.")}))

(mu/defn ^{:tool-name "query_feature_store"
           :scope     scope/agent-feature-store-query}
  query-feature-store-tool
  "Answer a business question about GSM / VinFast customers from the approved feature store.
  Pass the user's question through VERBATIM — do not rewrite it, translate it, or turn it into SQL
  yourself. The feature store decides whether it can answer, asks for clarification when the question
  is ambiguous, and refuses when the data is out of scope."
  [{:keys [question]} :- [:map {:closed true}
                          [:question [:string {:description "The user's question, verbatim, in their own language"}]]]]
  (let [url   (metabot.settings/feature-store-agent-url)
        db-id (metabot.settings/feature-store-database-id)]
    (if-let [err (config-error url db-id)]
      (do (log/warn "query_feature_store called but not configured")
          {:output err})
      ;; Permission-check before the request, and outside the try below: the user's question leaves
      ;; Metabase in the request body, so someone who cannot read the database must not be able to send
      ;; anything to the external service at all. Left to throw, like `create-sql-query` does — a
      ;; permission failure is not something the model should see dressed up as a service outage.
      (do (api/read-check :model/Database db-id)
          (try
            (handle-response db-id (call-agent! url question (shared/current-conversation-id)))
            (catch Exception e
              (log/warn "Feature store agent call failed" {:error (ex-message e)})
              ;; No `:structured-output` — the agent loop reads that as a failed call, so the model can
              ;; retry or tell the user the service is unavailable.
              {:output (str "Failed to reach the feature store: " (or (ex-message e) "Unknown error"))}))))))
