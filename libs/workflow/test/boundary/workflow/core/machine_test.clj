(ns boundary.workflow.core.machine-test
  "Unit tests for the workflow definition registry and defworkflow macro."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.workflow.core.machine :as machine]))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(defn with-clean-registry [f]
  (machine/clear-registry!)
  (f)
  (machine/clear-registry!))

(use-fixtures :each with-clean-registry)

;; =============================================================================
;; Sample workflow definitions
;; =============================================================================

(def ^:private order-def
  {:id             :order-workflow
   :initial-state  :pending
   :description    "Order lifecycle"
   :states         #{:pending :paid :shipped :delivered :cancelled}
   :transitions    [{:from :pending :to :paid
                     :required-permissions [:finance :admin]}
                    {:from :paid    :to :shipped}
                    {:from :shipped :to :delivered}
                    {:from :pending :to :cancelled}
                    {:from :paid    :to :cancelled}]})

;; =============================================================================
;; register-workflow! / get-workflow / list-workflows
;; =============================================================================

(deftest ^:unit register-and-retrieve-test
  (testing "registers a valid workflow and retrieves it by id"
    (machine/register-workflow! order-def)
    (is (= order-def (machine/get-workflow :order-workflow))))

  (testing "returns nil for unknown workflow"
    (is (nil? (machine/get-workflow :non-existent))))

  (testing "list-workflows returns registered ids"
    (machine/register-workflow! order-def)
    (is (contains? (set (machine/list-workflows)) :order-workflow))))

(deftest ^:unit register-invalid-workflow-test
  (testing "throws :validation-error when definition is invalid"
    (let [bad-def {:id :bad :states #{}}] ; missing :initial-state and :transitions
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                             #"Invalid workflow definition"
                             (machine/register-workflow! bad-def))))))

(deftest ^:unit unregister-workflow-test
  (testing "removes a registered workflow"
    (machine/register-workflow! order-def)
    (is (true? (machine/unregister-workflow! :order-workflow)))
    (is (nil? (machine/get-workflow :order-workflow))))

  (testing "returns false when workflow not found"
    (is (false? (machine/unregister-workflow! :ghost)))))

;; =============================================================================
;; Introspection helpers
;; =============================================================================

(deftest ^:unit states-test
  (testing "returns the states set"
    (is (= #{:pending :paid :shipped :delivered :cancelled}
           (machine/states order-def)))))

(deftest ^:unit initial-state-test
  (testing "returns the initial state keyword"
    (is (= :pending (machine/initial-state order-def)))))

(deftest ^:unit transitions-test
  (testing "returns the transitions vector"
    (is (= 5 (count (machine/transitions order-def))))))

(deftest ^:unit defworkflow-registers-from-caller-namespace-test
  (testing "defworkflow can be expanded from a non-library namespace"
    (let [result (binding [*ns* (create-ns 'boundary.workflow.test-sandbox)]
                   (clojure.core/refer 'clojure.core)
                   (eval
                    '(boundary.workflow.core.machine/defworkflow sandbox-workflow
                       {:id :sandbox-workflow
                        :initial-state :draft
                        :states #{:draft :done}
                        :transitions [{:from :draft :to :done :name :finish}]}))
                   (machine/get-workflow :sandbox-workflow))]
      (is (= :sandbox-workflow (:id result)))
      (is (= :draft (:initial-state result))))))
