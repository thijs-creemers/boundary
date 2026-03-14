#!/usr/bin/env bb
;; test/custom_templates/metadata_config_test.clj
;;
;; Tests for metadata-driven config template system

(ns metadata-config-test
  (:require [clojure.test :refer [deftest is testing run-tests]]))

;; Load the modules under test
(load-file "scripts/helpers.clj")
(load-file "scripts/library_metadata.clj")
(require '[helpers :as helpers]
         '[library-metadata :as library-metadata])

;; =============================================================================
;; Metadata Structure Tests
;; =============================================================================

(deftest test-all-libraries-have-config-template
  (testing "Every library has :config-template field"
    (let [all-libs (library-metadata/get-all-libraries)]
      (doseq [[lib-id lib-meta] all-libs]
        (is (contains? lib-meta :config-template)
            (str "Library " lib-id " should have :config-template field"))))))

(deftest test-config-template-matches-config-sections
  (testing "Config template keys match config-sections"
    (let [all-libs (library-metadata/get-all-libraries)]
      (doseq [[lib-id lib-meta] all-libs]
        (let [config-sections (set (:config-sections lib-meta))
              config-template (:config-template lib-meta)
              template-keys (set (keys config-template))]
          (is (= config-sections template-keys)
              (str "Library " lib-id " config-sections " config-sections
                   " should match template keys " template-keys)))))))

(deftest test-config-template-structure
  (testing "Config templates have expected structure"
    ;; User library
    (let [user-lib (library-metadata/get-library :user)
          config (:config-template user-lib)]
      (is (contains? config :auth) "User should have :auth config")
      (is (= (:enabled (:auth config)) true) "Auth should be enabled")
      (is (= (:jwt-secret (:auth config)) :env/JWT_SECRET) "JWT secret should be env var"))

    ;; Platform library
    (let [platform-lib (library-metadata/get-library :platform)
          config (:config-template platform-lib)]
      (is (contains? config :http) "Platform should have :http config")
      (is (contains? config :db) "Platform should have :db config")
      (is (contains? config :cli) "Platform should have :cli config")
      (is (= (:port (:http config)) 3000) "HTTP port should be 3000")
      (is (= (:type (:db config)) :sqlite) "DB type should be :sqlite"))

    ;; Storage library
    (let [storage-lib (library-metadata/get-library :storage)
          config (:config-template storage-lib)]
      (is (contains? config :storage) "Storage should have :storage config")
      (is (= (:provider (:storage config)) :local) "Storage provider should be :local")
      (is (= (:local-path (:storage config)) "uploads") "Local path should be uploads"))))

;; =============================================================================
;; Dynamic Config Merging Tests
;; =============================================================================

(deftest test-merge-single-library
  (testing "Merge config from single library uses metadata"
    (let [config (helpers/merge-library-configs #{:user})]
      (is (map? config) "Should return a map")
      (is (contains? config :auth) "Should have :auth from user")
      (is (= (:enabled (:auth config)) true) "Auth should be enabled")
      (is (= (:jwt-secret (:auth config)) :env/JWT_SECRET) "JWT secret from metadata"))))

(deftest test-merge-multiple-libraries
  (testing "Merge config from multiple libraries"
    (let [config (helpers/merge-library-configs #{:user :storage :cache})]
      (is (map? config) "Should return a map")
      (is (contains? config :auth) "Should have :auth from user")
      (is (contains? config :storage) "Should have :storage")
      (is (contains? config :cache) "Should have :cache")

      ;; Verify values come from metadata
      (is (= (:provider (:storage config)) :local) "Storage provider from metadata")
      (is (= (:provider (:cache config)) :in-memory) "Cache provider from metadata")
      (is (= (:ttl-seconds (:cache config)) 3600) "Cache TTL from metadata"))))

(deftest test-merge-with-dependencies
  (testing "Merge config includes dependencies"
    (let [selected #{:admin}
          resolved (library-metadata/resolve-dependencies selected)
          config (helpers/merge-library-configs resolved)]

      ;; Should have config from admin
      (is (contains? config :admin) "Should have :admin config")

      ;; Should have config from dependencies
      (is (contains? config :auth) "Should have :auth from user (dep)")
      (is (contains? config :http) "Should have :http from platform (dep)")
      (is (contains? config :db) "Should have :db from platform (dep)")
      (is (contains? config :observability) "Should have :observability from dep"))))

(deftest test-merge-foundation-only
  (testing "Merge config with foundation libraries"
    (let [foundation #{:core :observability :platform}
          config (helpers/merge-library-configs foundation)]
      (is (map? config) "Should return a map")

      ;; Core has no config
      ;; Observability has :observability
      (is (contains? config :observability) "Should have :observability")
      (is (= (:level (:observability config)) :info) "Level from metadata")

      ;; Platform has :http, :db, :cli
      (is (contains? config :http) "Should have :http")
      (is (contains? config :db) "Should have :db")
      (is (contains? config :cli) "Should have :cli")
      (is (= (:port (:http config)) 3000) "HTTP port from metadata")
      (is (= (:type (:db config)) :sqlite) "DB type from metadata"))))

(deftest test-merge-all-libraries
  (testing "Merge config from all 18 libraries"
    (let [all-libs (set (keys library-metadata/libraries))
          config (helpers/merge-library-configs all-libs)]
      (is (map? config) "Should return a map")

      ;; Should have all expected config sections (18 config sections total)
      ;; core: none
      ;; observability: 1 (:observability)
      ;; platform: 3 (:http, :db, :cli)
      ;; user: 1 (:auth)
      ;; admin: 1 (:admin)
      ;; storage, cache, jobs, email, tenant, realtime, workflow, search, external, reports, calendar, geo: 12 more

      (is (>= (count config) 18) "Should have at least 18 config sections")

      ;; Verify key sections exist
      (is (contains? config :auth) "Should have :auth")
      (is (contains? config :admin) "Should have :admin")
      (is (contains? config :storage) "Should have :storage")
      (is (contains? config :cache) "Should have :cache")
      (is (contains? config :jobs) "Should have :jobs")
      (is (contains? config :email) "Should have :email")
      (is (contains? config :tenant) "Should have :tenant")
      (is (contains? config :realtime) "Should have :realtime")
      (is (contains? config :workflow) "Should have :workflow")
      (is (contains? config :search) "Should have :search")
      (is (contains? config :external) "Should have :external")
      (is (contains? config :reports) "Should have :reports")
      (is (contains? config :calendar) "Should have :calendar")
      (is (contains? config :geo) "Should have :geo"))))

;; =============================================================================
;; Environment Variable Preservation Tests
;; =============================================================================

(deftest test-env-vars-preserved
  (testing "Environment variables are preserved as :env/VAR keywords"
    (let [config (helpers/merge-library-configs #{:user :email})]
      ;; User library
      (is (= (:jwt-secret (:auth config)) :env/JWT_SECRET)
          "JWT secret should be :env/JWT_SECRET keyword")

      ;; Email library
      (is (= (:smtp-host (:email config)) :env/SMTP_HOST)
          "SMTP host should be :env/SMTP_HOST keyword")
      (is (= (:smtp-port (:email config)) :env/SMTP_PORT)
          "SMTP port should be :env/SMTP_PORT keyword")
      (is (= (:from-address (:email config)) :env/EMAIL_FROM)
          "From address should be :env/EMAIL_FROM keyword"))))

;; =============================================================================
;; No Hardcoded Config Tests
;; =============================================================================

(deftest test-no-case-statement-in-merge
  (testing "merge-library-configs function doesn't use case statement"
    (let [source (slurp "scripts/helpers.clj")
          merge-fn-source (subs source
                                (.indexOf source "defn merge-library-configs")
                                (.indexOf source "defn generate-custom-template"))]
      (is (not (re-find #"\(case section" merge-fn-source))
          "merge-library-configs should not contain case statement")
      (is (re-find #":config-template" merge-fn-source)
          "merge-library-configs should use :config-template from metadata"))))

;; =============================================================================
;; Backward Compatibility Tests
;; =============================================================================

(deftest test-backward-compatibility-with-existing-templates
  (testing "Existing templates still work after metadata changes"
    (let [user-config (helpers/merge-library-configs #{:user})
          admin-config (helpers/merge-library-configs #{:admin :user})]

      ;; User template should work as before
      (is (contains? user-config :auth))
      (is (= (:enabled (:auth user-config)) true))

      ;; Admin template should work as before
      (is (contains? admin-config :admin))
      (is (contains? admin-config :auth))
      (is (= (:path (:admin admin-config)) "/admin")))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(defn -main []
  (let [results (run-tests 'metadata-config-test)]
    (println)
    (println "======================")
    (println "Metadata Config Test Summary:")
    (println (str "  Total:  " (+ (:pass results) (:fail results) (:error results))))
    (println (str "  Passed: " (:pass results)))
    (println (str "  Failed: " (:fail results)))
    (println (str "  Errors: " (:error results)))
    (println "======================")
    (System/exit (if (zero? (+ (:fail results) (:error results))) 0 1))))

;; For direct execution
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
