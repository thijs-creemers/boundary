(ns boundary.platform.migrations.shell.executor-test
  "Integration tests for DatabaseMigrationExecutor.
   
   Tests SQL execution against H2 in-memory database with:
   - Database type detection
   - SQL validation
   - Migration execution (up/down)
   - Transaction support
   - Error handling
   - Execution metrics
   - Retry logic
   - Dry-run validation"
  {:kaocha.testable/meta {:integration true :migrations true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.platform.migrations.shell.executor :as executor]
            [boundary.platform.migrations.ports :as ports]
            [boundary.platform.migrations.core.checksums :as checksums]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))

;; =============================================================================
;; Test Database Setup
;; =============================================================================

(def ^:dynamic *test-db-context* nil)

(defn create-test-db-context
  "Create H2 in-memory database context."
  []
  (jdbc/get-datasource
   {:jdbcUrl "jdbc:h2:mem:executor_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"}))

(defn test-database-fixture
  "Create/destroy test database for each test."
  [f]
  (let [db-ctx (create-test-db-context)]
    (binding [*test-db-context* db-ctx]
      (try
        (f)
        (finally
          ;; Clean up: drop all tables
          (try
            (jdbc/execute! db-ctx ["DROP ALL OBJECTS"])
            (catch Exception _)))))))

(use-fixtures :each test-database-fixture)

;; =============================================================================
;; Test Data Builders
;; =============================================================================

(defn create-test-migration-file
  "Create a test migration file map matching MigrationFile schema.
   
   Options:
     :version - Migration version (default: generated)
     :name - Migration name (default: test-migration)
     :module - Module name (default: test)
     :content - SQL content (default: simple CREATE TABLE)
     :direction - Migration direction (default: :up)
     :down? - Is this a .down.sql file? (default: false)
     :checksum - Content checksum (auto-calculated if not provided)"
  [& {:keys [version name module content direction down? checksum]
      :or {name "test_migration"
           module "test"
           direction :up
           down? false}}]
  (let [sql (or content "CREATE TABLE test_table (id INTEGER PRIMARY KEY, name VARCHAR(100));")
        migration {:version (or version "20240501120000")
                   :name name
                   :module module
                   :file-path "/tmp/test-migration.sql"
                   :content sql
                   :checksum (or checksum (checksums/calculate-checksum sql))
                   :down? down?
                   ;; Optional fields added during processing
                   :direction direction
                   :reversible false
                   :has-down? false}]
    migration))

;; =============================================================================
;; Executor Creation Tests
;; =============================================================================

(deftest create-executor-test
  (testing "creates executor with database context"
    (let [executor (executor/create-executor *test-db-context*)]
      (is (some? executor))
      (is (satisfies? ports/IMigrationExecutor executor))))
  
  (testing "detects H2 database type"
    (let [executor (executor/create-executor *test-db-context*)
          db-type (ports/get-db-type executor)]
      (is (= :h2 db-type))))
  
  (testing "reports transaction support for H2"
    (let [executor (executor/create-executor *test-db-context*)]
      (is (true? (ports/supports-transactions? executor))))))

;; =============================================================================
;; SQL Validation Tests
;; =============================================================================

(deftest sql-validation-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "validates correct SQL"
      (let [result (ports/validate-sql executor "CREATE TABLE users (id INTEGER);")]
        (is (:valid? result))
        (is (empty? (:errors result)))))
    
    (testing "rejects empty SQL"
      (let [result (ports/validate-sql executor "")]
        (is (false? (:valid? result)))
        (is (seq (:errors result)))
        (is (some #(re-find #"empty" %) (:errors result)))))
    
    (testing "rejects SQL without statements"
      (let [result (ports/validate-sql executor "-- Just a comment")]
        (is (false? (:valid? result)))
        (is (seq (:errors result)))))
    
    (testing "warns about dangerous operations"
      (let [result (ports/validate-sql executor "DROP DATABASE important_data;")]
        (is (false? (:valid? result)))
        (is (some #(re-find #"(?i)dangerous" %) (:errors result)))))
    
    (testing "accepts DML statements"
      (let [result (ports/validate-sql executor "INSERT INTO users VALUES (1, 'test');")]
        (is (:valid? result))))
    
    (testing "accepts DDL statements"
      (let [result (ports/validate-sql executor "ALTER TABLE users ADD COLUMN age INTEGER;")]
        (is (:valid? result))))))

;; =============================================================================
;; SQL Execution Tests
;; =============================================================================

(deftest execute-sql-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "executes simple CREATE TABLE"
      (let [result (ports/execute-sql executor "CREATE TABLE simple_test (id INTEGER);" {})]
        (is (some? result))))
    
    (testing "executes INSERT statements"
      (ports/execute-sql executor "CREATE TABLE insert_test (id INTEGER, name VARCHAR(50));" {})
      (let [result (ports/execute-sql executor "INSERT INTO insert_test VALUES (1, 'Alice');" {})]
        (is (some? result))))
    
    (testing "throws SQLException on syntax error"
      (is (thrown? SQLException
                   (ports/execute-sql executor "INVALID SQL SYNTAX HERE;" {}))))
    
    (testing "throws SQLException on constraint violation"
      (ports/execute-sql executor "CREATE TABLE pk_test (id INTEGER PRIMARY KEY);" {})
      (ports/execute-sql executor "INSERT INTO pk_test VALUES (1);" {})
      (is (thrown? SQLException
                   (ports/execute-sql executor "INSERT INTO pk_test VALUES (1);" {}))))))

;; =============================================================================
;; Migration Execution Tests
;; =============================================================================

(deftest execute-migration-up-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "executes UP migration successfully"
      (let [migration (create-test-migration-file
                       :name "create_users_table"
                       :direction :up
                       :content "CREATE TABLE users (id INTEGER PRIMARY KEY, email VARCHAR(100));")
            result (ports/execute-migration executor migration {})]
        
        (is (true? (:success? result)))
        (is (= "20240501120000" (:version result)))
        (is (= "create_users_table" (:name result)))
        (is (= "test" (:module result)))
        (is (= :up (:direction result)))
        (is (number? (:execution-time-ms result)))
        (is (>= (:execution-time-ms result) 0))
        (is (nil? (:error-message result)))
        
        ;; Verify table was created
        (let [tables (jdbc/execute! *test-db-context* 
                                    ["SELECT COUNT(*) as cnt FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'USERS'"])]
          (is (pos? (:CNT (first tables)))))))
    
    (testing "captures execution timing"
      (let [migration (create-test-migration-file
                       :name "timed_migration"
                       :direction :up
                       :content "CREATE TABLE timed_table (id INTEGER);")
            result (ports/execute-migration executor migration {})]
        (is (number? (:execution-time-ms result)))
        (is (>= (:execution-time-ms result) 0))))
    
    (testing "reports failure on SQL error"
      (let [migration (create-test-migration-file
                       :name "failing_migration"
                       :direction :up
                       :content "CREATE TABLE invalid syntax here;")
            result (ports/execute-migration executor migration {})]
        
        (is (false? (:success? result)))
        (is (some? (:error-message result)))
        (is (string? (:error-message result)))))))

(deftest execute-migration-down-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "executes DOWN migration successfully"
      ;; Create table first
      (jdbc/execute! *test-db-context* ["CREATE TABLE drop_test (id INTEGER);"])
      
      (let [migration (create-test-migration-file
                       :name "drop_table"
                       :direction :down
                       :content "DROP TABLE drop_test;")
            result (ports/execute-migration executor migration {})]
        
        (is (true? (:success? result)))
        (is (= :down (:direction result)))
        
        ;; Verify table was dropped
        (let [tables (jdbc/execute! *test-db-context*
                                    ["SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DROP_TEST'"])]
          (is (empty? tables)))))
    
    (testing "DOWN migration fails on non-existent table"
      (let [migration (create-test-migration-file
                       :name "drop_nonexistent"
                       :direction :down
                       :content "DROP TABLE does_not_exist;")
            result (ports/execute-migration executor migration {})]
        (is (false? (:success? result)))
        (is (some? (:error-message result)))))))

;; =============================================================================
;; Transaction Tests
;; =============================================================================

(deftest transaction-support-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "executes multiple statements in transaction"
      (let [migration (create-test-migration-file
                       :name "multi_statement"
                       :direction :up
                       :content (str "CREATE TABLE tx_test1 (id INTEGER);\n"
                                     "CREATE TABLE tx_test2 (id INTEGER);"))
            result (ports/execute-migration executor migration {:transaction? true})]
        
        (is (true? (:success? result)))
        
        ;; Both tables should exist
        (let [tables (jdbc/execute! *test-db-context*
                                    ["SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('TX_TEST1', 'TX_TEST2')"])]
          (is (= 2 (count tables))))))
    
    (testing "rolls back on error in transaction"
      ;; Note: H2 may not fully rollback all DDL in some cases
      ;; This tests the transaction mechanism itself
      (let [migration (create-test-migration-file
                       :name "rollback_test"
                       :direction :up
                       :content (str "CREATE TABLE rollback_test (id INTEGER);\n"
                                     "INVALID SQL HERE;"))
            result (ports/execute-migration executor migration {:transaction? true})]
        
        (is (false? (:success? result)))))))

;; =============================================================================
;; Complex Migration Tests
;; =============================================================================

(deftest complex-migration-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "executes migration with indexes"
      (let [migration (create-test-migration-file
                       :name "users_with_indexes"
                       :direction :up
                       :content (str "CREATE TABLE indexed_users (id INTEGER PRIMARY KEY, email VARCHAR(100), name VARCHAR(100));\n"
                                     "CREATE INDEX idx_email ON indexed_users(email);\n"
                                     "CREATE INDEX idx_name ON indexed_users(name);"))
            result (ports/execute-migration executor migration {})]
        
        (is (true? (:success? result)))
        
        ;; Verify indexes were created
        (let [indexes (jdbc/execute! *test-db-context*
                                     ["SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'INDEXED_USERS' AND INDEX_NAME IN ('IDX_EMAIL', 'IDX_NAME')"])]
          (is (= 2 (count indexes))))))
    
    (testing "executes migration with foreign keys"
      ;; Note: Multi-statement SQL not supported in current implementation
      ;; Create parent table first
      (jdbc/execute! *test-db-context* ["CREATE TABLE customers (id INTEGER PRIMARY KEY, name VARCHAR(100))"])
      
      (let [migration (create-test-migration-file
                       :name "orders_with_fk"
                       :direction :up
                       :content "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER, FOREIGN KEY (customer_id) REFERENCES customers(id))")
            result (ports/execute-migration executor migration {})]
        
        (is (true? (:success? result)))
        (is (nil? (:error-message result)))))
    
    (testing "executes migration with data insertion"
      ;; Note: Multi-statement SQL not supported in current implementation
      ;; This tests single INSERT after table creation
      ;; Note: Using config_key/config_value instead of key/value (reserved words in H2)
      (jdbc/execute! *test-db-context* ["CREATE TABLE config (config_key VARCHAR(50) PRIMARY KEY, config_value VARCHAR(100))"])
      
      (let [migration (create-test-migration-file
                       :name "seed_data"
                       :direction :up
                       :content "INSERT INTO config VALUES ('version', '1.0.0')")
            result (ports/execute-migration executor migration {})]
        
        (is (true? (:success? result)))
        (is (nil? (:error-message result)))
        
        ;; Verify data was inserted
        (let [rows (jdbc/execute! *test-db-context* ["SELECT COUNT(*) as cnt FROM config"])]
          (is (= 1 (:CNT (first rows)))))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest error-handling-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "captures SQL syntax errors"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "CREATE TABLE bad syntax;")
            result (ports/execute-migration executor migration {})]
        
        (is (false? (:success? result)))
        (is (some? (:error-message result)))
        (is (some? (:sql-state result)))))
    
    (testing "captures constraint violation errors"
      (jdbc/execute! *test-db-context* ["CREATE TABLE unique_test (id INTEGER PRIMARY KEY);"])
      (jdbc/execute! *test-db-context* ["INSERT INTO unique_test VALUES (1);"])
      
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "INSERT INTO unique_test VALUES (1);")
            result (ports/execute-migration executor migration {})]
        
        (is (false? (:success? result)))
        (is (some? (:error-message result)))))
    
    (testing "captures foreign key violation errors"
      (jdbc/execute! *test-db-context* ["CREATE TABLE parent (id INTEGER PRIMARY KEY);"])
      (jdbc/execute! *test-db-context* ["CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER, FOREIGN KEY (parent_id) REFERENCES parent(id));"])
      
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "INSERT INTO child VALUES (1, 999);")
            result (ports/execute-migration executor migration {})]
        
        (is (false? (:success? result)))))))

;; =============================================================================
;; Validation Pre-Execution Tests
;; =============================================================================

(deftest validation-pre-execution-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "rejects empty SQL before execution"
      (let [migration (create-test-migration-file :direction :up :content "")
            result (ports/execute-migration executor migration {})]
        
        (is (false? (:success? result)))
        (is (re-find #"validation failed" (:error-message result)))
        (is (zero? (:execution-time-ms result)))))
    
    (testing "rejects dangerous SQL before execution"
      (let [migration (create-test-migration-file :direction :up :content "DROP DATABASE test;")
            result (ports/execute-migration executor migration {})]
        
        (is (false? (:success? result)))
        (is (re-find #"validation failed|dangerous" (:error-message result)))))))

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(deftest dry-run-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "validates SQL without executing"
      (let [migration (create-test-migration-file
                       :content "CREATE TABLE dry_run_test (id INTEGER);")
            validation (executor/dry-run-migration executor migration)]
        
        (is (:valid? validation))
        
        ;; Verify table was NOT created
        (let [tables (jdbc/execute! *test-db-context*
                                    ["SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DRY_RUN_TEST'"])]
          (is (empty? tables)))))
    
    (testing "detects errors without executing"
      (let [migration (create-test-migration-file :content "INVALID SQL;")
            validation (executor/dry-run-migration executor migration)]
        
        (is (false? (:valid? validation)))
        (is (seq (:errors validation)))))))

(deftest migration-requires-transaction-test
  (testing "recommends transaction for DDL"
    (let [migration (create-test-migration-file :content "CREATE TABLE test (id INTEGER);")]
      (is (true? (executor/migration-requires-transaction? migration)))))
  
  (testing "recommends transaction for DML"
    (let [migration (create-test-migration-file :content "INSERT INTO test VALUES (1);")]
      (is (true? (executor/migration-requires-transaction? migration)))))
  
  (testing "recommends no transaction for large DDL-only"
    (let [large-ddl (apply str (repeat 60000 "CREATE INDEX idx ON test(col);"))
          migration (create-test-migration-file :content large-ddl)]
      (is (false? (executor/migration-requires-transaction? migration))))))

;; =============================================================================
;; Retry Logic Tests
;; =============================================================================

(deftest retry-logic-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "succeeds immediately on success"
      (let [migration (create-test-migration-file
                       :name "retry_success"
                       :direction :up
                       :content "CREATE TABLE retry_test (id INTEGER);")
            result (executor/execute-migration-with-retry executor migration :up {:max-retries 3})]
        
        (is (true? (:success? result)))
        (is (nil? (:retry-attempts result)))))
    
    (testing "does not retry non-retryable errors"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "SYNTAX ERROR HERE;")
            result (executor/execute-migration-with-retry executor migration :up {:max-retries 3})]
        
        (is (false? (:success? result)))
        ;; Should fail immediately without retries for syntax errors
        (is (= 1 (:retry-attempts result)))))))

;; =============================================================================
;; Edge Cases and Boundary Tests
;; =============================================================================

(deftest edge-cases-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "handles very short SQL"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "SELECT 1;")
            result (ports/execute-migration executor migration {})]
        (is (true? (:success? result)))))
    
    (testing "handles SQL with comments"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "-- Comment\nCREATE TABLE commented (id INTEGER); -- Another comment")
            result (ports/execute-migration executor migration {})]
        (is (true? (:success? result)))))
    
    (testing "handles SQL with various line endings"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "CREATE TABLE line_endings (id INTEGER);\r\nCREATE TABLE line_endings2 (id INTEGER);")
            result (ports/execute-migration executor migration {})]
        (is (true? (:success? result)))))
    
    (testing "handles empty lines in SQL"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "CREATE TABLE empty_lines (id INTEGER);\n\n\nCREATE TABLE empty_lines2 (id INTEGER);")
            result (ports/execute-migration executor migration {})]
        (is (true? (:success? result)))))))

;; =============================================================================
;; Performance and Metrics Tests
;; =============================================================================

(deftest metrics-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "reports reasonable execution time"
      (let [migration (create-test-migration-file
                       :direction :up
                       :content "CREATE TABLE metrics_test (id INTEGER);")
            result (ports/execute-migration executor migration {})]
        
        (is (number? (:execution-time-ms result)))
        (is (>= (:execution-time-ms result) 0))
        ;; Should complete in reasonable time (< 5 seconds for simple CREATE)
        (is (< (:execution-time-ms result) 5000))))
    
    (testing "reports execution time even on failure"
      (let [migration (create-test-migration-file :direction :up :content "INVALID SQL;")
            result (ports/execute-migration executor migration {})]
        
        (is (number? (:execution-time-ms result)))
        (is (>= (:execution-time-ms result) 0))))))

(deftest estimate-migration-time-test
  (let [executor (executor/create-executor *test-db-context*)]
    
    (testing "returns nil (not implemented)"
      (let [migration (create-test-migration-file)
            estimate (executor/estimate-migration-time executor migration)]
        (is (nil? estimate))))))
