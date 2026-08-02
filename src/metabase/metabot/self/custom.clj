(ns metabase.metabot.self.custom
  "Adapter for a custom, admin-configured OpenAI-compatible Chat Completions endpoint.

  Unlike the named providers, everything here is admin input: the API key and base URL come from
  the `llm-custom-*` settings and the model is free text, so nothing is validated against a
  whitelist. The base URL is used verbatim with `/chat/completions` (and `/models`) appended, so
  it has to carry whatever version segment the endpoint expects, e.g. `https://api.deepseek.com/v1`.

  The wire dialect is the same generic Chat Completions one the Z.AI and OpenRouter adapters
  speak (see [[metabase.metabot.self.openai.chat-completions]])."
  (:require
   [metabase.llm.settings :as llm]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.debug :as debug]
   [metabase.metabot.self.openai.chat-completions :as chat-completions]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.o11y :refer [with-span]]))

(set! *warn-on-reflection* true)

(defn- ai-proxy-unsupported-ex []
  (ex-info (tru "AI proxy is not supported for custom providers")
           {:api-error  true
            :error-code :proxy-unsupported}))

(defn- custom-error-msg
  "Canonical, status-specific error message for a custom endpoint."
  [res]
  (let [status (long (:status res 0))]
    (case status
      401 (tru "The custom endpoint rejected the API key")
      403 (tru "The custom endpoint's API key lacks permission for this model")
      404 (tru "The custom endpoint was not found — check the base URL")
      429 (tru "The custom endpoint has rate limited us")
      500 (tru "The custom endpoint returned an internal server error")
      (tru "Custom endpoint API error (HTTP {0})" status))))

(defn- custom-auth
  "Auth map for the custom endpoint: `credentials` (`{:api-key ... :base-url ...}`) when given,
  the `llm-custom-*` settings otherwise. Throws when either half is missing.
  `ai-proxy?` is accepted for parity with the other adapters but is not supported."
  [credentials ai-proxy?]
  (when ai-proxy?
    (throw (ai-proxy-unsupported-ex)))
  (let [api-key  (or (not-empty (:api-key credentials))
                     (not-empty (llm/llm-custom-api-key)))
        base-url (or (llm/normalize-llm-base-url (:base-url credentials))
                     (not-empty (llm/llm-custom-api-base-url)))]
    (core/resolve-auth "custom" "custom endpoint"
                       (when (and api-key base-url)
                         {:url     base-url
                          :headers {"Authorization" (str "Bearer " api-key)}})
                       ai-proxy?)))

(defn list-models
  "List the models the custom endpoint advertises (`GET /models`).

  The model itself is free text — this listing only populates the picker and doubles as the
  credential round-trip behind the admin Connect button.
  `:ai-proxy?` is not supported for custom providers and throws when true."
  ;; ponytail: `/models` is the only credential check we have, so an OpenAI-compatible endpoint
  ;; that doesn't implement it can't be connected. If those show up, fall back to a model-free
  ;; validation like `azure/list-models` does.
  ([] (list-models {}))
  ([{:keys [credentials ai-proxy?]}]
   (try
     (let [res (core/request (custom-auth credentials ai-proxy?)
                             {:method  :get
                              :url     "/models"
                              :as      :json
                              :headers {"Content-Type" "application/json"}})]
       {:models (->> (get-in res [:body :data])
                     (keep :id)
                     sort
                     (mapv (fn [id] {:id id :display_name id})))})
     (catch Exception e
       (core/rethrow-api-error! "custom" custom-error-msg e)))))

(mu/defn custom-raw
  "Perform a streaming request to the custom endpoint's Chat Completions API.
  `:ai-proxy?` is not supported for custom providers and throws when true."
  [{:keys [model tools ai-proxy?] :as opts} :- core/LLMRequestOpts]
  (let [req (chat-completions/request-body opts)]
    (log/debug "Custom endpoint request" {:model model :msg-count (count (:messages req)) :tools (count (or tools []))})
    (with-span :info {:name       :metabot.custom/request
                      :model      model
                      :msg-count  (count (:messages req))
                      :tool-count (count (or tools []))}
      (try
        (let [response (core/request (custom-auth nil ai-proxy?)
                                     {:method  :post
                                      :url     "/chat/completions"
                                      :as      :stream
                                      :headers {"Content-Type" "application/json"}
                                      :body    (json/encode req)})]
          (-> (core/sse-reducible (:body response))
              (debug/capture-stream {:provider "custom"
                                     :model    model
                                     :url      "/chat/completions"
                                     :request  req})))
        (catch Exception e
          (core/rethrow-api-error! "custom" custom-error-msg e))))))

(defn custom->aisdk-chunks-xf
  "Translates the custom endpoint's Chat Completions streaming chunks into AI SDK v5 protocol chunks."
  []
  (chat-completions/chat-completions->aisdk-chunks-xf))

(defn custom
  "Call a custom OpenAI-compatible Chat Completions API, return AISDK stream."
  [& args]
  (let [raw (apply custom-raw args)]
    (eduction (custom->aisdk-chunks-xf) raw)))
