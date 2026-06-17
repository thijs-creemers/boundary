(ns boundary.mcp.shell.tools-test
  (:require [boundary.ai.ports :as ai]
            [boundary.mcp.shell.system-source :as system-source]
            [boundary.mcp.shell.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(def ^:private snapshot
  {:module-graph {:modules [{:name "user" :deps ["core"] :has-ports? true :external-libs []}
                            {:name "core" :deps [] :has-ports? true :external-libs []}]
                  :edges   [["user" "core"]]}})

(defn- deps
  ([] (deps nil))
  ([provider]
   {:system-source (system-source/static-system-source snapshot)
    :ai-provider   provider}))

(deftest ^:unit validate-schema-valid-and-invalid
  (testing "valid value"
    (is (= {:valid? true}
           (tools/run (deps) "validate-schema"
                      {:schema "[:map [:name :string]]" :value {:name "Ada"}}))))
  (testing "invalid value yields humanized errors"
    (let [r (tools/run (deps) "validate-schema"
                       {:schema "[:map [:name :string]]" :value {:name 42}})]
      (is (false? (:valid? r)))
      (is (contains? (:errors r) :name)))))

(deftest ^:unit explain-error-enriches-bnd-code
  (testing "text naming a BND code is enriched from the catalog"
    (let [r (tools/run (deps) "explain-error"
                       {:error "Boot failed: BND-101 something about an env var"})]
      (is (= "BND-101" (:code r)))
      (is (string? (:rule r)))
      (is (string? (:fix r)))))
  (testing "plain error text still summarises"
    (let [r (tools/run (deps) "explain-error" {:error "NullPointerException at foo"})]
      (is (string? (:summary r)))
      (is (not (contains? r :code))))))

(deftest ^:unit describe-module-from-snapshot
  (let [r (tools/run (deps) "describe-module" {:module "user"})]
    (is (= "user" (:name r)))
    (is (= ["core"] (:deps r)))
    (is (true? (:has-ports? r))))
  (testing "unknown module lists what is available"
    (let [r (tools/run (deps) "describe-module" {:module "ghost"})]
      (is (= :not-found (:status r)))
      (is (= ["user" "core"] (:available r))))))

(deftest ^:unit sql-preview-uses-provider-or-reports-unavailable
  (testing "no provider → graceful :unavailable"
    (is (= :unavailable (:status (tools/run (deps) "sql-preview" {:query "all users"})))))
  (testing "provider response is parsed via ai.core.parsing"
    (let [stub (reify ai/IAIProvider
                 (complete [_ _ _]
                   {:text "{\"honeysql\":\"{:select [:*] :from [:users]}\",\"raw-sql\":\"SELECT * FROM users\",\"explanation\":\"all users\"}"})
                 (complete-json [_ _ _ _] {:data {}})
                 (provider-name [_] :stub))
          r    (tools/run (deps stub) "sql-preview" {:query "all users"})]
      (is (= "SELECT * FROM users" (:raw-sql r)))
      (is (re-find #":users" (:honeysql r))))))

(deftest ^:unit lint-returns-structured-findings
  (let [f (java.io.File/createTempFile "mcp-lint" ".clj")]
    (try
      (spit f "(ns t) (defn f [] (let [x 1] 2))")
      (let [r (tools/run (deps) "lint" {:paths [(.getAbsolutePath f)]})]
        (is (map? (:summary r)))
        (is (vector? (:findings r)))
        ;; unused binding x is an expected finding
        (is (some #(re-find #"unused" (str (:message %))) (:findings r))))
      (finally (.delete f)))))

(deftest ^:unit unknown-tool-is-nil
  (is (nil? (tools/run (deps) "nope" {}))))
