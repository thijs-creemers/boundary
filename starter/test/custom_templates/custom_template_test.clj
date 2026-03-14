#!/usr/bin/env bb
;; test/custom_templates/custom_template_test.clj
;;
;; Automated tests for custom template save/load/delete functionality

(ns custom-template-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; Load the modules under test
(load-file "scripts/helpers.clj")
(load-file "scripts/library_metadata.clj")
(require '[helpers :as helpers]
         '[library-metadata :as library-metadata])

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn cleanup-test-templates
  "Remove all test templates from saved-templates/"
  []
  (let [dir (io/file "saved-templates")]
    (when (.exists dir)
      (doseq [file (.listFiles dir)
              :when (and (.isFile file)
                         (or (.startsWith (.getName file) "test-")
                             (= (.getName file) ".gitkeep")))]
        (when-not (= (.getName file) ".gitkeep")
          (.delete file))))))

(defn setup-test-env
  "Setup test environment"
  []
  (cleanup-test-templates))

(defn teardown-test-env
  "Cleanup test environment"
  []
  (cleanup-test-templates))

;; =============================================================================
;; Save/Load/Delete Tests
;; =============================================================================

(deftest test-save-custom-template
  (testing "Save custom template creates file with correct format"
    (setup-test-env)
    (try
      (let [template-name "test-save-template"
            libs #{:core :user :admin}
            path (helpers/save-custom-template template-name libs)
            file (io/file path)]

        ;; File exists
        (is (.exists file) "Template file should exist")

        ;; File has correct path
        (is (= path "saved-templates/test-save-template.edn") "Path should match expected")

        ;; File contains valid EDN
        (let [content (edn/read-string (slurp file))]
          (is (map? content) "Content should be a map")
          (is (= (:name content) template-name) "Name should match")
          (is (string? (:created-at content)) "Should have timestamp")
          (is (vector? (:libraries content)) "Libraries should be a vector")
          (is (= (set (:libraries content)) libs) "Libraries should match input")))
      (finally
        (teardown-test-env)))))

(deftest test-load-custom-template
  (testing "Load custom template reads file correctly"
    (setup-test-env)
    (try
      (let [template-name "test-load-template"
            libs #{:core :user :admin}]
        ;; Save first
        (helpers/save-custom-template template-name libs)

        ;; Load
        (let [loaded (helpers/load-saved-template template-name)]
          (is (= (:name loaded) template-name) "Name should match")
          (is (string? (:created-at loaded)) "Should have timestamp")
          (is (= (set (:libraries loaded)) libs) "Libraries should match")))
      (finally
        (teardown-test-env)))))

(deftest test-load-nonexistent-template
  (testing "Load non-existent template throws exception"
    (setup-test-env)
    (try
      (is (thrown? Exception
                   (helpers/load-saved-template "does-not-exist"))
          "Should throw exception for non-existent template")
      (finally
        (teardown-test-env)))))

(deftest test-list-custom-templates
  (testing "List custom templates returns all saved templates"
    (setup-test-env)
    (try
      ;; Save multiple templates
      (helpers/save-custom-template "test-list-1" #{:core :user})
      (helpers/save-custom-template "test-list-2" #{:core :admin})
      (helpers/save-custom-template "test-list-3" #{:core :storage})

      ;; List
      (let [templates (helpers/list-saved-templates)]
        (is (= (count templates) 3) "Should return 3 templates")
        (is (every? map? templates) "All items should be maps")
        (is (every? :name templates) "All should have :name")
        (is (every? :libraries templates) "All should have :libraries")
        (is (= (set (map :name templates))
               #{"test-list-1" "test-list-2" "test-list-3"})
            "Names should match"))
      (finally
        (teardown-test-env)))))

(deftest test-list-empty-directory
  (testing "List templates in empty directory returns empty vector"
    (setup-test-env)
    (try
      (let [templates (helpers/list-saved-templates)]
        (is (vector? templates) "Should return a vector")
        (is (empty? templates) "Should be empty"))
      (finally
        (teardown-test-env)))))

(deftest test-delete-custom-template
  (testing "Delete custom template removes file"
    (setup-test-env)
    (try
      (let [template-name "test-delete-template"]
        ;; Save
        (helpers/save-custom-template template-name #{:core :user})
        (is (.exists (io/file "saved-templates/test-delete-template.edn"))
            "File should exist after save")

        ;; Delete
        (let [result (helpers/delete-saved-template template-name)]
          (is (true? result) "Delete should return true")
          (is (not (.exists (io/file "saved-templates/test-delete-template.edn")))
              "File should not exist after delete")))
      (finally
        (teardown-test-env)))))

(deftest test-delete-nonexistent-template
  (testing "Delete non-existent template returns false"
    (setup-test-env)
    (try
      (let [result (helpers/delete-saved-template "does-not-exist")]
        (is (false? result) "Delete should return false for non-existent template"))
      (finally
        (teardown-test-env)))))

;; =============================================================================
;; Config Merging Tests
;; =============================================================================

(deftest test-merge-library-configs-empty
  (testing "Merge config with no libraries returns empty map"
    (let [config (helpers/merge-library-configs #{})]
      (is (map? config) "Should return a map")
      (is (empty? config) "Should be empty"))))

(deftest test-merge-library-configs-foundation-only
  (testing "Merge config with foundation libraries only"
    (let [config (helpers/merge-library-configs #{:core :observability :platform})]
      (is (map? config) "Should return a map")
      ;; Foundation libs have minimal config
      (is (contains? config :http) "Should have :http from platform")
      (is (contains? config :db) "Should have :db from platform")
      (is (contains? config :observability) "Should have :observability"))))

(deftest test-merge-library-configs-single-lib
  (testing "Merge config with single library"
    (let [config (helpers/merge-library-configs #{:user})]
      (is (map? config) "Should return a map")
      (is (contains? config :auth) "Should have :auth section")
      (is (= (:enabled (:auth config)) true) "Auth should be enabled")
      (is (= (:jwt-secret (:auth config)) :env/JWT_SECRET) "Should reference env var"))))

(deftest test-merge-library-configs-multiple-libs
  (testing "Merge config with multiple libraries"
    (let [config (helpers/merge-library-configs #{:user :admin :storage :cache})]
      (is (map? config) "Should return a map")
      (is (contains? config :auth) "Should have :auth")
      (is (contains? config :admin) "Should have :admin")
      (is (contains? config :storage) "Should have :storage")
      (is (contains? config :cache) "Should have :cache")

      ;; Check structure
      (is (= (:enabled (:auth config)) true) "Auth enabled")
      (is (= (:enabled (:admin config)) true) "Admin enabled")
      (is (= (:provider (:storage config)) :local) "Storage provider")
      (is (= (:provider (:cache config)) :in-memory) "Cache provider"))))

(deftest test-merge-library-configs-all-libs
  (testing "Merge config with all 18 libraries"
    (let [all-libs (set (keys library-metadata/libraries))
          config (helpers/merge-library-configs all-libs)]
      (is (map? config) "Should return a map")
      (is (>= (count config) 10) "Should have at least 10 config sections")

      ;; Check key sections exist
      (is (contains? config :auth) "Should have :auth")
      (is (contains? config :admin) "Should have :admin")
      (is (contains? config :storage) "Should have :storage")
      (is (contains? config :cache) "Should have :cache")
      (is (contains? config :jobs) "Should have :jobs")
      (is (contains? config :email) "Should have :email")
      (is (contains? config :tenant) "Should have :tenant"))))

;; =============================================================================
;; Custom Template Generation Tests
;; =============================================================================

(deftest test-generate-custom-template-minimal
  (testing "Generate custom template with minimal libs"
    (let [libs #{:core :observability :platform}
          template (helpers/generate-custom-template libs)]
      (is (map? template) "Should return a map")
      (is (contains? template :boundary-libs) "Should have :boundary-libs")
      (is (contains? template :config) "Should have :config")
      (is (contains? template :meta) "Should have :meta")

      ;; Check boundary-libs
      (is (vector? (:boundary-libs template)) ":boundary-libs should be vector")
      (is (= (set (:boundary-libs template)) libs) "Libraries should match input"))))

(deftest test-generate-custom-template-with-deps
  (testing "Generate custom template with libraries that have dependencies"
    (let [libs #{:admin}  ;; admin depends on user, platform, observability, core
          template (helpers/generate-custom-template libs)
          boundary-libs (set (:boundary-libs template))]

      ;; Should include admin
      (is (contains? boundary-libs :admin) "Should include selected lib")

      ;; Config should have sections from admin and its dependencies
      (is (contains? (:config template) :admin) "Should have admin config")
      (is (contains? (:config template) :auth) "Should have auth config from user dep"))))

(deftest test-generate-custom-template-config-merged
  (testing "Generate custom template merges config from libraries"
    (let [libs #{:user :storage}
          template (helpers/generate-custom-template libs)
          config (:config template)]

      ;; Should have config from both libraries
      (is (contains? config :auth) "Should have auth from user")
      (is (contains? config :storage) "Should have storage config")

      ;; Should also have base config
      (is (contains? config :http) "Should have http from base")
      (is (contains? config :db) "Should have db from base"))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest test-saved-template-format-validation
  (testing "Saved template has all required fields"
    (setup-test-env)
    (try
      (helpers/save-custom-template "test-validation" #{:core :user})
      (let [file (io/file "saved-templates/test-validation.edn")
            content (edn/read-string (slurp file))]

        ;; Required fields
        (is (contains? content :name) "Should have :name")
        (is (contains? content :created-at) "Should have :created-at")
        (is (contains? content :libraries) "Should have :libraries")

        ;; Field types
        (is (string? (:name content)) ":name should be string")
        (is (string? (:created-at content)) ":created-at should be string")
        (is (vector? (:libraries content)) ":libraries should be vector")

        ;; Timestamp format (ISO 8601)
        (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (:created-at content))
            "Timestamp should be ISO 8601 format"))
      (finally
        (teardown-test-env)))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest test-save-template-creates-directory
  (testing "Save template creates directory if it doesn't exist"
    (let [dir (io/file "saved-templates")]
      ;; Remove directory
      (when (.exists dir)
        (doseq [file (.listFiles dir)]
          (.delete file))
        (.delete dir))

      (is (not (.exists dir)) "Directory should not exist")

      ;; Save template
      (helpers/save-custom-template "test-create-dir" #{:core})

      (is (.exists dir) "Directory should be created")
      (is (.exists (io/file "saved-templates/test-create-dir.edn"))
          "File should be created")

      ;; Cleanup
      (teardown-test-env))))

(deftest test-save-template-overwrites-existing
  (testing "Save template overwrites existing file with same name"
    (setup-test-env)
    (try
      (let [name "test-overwrite"]
        ;; Save first version
        (helpers/save-custom-template name #{:core :user})
        (let [first-content (slurp "saved-templates/test-overwrite.edn")
              first-libs (set (:libraries (edn/read-string first-content)))]
          (is (= first-libs #{:core :user}) "First save should have 2 libs")

          ;; Wait a moment to ensure different timestamp
          (Thread/sleep 10)

          ;; Save second version with different libs
          (helpers/save-custom-template name #{:core :admin :storage})
          (let [second-content (slurp "saved-templates/test-overwrite.edn")
                second-libs (set (:libraries (edn/read-string second-content)))]
            (is (= second-libs #{:core :admin :storage}) "Second save should have 3 libs")
            (is (not= first-content second-content) "Content should be different"))))
      (finally
        (teardown-test-env)))))

;; =============================================================================
;; Corrupted File Tests
;; =============================================================================

(deftest test-load-corrupted-edn
  (testing "Load template with corrupted EDN throws exception"
    (setup-test-env)
    (try
      ;; Write invalid EDN
      (spit "saved-templates/test-corrupted.edn" "{:name \"test\" :libraries [")

      (is (thrown? Exception
                   (helpers/load-saved-template "test-corrupted"))
          "Should throw exception for corrupted EDN")
      (finally
        (teardown-test-env)))))

(deftest test-load-missing-fields
  (testing "Load template missing required fields"
    (setup-test-env)
    (try
      ;; Write EDN with missing :libraries
      (spit "saved-templates/test-missing-fields.edn"
            "{:name \"test\" :created-at \"2026-03-14T10:00:00Z\"}")

      (let [loaded (helpers/load-saved-template "test-missing-fields")]
        (is (= (:name loaded) "test") "Should load name")
        (is (nil? (:libraries loaded)) "Missing field should be nil"))
      (finally
        (teardown-test-env)))))

(deftest test-load-wrong-field-types
  (testing "Load template with wrong field types"
    (setup-test-env)
    (try
      ;; Write EDN with :libraries as map instead of vector
      (spit "saved-templates/test-wrong-types.edn"
            "{:name \"test\" :created-at \"2026-03-14T10:00:00Z\" :libraries {:core true}}")

      (let [loaded (helpers/load-saved-template "test-wrong-types")]
        (is (= (:name loaded) "test") "Should load name")
        (is (map? (:libraries loaded)) "Wrong type should be preserved"))
      (finally
        (teardown-test-env)))))

;; =============================================================================
;; Dependency Visualization Tests
;; =============================================================================

(deftest test-dependency-summary
  (testing "Generate dependency summary for selected libraries"
    (let [selected #{:admin}
          summary (library-metadata/generate-dependency-summary selected)]

      (is (string? summary) "Should return a string")
      (is (re-find #"Foundation" summary) "Should mention foundation")
      (is (re-find #"Selected" summary) "Should mention selected")
      (is (re-find #"Auto-added" summary) "Should mention auto-added")
      (is (re-find #"admin" summary) "Should list admin")
      (is (re-find #"user" summary) "Should list user dependency"))))

(deftest test-dependency-tree-simple
  (testing "Generate dependency tree for simple case"
    (let [libs #{:user}
          tree (library-metadata/generate-dependency-tree libs)]

      (is (string? tree) "Should return a string")
      (is (re-find #"user" tree) "Should contain user")
      (is (re-find #"platform" tree) "Should contain platform dependency"))))

(deftest test-dependency-tree-complex
  (testing "Generate dependency tree for complex case with multiple levels"
    (let [libs #{:admin}
          tree (library-metadata/generate-dependency-tree libs)]

      (is (string? tree) "Should return a string")
      (is (re-find #"admin" tree) "Should contain admin")
      (is (re-find #"user" tree) "Should contain user (1st level dep)")
      (is (re-find #"platform" tree) "Should contain platform (2nd level dep)")
      (is (re-find #"core" tree) "Should contain core (3rd level dep)"))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(defn -main []
  (let [results (run-tests 'custom-template-test)]
    (println)
    (println "======================")
    (println "Test Summary:")
    (println (str "  Total:  " (+ (:pass results) (:fail results) (:error results))))
    (println (str "  Passed: " (:pass results)))
    (println (str "  Failed: " (:fail results)))
    (println (str "  Errors: " (:error results)))
    (println "======================")
    (System/exit (if (zero? (+ (:fail results) (:error results))) 0 1))))

;; For direct execution
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
