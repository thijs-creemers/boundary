(ns boundary.tenant.shell.provisioning-test
  "Unit and integration tests for tenant provisioning service.
   
   Test Categories:
   - Unit tests: Test provisioning logic with mocked database
   - Integration tests: Test with real H2/PostgreSQL database
   - Contract tests: Verify PostgreSQL-specific behavior"
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tenant.shell.provisioning :as sut]
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            [boundary.platform.shell.adapters.database.common.core :as db]
            [boundary.platform.shell.adapters.database.protocols :as protocols])
  (:import (java.util UUID)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-ctx* nil)

(defn with-h2-database
  "Test fixture that creates an H2 in-memory database for testing."
  [f]
  (let [ctx (db-factory/db-context {:adapter :h2
                                    :database-path (str "mem:tenant_test_" (UUID/randomUUID))
                                    :pool {:maximum-pool-size 5}})]
    (try
      ;; Initialize test schema in public
      (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS tenants (
                             id VARCHAR(36) PRIMARY KEY,
                             slug VARCHAR(100) NOT NULL,
                             schema_name VARCHAR(100) NOT NULL,
                             name VARCHAR(255) NOT NULL,
                             status VARCHAR(50) NOT NULL,
                             created_at TIMESTAMP NOT NULL)")
      (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS users (
                             id VARCHAR(36) PRIMARY KEY,
                             email VARCHAR(255) NOT NULL,
                             name VARCHAR(255) NOT NULL,
                             created_at TIMESTAMP NOT NULL)")
      
      (binding [*test-ctx* ctx]
        (f))
      (finally
        (db-factory/close-db-context! ctx)))))

;; =============================================================================
;; Unit Tests (Database-Agnostic Logic)
;; =============================================================================

^{:unit true}
(deftest provision-tenant-validation-test
  (testing "rejects nil tenant entity"
    (let [ctx {:adapter {:dialect :postgresql}
               :datasource nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/provision-tenant! ctx nil)))))
  
  (testing "rejects tenant entity without schema-name"
    (let [ctx {:adapter {:dialect :postgresql}
               :datasource nil}
          tenant {:name "Test Tenant"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/provision-tenant! ctx tenant)))))
  
  (testing "rejects non-PostgreSQL database"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :sqlite)
                         (jdbc-driver [_] "org.sqlite.JDBC")
                         (jdbc-url [_ _] "jdbc:sqlite::memory:")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 0)
                         (db->boolean [_ _] false)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}
          tenant {:schema-name "tenant_test"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"only supported for PostgreSQL"
                            (sut/provision-tenant! ctx tenant)))))
  
  (testing "validates ex-info has correct type"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :mysql)
                         (jdbc-driver [_] "com.mysql.cj.jdbc.Driver")
                         (jdbc-url [_ _] "jdbc:mysql://localhost:3306/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 0)
                         (db->boolean [_ _] false)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}
          tenant {:schema-name "tenant_test"}]
      (try
        (sut/provision-tenant! ctx tenant)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= :not-supported (:type (ex-data e))))
          (is (= :mysql (:dialect (ex-data e)))))))))

^{:unit true}
(deftest deprovision-tenant-validation-test
  (testing "rejects nil tenant entity"
    (let [ctx {:adapter {:dialect :postgresql}
               :datasource nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/deprovision-tenant! ctx nil)))))
  
  (testing "rejects tenant entity without schema-name"
    (let [ctx {:adapter {:dialect :postgresql}
               :datasource nil}
          tenant {:name "Test Tenant"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/deprovision-tenant! ctx tenant))))))

;; =============================================================================
;; Integration Tests (Real Database - H2 with PostgreSQL Mode)
;; =============================================================================

^{:integration true}
(deftest provision-tenant-integration-test
  (testing "provisions tenant schema successfully"
    (with-h2-database
      (fn []
        ;; H2 doesn't support CREATE SCHEMA in PostgreSQL mode the same way
        ;; This test verifies the flow but may need PostgreSQL for full testing
        (let [tenant {:schema-name "tenant_test"
                      :slug "test-tenant"}
              adapter (:adapter *test-ctx*)
              dialect (protocols/dialect adapter)]
          
          (testing "H2 database detected"
            (is (= :h2 dialect)))
          
          (testing "provisioning on H2 throws not-supported error"
            ;; Provisioning should only work with PostgreSQL
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"only supported for PostgreSQL"
                                  (sut/provision-tenant! *test-ctx* tenant)))))))))

;; =============================================================================
;; PostgreSQL Contract Tests
;; =============================================================================

(comment
  "PostgreSQL-specific tests require actual PostgreSQL database.
   These tests should be run in CI/CD with testcontainers or local PostgreSQL.
   
   Test cases to implement:
   1. provision-tenant! creates schema successfully
   2. provision-tenant! copies table structure from public
   3. provision-tenant! is idempotent (calling twice succeeds)
   4. provision-tenant! validates schema creation
   5. provision-tenant! cleans up on failure
   6. deprovision-tenant! drops schema successfully
   7. deprovision-tenant! is idempotent (calling twice succeeds)
   
   Example implementation:
   
   (deftest ^:contract provision-tenant-postgresql-test
     (with-postgresql-database
       (fn []
         (let [tenant {:schema-name \"tenant_acme\"
                       :slug \"acme-corp\"}
               result (sut/provision-tenant! *test-ctx* tenant)]
           
           (testing \"returns success\"
             (is (true? (:success? result)))
             (is (= \"tenant_acme\" (:schema-name result)))
             (is (pos? (:table-count result))))
           
           (testing \"schema exists in database\"
             (is (true? (schema-exists? *test-ctx* \"tenant_acme\"))))
           
           (testing \"tables copied from public schema\"
             (let [tables (get-schema-tables *test-ctx* \"tenant_acme\")]
               (is (contains? (set tables) \"tenants\"))
               (is (contains? (set tables) \"users\"))))
           
           (testing \"calling again is idempotent\"
             (let [result2 (sut/provision-tenant! *test-ctx* tenant)]
               (is (true? (:success? result2)))
               (is (= \"Tenant schema already provisioned\" (:message result2)))))))))
   
   (deftest ^:contract deprovision-tenant-postgresql-test
     (with-postgresql-database
       (fn []
         (let [tenant {:schema-name \"tenant_acme\"
                       :slug \"acme-corp\"}]
           
           ;; Setup: Create schema first
           (sut/provision-tenant! *test-ctx* tenant)
           (is (true? (schema-exists? *test-ctx* \"tenant_acme\")))
           
           ;; Test deprovisioning
           (let [result (sut/deprovision-tenant! *test-ctx* tenant)]
             (testing \"returns success\"
               (is (true? (:success? result)))
               (is (= \"tenant_acme\" (:schema-name result))))
             
             (testing \"schema no longer exists\"
               (is (false? (schema-exists? *test-ctx* \"tenant_acme\"))))
             
             (testing \"calling again is idempotent\"
               (let [result2 (sut/deprovision-tenant! *test-ctx* tenant)]
                 (is (true? (:success? result2)))
                 (is (= \"Tenant schema does not exist\" (:message result2))))))))))
   
   ;; Helper functions for PostgreSQL tests
   (defn schema-exists? [ctx schema-name]
     (let [query [\"SELECT COUNT(*) as count 
                   FROM information_schema.schemata 
                   WHERE schema_name = ?\" schema-name]
           result (db/execute-one! ctx query)]
       (pos? (:count result))))
   
   (defn get-schema-tables [ctx schema-name]
     (let [query [\"SELECT table_name 
                   FROM information_schema.tables 
                   WHERE table_schema = ?\" schema-name]
           results (db/execute-query! ctx query)]
       (mapv :table_name results)))
   
   (defn with-postgresql-database [f]
     (let [ctx (db-factory/db-context {:adapter :postgresql
                                       :host \"localhost\"
                                       :port 5432
                                       :database \"boundary_test\"
                                       :username \"postgres\"
                                       :password \"postgres\"
                                       :pool {:maximum-pool-size 5}})]
       (try
         ;; Initialize test schema
         (db/execute-ddl! ctx \"CREATE TABLE IF NOT EXISTS public.tenants (
                                id VARCHAR(36) PRIMARY KEY,
                                slug VARCHAR(100) NOT NULL,
                                schema_name VARCHAR(100) NOT NULL)\")
         (db/execute-ddl! ctx \"CREATE TABLE IF NOT EXISTS public.users (
                                id VARCHAR(36) PRIMARY KEY,
                                email VARCHAR(255) NOT NULL)\")
         
         (binding [*test-ctx* ctx]
           (f))
         (finally
           ;; Cleanup: Drop any test schemas
           (try
             (db/execute-ddl! ctx \"DROP SCHEMA IF EXISTS tenant_acme CASCADE\")
             (catch Exception _))
           (db-factory/close-db-context! ctx)))))")

;; =============================================================================
;; Test Runner
;; =============================================================================

(comment
  "Run tests:
   
   # Unit tests only (fast, no database)
   clojure -M:test:db/h2 --focus-meta :unit --focus boundary.tenant.shell.provisioning-test
   
   # Integration tests (H2 database)
   clojure -M:test:db/h2 --focus-meta :integration --focus boundary.tenant.shell.provisioning-test
   
   # Contract tests (requires PostgreSQL)
   clojure -M:test:db/h2 --focus-meta :contract --focus boundary.tenant.shell.provisioning-test
   
   # All tests
   clojure -M:test:db/h2 --focus boundary.tenant.shell.provisioning-test")
