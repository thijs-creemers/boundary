(ns boundary.shell.adapters.database.sqlite
  "Shared SQLite database adapter utilities for Boundary framework.

   This namespace provides common SQLite-specific functionality that can be reused
   across different modules. It handles SQLite's unique characteristics and provides
   optimized patterns for embedded database usage.

   Key Features:
   - SQLite-optimized connection management and pooling
   - Query execution framework with performance monitoring
   - Transaction management with proper error handling
   - Schema initialization and migration support
   - Common query building patterns for SQLite
   - Integration with shared type conversion utilities

   SQLite-Specific Optimizations:
   - Text-based UUID and timestamp storage
   - Boolean as integer (0/1) representation
   - Proper PRAGMA settings for performance
   - WAL mode support for concurrent access"
  (:require [boundary.shared.utils.type-conversion :as tc]
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

(defn create-sqlite-connection-pool
  "Create HikariCP connection pool optimized for SQLite.

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
  (let [db-path (:database-path config)
        pool-config (:pool config {})
        hikari-config (doto (HikariConfig.)
                        (.setDriverClassName "org.sqlite.JDBC")
                        (.setJdbcUrl (str "jdbc:sqlite:" db-path))
                        (.setMinimumIdle (get pool-config :minimum-idle 1))
                        (.setMaximumPoolSize (get pool-config :maximum-pool-size 5))
                        (.setConnectionTimeout (get pool-config :connection-timeout-ms 30000))
                        (.setIdleTimeout (get pool-config :idle-timeout-ms 600000))
                        (.setMaxLifetime (get pool-config :max-lifetime-ms 1800000))
                        (.setPoolName "SQLite-Pool")
                        (.setAutoCommit true))]

    (log/info "Creating SQLite connection pool" {:database-path db-path
                                                 :pool-size (:maximum-pool-size pool-config 5)})
    (HikariDataSource. hikari-config)))

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
   (log/debug "Initializing SQLite PRAGMA settings")
   (let [all-pragmas (concat default-sqlite-pragmas custom-pragmas)]
     (doseq [pragma all-pragmas]
       (try
         (jdbc/execute! datasource [pragma])
         (log/debug "Applied PRAGMA" {:pragma pragma})
         (catch Exception e
           (log/warn "Failed to apply PRAGMA" {:pragma pragma :error (.getMessage e)}))))
     (log/info "SQLite PRAGMA settings initialized" {:pragmas-count (count all-pragmas)}))))

;; =============================================================================
;; Query Execution Framework
;; =============================================================================

(defn execute-query!
  "Execute SELECT query with SQLite-specific optimizations and logging.

   Args:
     datasource: Database connection or connection pool
     query-map: HoneySQL query map
     options: Optional execution options

   Returns:
     Vector of result maps

   Example:
     (execute-query! ds {:select [:*] :from [:users] :where [:= :active 1]})"
  ([datasource query-map]
   (execute-query! datasource query-map {}))
  ([datasource query-map _options]
   (let [sql-query (sql/format query-map {:dialect :sqlite})
         start-time (System/currentTimeMillis)]
     (log/debug "Executing SQLite query" {:sql (first sql-query) :params (rest sql-query)})
     (try
       (let [result (jdbc/execute! datasource sql-query
                                   {:builder-fn rs/as-unqualified-lower-maps})
             duration (- (System/currentTimeMillis) start-time)]
         (log/debug "SQLite query completed" {:duration-ms duration :row-count (count result)})
         result)
       (catch Exception e
         (log/error "SQLite query failed" {:sql (first sql-query) :error (.getMessage e)})
         (throw e))))))

(defn execute-one!
  "Execute query expecting single result.

   Args:
     datasource: Database connection or connection pool
     query-map: HoneySQL query map

   Returns:
     Single result map or nil

   Example:
     (execute-one! ds {:select [:*] :from [:users] :where [:= :id \"123\"]})"
  [datasource query-map]
  (first (execute-query! datasource query-map)))

(defn execute-update!
  "Execute UPDATE/INSERT/DELETE query with affected row count.

   Args:
     datasource: Database connection or connection pool
     query-map: HoneySQL query map

   Returns:
     Number of affected rows

   Example:
     (execute-update! ds {:update :users :set {:active 0} :where [:= :id \"123\"]})"
  [datasource query-map]
  (let [sql-query (sql/format query-map {:dialect :sqlite})
        start-time (System/currentTimeMillis)]
    (log/debug "Executing SQLite update" {:sql (first sql-query) :params (rest sql-query)})
    (try
      (let [result (jdbc/execute! datasource sql-query)
            duration (- (System/currentTimeMillis) start-time)
            affected-rows (:next.jdbc/update-count (first result))]
        (log/debug "SQLite update completed" {:duration-ms duration :affected-rows affected-rows})
        affected-rows)
      (catch Exception e
        (log/error "SQLite update failed" {:sql (first sql-query) :error (.getMessage e)})
        (throw e)))))

(defn execute-batch!
  "Execute multiple queries in a single transaction.

   Args:
     datasource: Database connection or connection pool
     query-maps: Vector of HoneySQL query maps

   Returns:
     Vector of results (for queries) or affected row counts (for updates)

   Example:
     (execute-batch! ds [{:insert-into :users :values [{:name \"John\"}]}
                        {:insert-into :users :values [{:name \"Jane\"}]}])"
  [datasource query-maps]
  (log/debug "Executing SQLite batch operation" {:query-count (count query-maps)})
  (jdbc/with-transaction [tx datasource]
    (let [start-time (System/currentTimeMillis)
          results (mapv #(if (contains? % :select)
                           (execute-query! tx %)
                           (execute-update! tx %))
                        query-maps)
          duration (- (System/currentTimeMillis) start-time)]
      (log/info "SQLite batch operation completed" {:query-count (count query-maps)
                                                    :duration-ms duration})
      results)))

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defn with-transaction*
  "Execute function within SQLite transaction context.

   Args:
     datasource: Database connection or connection pool
     f: Function to execute within transaction

   Returns:
     Result of function execution

   Example:
     (with-transaction* ds (fn [tx] (execute-update! tx query)))"
  [datasource f]
  (jdbc/with-transaction [tx datasource]
    (try
      (let [result (f tx)]
        (log/debug "SQLite transaction completed successfully")
        result)
      (catch Exception e
        (log/error "SQLite transaction failed, rolling back" {:error (.getMessage e)})
        (throw e)))))

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

   Args:
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause or nil

   Example:
     (build-where-clause {:name \"John\" :active true :role [:admin :user]})
     ;; => [:and [:= :name \"John\"] [:= :active 1] [:in :role [\"admin\" \"user\"]]]"
  [filters]
  (when (seq filters)
    (let [conditions (for [[field value] filters
                            :when (some? value)]
                       (cond
                         (string? value) [:like field (str "%" value "%")] ; SQLite LIKE instead of ILIKE
                         (vector? value) [:in field value]
                         (boolean? value) [:= field (tc/boolean->int value)]
                         :else [:= field value]))]
      (when (seq conditions)
        (if (= 1 (count conditions))
          (first conditions)
          (cons :and conditions))))))

(defn build-pagination
  "Build LIMIT/OFFSET clause from pagination options.

   Args:
     options: Map with :limit and :offset keys

   Returns:
     Map with sanitized :limit and :offset values

   Example:
     (build-pagination {:limit 50 :offset 100})
     ;; => {:limit 50 :offset 100}"
  [options]
  (let [limit (get options :limit 20)
        offset (get options :offset 0)]
    {:limit (min (max limit 1) 1000)  ; Limit between 1 and 1000
     :offset (max offset 0)}))

(defn build-ordering
  "Build ORDER BY clause from sort options.

   Args:
     options: Map with :sort-by and :sort-direction keys
     default-field: Default field to sort by

   Returns:
     Vector of [field direction] pairs

   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)
     ;; => [[:created_at :desc]]"
  [options default-field]
  (let [sort-field (get options :sort-by default-field)
        direction (get options :sort-direction :asc)]
    [[sort-field direction]]))

;; =============================================================================
;; Schema Management Utilities
;; =============================================================================

(defn table-exists?
  "Check if a table exists in the SQLite database.

   Args:
     datasource: Database connection or connection pool
     table-name: String or keyword table name

   Returns:
     Boolean indicating if table exists

   Example:
     (table-exists? ds :users) ;; => true"
  [datasource table-name]
  (let [table-str (name table-name)
        query {:select [:%count.*]
               :from [:sqlite_master]
               :where [:and
                       [:= :type "table"]
                       [:= :name table-str]]}
        result (execute-one! datasource query)]
    (> (:count result 0) 0)))

(defn get-table-info
  "Get column information for a SQLite table.

   Args:
     datasource: Database connection or connection pool
     table-name: String or keyword table name

   Returns:
     Vector of column info maps

   Example:
     (get-table-info ds :users)
     ;; => [{:name \"id\" :type \"TEXT\" :pk true} ...]"
  [datasource table-name]
  (let [pragma-sql (str "PRAGMA table_info(" (name table-name) ")")
        results (jdbc/execute! datasource [pragma-sql] {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:name (:name row)
             :type (:type row)
             :not-null (= (:notnull row) 1)
             :default (:dflt_value row)
             :primary-key (= (:pk row) 1)})
          results)))

(defn execute-ddl!
  "Execute DDL statement (CREATE TABLE, INDEX, etc.) with logging.

   Args:
     datasource: Database connection or connection pool
     ddl-statement: String DDL statement

   Returns:
     Execution result

   Example:
     (execute-ddl! ds \"CREATE TABLE users (id TEXT PRIMARY KEY)\")"
  [datasource ddl-statement]
  (log/info "Executing SQLite DDL" {:statement (str/join " "
                                                                    (take 5 (str/split ddl-statement #"\\s+")))})
  (try
    (let [result (jdbc/execute! datasource [ddl-statement])]
      (log/info "SQLite DDL executed successfully")
      result)
    (catch Exception e
      (log/error "SQLite DDL execution failed" {:statement ddl-statement :error (.getMessage e)})
      (throw e))))

;; =============================================================================
;; Database Introspection
;; =============================================================================

(defn database-info
  "Get SQLite database information and statistics.

   Args:
     datasource: Database connection or connection pool

   Returns:
     Map with database information

   Example:
     (database-info ds)
     ;; => {:version \"3.40.0\" :page-size 4096 :tables [...] :pragmas {...}}"
  [datasource]
  (let [version-result (execute-one! datasource {:select [[[:sqlite_version] :version]]})
        page-size-result (jdbc/execute-one! datasource ["PRAGMA page_size"] {:builder-fn rs/as-unqualified-lower-maps})
        tables-result (execute-query! datasource {:select [:name]
                                                  :from [:sqlite_master]
                                                  :where [:= :type "table"]})]
    {:version (:version version-result)
     :page-size (:page_size page-size-result)
     :table-count (count tables-result)
     :tables (mapv :name tables-result)}))
