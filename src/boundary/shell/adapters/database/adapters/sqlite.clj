(ns boundary.shell.adapters.database.adapters.sqlite
  "SQLite database adapter implementation.
   
   This adapter provides SQLite-specific functionality including:
   - PRAGMA settings optimization for performance and reliability
   - Boolean value conversions (1/0 for true/false)
   - WAL mode for better concurrency
   - SQLite-specific table introspection
   - Case-insensitive LIKE operations"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; SQLite Adapter Implementation
;; =============================================================================

(defrecord SQLiteAdapter []
  protocols/DBAdapter

  (dialect [_]
    ;; SQLite should return :sqlite for identification purposes
    :sqlite)

  (jdbc-driver [_]
    "org.sqlite.JDBC")

  (jdbc-url [_ db-config]
    (let [db-path (:database-path db-config)]
      (if (= ":memory:" db-path)
        "jdbc:sqlite::memory:"
        (str "jdbc:sqlite:" db-path))))

  (pool-defaults [_]
    ;; SQLite works best with smaller connection pools due to file locking
    {:minimum-idle 1
     :maximum-pool-size 5
     :connection-timeout-ms 30000
     :idle-timeout-ms 600000
     :max-lifetime-ms 1800000})

  (init-connection! [_ datasource _db-config]
    ;; Apply SQLite PRAGMA settings for optimal performance and reliability
    (try
      (with-open [conn (jdbc/get-connection datasource)]
        (log/debug "Initializing SQLite connection with optimized PRAGMA settings")

        ;; Enable WAL mode for better concurrency
        (jdbc/execute! conn ["PRAGMA journal_mode = WAL"])

        ;; Optimize synchronous mode (NORMAL is good balance of safety/performance)
        (jdbc/execute! conn ["PRAGMA synchronous = NORMAL"])

        ;; Set reasonable timeout for busy database
        (jdbc/execute! conn ["PRAGMA busy_timeout = 30000"])

        ;; Enable foreign key enforcement
        (jdbc/execute! conn ["PRAGMA foreign_keys = ON"])

        ;; Optimize temp store
        (jdbc/execute! conn ["PRAGMA temp_store = MEMORY"])

        ;; Set reasonable cache size (in KB)
        (jdbc/execute! conn ["PRAGMA cache_size = -64000"])  ; 64MB

        (log/info "SQLite connection initialized with optimized settings"))
      (catch Exception e
        (log/warn "Failed to apply SQLite PRAGMA settings" {:error (.getMessage e)})
        ;; Don't fail initialization for PRAGMA issues
        nil)))

  (build-where [_ filters]
    (when (seq filters)
      (let [conditions (map (fn [[field value]]
                              (cond
                               ;; Handle nil values
                                (nil? value)
                                [:is field nil]

                               ;; Handle collections (IN clause)
                                (coll? value)
                                (if (empty? value)
                                 ;; Empty collection should match nothing
                                  [:= 1 0]
                                  [:in field value])

                               ;; Handle string pattern matching (case-insensitive)
                                (and (string? value) (str/includes? value "*"))
                                [:like field (str/replace value "*" "%")]

                               ;; Handle boolean values (convert to integers)
                                (boolean? value)
                                [:= field (if value 1 0)]

                               ;; Default equality
                                :else
                                [:= field value]))
                            filters)]
        (if (= 1 (count conditions))
          (first conditions)
          (into [:and] conditions)))))

  (boolean->db [_ boolean-value]
    ;; SQLite stores booleans as integers
    (cond
      (true? boolean-value) 1
      (false? boolean-value) 0
      :else nil))

  (db->boolean [_ db-value]
    ;; Convert SQLite integer values back to booleans
    (cond
      (= db-value 1) true
      (= db-value 0) false
      (= db-value "1") true
      (= db-value "0") false
      (= db-value "true") true
      (= db-value "false") false
      :else (boolean db-value)))

  (table-exists? [_ datasource table-name]
    (try
      (let [table-str (name table-name)
            query "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
            result (jdbc/execute! datasource [query table-str])]
        (seq result))
      (catch Exception e
        (log/error "Failed to check table existence"
                   {:table table-name :error (.getMessage e)})
        false)))

  (get-table-info [_ datasource table-name]
    (try
      (let [table-str (name table-name)
            query (str "PRAGMA table_info(" table-str ")")
            results (jdbc/execute! datasource [query])]
        (mapv (fn [row]
                {:name (:name row)
                 :type (str/upper-case (:type row))
                 :not-null (= 1 (:notnull row))
                 :default (:dflt_value row)
                 :primary-key (= 1 (:pk row))})
              results))
      (catch Exception e
        (log/error "Failed to get table info"
                   {:table table-name :error (.getMessage e)})
        []))))

;; =============================================================================
;; Constructor and Utilities
;; =============================================================================

(defn create-sqlite-adapter
  "Create a new SQLite database adapter.
   
   Returns:
     SQLiteAdapter instance implementing DBAdapter protocol"
  []
  (->SQLiteAdapter))

;; Alias for compatibility with factory
(def new-adapter create-sqlite-adapter)

;; =============================================================================
;; SQLite-Specific Utilities
;; =============================================================================

(defn vacuum-database!
  "Run VACUUM command to reclaim space and optimize database.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     nil"
  [datasource]
  (log/info "Running VACUUM on SQLite database")
  (try
    (jdbc/execute! datasource ["VACUUM"])
    (log/info "VACUUM completed successfully")
    (catch Exception e
      (log/error "VACUUM failed" {:error (.getMessage e)})
      (throw e))))

(defn analyze-database!
  "Run ANALYZE command to update SQLite query planner statistics.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     nil"
  [datasource]
  (log/info "Running ANALYZE on SQLite database")
  (try
    (jdbc/execute! datasource ["ANALYZE"])
    (log/info "ANALYZE completed successfully")
    (catch Exception e
      (log/error "ANALYZE failed" {:error (.getMessage e)})
      (throw e))))

(defn get-sqlite-version
  "Get SQLite version information.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     String with SQLite version"
  [datasource]
  (try
    (let [result (jdbc/execute! datasource ["SELECT sqlite_version() as version"])]
      (:version (first result)))
    (catch Exception e
      (log/error "Failed to get SQLite version" {:error (.getMessage e)})
      "unknown")))

(defn get-pragma-settings
  "Get current PRAGMA settings from SQLite database.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     Map of pragma settings"
  [datasource]
  (try
    (let [pragmas ["journal_mode" "synchronous" "foreign_keys" "cache_size" "temp_store"]
          settings (reduce (fn [acc pragma]
                             (try
                               (let [query (str "PRAGMA " pragma)
                                     result (jdbc/execute! datasource [query])]
                                 (assoc acc (keyword pragma)
                                        (-> result first vals first)))
                               (catch Exception e
                                 (log/debug "Failed to get PRAGMA"
                                            {:pragma pragma :error (.getMessage e)})
                                 acc)))
                           {} pragmas)]
      settings)
    (catch Exception e
      (log/error "Failed to get PRAGMA settings" {:error (.getMessage e)})
      {})))