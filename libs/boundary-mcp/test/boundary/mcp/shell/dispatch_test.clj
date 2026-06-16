(ns boundary.mcp.shell.dispatch-test
  (:require [boundary.mcp.core.registry :as registry]
            [boundary.mcp.core.resources :as resources]
            [boundary.mcp.core.security :as security]
            [boundary.mcp.shell.audit :as audit]
            [boundary.mcp.shell.codec :as codec]
            [boundary.mcp.shell.dispatch :as dispatch]
            [boundary.mcp.shell.system-source :as system-source]
            [clojure.test :refer [deftest is testing]]))

(def ^:private snapshot
  {:conventions {:fc-is {:rules ["core pure"]}}})

(defn- deps
  ([] (deps (security/resolve-context {"BND_ENV" "dev"})))
  ([ctx]
   {:registry      (reduce registry/register-resource registry/empty-registry resources/catalog)
    :security      ctx
    :audit         (audit/in-memory-audit-log)
    :system-source (system-source/static-system-source snapshot)}))

(deftest ^:unit resources-list-advertises-catalog
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 1 :method "resources/list"})]
    (is (= 7 (count (get-in resp [:result :resources]))))
    (is (some #(= "boundary://conventions" (:uri %)) (get-in resp [:result :resources])))))

(deftest ^:unit resources-read-returns-json-content
  (let [d    (deps)
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 2 :method "resources/read"
                                   :params {:uri "boundary://conventions"}})
        content (first (get-in resp [:result :contents]))]
    (is (= "boundary://conventions" (:uri content)))
    (is (= "application/json" (:mimeType content)))
    ;; the content text is the JSON-encoded resource data (the conventions value)
    (is (= (:conventions snapshot) (codec/decode (:text content))))
    (testing "a successful read is audited"
      (is (some #(= :resource-read (:event %)) (audit/events (:audit d)))))))

(deftest ^:unit resources-read-unknown-uri-is-invalid-params
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 3 :method "resources/read"
                                        :params {:uri "boundary://nope"}})]
    (is (= -32602 (get-in resp [:error :code])))))

(deftest ^:unit resources-read-denied-in-disabled-context-yields-guardrail
  (let [d    (deps (security/resolve-context {"MCP_CAPABILITY_MODE" "disabled"}))
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 4 :method "resources/read"
                                   :params {:uri "boundary://conventions"}})]
    (is (= -32001 (get-in resp [:error :code])))            ;; :forbidden
    (is (= "BND-801" (get-in resp [:error :data :code])))   ;; capabilities disabled
    (testing "the denial is audited"
      (is (some #(= :resource-read-denied (:event %)) (audit/events (:audit d)))))))

(deftest ^:unit dispatch-delegates-pure-methods-to-core
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 5 :method "ping"})]
    (is (= {} (:result resp)))))
