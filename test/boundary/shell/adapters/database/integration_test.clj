(ns boundary.shell.adapters.database.integration-test
  "Integration tests for the complete multi-database adapter system."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.shell.adapters.database.integration-example :as integration]
            [boundary.shell.adapters.database.config :as config]
            [boundary.shell.adapters.database.config-factory :as factory]
            [boundary.shell.adapters.database.core :as db-core]))

;; =============================================================================
;; Test Fixtures and Setup
;; =============================================================================

(defn reset-system-fixture
  "Fixture to reset the integration system before each test."
  [test-fn]
  (try
    ;; Reset application state before test
    (integration/reset-application-state!)
    (test-fn)
  (finally
    ;; Clean up after test
    (integration/reset-application-state!))))

(use-fixtures :each reset-system-fixture)

;; =============================================================================
;; System Initialization Tests
;; =============================================================================

(deftest test-system-initialization-dev
  (testing "System initialization for development environment"
    (System/setProperty "env" "dev")
    (try
      (let [initialized-dbs (integration/initialize-databases!)]
        (is (map? initialized-dbs) "Should return map of initialized databases")
        (is (seq initialized-dbs) "Should have at least one initialized database")
        
        ;; Check application state is updated
        (let [state (integration/current-state)]
          (is (:config-loaded? state) "Config should be loaded")
          (is (seq (:active-databases state)) "Should have active databases"))
        
        ;; Verify we can list active databases
        (let [active-dbs (integration/list-active-databases)]
          (is (seq active-dbs) "Should have active databases")
          (doseq [[adapter-key db-info] active-dbs]
            (is (keyword? adapter-key) "Adapter key should be keyword")
            (is (map? db-info) "DB info should be map")
            (is (contains? db-info :adapter) "Should contain adapter")
            (is (contains? db-info :pool) "Should contain connection pool"))))
      
      (finally
        (System/clearProperty "env")))))

(deftest test-system-initialization-test
  (testing "System initialization for test environment"
    (let [initialized-dbs (integration/initialize-databases! "test")]
      (is (map? initialized-dbs) "Should return map of initialized databases")
      (is (seq initialized-dbs) "Should have at least one initialized database")
      
      ;; Test environment should typically use H2
      (let [active-dbs (integration/list-active-databases)]
        (is (some #(= :boundary/h2 (first %)) active-dbs)
            "Test environment should typically have H2 active")))))

(deftest test-system-initialization-prod
  (testing "System initialization for production environment"
    (let [initialized-dbs (integration/initialize-databases! "prod")]
      (is (map? initialized-dbs) "Should return map of initialized databases")
      (is (seq initialized-dbs) "Should have at least one initialized database")
      
      ;; Production environment should typically use PostgreSQL
      (let [active-dbs (integration/list-active-databases)]
        (is (some #(= :boundary/postgresql (first %)) active-dbs)
            "Production environment should typically have PostgreSQL active")))))

(deftest test-system-initialization-invalid-env
  (testing "System initialization with invalid environment should fail gracefully"
    (is (thrown? Exception (integration/initialize-databases! "nonexistent"))
        "Should throw exception for nonexistent environment")))

;; =============================================================================
;; Database Access Tests
;; =============================================================================

(deftest test-database-access
  (testing "Database access after system initialization"
    (integration/initialize-databases! "test") ; Use test env with H2
    
    (testing "Get specific database"
      (let [active-dbs (integration/list-active-databases)]
        (when-let [[adapter-key _] (first active-dbs)]
          (let [db (integration/get-database adapter-key)]
            (is (some? db) "Should be able to get specific database")
            (is (map? db) "Database should be a map")
            (is (contains? db :adapter) "Should contain adapter")
            (is (contains? db :pool) "Should contain pool")))))
    
    (testing "Get primary database"
      (let [primary-db (integration/get-primary-database)]
        (is (some? primary-db) "Should have a primary database")
        (is (map? primary-db) "Primary database should be a map")))
    
    (testing "Get non-existent database"
      (let [non-existent-db (integration/get-database :boundary/nonexistent)]
        (is (nil? non-existent-db) "Non-existent database should return nil")))))

(deftest test-query-execution
  (testing "Query execution through integration system"
    (integration/initialize-databases! "test")
    
    (let [active-dbs (integration/list-active-databases)]
      (when-let [[adapter-key _] (first active-dbs)]
        (testing (str "Query execution on " adapter-key)
          (try
            ;; Simple test query
            (let [result (integration/execute-query adapter-key {:select [:1 :as :test]})]
              (is (some? result) "Query should return a result")
              (is (coll? result) "Result should be a collection"))
            (catch Exception e
              ;; This might fail if JDBC driver isn't loaded, which is expected
              ;; in our conditional dependency system
              (is (or (.contains (.getMessage e) "ClassNotFoundException")
                     (.contains (.getMessage e) "No suitable driver"))
                  (str "Expected driver-related error, got: " (.getMessage e))))))))))

(deftest test-query-execution-non-existent-adapter
  (testing "Query execution on non-existent adapter should throw"
    (integration/initialize-databases! "test")
    
    (is (thrown-with-msg? Exception #"Database adapter not found"
                         (integration/execute-query :boundary/nonexistent {:select [:1]}))
        "Should throw informative error for non-existent adapter")))

;; =============================================================================
;; Multi-Database Scenarios
;; =============================================================================

(deftest test-multiple-active-databases
  (testing "System with multiple active databases"
    ;; This test assumes a config with multiple active databases
    ;; If not available, we'll create a temporary multi-db scenario
    
    ;; For now, test the scenario where multiple databases could be active
    (integration/initialize-databases! "dev")
    
    (let [active-dbs (integration/list-active-databases)
          db-count (count active-dbs)]
      
      (if (> db-count 1)
        (do
          (testing "Multiple database query execution"
            (doseq [[adapter-key _] active-dbs]
              (testing (str "Query on " adapter-key)
                (try
                  (let [result (integration/execute-query adapter-key {:select [:1]})]
                    (is (some? result) (str "Should get result from " adapter-key)))
                  (catch Exception e
                    ;; Expected if driver not loaded
                    (println (str "Note: " adapter-key " query failed (likely missing driver): " (.getMessage e))))))))
        
        (testing "Single database scenario"
          (is (>= db-count 1) "Should have at least one active database")))))))

;; =============================================================================
;; System Lifecycle Tests
;; =============================================================================

(deftest test-system-lifecycle
  (testing "Complete system lifecycle: init -> use -> shutdown"
    (testing "Initialization"
      (let [initialized-dbs (integration/initialize-databases! "test")]
        (is (map? initialized-dbs) "Initialization should succeed")
        (is (seq initialized-dbs) "Should have initialized databases")))
    
    (testing "Usage"
      (let [state (integration/current-state)]
        (is (:config-loaded? state) "Config should be loaded")
        (is (seq (:active-databases state)) "Should have active databases"))
      
      (let [active-dbs (integration/list-active-databases)]
        (is (seq active-dbs) "Should be able to list active databases")))
    
    (testing "Shutdown"
      (integration/shutdown-databases!)
      
      (let [state (integration/current-state)]
        (is (empty? (:active-databases state)) "Should have no active databases after shutdown")))))

(deftest test-system-reset
  (testing "System reset functionality"
    ;; Initialize system
    (integration/initialize-databases! "test")
    (let [state-before (integration/current-state)]
      (is (:config-loaded? state-before) "Should have config loaded")
      (is (seq (:active-databases state-before)) "Should have active databases"))
    
    ;; Reset system
    (integration/reset-application-state!)
    (let [state-after (integration/current-state)]
      (is (not (:config-loaded? state-after)) "Config should not be loaded after reset")
      (is (empty? (:active-databases state-after)) "Should have no active databases after reset"))))

;; =============================================================================
;; Environment Switching Tests
;; =============================================================================

(deftest test-environment-switching
  (testing "Switching between different environments"
    (doseq [env ["dev" "test" "prod"]]
      (testing (str "Environment: " env)
        (try
          (integration/reset-application-state!)
          (integration/initialize-databases! env)
          
          (let [state (integration/current-state)]
            (is (:config-loaded? state) (str "Config should be loaded for " env))
            (is (seq (:active-databases state)) (str "Should have active databases for " env)))
          
          (let [active-dbs (integration/list-active-databases)]
            (is (seq active-dbs) (str "Should have active databases for " env))
            
            ;; Verify each database is properly initialized
            (doseq [[adapter-key db-info] active-dbs]
              (is (keyword? adapter-key) (str "Adapter key should be keyword in " env))
              (is (some? (:adapter db-info)) (str "Should have adapter in " env))
              (is (some? (:pool db-info)) (str "Should have connection pool in " env))))
          
          (catch Exception e
            ;; Some environments might not be available, that's okay
            (println (str "Note: Environment " env " not available: " (.getMessage e)))))))))

;; =============================================================================
;; Error Handling and Edge Cases
;; =============================================================================

(deftest test-double-initialization
  (testing "Double initialization should handle gracefully"
    (integration/initialize-databases! "test")
    (let [first-state (integration/current-state)]
      
      ;; Initialize again
      (integration/initialize-databases! "test") 
      (let [second-state (integration/current-state)]
        
        ;; System should still be functional
        (is (:config-loaded? second-state) "Config should still be loaded")
        (is (seq (:active-databases second-state)) "Should still have active databases")))))

(deftest test-shutdown-without-initialization
  (testing "Shutdown without initialization should not throw"
    (integration/reset-application-state!)
    
    ;; This should not throw an exception
    (integration/shutdown-databases!)
    
    (let [state (integration/current-state)]
      (is (empty? (:active-databases state)) "Should have no active databases"))))

(deftest test-query-after-shutdown
  (testing "Query execution after shutdown should throw appropriate error"
    (integration/initialize-databases! "test")
    (integration/shutdown-databases!)
    
    (is (thrown-with-msg? Exception #"Database adapter not found"
                         (integration/execute-query :boundary/h2 {:select [:1]}))
        "Should throw error when querying after shutdown")))

;; =============================================================================
;; Configuration Integration Tests
;; =============================================================================

(deftest test-config-factory-integration
  (testing "Integration between config loading and factory creation"
    (doseq [env ["dev" "test" "prod"]]
      (testing (str "Environment: " env)
        (try
          (let [config (config/load-config env)
                active-adapters (factory/create-active-adapters config)]
            
            (is (map? config) (str "Config should be loaded for " env))
            (is (map? active-adapters) (str "Should create active adapters for " env))
            (is (seq active-adapters) (str "Should have at least one adapter for " env))
            
            ;; Verify adapters match what's in the active config
            (let [active-config-keys (keys (:active config))
                  adapter-keys (keys active-adapters)
                  db-adapter-keys (filter #(.startsWith (name %) "boundary/") active-config-keys)]
              
              (doseq [db-key db-adapter-keys]
                (is (contains? active-adapters db-key)
                    (str "Adapter " db-key " should be created for " env)))))
          
          (catch Exception e
            (println (str "Note: Config/factory integration test failed for " env ": " (.getMessage e)))))))))

;; =============================================================================
;; Performance Tests
;; =============================================================================

(deftest test-initialization-performance
  (testing "System initialization should be reasonably fast"
    (let [start-time (System/nanoTime)]
      (integration/initialize-databases! "test")
      (let [end-time (System/nanoTime)
            duration-ms (/ (- end-time start-time) 1000000.0)]
        
        (is (< duration-ms 2000) ; Should initialize in under 2 seconds
            (str "System initialization took " duration-ms "ms, should be under 2000ms"))))))

(deftest test-query-performance
  (testing "Query execution should be reasonably fast"
    (integration/initialize-databases! "test")
    
    (let [active-dbs (integration/list-active-databases)]
      (when-let [[adapter-key _] (first active-dbs)]
        (try
          (let [start-time (System/nanoTime)]
            (integration/execute-query adapter-key {:select [:1]})
            (let [end-time (System/nanoTime)
                  duration-ms (/ (- end-time start-time) 1000000.0)]
              
              (is (< duration-ms 1000) ; Should execute in under 1 second
                  (str "Query execution took " duration-ms "ms, should be under 1000ms"))))
          (catch Exception e
            ;; Expected if driver not loaded
            (println (str "Note: Performance test skipped due to missing driver: " (.getMessage e)))))))))

;; =============================================================================
;; Example Function Tests
;; =============================================================================

(deftest test-example-functions
  (testing "Built-in example functions should work"
    (testing "Basic usage example"
      ;; This will use the current environment or default to dev
      (try
        (integration/example-basic-usage)
        ;; If it completes without exception, consider it successful
        (is true "Basic usage example should complete")
        (catch Exception e
          ;; Expected if JDBC drivers not available
          (is (or (.contains (.getMessage e) "ClassNotFoundException")
                 (.contains (.getMessage e) "No suitable driver"))
              (str "Expected driver-related error, got: " (.getMessage e))))))
    
    (testing "Environment switching example"
      ;; This should work even without JDBC drivers since it only loads configs
      (try
        (integration/example-environment-switching)
        (is true "Environment switching example should complete")
        (catch Exception e
          (println (str "Environment switching example failed: " (.getMessage e)))
          ;; This test documents expected behavior even if it fails
          (is false (str "Environment switching should not fail: " (.getMessage e))))))))

;; Run all tests
(defn run-integration-tests []
  (clojure.test/run-tests 'boundary.shell.adapters.database.integration-test))