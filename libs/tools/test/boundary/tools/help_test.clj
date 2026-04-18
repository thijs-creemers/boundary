(ns boundary.tools.help-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.help :as help]))

;; =============================================================================
;; Error catalog
;; =============================================================================

(deftest error-catalog-completeness
  (testing "all error codes have required fields"
    (doseq [[code entry] help/error-catalog]
      (is (string? (:title entry)) (str code " missing :title"))
      (is (string? (:explain entry)) (str code " missing :explain"))
      (is (string? (:fix entry)) (str code " missing :fix"))))

  (testing "error codes follow BND-xxx format"
    (doseq [code (keys help/error-catalog)]
      (is (re-matches #"BND-\d{3}" code)
          (str "invalid error code format: " code)))))

;; =============================================================================
;; Topic functions
;; =============================================================================

(deftest topic-fns-cover-all-documented-topics
  (testing "expected topics are registered"
    (let [topics (set (keys help/topic-fns))]
      (is (contains? topics "scaffold"))
      (is (contains? topics "testing"))
      (is (contains? topics "database"))
      (is (contains? topics "fcis"))
      (is (contains? topics "config")))))

;; =============================================================================
;; State analysis (pure functions via #')
;; =============================================================================

(deftest integrated?-test
  (testing "detects module path in deps.edn text"
    (is (#'help/integrated? "\"libs/admin/src\"" "admin"))
    (is (not (#'help/integrated? "\"libs/admin/src\"" "user")))))

(deftest non-module-libs-excludes-infrastructure
  (testing "tools, devtools, and e2e are excluded"
    (let [libs @#'help/non-module-libs]
      (is (contains? libs "tools"))
      (is (contains? libs "devtools"))
      (is (contains? libs "e2e")))))

;; =============================================================================
;; General help output
;; =============================================================================

(deftest help-general-includes-new-commands
  (testing "general help lists all new Phase 1 commands"
    (let [output (with-out-str ((var help/help-general)))]
      (is (re-find #"bb quickstart" output) "missing quickstart")
      (is (re-find #"bb check\b" output) "missing check")
      (is (re-find #"bb doctor:env" output) "missing doctor:env")
      (is (re-find #"bb doctor --all" output) "missing doctor --all")
      (is (re-find #"bb db:status" output) "missing db:status")
      (is (re-find #"bb db:reset" output) "missing db:reset")
      (is (re-find #"bb db:seed" output) "missing db:seed"))))
