(ns boundary.shell.adapters.database.sqlite
  "SQLite database adapter implementing the DBAdapter protocol.

   This namespace provides SQLite-specific functionality while delegating
   common database operations to the shared core namespace. It handles
   SQLite's unique characteristics and provides optimized patterns for
   embedded database usage.

   Key Features:
   - SQLite-optimized connection management with PRAGMAs
   - Database-specific query building (LIKE for strings, boolean->int)
   - Schema introspection via sqlite_master and PRAGMA table_info
   - Backward-compatible API that delegates to core
   - Integration with shared type conversion utilities

   SQLite-Specific Optimizations:
   - Text-based UUID and timestamp storage
   - Boolean as integer (0/1) representation
   - WAL mode, synchronous settings, and other PRAGMAs
   - Connection pool tuning for embedded usage

   Migration Note:
   This adapter now implements the DBAdapter protocol and delegates common
   operations to boundary.shell.adapters.database.core. The existing public
   API is maintained for backward compatibility."
  (:require [boundary.shared.utils.type-conversion :as tc]
            [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shell.adapters.database.core :as core]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

;; =============================================================================
;; SQLite Connection Management
;; =============================================================================

(def ^:private default-sqlite-pragmas
  "Default SQLite PRAGMA settings for optimal performance and reliability."
  ["PRAGMA journal_mode=WAL"          ; Write-Ahead Logging for better concurrency
   "PRAGMA synchronous=NORMAL"        ; Balance between safety and performance
   "PRAGMA foreign_keys=ON"           ; Enable foreign key constraints
   "PRAGMA temp_store=MEMORY"         ; Store temporary tables in memory
   "PRAGMA mmap_size=268435456"       ; 256MB memory-mapped I/O
   "PRAGMA cache_size=10000"          ; 10MB page cache
   "PRAGMA busy_timeout=5000"])       ; 5-second busy timeout

(defn initialize-sqlite-pragmas!
  "Apply SQLite PRAGMA settings to a connection.

   Args:
     datasource: Database connection or connection pool
     custom-pragmas: Optional vector of additional PRAGMA statements

   Example:
     (initialize-sqlite-pragmas! datasource [\"PRAGMA journal_mode=DELETE\"])"
  ([datasource]
   (initialize-sqlite-pragmas! datasource []))
  ([datasource custom-pragmas]
   (let [all-pragmas (concat default-sqlite-pragmas custom-pragmas)
         success-count (atom 0)
         failure-count (atom 0)]
     (log/debug "Initializing SQLite PRAGMA settings" {:pragmas-count (count all-pragmas)})
     (doseq [pragma all-pragmas]
       (try
         (jdbc/execute! datasource [pragma])
         (log/debug "Applied PRAGMA successfully" {:pragma pragma})
         (swap! success-count inc)
         (catch Exception e
           (log/warn "Failed to apply PRAGMA, continuing" 
                     {:pragma pragma 
                      :error (.getMessage e)})
           (swap! failure-count inc))))
     (log/info "SQLite PRAGMA initialization completed" 
               {:successful-pragmas @success-count
                :failed-pragmas @failure-count
                :total-pragmas (count all-pragmas)}))))

;; =============================================================================
;; SQLite Adapter Implementation
;; =============================================================================

(defrecord SQLiteAdapter []
  protocols/DBAdapter
  
  (dialect [_]
    :sqlite)
  
  (jdbc-driver [_]
    "org.sqlite.JDBC")
  
  (jdbc-url [_ db-config]
    (str "jdbc:sqlite:" (:database-path db-config)))
  
  (pool-defaults [_]
    {:minimum-idle 1
     :maximum-pool-size 5
     :connection-timeout-ms 30000
     :idle-timeout-ms 600000
     :max-lifetime-ms 1800000})
  
  (init-connection! [_ datasource _db-config]
    (initialize-sqlite-pragmas! datasource))
  
  (build-where [_ filters]
    (when (seq filters)
      (let [conditions (for [[field value] filters
                              :when (some? value)]
                         (cond
                           (string? value) [:like field (str "%" value "%")]
                           (vector? value) [:in field value]
                           (boolean? value) [:= field (tc/boolean->int value)]
                           :else [:= field value]))]
        (when (seq conditions)
          (if (= 1 (count conditions))
            (first conditions)
            (cons :and conditions))))))
  
  (boolean->db [_ boolean-value]
    (tc/boolean->int boolean-value))
  
  (db->boolean [_ db-value]
    (tc/int->boolean db-value))
  
  (table-exists? [_ datasource table-name]
    (let [table-str (name table-name)
          query {:select [:%count.*]
                 :from [:sqlite_master]
                 :where [:and
                         [:= :type "table"]
                         [:= :name table-str]]}
          result (first (jdbc/execute! datasource 
                                       (sql/format query {:dialect :sqlite})
                                       {:builder-fn rs/as-unqualified-lower-maps}))]
      (> (:count result 0) 0)))
  
  (get-table-info [_ datasource table-name]
    (let [pragma-sql (str "PRAGMA table_info(" (name table-name) ")")
          results (jdbc/execute! datasource [pragma-sql] {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [row]
              {:name (:name row)
               :type (:type row)
               :not-null (= (:notnull row) 1)
               :default (:dflt_value row)
               :primary-key (= (:pk row) 1)})
            results))))

(defn new-adapter
  "Create new SQLite adapter instance.
   
   Returns:
     SQLite adapter implementing DBAdapter protocol"
  []
  (->SQLiteAdapter))

;; =============================================================================
;; Backward-Compatible API (delegating to core)
;; =============================================================================

(def ^:private sqlite-adapter
  "Default SQLite adapter instance for backward compatibility."
  (new-adapter))

(defn create-sqlite-connection-pool
  "Create HikariCP connection pool optimized for SQLite.
   
   DEPRECATED: Use boundary.shell.adapters.database.factory/create-datasource instead.

   Args:
     config: Configuration map with:
             {:database-path \"/path/to/database.db\"
              :pool {:minimum-idle 1
                     :maximum-pool-size 5
                     :connection-timeout-ms 30000
                     :idle-timeout-ms 600000
                     :max-lifetime-ms 1800000}}

   Returns:
     HikariDataSource configured for SQLite

   Example:
     (create-sqlite-connection-pool {:database-path \"./data/app.db\"
                                    :pool {:maximum-pool-size 3}})"
  [config]
  {:pre [(some? (:database-path config))]}
  (let [db-config (assoc config :adapter :sqlite)]
    (core/create-connection-pool sqlite-adapter db-config)))

;; =============================================================================
;; Query Execution Framework
;; =============================================================================

(defn execute-query!
  "Execute SELECT query with SQLite-specific optimizations and logging.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/execute-query! instead.

   Args:
     datasource: Database connection or connection pool
     query-map: HoneySQL query map
     options: Optional execution options (ignored)

   Returns:
     Vector of result maps

   Example:
     (execute-query! ds {:select [:*] :from [:users] :where [:= :active 1]})"
  ([datasource query-map]
   (execute-query! datasource query-map {}))
  ([datasource query-map _options]
   (let [ctx {:datasource datasource :adapter sqlite-adapter}]
     (core/execute-query! ctx query-map))))

(defn execute-one!
  "Execute query expecting single result.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/execute-one! instead.

   Args:
     datasource: Database connection or connection pool
     query-map: HoneySQL query map

   Returns:
     Single result map or nil

   Example:
     (execute-one! ds {:select [:*] :from [:users] :where [:= :id \"123\"]})"
  [datasource query-map]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/execute-one! ctx query-map)))

(defn execute-update!
  "Execute UPDATE/INSERT/DELETE query with affected row count.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/execute-update! instead.

   Args:
     datasource: Database connection or connection pool
     query-map: HoneySQL query map

   Returns:
     Number of affected rows

   Example:
     (execute-update! ds {:update :users :set {:active 0} :where [:= :id \"123\"]})"
  [datasource query-map]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/execute-update! ctx query-map)))

(defn execute-batch!
  "Execute multiple queries in a single transaction.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/execute-batch! instead.

   Args:
     datasource: Database connection or connection pool
     query-maps: Vector of HoneySQL query maps

   Returns:
     Vector of results (for queries) or affected row counts (for updates)

   Example:
     (execute-batch! ds [{:insert-into :users :values [{:name \"John\"}]}
                        {:insert-into :users :values [{:name \"Jane\"}]}])"
  [datasource query-maps]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/execute-batch! ctx query-maps)))

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defn with-transaction*
  "Execute function within SQLite transaction context.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/with-transaction* instead.

   Args:
     datasource: Database connection or connection pool
     f: Function to execute within transaction

   Returns:
     Result of function execution

   Example:
     (with-transaction* ds (fn [tx] (execute-update! tx query)))"
  [datasource f]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/with-transaction* ctx f)))

(defmacro with-transaction
  "Macro for SQLite transaction management with consistent error handling.

   Args:
     binding: [tx-var datasource]
     body: Expressions to execute within transaction

   Example:
     (with-transaction [tx datasource]
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
  "Build dynamic WHERE clause from filter map with SQLite optimizations.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/build-where-clause instead.

   Args:
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause or nil

   Example:
     (build-where-clause {:name \"John\" :active true :role [:admin :user]})
     ;; => [:and [:= :name \"John\"] [:= :active 1] [:in :role [\"admin\" \"user\"]]]"
  [filters]
  (let [ctx {:adapter sqlite-adapter}]
    (core/build-where-clause ctx filters)))

(defn build-pagination
  "Build LIMIT/OFFSET clause from pagination options.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/build-pagination instead.

   Args:
     options: Map with :limit and :offset keys

   Returns:
     Map with sanitized :limit and :offset values

   Example:
     (build-pagination {:limit 50 :offset 100})
     ;; => {:limit 50 :offset 100}"
  [options]
  (core/build-pagination options))

(defn build-ordering
  "Build ORDER BY clause from sort options.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/build-ordering instead.

   Args:
     options: Map with :sort-by and :sort-direction keys
     default-field: Default field to sort by

   Returns:
     Vector of [field direction] pairs

   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)
     ;; => [[:created_at :desc]]"
  [options default-field]
  (core/build-ordering options default-field))

;; =============================================================================
;; Schema Management Utilities
;; =============================================================================

(defn table-exists?
  "Check if a table exists in the SQLite database.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/table-exists? instead.

   Args:
     datasource: Database connection or connection pool
     table-name: String or keyword table name

   Returns:
     Boolean indicating if table exists

   Example:
     (table-exists? ds :users) ;; => true"
  [datasource table-name]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/table-exists? ctx table-name)))

(defn get-table-info
  "Get column information for a SQLite table.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/get-table-info instead.

   Args:
     datasource: Database connection or connection pool
     table-name: String or keyword table name

   Returns:
     Vector of column info maps

   Example:
     (get-table-info ds :users)
     ;; => [{:name \"id\" :type \"TEXT\" :pk true} ...]"
  [datasource table-name]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/get-table-info ctx table-name)))

(defn execute-ddl!
  "Execute DDL statement (CREATE TABLE, INDEX, etc.) with logging.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/execute-ddl! instead.

   Args:
     datasource: Database connection or connection pool
     ddl-statement: String DDL statement

   Returns:
     Execution result

   Example:
     (execute-ddl! ds \"CREATE TABLE users (id TEXT PRIMARY KEY)\")"
  [datasource ddl-statement]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/execute-ddl! ctx ddl-statement)))

;; =============================================================================
;; Database Introspection
;; =============================================================================

(defn database-info
  "Get SQLite database information and statistics.
   
   DEPRECATED: Use boundary.shell.adapters.database.core/database-info instead.

   Args:
     datasource: Database connection or connection pool

   Returns:
     Map with database information

   Example:
     (database-info ds)
     ;; => {:adapter :sqlite :dialect :sqlite :pool-info {...}}"
  [datasource]
  (let [ctx {:datasource datasource :adapter sqlite-adapter}]
    (core/database-info ctx)))
