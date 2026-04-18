(ns boundary.tools.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.check :as check]))

;; =============================================================================
;; Check definitions
;; =============================================================================

(deftest all-checks-have-required-fields
  (testing "every check has :id, :label, :cmd"
    (doseq [c check/all-checks]
      (is (keyword? (:id c)) (str "check missing :id"))
      (is (string? (:label c)) (str "check " (:id c) " missing :label"))
      (is (vector? (:cmd c)) (str "check " (:id c) " missing :cmd")))))

(deftest quick-check-ids-are-subset-of-all-checks
  (testing "all quick-check-ids exist in all-checks"
    (let [all-ids (set (map :id check/all-checks))]
      (doseq [qid check/quick-check-ids]
        (is (contains? all-ids qid)
            (str "quick-check-id " qid " not found in all-checks"))))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(deftest parse-args-test
  (testing "defaults when no args"
    (let [opts (#'check/parse-args [])]
      (is (false? (:quick opts)))
      (is (false? (:fix opts)))
      (is (false? (:ci opts)))))

  (testing "parses --quick flag"
    (is (true? (:quick (#'check/parse-args ["--quick"])))))

  (testing "parses --fix flag"
    (is (true? (:fix (#'check/parse-args ["--fix"])))))

  (testing "parses --ci flag"
    (is (true? (:ci (#'check/parse-args ["--ci"])))))

  (testing "parses multiple flags"
    (let [opts (#'check/parse-args ["--quick" "--ci"])]
      (is (true? (:quick opts)))
      (is (true? (:ci opts)))
      (is (false? (:fix opts)))))

  (testing "parses --help flag"
    (is (true? (:help (#'check/parse-args ["--help"]))))))
