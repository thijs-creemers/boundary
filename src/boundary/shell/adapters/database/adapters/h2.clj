(ns boundary.shell.adapters.database.adapters.h2
  "H2 database adapter implementation.
   
   This adapter provides H2-specific functionality including:
   - PostgreSQL compatibility mode for maximum compatibility
   - In-memory and file-based database support
   - Native boolean support
   - H2-specific MERGE statements for upserts
   - INFORMATION_SCHEMA table introspection"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; H2 Adapter Implementation
;; =============================================================================

(defrecord H2Adapter []
  protocols/DBAdapter
  
  (dialect [_]
    ;; H2 should return its own dialect identifier
    :h2)
  
  (jdbc-driver [_]
    "org.h2.Driver")
  
  (jdbc-url [_ db-config]
    (let [db-path (:database-path db-config)]
      (cond
        ;; In-memory database
        (:memory db-config)
        "jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1"
        
        ;; Handle explicit memory path
        (or (= ":memory:" db-path)
            (str/starts-with? db-path "mem:"))
        (str "jdbc:h2:" db-path ";MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1")
        
        ;; File-based database
        :else
        (str "jdbc:h2:file:" db-path ";MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE;AUTO_SERVER=TRUE"))))
  
  (pool-defaults [_]
    ;; H2 can handle more connections efficiently
    {:minimum-idle 2
     :maximum-pool-size 15
     :connection-timeout-ms 30000
     :idle-timeout-ms 600000
     :max-lifetime-ms 1800000})
  
  (init-connection! [_ datasource _db-config]
    ;; Set up H2-specific connection settings
    (try
      (with-open [conn (jdbc/get-connection datasource)]
        (log/debug "Initializing H2 connection with compatibility settings")
        
        ;; Set timezone to UTC for consistency
        (jdbc/execute! conn ["SET TIME ZONE 'UTC'"])
        
        ;; Enable PostgreSQL compatibility mode (if not already set in URL)
        (jdbc/execute! conn ["SET MODE PostgreSQL"])
        
        ;; Enable lowercase identifiers for PostgreSQL compatibility
        (jdbc/execute! conn ["SET DATABASE_TO_LOWER TRUE"])
        
        ;; Set reasonable lock timeout
        (jdbc/execute! conn ["SET LOCK_TIMEOUT 30000"])
        
        (log/info "H2 connection initialized with PostgreSQL compatibility mode"))
      (catch Exception e
        (log/warn "Failed to apply H2 settings" {:error (.getMessage e)})
        ;; Don't fail initialization for settings issues
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
                               
                               ;; Handle string pattern matching with ILIKE for case-insensitive
                               (and (string? value) (str/includes? value "*"))
                               [:ilike field (str/replace value "*" "%")]
                               
                               ;; Handle boolean values (H2 supports native booleans)
                               (boolean? value)
                               [:= field value]
                               
                               ;; Default equality
                               :else
                               [:= field value]))
                           filters)]
        (if (= 1 (count conditions))
          (first conditions)
          (into [:and] conditions)))))
  
  (boolean->db [_ boolean-value]
    ;; H2 supports native boolean values
    boolean-value)
  
  (db->boolean [_ db-value]
    ;; H2 returns proper boolean values, but handle edge cases
    (cond
      (boolean? db-value) db-value
      (= db-value 1) true
      (= db-value 0) false
      (= db-value "1") true
      (= db-value "0") false
      (= db-value "true") true
      (= db-value "false") false
      (= db-value "TRUE") true
      (= db-value "FALSE") false
      :else (boolean db-value)))
  
  (table-exists? [_ datasource table-name]
    (try
      (let [table-str (str/lower-case (name table-name))  ; Use lowercase for PostgreSQL compatibility mode
            query "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND LOWER(TABLE_NAME) = ?"
            result (jdbc/execute! datasource [query table-str])]
        (boolean (seq result)))  ; Ensure boolean return instead of truthy/falsy
      (catch Exception e
        (log/error "Failed to check table existence" 
                  {:table table-name :error (.getMessage e)})
        false)))
  
  (get-table-info [_ datasource table-name]
    (try
      (let [table-str (str/lower-case (name table-name))  ; Use lowercase for PostgreSQL compatibility mode
            query "SELECT c.COLUMN_NAME, c.DATA_TYPE, c.IS_NULLABLE, c.COLUMN_DEFAULT,
                         CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN TRUE ELSE FALSE END AS IS_PRIMARY
                   FROM INFORMATION_SCHEMA.COLUMNS c
                   LEFT JOIN (
                     SELECT kcu.COLUMN_NAME
                     FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                     JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                       ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                       AND kcu.TABLE_SCHEMA = tc.TABLE_SCHEMA
                     WHERE LOWER(kcu.TABLE_NAME) = ? AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'
                   ) pk ON c.COLUMN_NAME = pk.COLUMN_NAME
                   WHERE c.TABLE_SCHEMA = 'PUBLIC' AND LOWER(c.TABLE_NAME) = ?
                   ORDER BY c.ORDINAL_POSITION"
            results (jdbc/execute! datasource [query table-str table-str]
                                   {:builder-fn rs/as-unqualified-lower-maps})]  ; Pass table name twice for subquery and main query
        (mapv (fn [row]
                {:name (str/lower-case (:column_name row))
                 :type (:data_type row)
                 :not-null (= "NO" (:is_nullable row))
                 :default (:column_default row)
                 :primary-key (boolean (:is_primary row))})
              results))
      (catch Exception e
        (log/error "Failed to get table info" 
                  {:table table-name :error (.getMessage e)})
        []))))

;; =============================================================================
;; Constructor and Utilities
;; =============================================================================

(defn create-h2-adapter
  "Create a new H2 database adapter.
   
   Returns:
     H2Adapter instance implementing DBAdapter protocol"
  []
  (->H2Adapter))

;; Alias for compatibility with factory
(def new-adapter create-h2-adapter)

;; =============================================================================
;; H2-Specific Utilities
;; =============================================================================

(defn get-h2-version
  "Get H2 database version information.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     String with H2 version"
  [datasource]
  (try
    (let [result (jdbc/execute! datasource ["SELECT H2VERSION() as version"])]
      (:version (first result)))
    (catch Exception e
      (log/error "Failed to get H2 version" {:error (.getMessage e)})
      "unknown")))

(defn get-h2-settings
  "Get current H2 database settings.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     Map of H2 settings"
  [datasource]
  (try
    (let [settings-query "SELECT * FROM INFORMATION_SCHEMA.SETTINGS"
          results (jdbc/execute! datasource [settings-query])]
      (into {} (map (fn [row]
                     [(keyword (str/lower-case (:setting_name row)))
                      (:setting_value row)])
                   results)))
    (catch Exception e
      (log/error "Failed to get H2 settings" {:error (.getMessage e)})
      {})))

(defn create-sequence!
  "Create a sequence in H2 database.
   
   Args:
     datasource: Database connection or datasource
     sequence-name: Name of the sequence
     start-value: Starting value (optional, default 1)
     increment: Increment value (optional, default 1)
     
   Returns:
     nil"
  [datasource sequence-name & {:keys [start-value increment]
                               :or {start-value 1 increment 1}}]
  (let [sql (str "CREATE SEQUENCE IF NOT EXISTS " sequence-name 
                " START WITH " start-value 
                " INCREMENT BY " increment)]
    (log/info "Creating H2 sequence" {:sequence sequence-name :start start-value :increment increment})
    (try
      (jdbc/execute! datasource [sql])
      (log/info "H2 sequence created successfully" {:sequence sequence-name})
      (catch Exception e
        (log/error "Failed to create H2 sequence" 
                  {:sequence sequence-name :error (.getMessage e)})
        (throw e)))))

(defn upsert!
  "Perform MERGE (upsert) operation using H2's MERGE statement.
   
   Args:
     datasource: Database connection or datasource
     table: Table name
     key-columns: Columns to match on
     data: Map of column -> value data
     
   Returns:
     Number of affected rows"
  [datasource table key-columns data]
  (let [table-str (name table)
        all-columns (keys data)
        key-cols (map name key-columns)
        value-cols (map name all-columns)
        placeholders (str/join ", " (repeat (count all-columns) "?"))
        key-conditions (str/join " AND " 
                                (map #(str "KEY." % " = VALUES." %) key-cols))
        sql (str "MERGE INTO " table-str " KEY(" (str/join ", " key-cols) ") "
                "VALUES (" placeholders ")")]
    
    (log/debug "Executing H2 MERGE" {:table table :key-columns key-columns})
    (try
      (let [values (mapv data (map keyword value-cols))
            result (jdbc/execute! datasource (into [sql] values))]
        (log/debug "H2 MERGE completed" {:table table :affected-rows result})
        (first result))
      (catch Exception e
        (log/error "H2 MERGE failed" 
                  {:table table :key-columns key-columns :error (.getMessage e)})
        (throw e)))))

(defn truncate-table!
  "Truncate table in H2 database (faster than DELETE).
   
   Args:
     datasource: Database connection or datasource
     table-name: Table to truncate
     
   Returns:
     nil"
  [datasource table-name]
  (let [sql (str "TRUNCATE TABLE " (name table-name))]
    (log/info "Truncating H2 table" {:table table-name})
    (try
      (jdbc/execute! datasource [sql])
      (log/info "H2 table truncated successfully" {:table table-name})
      (catch Exception e
        (log/error "Failed to truncate H2 table" 
                  {:table table-name :error (.getMessage e)})
        (throw e)))))