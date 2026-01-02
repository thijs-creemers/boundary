(ns boundary.platform.migrations.shell.executor
  "SQL execution engine for database migrations.
   
   Provides concrete implementation of IMigrationExecutor protocol
   for executing migration SQL with transaction support.
   
   Architecture:
   - Implements: boundary.platform.migrations.ports/IMigrationExecutor
   - Uses: next.jdbc for SQL execution
   - Uses: boundary.platform.migrations.schema for validation
   - Pure delegation to database layer
   
   Responsibilities:
   - Execute migration SQL (up/down) within transactions
   - Capture execution timing
   - Validate SQL syntax (basic checks)
   - Handle database-specific behaviors
   
   Non-responsibilities:
   - Migration planning (see core/planning.clj)
   - Checksum verification (see core/checksums.clj)
   - File discovery (see discovery.clj)
   - Locking (see locking/)"
  (:require [boundary.platform.migrations.ports :as ports]
            [boundary.platform.migrations.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))

;; -----------------------------------------------------------------------------
;; Database Type Detection
;; -----------------------------------------------------------------------------

(defn- detect-db-type
  "Detect database type from JDBC connection metadata.
   
   Args:
     db-ctx - next.jdbc database connection
     
   Returns:
     Keyword - :postgresql, :h2, :sqlite, :mysql, or :unknown
     
   Pure: false (reads database metadata)"
  [db-ctx]
  (try
    (with-open [conn (jdbc/get-connection db-ctx)]
      (let [metadata (.getMetaData conn)
            product-name (.getDatabaseProductName metadata)
            product-lower (clojure.string/lower-case product-name)]
        (cond
          (clojure.string/includes? product-lower "postgresql") :postgresql
          (clojure.string/includes? product-lower "h2") :h2
          (clojure.string/includes? product-lower "sqlite") :sqlite
          (clojure.string/includes? product-lower "mysql") :mysql
          :else :unknown)))
    (catch Exception _
      :unknown)))

(defn- supports-transactions?*
  "Check if database supports transactions.
   
   Most databases support transactions. SQLite supports them
   but has some limitations with concurrent writes.
   
   Args:
     db-type - Database type keyword
     
   Returns:
     Boolean - true if transactions are supported
     
   Pure: true"
  [db-type]
  (case db-type
    :postgresql true
    :h2 true
    :sqlite true  ; Supports but with limitations
    :mysql true
    false))  ; Unknown databases default to false

;; -----------------------------------------------------------------------------
;; SQL Validation
;; -----------------------------------------------------------------------------

(defn- validate-sql*
  "Basic SQL syntax validation.
   
   Performs simple checks:
   - Non-empty content
   - Contains at least one SQL statement
   - No obviously dangerous patterns (basic check)
   
   Note: This is NOT comprehensive SQL parsing. Database will
   perform full validation during execution.
   
   Args:
     sql - SQL string to validate
     
   Returns:
     Map with :valid? boolean and :errors vector
     
   Pure: true"
  [sql]
  (cond
    (empty? sql)
    {:valid? false :errors ["SQL content is empty"]}
    
    (not (re-find #"(?i)(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|SELECT)" sql))
    {:valid? false :errors ["No SQL statements detected"]}
    
    ;; Warn about potentially dangerous patterns (but don't block)
    (re-find #"(?i)(DROP\s+DATABASE|TRUNCATE\s+ALL)" sql)
    {:valid? false :errors ["Potentially dangerous SQL detected (DROP DATABASE, TRUNCATE ALL)"]}
    
    :else
    {:valid? true :errors []}))

;; -----------------------------------------------------------------------------
;; SQL Execution
;; -----------------------------------------------------------------------------

(defn- execute-sql-with-timing
  "Execute SQL and measure execution time.
   
   Args:
     db-ctx - Database context
     sql - SQL string to execute
     
   Returns:
     Map with :result, :execution-time-ms, :rows-affected
     
   Throws:
     SQLException - If SQL execution fails
     
   Pure: false (executes SQL)"
  [db-ctx sql]
  (let [start-time (System/currentTimeMillis)]
    (try
      (let [result (jdbc/execute! db-ctx [sql])
            end-time (System/currentTimeMillis)
            execution-time (- end-time start-time)
            rows-affected (if (map? result)
                            (get result :next.jdbc/update-count 0)
                            (count result))]
        {:result result
         :execution-time-ms execution-time
         :rows-affected rows-affected
         :success? true})
      (catch SQLException e
        (let [end-time (System/currentTimeMillis)
              execution-time (- end-time start-time)]
          {:result nil
           :execution-time-ms execution-time
           :rows-affected 0
           :success? false
           :error (.getMessage e)
           :sql-state (.getSQLState e)
           :error-code (.getErrorCode e)})))))

(defn- execute-migration-sql
  "Execute migration SQL with transaction support.
   
   Args:
     db-ctx - Database context
     migration-file - MigrationFile entity
     direction - :up or :down
     use-transaction? - Boolean, wrap in transaction
     
   Returns:
     MigrationExecutionResult entity
     
   Throws:
     SQLException - If execution fails and not caught
     
   Pure: false (executes SQL, side effects)"
  [db-ctx migration-file direction use-transaction?]
  {:pre [(m/validate schema/MigrationFile migration-file)
         (#{:up :down} direction)
         (boolean? use-transaction?)]}
  
  (let [sql (:content migration-file)
        version (:version migration-file)
        name (:name migration-file)
        module (:module migration-file)
        
        ;; Validate SQL before execution
        validation (validate-sql* sql)]
    
    (if-not (:valid? validation)
      ;; Return failure without executing
      {:version version
       :name name
       :module module
       :direction direction
       :success? false
       :execution-time-ms 0
       :error-message (str "SQL validation failed: " (first (:errors validation)))
       :sql-state nil
       :rows-affected 0}
      
      ;; Execute SQL
      (let [exec-fn (fn []
                      (execute-sql-with-timing db-ctx sql))
            
            result (if use-transaction?
                     (jdbc/with-transaction [tx db-ctx]
                       (exec-fn))
                     (exec-fn))]
        
        ;; Transform to MigrationExecutionResult
        {:version version
         :name name
         :module module
         :direction direction
         :success? (:success? result)
         :execution-time-ms (:execution-time-ms result)
         :error-message (:error result)
         :sql-state (:sql-state result)
         :rows-affected (:rows-affected result)}))))

;; -----------------------------------------------------------------------------
;; Executor Implementation
;; -----------------------------------------------------------------------------

(defrecord DatabaseMigrationExecutor [db-ctx db-type]
  ports/IMigrationExecutor
  
  (execute-migration [this migration-file opts]
    {:pre [(m/validate schema/MigrationFile migration-file)]}
    (let [direction (get migration-file :direction :up)
          use-transaction? (get opts :transaction? true)]
      (execute-migration-sql db-ctx migration-file direction use-transaction?)))
  
  (execute-sql [this sql opts]
    {:pre [(string? sql)]}
    (let [result (execute-sql-with-timing db-ctx sql)]
      (if (:success? result)
        (:result result)
        (throw (SQLException. 
                 (str "SQL execution failed: " (:error result))
                 (:sql-state result)
                 (:error-code result 0))))))
  
  (validate-sql [this sql]
    {:pre [(string? sql)]}
    (validate-sql* sql))
  
  (supports-transactions? [this]
    (supports-transactions?* db-type))
  
  (get-db-type [this]
    db-type))

;; -----------------------------------------------------------------------------
;; Factory Functions
;; -----------------------------------------------------------------------------

(defn create-executor
  "Create a new DatabaseMigrationExecutor instance.
   
   Args:
     db-ctx - next.jdbc database connection or datasource
     
   Returns:
     DatabaseMigrationExecutor instance implementing IMigrationExecutor
     
   Example:
     (def executor (create-executor db-ctx))
     (ports/get-db-type executor)  ; => :postgresql"
  [db-ctx]
  {:pre [db-ctx]}
  (let [db-type (detect-db-type db-ctx)]
    (->DatabaseMigrationExecutor db-ctx db-type)))

;; -----------------------------------------------------------------------------
;; Utility Functions
;; -----------------------------------------------------------------------------

(defn execute-migration-with-retry
  "Execute migration with retry logic for transient failures.
   
   Retries only on specific transient errors (connection issues, etc.)
   Does NOT retry on syntax errors or constraint violations.
   
   Args:
     executor - IMigrationExecutor instance
     migration-file - MigrationFile entity (with :direction key)
     direction - :up or :down (deprecated, use :direction in migration-file)
     options - Execution options map with optional :max-retries
     
   Returns:
     MigrationExecutionResult entity
     
   Example:
     (execute-migration-with-retry executor migration :up {:max-retries 3})"
  [executor migration-file direction options]
  (let [max-retries (get options :max-retries 3)
        retry-delay-ms (get options :retry-delay-ms 1000)
        ;; Merge direction into migration-file for backwards compatibility
        migration (assoc migration-file :direction direction)]
    
    (loop [attempt 1]
      (let [result (ports/execute-migration executor migration {})]
        (if (:success? result)
          result
          
          ;; Check if error is retryable
          (let [error-msg (or (:error-message result) "")
                retryable? (or (str/includes? error-msg "connection")
                               (str/includes? error-msg "timeout")
                               (str/includes? error-msg "deadlock"))]
            
            (if (and retryable? (< attempt max-retries))
              (do
                (Thread/sleep retry-delay-ms)
                (recur (inc attempt)))
              
              ;; Return final failure
              (assoc result :retry-attempts attempt))))))))
(defn dry-run-migration
  "Validate migration without executing (dry run).
   
   Only validates SQL syntax, does NOT execute against database.
   
   Args:
     executor - IMigrationExecutor instance
     migration-file - MigrationFile entity
     
   Returns:
     Map with :valid? boolean and :errors vector
     
   Example:
     (dry-run-migration executor migration)"
  [executor migration-file]
  (ports/validate-sql executor (:content migration-file)))

(defn estimate-migration-time
  "Estimate migration execution time (placeholder).
   
   Current implementation returns nil (not implemented).
   Future: Could analyze SQL complexity for estimates.
   
   Args:
     executor - IMigrationExecutor instance
     migration-file - MigrationFile entity
     
   Returns:
     Integer milliseconds estimate, or nil if not available"
  [executor migration-file]
  nil)  ; Future enhancement

(defn migration-requires-transaction?
  "Determine if migration should use transaction.
   
   Recommendations:
   - DDL (CREATE/ALTER/DROP) - Use transaction if DB supports
   - DML (INSERT/UPDATE/DELETE) - Always use transaction
   - Mixed DDL+DML - Use transaction if DB supports
   - Large batch operations - May want to disable for progress tracking
   
   Args:
     migration-file - MigrationFile entity
     
   Returns:
     Boolean - true if transaction recommended
     
   Pure: true"
  [migration-file]
  (let [sql (:content migration-file)
        has-ddl? (re-find #"(?i)(CREATE|ALTER|DROP)" sql)
        has-dml? (re-find #"(?i)(INSERT|UPDATE|DELETE)" sql)]
    
    ;; Default to true (safer)
    ;; Only disable explicitly for specific cases
    (not (and has-ddl? 
              (not has-dml?)
              (> (count sql) 50000)))))  ; Large DDL-only migrations
