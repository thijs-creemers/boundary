(ns boundary.devtools.shell.auto-fix-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [boundary.devtools.shell.auto-fix :as executor]))

(deftest ^:integration execute-safe-fix-test
  (testing "safe fix executes without confirmation at :full guidance"
    (let [fix {:fix-id :set-env-var
               :label "Set DATABASE_URL"
               :safe? true
               :action :set-env
               :params {:var-name "TEST_AUTO_FIX_VAR" :value "test-value"}}
          output (with-out-str
                   (executor/execute-fix! fix
                                          {:guidance-level :full
                                           :confirm-fn (fn [_] (throw (ex-info "should not confirm" {})))}))]
      (is (str/includes? output "Applying"))))

  (testing "safe fix executes silently at :minimal guidance"
    (let [fix {:fix-id :set-env-var
               :label "Set var"
               :safe? true
               :action :set-env
               :params {:var-name "TEST_AUTO_FIX_SILENT" :value "silent"}}
          output (with-out-str
                   (executor/execute-fix! fix {:guidance-level :minimal}))]
      (is (= "" output)))))

(deftest ^:integration execute-risky-fix-requires-confirmation-test
  (testing "risky fix requires confirmation even at :minimal"
    (let [confirmed? (atom false)
          fix {:fix-id :refactor-fcis
               :label "Show refactoring"
               :safe? false
               :action :show-refactoring
               :params {}}]
      (with-out-str
        (executor/execute-fix! fix
                               {:guidance-level :minimal
                                :confirm-fn (fn [_] (reset! confirmed? true) true)}))
      (is (true? @confirmed?))))

  (testing "risky fix aborted when user declines"
    (let [fix {:fix-id :refactor-fcis
               :label "Show refactoring"
               :safe? false
               :action :show-refactoring
               :params {}}
          output (with-out-str
                   (executor/execute-fix! fix
                                          {:guidance-level :full
                                           :confirm-fn (fn [_] false)}))]
      (is (str/includes? output "Aborted")))))
