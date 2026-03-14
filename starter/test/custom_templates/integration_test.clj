#!/usr/bin/env bb
;; test/custom_templates/integration_test.clj
;;
;; Integration tests for custom template wizard and CLI flows

(ns integration-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.io :as io]))

;; Load the modules under test
(load-file "scripts/helpers.clj")
(load-file "scripts/library_metadata.clj")
(load-file "scripts/file_generators.clj")

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn cleanup-integration-env
  "Clean up integration test artifacts"
  []
  (let [saved-dir (io/file "saved-templates")
        output-dir (io/file "test-output")]
    ;; Remove saved templates
    (when (.exists saved-dir)
      (doseq [file (.listFiles saved-dir)
              :when (and (.isFile file)
                         (.startsWith (.getName file) "test-integration-"))]
        (.delete file)))

    ;; Remove generated projects
    (when (.exists output-dir)
      (let [delete-recursive (fn delete-recursive [^java.io.File file]
                               (when (.isDirectory file)
                                 (doseq [child (.listFiles file)]
                                   (delete-recursive child)))
                               (.delete file))]
        (delete-recursive output-dir)))))

(defn setup-integration-env
  "Setup integration test environment"
  []
  (cleanup-integration-env)
  (.mkdir (io/file "test-output")))

(defn teardown-integration-env
  "Teardown integration test environment"
  []
  (cleanup-integration-env))

;; =============================================================================
;; Full Wizard Flow Tests
;; =============================================================================

(deftest test-full-custom-template-flow
  (testing "Full flow: select libs, save template, generate project"
    (setup-integration-env)
    (try
      ;; Step 1: Select libraries
      (let [selected-libs #{:user :admin :storage}

            ;; Step 2: Save custom template
            template-name "test-integration-full-flow"
            path (helpers/save-custom-template template-name selected-libs)]

        (is (.exists (io/file path)) "Template file should exist")

        ;; Step 3: Load saved template
        (let [loaded (helpers/load-saved-template template-name)]
          (is (= (:name loaded) template-name) "Name should match")
          (is (= (set (:libraries loaded)) selected-libs) "Libraries should match")

          ;; Step 4: Generate custom template config
          (let [template-config (helpers/generate-custom-template selected-libs)
                boundary-libs (set (:boundary-libs template-config))]

            ;; Should include selected libs + their dependencies
            (is (contains? boundary-libs :user) "Should include user")
            (is (contains? boundary-libs :admin) "Should include admin")
            (is (contains? boundary-libs :storage) "Should include storage")
            (is (contains? boundary-libs :platform) "Should include platform (dep)")
            (is (contains? boundary-libs :core) "Should include core (dep)")

            ;; Config should have sections from all resolved libraries
            (is (contains? (:config template-config) :auth) "Should have auth config")
            (is (contains? (:config template-config) :admin) "Should have admin config")
            (is (contains? (:config template-config) :storage) "Should have storage config"))))
      (finally
        (teardown-integration-env)))))

(deftest test-save-load-generate-cycle
  (testing "Save template, load it, generate project from it"
    (setup-integration-env)
    (try
      (let [template-name "test-integration-cycle"
            libs #{:cache :jobs}]

        ;; Save
        (helpers/save-custom-template template-name libs)

        ;; Load and generate from loaded libraries
        (let [loaded (helpers/load-saved-template template-name)
              loaded-libs (set (:libraries loaded))
              template-config (helpers/generate-custom-template loaded-libs)
              boundary-libs (set (:boundary-libs template-config))]
          (is (map? template-config) "Should generate valid config")
          (is (contains? template-config :boundary-libs) "Should have boundary-libs")
          (is (contains? template-config :config) "Should have config")

          ;; Check that dependencies are resolved
          (is (contains? boundary-libs :cache) "Should include cache")
          (is (contains? boundary-libs :jobs) "Should include jobs")
          (is (contains? boundary-libs :platform) "Should include platform (dep)")
          (is (contains? boundary-libs :core) "Should include core (dep)")))
      (finally
        (teardown-integration-env)))))

(deftest test-dependency-resolution-in-flow
  (testing "Dependency resolution works throughout the flow"
    (setup-integration-env)
    (try
      ;; Select only admin (has deep dependency chain)
      (let [selected #{:admin}
            template-name "test-integration-deps"]

        ;; Save
        (helpers/save-custom-template template-name selected)

        ;; Load and generate - should resolve all dependencies
        (let [loaded (helpers/load-saved-template template-name)
              template-config (helpers/generate-custom-template (set (:libraries loaded)))
              boundary-libs (set (:boundary-libs template-config))]

          ;; admin → user → platform → observability → core
          (is (contains? boundary-libs :admin) "Should include admin")
          (is (contains? boundary-libs :user) "Should include user (dep of admin)")
          (is (contains? boundary-libs :platform) "Should include platform (dep of user)")
          (is (contains? boundary-libs :observability) "Should include observability (dep of platform)")
          (is (contains? boundary-libs :core) "Should include core (dep of observability)")

          ;; Config should have sections from dependencies
          (is (contains? (:config template-config) :admin) "Should have admin config")
          (is (contains? (:config template-config) :auth) "Should have auth config from user")))
      (finally
        (teardown-integration-env)))))

;; =============================================================================
;; Non-Interactive Mode Tests
;; =============================================================================

(deftest test-load-saved-template-for-generation
  (testing "Load saved template for non-interactive project generation"
    (setup-integration-env)
    (try
      (let [template-name "test-integration-noninteractive"
            libs #{:user :storage :email}]

        ;; Save template
        (helpers/save-custom-template template-name libs)

        ;; Simulate non-interactive mode: load and use immediately
        (let [loaded (helpers/load-saved-template template-name)
              template-config (helpers/generate-custom-template (set (:libraries loaded)))]

          ;; Verify it's ready for file generation
          (is (map? template-config) "Should be valid config")
          (is (vector? (:boundary-libs template-config)) "Should have boundary-libs vector")
          (is (map? (:config template-config)) "Should have config map")

          ;; Config should be merged from all libraries
          (is (contains? (:config template-config) :auth) "Should have auth")
          (is (contains? (:config template-config) :storage) "Should have storage")
          (is (contains? (:config template-config) :email) "Should have email")))
      (finally
        (teardown-integration-env)))))

;; =============================================================================
;; Template Management Tests
;; =============================================================================

(deftest test-list-multiple-templates
  (testing "List multiple saved templates"
    (setup-integration-env)
    (try
      ;; Save multiple templates
      (helpers/save-custom-template "test-integration-list-1" #{:user})
      (helpers/save-custom-template "test-integration-list-2" #{:admin})
      (helpers/save-custom-template "test-integration-list-3" #{:storage})

      ;; List
      (let [templates (helpers/list-saved-templates)]
        (is (= (count templates) 3) "Should have 3 templates")
        (is (every? map? templates) "All should be maps")

        ;; Each template should be valid
        (doseq [t templates]
          (is (string? (:name t)) "Should have name")
          (is (vector? (:libraries t)) "Should have libraries")
          (is (string? (:created-at t)) "Should have timestamp")))
      (finally
        (teardown-integration-env)))))

(deftest test-delete-template-from-list
  (testing "Delete specific template from list"
    (setup-integration-env)
    (try
      ;; Save multiple
      (helpers/save-custom-template "test-integration-delete-keep" #{:user})
      (helpers/save-custom-template "test-integration-delete-remove" #{:admin})

      ;; Delete one
      (helpers/delete-saved-template "test-integration-delete-remove")

      ;; List remaining
      (let [templates (helpers/list-saved-templates)]
        (is (= (count templates) 1) "Should have 1 template left")
        (is (= (:name (first templates)) "test-integration-delete-keep")
            "Should keep the correct one"))
      (finally
        (teardown-integration-env)))))

;; =============================================================================
;; Config Merging Integration Tests
;; =============================================================================

(deftest test-config-merge-with-dependencies
  (testing "Config merge includes sections from dependencies"
    (let [selected #{:admin}  ;; admin depends on user
          template-config (helpers/generate-custom-template selected)
          config (:config template-config)]

      ;; Should have config from admin
      (is (contains? config :admin) "Should have admin config")
      (is (= (:enabled (:admin config)) true) "Admin should be enabled")

      ;; Should have config from user dependency
      (is (contains? config :auth) "Should have auth config from user")
      (is (= (:enabled (:auth config)) true) "Auth should be enabled")

      ;; Should have config from platform dependency
      (is (contains? config :http) "Should have http config from platform")
      (is (contains? config :db) "Should have db config from platform"))))

(deftest test-config-merge-no-duplicates
  (testing "Config merge doesn't duplicate sections"
    (let [selected #{:user :admin}  ;; Both depend on platform
          template-config (helpers/generate-custom-template selected)
          config (:config template-config)]

      ;; Should have each section only once
      (is (contains? config :auth) "Should have auth")
      (is (contains? config :admin) "Should have admin")
      (is (contains? config :http) "Should have http")
      (is (contains? config :db) "Should have db")

      ;; Check that platform config is present (not duplicated)
      (is (= (type (:http config)) clojure.lang.PersistentArrayMap)
          "HTTP config should be a single map"))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(defn -main []
  (let [results (run-tests 'integration-test)]
    (println)
    (println "======================")
    (println "Integration Test Summary:")
    (println (str "  Total:  " (+ (:pass results) (:fail results) (:error results))))
    (println (str "  Passed: " (:pass results)))
    (println (str "  Failed: " (:fail results)))
    (println (str "  Errors: " (:error results)))
    (println "======================")
    (System/exit (if (zero? (+ (:fail results) (:error results))) 0 1))))

;; For direct execution
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
