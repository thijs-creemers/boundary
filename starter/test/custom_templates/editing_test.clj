#!/usr/bin/env bb
;; test/custom_templates/editing_test.clj
;;
;; Tests for template editing operations (edit, duplicate, rename).
;;
;; Run:
;;   bb -e "(load-file \"test/custom_templates/editing_test.clj\") \
;;     (clojure.test/run-tests 'editing-test)"

(ns editing-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; Load dependencies
(load-file "scripts/helpers.clj")
(require '[helpers :as helpers])

;; Test directory
(def test-dir "saved-templates")

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn setup-test-dir
  "Create test directory if it doesn't exist."
  []
  (.mkdirs (io/file test-dir)))

(defn cleanup-test-templates
  "Remove all .edn files from saved-templates (keep directory)."
  []
  (when (.exists (io/file test-dir))
    (doseq [file (.listFiles (io/file test-dir))
            :when (and (.isFile file)
                       (.endsWith (.getName file) ".edn"))]
      (.delete file))))

(defn create-test-template
  "Create a test template file."
  [name libs & {:keys [created-at updated-at]}]
  (let [path (str test-dir "/" name ".edn")
        data (cond-> {:name name :libraries libs}
               created-at (assoc :created-at created-at)
               updated-at (assoc :updated-at updated-at))]
    (spit path (pr-str data))))

;; =============================================================================
;; Edit Template Tests
;; =============================================================================

(deftest test-edit-saved-template
  (testing "Edit existing template - update libraries"
    (setup-test-dir)
    (try
      ;; Create template
      (create-test-template "my-api" [:core :platform :observability :user]
                            :created-at "2026-03-14T10:00:00Z")

      ;; Edit template
      (helpers/edit-saved-template "my-api" [:core :platform :observability :admin])

      ;; Verify
      (let [loaded (edn/read-string (slurp (str test-dir "/my-api.edn")))]
        (is (= "my-api" (:name loaded)))
        (is (= (sort [:core :platform :observability :admin])
               (sort (:libraries loaded)))
            "Libraries should match (order-independent)")
        (is (= "2026-03-14T10:00:00Z" (:created-at loaded)) "Should preserve created-at")
        (is (some? (:updated-at loaded)) "Should add updated-at timestamp")
        (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" (:updated-at loaded))
            "updated-at should be ISO 8601 format"))

      (finally
        (cleanup-test-templates)))))

(deftest test-edit-nonexistent-template
  (testing "Edit nonexistent template - throws exception"
    (setup-test-dir)
    (try
      (is (thrown-with-msg? Exception #"Template not found"
                            (helpers/edit-saved-template "nonexistent" [:core :platform])))
      (finally
        (cleanup-test-templates)))))

(deftest test-edit-preserves-created-at
  (testing "Edit preserves created-at, updates updated-at"
    (setup-test-dir)
    (try
      ;; Create with specific timestamp
      (create-test-template "old-template" [:core :platform]
                            :created-at "2026-01-01T00:00:00Z")

      ;; Edit
      (helpers/edit-saved-template "old-template" [:core :platform :user])

      ;; Verify
      (let [loaded (edn/read-string (slurp (str test-dir "/old-template.edn")))]
        (is (= "2026-01-01T00:00:00Z" (:created-at loaded))
            "created-at should NOT change")
        (is (some? (:updated-at loaded))
            "updated-at should be added")
        (is (not= "2026-01-01T00:00:00Z" (:updated-at loaded))
            "updated-at should be current timestamp"))

      (finally
        (cleanup-test-templates)))))

;; =============================================================================
;; Duplicate Template Tests
;; =============================================================================

(deftest test-duplicate-saved-template
  (testing "Duplicate template - creates new with same libraries"
    (setup-test-dir)
    (try
      ;; Create source
      (create-test-template "original" [:core :platform :user]
                            :created-at "2026-03-01T00:00:00Z")

      ;; Duplicate
      (helpers/duplicate-saved-template "original" "copy")

      ;; Verify source unchanged
      (let [original (edn/read-string (slurp (str test-dir "/original.edn")))]
        (is (= "original" (:name original)))
        (is (= "2026-03-01T00:00:00Z" (:created-at original))))

      ;; Verify copy created
      (let [copy (edn/read-string (slurp (str test-dir "/copy.edn")))]
        (is (= "copy" (:name copy)))
        (is (= [:core :platform :user] (:libraries copy)))
        (is (some? (:created-at copy)))
        (is (not= "2026-03-01T00:00:00Z" (:created-at copy))
            "Copy should have NEW created-at timestamp")
        (is (nil? (:updated-at copy))
            "Copy should NOT have updated-at (it's a new template)"))

      (finally
        (cleanup-test-templates)))))

(deftest test-duplicate-nonexistent-source
  (testing "Duplicate nonexistent source - throws exception"
    (setup-test-dir)
    (try
      (is (thrown-with-msg? Exception #"Source template not found"
                            (helpers/duplicate-saved-template "missing" "new-copy")))
      (finally
        (cleanup-test-templates)))))

(deftest test-duplicate-existing-destination
  (testing "Duplicate to existing name - throws exception"
    (setup-test-dir)
    (try
      ;; Create two templates
      (create-test-template "source" [:core :platform])
      (create-test-template "existing" [:core :user])

      ;; Try to duplicate with existing name
      (is (thrown-with-msg? Exception #"Destination template already exists"
                            (helpers/duplicate-saved-template "source" "existing")))
      (finally
        (cleanup-test-templates)))))

;; =============================================================================
;; Rename Template Tests
;; =============================================================================

(deftest test-rename-saved-template
  (testing "Rename template - changes name and filename"
    (setup-test-dir)
    (try
      ;; Create template
      (create-test-template "old-name" [:core :platform :user]
                            :created-at "2026-03-01T00:00:00Z"
                            :updated-at "2026-03-10T00:00:00Z")

      ;; Rename
      (helpers/rename-saved-template "old-name" "new-name")

      ;; Verify old file deleted
      (is (not (.exists (io/file test-dir "old-name.edn")))
          "Old file should be deleted")

      ;; Verify new file created
      (is (.exists (io/file test-dir "new-name.edn"))
          "New file should be created")

      ;; Verify metadata preserved
      (let [renamed (edn/read-string (slurp (str test-dir "/new-name.edn")))]
        (is (= "new-name" (:name renamed)))
        (is (= [:core :platform :user] (:libraries renamed)))
        (is (= "2026-03-01T00:00:00Z" (:created-at renamed))
            "Should preserve created-at")
        (is (= "2026-03-10T00:00:00Z" (:updated-at renamed))
            "Should preserve updated-at"))

      (finally
        (cleanup-test-templates)))))

(deftest test-rename-nonexistent
  (testing "Rename nonexistent template - throws exception"
    (setup-test-dir)
    (try
      (is (thrown-with-msg? Exception #"Template not found"
                            (helpers/rename-saved-template "missing" "new-name")))
      (finally
        (cleanup-test-templates)))))

(deftest test-rename-existing-destination
  (testing "Rename to existing name - throws exception"
    (setup-test-dir)
    (try
      ;; Create two templates
      (create-test-template "template-a" [:core :platform])
      (create-test-template "template-b" [:core :user])

      ;; Try to rename to existing name
      (is (thrown-with-msg? Exception #"Destination template already exists"
                            (helpers/rename-saved-template "template-a" "template-b")))
      (finally
        (cleanup-test-templates)))))

;; =============================================================================
;; Test Runner
;; =============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (clojure.test/run-tests 'editing-test))
