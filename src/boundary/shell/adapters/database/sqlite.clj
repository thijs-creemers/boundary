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
            [boundary.shell.adapters.database.core :refer [with-transaction*]]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; SQLite Connection Management
;; =============================================================================

(def ^:private default-sqlite-pragmas
  "Default SQLite PRAGMA settings for optimal performance and reliability."
  ["PRAGMA journal_mode=WAL"                                ; Write-Ahead Logging for better concurrency
   "PRAGMA synchronous=NORMAL"                              ; Balance between safety and performance
   "PRAGMA foreign_keys=ON"                                 ; Enable foreign key constraints
   "PRAGMA temp_store=MEMORY"                               ; Store temporary tables in memory
   "PRAGMA mmap_size=268435456"                             ; 256MB memory-mapped I/O
   "PRAGMA cache_size=10000"                                ; 10MB page cache
   "PRAGMA busy_timeout=5000"])                             ; 5-second busy timeout

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
   (let [all-pragmas   (concat default-sqlite-pragmas custom-pragmas)
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
                      :error  (.getMessage e)})
           (swap! failure-count inc))))
     (log/info "SQLite PRAGMA initialization completed"
               {:successful-pragmas @success-count
                :failed-pragmas     @failure-count
                :total-pragmas      (count all-pragmas)}))))

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
    {:minimum-idle          1
     :maximum-pool-size     5
     :connection-timeout-ms 30000
     :idle-timeout-ms       600000
     :max-lifetime-ms       1800000})

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
          query     {:select [:%count.*]
                     :from   [:sqlite_master]
                     :where  [:and
                              [:= :type "table"]
                              [:= :name table-str]]}
          result    (first (jdbc/execute! datasource
                                          (sql/format query {:dialect :sqlite})
                                          {:builder-fn rs/as-unqualified-lower-maps}))]
      (> (:count result 0) 0)))

  (get-table-info [_ datasource table-name]
    (let [pragma-sql (str "PRAGMA table_info(" (name table-name) ")")
          results    (jdbc/execute! datasource [pragma-sql] {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [row]
              {:name        (:name row)
               :type        (:type row)
               :not-null    (= (:notnull row) 1)
               :default     (:dflt_value row)
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

;; =============================================================================
;; Transaction Management
;; =============================================================================

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