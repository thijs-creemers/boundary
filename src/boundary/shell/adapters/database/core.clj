(ns boundary.shell.adapters.database.core
  "Core database operations shared across all database adapters.
   
   This namespace provides common database functionality that works across
   different database types (SQLite, PostgreSQL, MySQL, H2) by delegating
   database-specific behavior to adapter protocol implementations.
   
   Key Features:
   - Connection pool management with HikariCP
   - Query execution with consistent logging and error handling
   - Transaction management with proper rollback handling  
   - Schema introspection and DDL execution
   - Query building utilities with database-specific adaptations
   - Dialect-aware HoneySQL formatting
   
   Usage:
     (require '[boundary.shell.adapters.database.core :as db])
     
     (db/execute-query! ctx {:select [:*] :from [:users]})
     (db/with-transaction [tx ctx] ...)"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

;; =============================================================================
;; Database Context Type
;; =============================================================================

(defn db-context?
  "Check if value is a valid database context.
   
   Args:
     ctx: Value to check
     
   Returns:
     Boolean - true if valid database context"
  [ctx]
  (and (map? ctx)
       (:datasource ctx)
       (:adapter ctx)
       (satisfies? protocols/DBAdapter (:adapter ctx))))

(defn validate-context
  "Validate database context and throw if invalid.
   
   Args:
     ctx: Database context to validate
     
   Returns:
     ctx if valid
     
   Throws:
     IllegalArgumentException if invalid"
  [ctx]
  (if (db-context? ctx)
    ctx
    (throw (IllegalArgumentException. 
            (str "Invalid database context. Expected map with :datasource and :adapter keys. Got: " 
                 (type ctx))))))

;; =============================================================================
;; Connection Pool Management
;; =============================================================================

(defn create-connection-pool
  "Create HikariCP connection pool using database adapter configuration.
   
   Args:
     adapter: Database adapter implementing DBAdapter protocol
     db-config: Database configuration map
     
   Returns:
     HikariDataSource configured for the database type
     
   Example:
     (create-connection-pool sqlite-adapter {:database-path \"./app.db\"})"
  [adapter db-config]
  {:pre [(satisfies? protocols/DBAdapter adapter)
         (map? db-config)]}
  (protocols/validate-db-config db-config)
  
  (let [pool-config (or (:pool db-config) {})
        defaults (protocols/pool-defaults adapter)
        hikari-config (doto (HikariConfig.)
                        (.setDriverClassName (protocols/jdbc-driver adapter))
                        (.setJdbcUrl (protocols/jdbc-url adapter db-config))
                        (.setMinimumIdle (get pool-config :minimum-idle (:minimum-idle defaults 1)))
                        (.setMaximumPoolSize (get pool-config :maximum-pool-size (:maximum-pool-size defaults 10)))
                        (.setConnectionTimeout (get pool-config :connection-timeout-ms (:connection-timeout-ms defaults 30000)))
                        (.setIdleTimeout (get pool-config :idle-timeout-ms (:idle-timeout-ms defaults 600000)))
                        (.setMaxLifetime (get pool-config :max-lifetime-ms (:max-lifetime-ms defaults 1800000)))
                        (.setPoolName (str (or (when-let [d (protocols/dialect adapter)] (name d)) "default") "-Pool"))
                        (.setAutoCommit true))]
    
    (when-let [username (:username db-config)]
      (.setUsername hikari-config username))
    
    (when-let [password (:password db-config)]
      (.setPassword hikari-config password))
    
    (log/info "Creating database connection pool"
              {:adapter (protocols/dialect adapter)
               :pool-size (get pool-config :maximum-pool-size (:maximum-pool-size defaults 10))
               :pool-name (.getPoolName hikari-config)})
    
    (let [datasource (HikariDataSource. hikari-config)]
      ;; Initialize database-specific connection settings
      (protocols/init-connection! adapter datasource db-config)
      (log/info "Database connection pool created successfully"
                {:adapter (protocols/dialect adapter)
                 :pool-name (.getPoolName hikari-config)})
      datasource)))

(defn close-connection-pool!
  "Close HikariCP connection pool.
   
   Args:
     datasource: HikariDataSource to close
     
   Returns:
     nil"
  [datasource]
  (when (instance? HikariDataSource datasource)
    (let [pool-name (try (.getPoolName ^HikariDataSource datasource) 
                        (catch Exception _ "unknown"))]
      (log/info "Closing database connection pool" {:pool-name pool-name})
      (.close ^HikariDataSource datasource)
      (log/info "Database connection pool closed" {:pool-name pool-name}))))

;; =============================================================================
;; HoneySQL Formatting
;; =============================================================================

(defn format-sql
  "Format HoneySQL query map using adapter's dialect.
   
   Args:
     ctx: Database context
     query-map: HoneySQL query map
     
   Returns:
     Vector of [sql & params]
     
   Example:
     (format-sql ctx {:select [:*] :from [:users]})"
  [ctx query-map]
  (validate-context ctx)
  (if-let [dialect (protocols/dialect (:adapter ctx))]
    (sql/format query-map {:dialect dialect})
    (sql/format query-map)))

(defn format-sql*
  "Format HoneySQL query map with custom options.
   
   Args:
     ctx: Database context
     query-map: HoneySQL query map
     opts: Additional formatting options
     
   Returns:
     Vector of [sql & params]"
  [ctx query-map opts]
  (validate-context ctx)
  (let [dialect (protocols/dialect (:adapter ctx))
        dialect-opts (if dialect
                       (merge opts {:dialect dialect})
                       opts)]
    (sql/format query-map dialect-opts)))

;; =============================================================================
;; Query Execution
;; =============================================================================

(defn execute-query!
  "Execute SELECT query and return results.
   
   Args:
     ctx: Database context {:datasource ds :adapter adapter}
     query-map: HoneySQL query map
     
   Returns:
     Vector of result maps
     
   Example:
     (execute-query! ctx {:select [:*] :from [:users] :where [:= :active true]})"
  [ctx query-map]
  (validate-context ctx)
  (let [sql-query (format-sql ctx query-map)
        start-time (System/currentTimeMillis)]
    (log/debug "Executing query" 
               {:adapter (protocols/dialect (:adapter ctx))
                :sql (first sql-query) 
                :params (rest sql-query)})
    (try
      (let [result (jdbc/execute! (:datasource ctx) sql-query
                                  {:builder-fn rs/as-unqualified-lower-maps})
            duration (- (System/currentTimeMillis) start-time)]
        (log/debug "Query completed" 
                   {:adapter (protocols/dialect (:adapter ctx))
                    :duration-ms duration 
                    :row-count (count result)})
        result)
      (catch Exception e
        (log/error "Query failed" 
                   {:adapter (protocols/dialect (:adapter ctx))
                    :sql (first sql-query)
                    :error (.getMessage e)})
        (throw (ex-info "Database query failed"
                        {:adapter (protocols/dialect (:adapter ctx))
                         :sql (first sql-query)
                         :params (rest sql-query)
                         :original-error (.getMessage e)}
                        e))))))

(defn execute-one!
  "Execute query expecting single result.
   
   Args:
     ctx: Database context
     query-map: HoneySQL query map
     
   Returns:
     Single result map or nil
     
   Example:
     (execute-one! ctx {:select [:*] :from [:users] :where [:= :id \"123\"]})"
  [ctx query-map]
  (first (execute-query! ctx query-map)))

(defn execute-update!
  "Execute UPDATE/INSERT/DELETE query.
   
   Args:
     ctx: Database context
     query-map: HoneySQL query map
     
   Returns:
     Number of affected rows
     
   Example:
     (execute-update! ctx {:update :users :set {:active false} :where [:= :id \"123\"]})"
  [ctx query-map]
  (validate-context ctx)
  (let [sql-query (format-sql ctx query-map)
        start-time (System/currentTimeMillis)]
    (log/debug "Executing update" 
               {:adapter (protocols/dialect (:adapter ctx))
                :sql (first sql-query) 
                :params (rest sql-query)})
    (try
      (let [result (jdbc/execute! (:datasource ctx) sql-query)
            duration (- (System/currentTimeMillis) start-time)
            affected-rows (::jdbc/update-count (first result))]
        (log/debug "Update completed" 
                   {:adapter (protocols/dialect (:adapter ctx))
                    :duration-ms duration 
                    :affected-rows affected-rows})
        affected-rows)
      (catch Exception e
        (log/error "Update failed" 
                   {:adapter (protocols/dialect (:adapter ctx))
                    :sql (first sql-query)
                    :error (.getMessage e)})
        (throw (ex-info "Database update failed"
                        {:adapter (protocols/dialect (:adapter ctx))
                         :sql (first sql-query)
                         :params (rest sql-query)
                         :original-error (.getMessage e)}
                        e))))))

(defn execute-batch!
  "Execute multiple queries in a single transaction.
   
   Args:
     ctx: Database context
     query-maps: Vector of HoneySQL query maps
     
   Returns:
     Vector of results (for SELECTs) or affected row counts (for updates)
     
   Example:
     (execute-batch! ctx [{:insert-into :users :values [{:name \"John\"}]}
                         {:insert-into :users :values [{:name \"Jane\"}]}])"
  [ctx query-maps]
  (validate-context ctx)
  (log/debug "Executing batch operation" 
             {:adapter (protocols/dialect (:adapter ctx))
              :query-count (count query-maps)})
  (jdbc/with-transaction [tx (:datasource ctx)]
    (let [tx-ctx (assoc ctx :datasource tx)
          start-time (System/currentTimeMillis)
          results (mapv (fn [query-map]
                         (if (contains? query-map :select)
                           (execute-query! tx-ctx query-map)
                           (execute-update! tx-ctx query-map)))
                       query-maps)
          duration (- (System/currentTimeMillis) start-time)]
      (log/info "Batch operation completed" 
                {:adapter (protocols/dialect (:adapter ctx))
                 :query-count (count query-maps)
                 :duration-ms duration})
      results)))

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defn with-transaction*
  "Execute function within database transaction context.
   
   Args:
     ctx: Database context
     f: Function that takes transaction context and returns result
     
   Returns:
     Result of function execution
     
   Example:
     (with-transaction* ctx (fn [tx] (execute-update! tx query)))"
  [ctx f]
  (validate-context ctx)
  (jdbc/with-transaction [tx (:datasource ctx)]
    (try
      (let [tx-ctx (assoc ctx :datasource tx)
            result (f tx-ctx)]
        (log/debug "Transaction completed successfully"
                   {:adapter (protocols/dialect (:adapter ctx))})
        result)
      (catch Exception e
        (log/error "Transaction failed, rolling back" 
                   {:adapter (protocols/dialect (:adapter ctx))
                    :error (.getMessage e)})
        (throw e)))))

(defmacro with-transaction
  "Macro for database transaction management with consistent error handling.
   
   Args:
     binding: [tx-var ctx]
     body: Expressions to execute within transaction
     
   Example:
     (with-transaction [tx ctx]
       (execute-update! tx query1)
       (execute-update! tx query2))"
  [binding & body]
  `(with-transaction* ~(second binding)
     (fn [~(first binding)]
       ~@body)))

;; =============================================================================
;; Query Building Utilities
;; =============================================================================

(defn build-where-clause
  "Build dynamic WHERE clause from filter map using adapter-specific logic.
   
   Args:
     ctx: Database context
     filters: Map of field -> value filters
     
   Returns:
     HoneySQL WHERE clause or nil
     
   Example:
     (build-where-clause ctx {:name \"John\" :active true :role [:admin :user]})"
  [ctx filters]
  (validate-context ctx)
  (when (seq filters)
    (protocols/build-where (:adapter ctx) filters)))

(defn build-pagination
  "Build LIMIT/OFFSET clause from pagination options with safe defaults.
   
   Args:
     options: Map with :limit and :offset keys
     
   Returns:
     Map with sanitized :limit and :offset values
     
   Example:
     (build-pagination {:limit 50 :offset 100})"
  [options]
  (let [limit (get options :limit 20)
        offset (get options :offset 0)]
    {:limit (min (max limit 1) 1000)  ; Clamp between 1 and 1000
     :offset (max offset 0)}))

(defn build-ordering
  "Build ORDER BY clause from sort options.
   
   Args:
     options: Map with :sort-by and :sort-direction keys
     default-field: Default field to sort by (keyword)
     
   Returns:
     Vector of [field direction] pairs
     
   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)"
  [options default-field]
  (let [sort-field (get options :sort-by default-field)
        direction (get options :sort-direction :asc)]
    [[sort-field direction]]))

;; =============================================================================
;; Schema Management
;; =============================================================================

(defn table-exists?
  "Check if a table exists using adapter-specific introspection.
   
   Args:
     ctx: Database context
     table-name: String or keyword table name
     
   Returns:
     Boolean - true if table exists
     
   Example:
     (table-exists? ctx :users)"
  [ctx table-name]
  (validate-context ctx)
  (protocols/table-exists? (:adapter ctx) (:datasource ctx) table-name))

(defn get-table-info
  "Get column information for a table using adapter-specific introspection.
   
   Args:
     ctx: Database context
     table-name: String or keyword table name
     
   Returns:
     Vector of column info maps
     
   Example:
     (get-table-info ctx :users)"
  [ctx table-name]
  (validate-context ctx)
  (protocols/get-table-info (:adapter ctx) (:datasource ctx) table-name))

(defn execute-ddl!
  "Execute DDL statement with logging.
   
   Args:
     ctx: Database context
     ddl-statement: String DDL statement
     
   Returns:
     Execution result
     
   Example:
     (execute-ddl! ctx \"CREATE TABLE users (id TEXT PRIMARY KEY)\")"
  [ctx ddl-statement]
  (validate-context ctx)
  (let [statement-preview (str/join " " (take 5 (str/split ddl-statement #"\\s+")))]
    (log/debug "Executing DDL statement" 
               {:adapter (protocols/dialect (:adapter ctx))
                :statement-preview statement-preview})
    (try
      (let [result (jdbc/execute! (:datasource ctx) [ddl-statement])]
        (log/info "DDL statement executed successfully"
                  {:adapter (protocols/dialect (:adapter ctx))
                   :statement-preview statement-preview})
        result)
      (catch Exception e
        (log/error "DDL execution failed" 
                   {:adapter (protocols/dialect (:adapter ctx))
                    :statement ddl-statement 
                    :error (.getMessage e)
                    :exception-type (type e)})
        (throw (ex-info "DDL execution failed"
                        {:adapter (protocols/dialect (:adapter ctx))
                         :statement ddl-statement
                         :original-error (.getMessage e)}
                        e))))))

(defn create-index-if-not-exists!
  "Create index with IF NOT EXISTS support when available.
   
   Args:
     ctx: Database context
     index-name: String index name
     table: String or keyword table name
     columns: Vector of column names
     
   Returns:
     Execution result
     
   Example:
     (create-index-if-not-exists! ctx \"idx_users_email\" :users [:email])"
  [ctx index-name table columns]
  (validate-context ctx)
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)
        table-str (name table)
        cols-str (str/join ", " (map name columns))
        ;; Most databases support IF NOT EXISTS for indexes
        if-not-exists (case dialect
                        :mysql ""  ; MySQL doesn't support IF NOT EXISTS for indexes
                        "IF NOT EXISTS ")
        ddl (str "CREATE INDEX " if-not-exists index-name " ON " table-str " (" cols-str ")")]
    (execute-ddl! ctx ddl)))

;; =============================================================================
;; Database Information
;; =============================================================================

(defn database-info
  "Get database information and statistics.
   
   Args:
     ctx: Database context
     
   Returns:
     Map with database information
     
   Example:
     (database-info ctx)"
  [ctx]
  (validate-context ctx)
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)]
    {:adapter dialect
     :dialect dialect
     :pool-info (when (instance? HikariDataSource (:datasource ctx))
                  (let [ds ^HikariDataSource (:datasource ctx)]
                    {:pool-name (.getPoolName ds)
                     :active-connections (.getActiveConnections ds)
                     :idle-connections (.getIdleConnections ds)
                     :total-connections (.getTotalConnections ds)
                     :maximum-pool-size (.getMaximumPoolSize ds)}))}))

(defn list-tables
  "List all tables in the database.
   
   Args:
     ctx: Database context
     
   Returns:
     Vector of table name strings
     
   Example:
     (list-tables ctx)"
  [ctx]
  (validate-context ctx)
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)
        query (case dialect
                nil {:select [:name]  ; SQLite uses nil dialect
                     :from [:sqlite_master]
                     :where [:= :type "table"]}
                :ansi {:select [:table_name]  ; H2 uses :ansi
                       :from [:information_schema.tables]
                       :where [:= :table_schema "PUBLIC"]}
                :mysql {:select [:table_name]
                        :from [:information_schema.tables]
                        :where [:= :table_schema [:database]]}
                ;; Default case for other dialects (PostgreSQL uses nil)
                {:select [:table_name]
                 :from [:information_schema.tables]
                 :where [:= :table_schema "public"]})
        results (execute-query! ctx query)]
    (mapv (fn [row]
            (or (:name row) 
                (:table_name row) 
                (:table-name row)))
          results)))