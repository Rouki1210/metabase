(ns metabase.metabot.tools.feature-store-test
  "Tests that `query_feature_store` maps each of the external service's three answer shapes onto
   `:structured-output`, and — critically — that failures do *not* get one, since the agent loop reads
   its presence as the success signal."
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.metabot.tools.feature-store :as feature-store]
   [metabase.test :as mt]))

(defn- with-agent-response
  "Run `thunk` with the feature store service stubbed to return `body`, and return
  `[result requests]` where `requests` are the POSTs that were actually issued."
  [body thunk]
  (let [requests (atom [])]
    (with-redefs [http/post (fn [url opts]
                              (swap! requests conj [url opts])
                              (if (instance? Throwable body)
                                (throw body)
                                {:body body}))]
      [(thunk) @requests])))

(defmacro ^:private with-configured-store
  "Bind the three feature-store settings around `body`, with `db-id` as the configured database."
  [db-id & body]
  `(mt/with-temporary-setting-values [feature-store-agent-url        "http://feature-agent:8000"
                                      feature-store-database-id      ~db-id
                                      feature-store-agent-timeout-ms 30000]
     ~@body))

(deftest stage-stats-test
  (testing "per-stage cost is forwarded field-by-field, and prompt/SQL payloads are never logged"
    (is (= {"retriever" {:duration_ms 12 :tokens_in 1840 :tokens_out 90}
            "generator" {:duration_ms 2180 :tokens_out 310}
            "validator" {:duration_ms 4}}
           (#'feature-store/stage-stats
            [{:stage "retriever" :duration_ms 12 :tokens_in 1840 :tokens_out 90
              :retrieved_features ["gsm_spend_90d"]}
             ;; a stage may report only some of the fields
             {:stage "generator" :duration_ms 2180 :tokens_out 310 :sql "SELECT 1"}
             {:stage "validator" :duration_ms 4}
             ;; nothing worth logging — dropped rather than logged as an empty map
             {:stage "router"}])))))

(deftest query-result-test
  (testing "a SQL-bearing response produces a card plus a verbatim-relay instruction"
    (mt/test-drivers #{:h2}
      (mt/with-current-user (mt/user->id :crowberto)
        (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
          (with-configured-store db-id
            (let [[result requests]
                  (with-agent-response
                    {:status         "ok"
                     :answer_vi      "Tổng chi tiêu GSM 3 tháng gần nhất: 4.271.000.000 VNĐ"
                     :sql            "SELECT 1"
                     :title          "Chi tiêu GSM"
                     :coverage       0.87
                     :pipeline_trace [{:stage "retriever" :duration_ms 1}
                                      {:stage "generator" :duration_ms 2180}]}
                    #(feature-store/query-feature-store-tool
                      {:question "Tổng chi tiêu GSM 3 tháng gần nhất"}))]
              (testing "the question is forwarded verbatim"
                (is (= 1 (count requests)))
                (is (= "http://feature-agent:8000/ask" (ffirst requests)))
                (is (= "Tổng chi tiêu GSM 3 tháng gần nhất"
                       (get-in (second (first requests)) [:form-params :question]))))
              (testing "structured-output marks the call successful"
                (is (= :query (get-in result [:structured-output :result-type])))
                (is (some? (get-in result [:structured-output :query-id]))))
              (testing "a card is emitted for the frontend"
                (is (= 1 (count (:data-parts result))))
                (is (= "Chi tiêu GSM" (get-in result [:data-parts 0 :data :title]))))
              (testing "the answer is relayed and coverage is rendered as a percentage"
                (is (str/includes? (:output result) "4.271.000.000 VNĐ"))
                (is (str/includes? (:output result) "Coverage: 87%"))
                (is (str/includes? (:output result) "VERBATIM"))))))))))

(deftest clarification-test
  (testing "a clarifying question ends in structured-output and emits no card"
    (mt/with-current-user (mt/user->id :crowberto)
      (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
        (with-configured-store db-id
          (let [[result _] (with-agent-response
                             {:status                "clarify"
                              :clarifying_question   "Bạn muốn xem GSM hay VinFast?"
                              :clarification_options ["GSM" "VinFast"]
                              :missing_slots         ["business_unit"]}
                             #(feature-store/query-feature-store-tool {:question "Tổng chi tiêu"}))]
            (is (= :clarification (get-in result [:structured-output :result-type])))
            (is (= ["GSM" "VinFast"] (get-in result [:structured-output :options])))
            (is (= ["business_unit"] (get-in result [:structured-output :missing-slots])))
            (is (empty? (:data-parts result)))
            (is (str/includes? (:output result) "Bạn muốn xem GSM hay VinFast?"))
            (testing "the model is told not to guess the missing slot"
              (is (str/includes? (:output result) "Do NOT guess")))))))))

(deftest refusal-test
  (testing "a refusal ends in structured-output and blocks the read_resource workaround"
    (mt/with-current-user (mt/user->id :crowberto)
      (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
        (with-configured-store db-id
          (let [[result _] (with-agent-response
                             {:status       "out_of_scope"
                              :answer_vi    "Dữ liệu PII nằm ngoài phạm vi truy cập."
                              :refusal_code "pii_out_of_scope"}
                             #(feature-store/query-feature-store-tool
                               {:question "Số điện thoại của khách chi nhiều nhất"}))]
            (is (= :refusal (get-in result [:structured-output :result-type])))
            (is (= "pii_out_of_scope" (get-in result [:structured-output :refusal-code])))
            (is (empty? (:data-parts result)))
            (is (str/includes? (:output result) "read_resource"))))))))

(deftest service-failure-has-no-structured-output-test
  (testing "an unreachable service returns only :output, so the agent loop sees a failed call"
    (mt/with-current-user (mt/user->id :crowberto)
      (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
        (with-configured-store db-id
          (let [[result _] (with-agent-response
                             (ex-info "Connection refused" {})
                             #(feature-store/query-feature-store-tool {:question "Tổng chi tiêu GSM"}))]
            (is (nil? (:structured-output result)))
            (is (nil? (:data-parts result)))
            (is (str/includes? (:output result) "Connection refused"))))))))

(deftest pipeline-error-has-no-structured-output-test
  (testing "a pipeline error is surfaced with its message and is not a successful call"
    (mt/with-current-user (mt/user->id :crowberto)
      (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
        (with-configured-store db-id
          (let [[result _] (with-agent-response
                             {:status "error"
                              :error  "SQL generation failed: LLM_API_KEY chưa được cấu hình."}
                             #(feature-store/query-feature-store-tool {:question "Tổng chi tiêu GSM"}))]
            (is (nil? (:structured-output result)))
            (testing "the service's own error text reaches the model rather than a generic message"
              (is (str/includes? (:output result) "LLM_API_KEY")))))))))

(deftest unusable-response-has-no-structured-output-test
  (testing "a response with no sql, question, refusal code, or error is a failure, not an answer"
    (mt/with-current-user (mt/user->id :crowberto)
      (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
        (with-configured-store db-id
          (let [[result _] (with-agent-response
                             {:status "ok"}
                             #(feature-store/query-feature-store-tool {:question "Tổng chi tiêu GSM"}))]
            (is (nil? (:structured-output result)))))))))

(deftest permission-check-precedes-request-test
  (testing "a user who cannot read the database never has their question sent to the external service"
    (mt/with-temp [:model/Database {db-id :id} {:engine :h2}]
      (with-configured-store db-id
        (mt/with-no-data-perms-for-all-users!
          (mt/with-current-user (mt/user->id :rasta)
            (let [[thrown requests]
                  (with-agent-response
                    {:answer "leaked" :sql "SELECT 1"}
                    #(is (thrown? clojure.lang.ExceptionInfo
                                  (feature-store/query-feature-store-tool
                                   {:question "Tổng chi tiêu GSM"}))))]
              (is (some? thrown))
              (is (empty? requests) "the question must not leave Metabase"))))))))

(deftest unconfigured-test
  (testing "an unconfigured tool names the missing setting instead of calling anything"
    (mt/with-current-user (mt/user->id :crowberto)
      (mt/with-temporary-setting-values [feature-store-agent-url   nil
                                         feature-store-database-id nil]
        (let [[result requests]
              (with-agent-response
                {:answer "x"}
                #(feature-store/query-feature-store-tool {:question "Tổng chi tiêu GSM"}))]
          (is (empty? requests))
          (is (nil? (:structured-output result)))
          (is (str/includes? (:output result) "feature-store-database-id")))))))
