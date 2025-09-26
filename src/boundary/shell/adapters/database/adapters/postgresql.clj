(ns boundary.shell.adapters.database.adapters.postgresql
  "PostgreSQL database adapter implementation.
   
   This adapter provides PostgreSQL-specific functionality including:
   - Full ACID compliance with proper transaction isolation
   - JSON and JSONB support for complex data types
   - Array data types support
   - ILIKE for case-insensitive pattern matching
   - ON CONFLICT for upserts (INSERT ... ON CONFLICT)
   - Advanced indexing options (GIN, GIST, etc.)
   - INFORMATION_SCHEMA introspection"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; PostgreSQL Adapter Implementation
;; =============================================================================

(defrecord PostgreSQLAdapter []
  protocols/DBAdapter
  
  (dialect [_]
    ;; PostgreSQL uses nil dialect (default) for HoneySQL
    nil)
  
  (jdbc-driver [_]
    "org.postgresql.Driver")
  
  (jdbc-url [_ db-config]
    (let [host (:host db-config)
          port (:port db-config)
          dbname (:name db-config)]
      (str "jdbc:postgresql://" host ":" port "/" dbname)))
  
  (pool-defaults [_]
    ;; PostgreSQL can handle large connection pools efficiently
    {:minimum-idle 5
     :maximum-pool-size 25
     :connection-timeout-ms 30000
     :idle-timeout-ms 600000
     :max-lifetime-ms 1800000})
  
  (init-connection! [_ datasource db-config]
    ;; Set up PostgreSQL-specific connection settings
    (try
      (with-open [conn (jdbc/get-connection datasource)]
        (log/debug "Initializing PostgreSQL connection with optimal settings")
        
        ;; Set timezone to UTC for consistency across all connections
        (jdbc/execute! conn ["SET TIME ZONE 'UTC'"])
        
        ;; Set application name for connection tracking
        (let [app-name (or (:application-name db-config) "clojure-app")]
          (jdbc/execute! conn [(str "SET application_name = '" app-name "'")]))
        
        ;; Set search path if specified
        (when-let [schema (:schema db-config)]
          (jdbc/execute! conn [(str "SET search_path = " schema)]))
        
        ;; Enable standard conforming strings
        (jdbc/execute! conn ["SET standard_conforming_strings = ON"])
        
        ;; Set statement timeout (optional)
        (when-let [timeout (:statement-timeout-ms db-config)]
          (jdbc/execute! conn [(str "SET statement_timeout = " timeout)]))
        
        ;; Set lock timeout
        (jdbc/execute! conn ["SET lock_timeout = '30s'"])
        
        (log/info "PostgreSQL connection initialized with optimal settings"))
      (catch Exception e
        (log/warn "Failed to apply PostgreSQL settings" {:error (.getMessage e)})
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
                               
                               ;; Handle JSON queries (if field ends with ->)
                               (and (string? value) (str/ends-with? (name field) "->"))
                               ;; This would need custom HoneySQL extension for complex JSON queries
                               [:= field value]
                               
                               ;; Handle array contains (if value is a vector and field suggests array)
                               (and (vector? value) (str/includes? (name field) "array"))
                               ;; PostgreSQL array containment: field @> ARRAY[...]
                               [:raw (str (name field) " @> ARRAY[" 
                                         (str/join "," (map pr-str value)) "]")]
                               
                               ;; Handle boolean values (PostgreSQL supports native booleans)
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
    ;; PostgreSQL supports native boolean values
    boolean-value)
  
  (db->boolean [_ db-value]
    ;; PostgreSQL returns proper boolean values, but handle edge cases
    (cond
      (boolean? db-value) db-value
      (= db-value "t") true
      (= db-value "f") false
      (= db-value "true") true
      (= db-value "false") false
      (= db-value "TRUE") true
      (= db-value "FALSE") false
      (= db-value 1) true
      (= db-value 0) false
      :else (boolean db-value)))
  
  (table-exists? [_ datasource table-name]
    (try
      (let [table-str (name table-name)
            query "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = ?"
            result (jdbc/execute! datasource [query table-str])]
        (seq result))
      (catch Exception e
        (log/error "Failed to check table existence" 
                  {:table table-name :error (.getMessage e)})
        false)))
  
  (get-table-info [_ datasource table-name]
    (try
      (let [table-str (name table-name)
            query "SELECT 
                     c.column_name,
                     c.data_type,
                     c.is_nullable,
                     c.column_default,
                     CASE WHEN pk.column_name IS NOT NULL THEN true ELSE false END AS is_primary_key
                   FROM information_schema.columns c
                   LEFT JOIN (
                     SELECT ku.column_name
                     FROM information_schema.table_constraints tc
                     JOIN information_schema.key_column_usage ku
                       ON tc.constraint_name = ku.constraint_name
                       AND tc.table_schema = ku.table_schema
                     WHERE tc.constraint_type = 'PRIMARY KEY'
                       AND tc.table_schema = 'public'
                       AND tc.table_name = ?
                   ) pk ON c.column_name = pk.column_name
                   WHERE c.table_schema = 'public' 
                     AND c.table_name = ?
                   ORDER BY c.ordinal_position"
            results (jdbc/execute! datasource [query table-str table-str])]
        (mapv (fn [row]
                {:name (:column_name row)
                 :type (:data_type row)
                 :not-null (= "NO" (:is_nullable row))
                 :default (:column_default row)
                 :primary-key (:is_primary_key row)})
              results))
      (catch Exception e
        (log/error "Failed to get table info" 
                  {:table table-name :error (.getMessage e)})
        []))))

;; =============================================================================
;; Constructor and Utilities
;; =============================================================================

(defn create-postgresql-adapter
  "Create a new PostgreSQL database adapter.
   
   Returns:
     PostgreSQLAdapter instance implementing DBAdapter protocol"
  []
  (->PostgreSQLAdapter))

;; Alias for compatibility with factory
(def new-adapter create-postgresql-adapter)

;; =============================================================================
;; PostgreSQL-Specific Utilities
;; =============================================================================

(defn get-postgresql-version
  "Get PostgreSQL version information.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     String with PostgreSQL version"
  [datasource]
  (try
    (let [result (jdbc/execute! datasource ["SELECT version() as version"])]
      (:version (first result)))
    (catch Exception e
      (log/error "Failed to get PostgreSQL version" {:error (.getMessage e)})
      "unknown")))

(defn get-database-stats
  "Get PostgreSQL database statistics.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     Map with database statistics"
  [datasource]
  (try
    (let [stats-query "SELECT 
                         schemaname,
                         tablename,
                         attname,
                         n_distinct,
                         most_common_vals,
                         most_common_freqs
                       FROM pg_stats 
                       WHERE schemaname = 'public' 
                       LIMIT 50"
          results (jdbc/execute! datasource [stats-query])]
      {:table-count (count (distinct (map :tablename results)))
       :column-stats results})
    (catch Exception e
      (log/error "Failed to get PostgreSQL database stats" {:error (.getMessage e)})
      {:table-count 0 :column-stats []})))

(defn analyze-table!
  "Run ANALYZE on a specific table to update statistics.
   
   Args:
     datasource: Database connection or datasource
     table-name: Table to analyze
     
   Returns:
     nil"
  [datasource table-name]
  (let [sql (str "ANALYZE " (name table-name))]
    (log/info "Running ANALYZE on PostgreSQL table" {:table table-name})
    (try
      (jdbc/execute! datasource [sql])
      (log/info "ANALYZE completed successfully" {:table table-name})
      (catch Exception e
        (log/error "ANALYZE failed" 
                  {:table table-name :error (.getMessage e)})
        (throw e)))))

(defn create-index-concurrently!
  "Create index concurrently without locking the table.
   
   Args:
     datasource: Database connection or datasource
     index-name: Name of the index
     table-name: Table to index
     columns: Vector of column names
     index-type: Optional index type (:btree, :gin, :gist, etc.)
     
   Returns:
     nil"
  [datasource index-name table-name columns & {:keys [index-type unique?]
                                               :or {index-type :btree unique? false}}]
  (let [table-str (name table-name)
        cols-str (str/join ", " (map name columns))
        unique-str (if unique? "UNIQUE " "")
        type-str (if (= index-type :btree) "" (str " USING " (name index-type)))
        sql (str "CREATE " unique-str "INDEX CONCURRENTLY IF NOT EXISTS " 
                index-name " ON " table-str type-str " (" cols-str ")")]
    
    (log/info "Creating PostgreSQL index concurrently" 
             {:index index-name :table table-name :columns columns :type index-type})
    (try
      (jdbc/execute! datasource [sql])
      (log/info "PostgreSQL index created successfully" {:index index-name})
      (catch Exception e
        (log/error "Failed to create PostgreSQL index" 
                  {:index index-name :error (.getMessage e)})
        (throw e)))))

(defn upsert!
  "Perform upsert using PostgreSQL's INSERT ... ON CONFLICT.
   
   Args:
     datasource: Database connection or datasource
     table: Table name
     conflict-columns: Columns that define the conflict
     data: Map of column -> value data
     update-columns: Columns to update on conflict (optional, defaults to all non-conflict columns)
     
   Returns:
     Result of the operation"
  [datasource table conflict-columns data & {:keys [update-columns]}]
  (let [table-str (name table)
        all-columns (keys data)
        conflict-cols (map name conflict-columns)
        update-cols (or update-columns 
                       (remove (set conflict-cols) (map name all-columns)))
        
        ;; Build INSERT part
        insert-columns (str/join ", " (map name all-columns))
        placeholders (str/join ", " (repeat (count all-columns) "?"))
        
        ;; Build ON CONFLICT UPDATE part
        conflict-clause (str/join ", " conflict-cols)
        update-clause (str/join ", " 
                               (map #(str % " = EXCLUDED." %) update-cols))
        
        sql (str "INSERT INTO " table-str " (" insert-columns ") "
                "VALUES (" placeholders ") "
                "ON CONFLICT (" conflict-clause ") "
                "DO UPDATE SET " update-clause)]
    
    (log/debug "Executing PostgreSQL upsert" 
              {:table table :conflict-columns conflict-columns :update-columns update-cols})
    (try
      (let [values (mapv data (map keyword (map name all-columns)))
            result (jdbc/execute! datasource (into [sql] values))]
        (log/debug "PostgreSQL upsert completed" {:table table :result result})
        result)
      (catch Exception e
        (log/error "PostgreSQL upsert failed" 
                  {:table table :conflict-columns conflict-columns :error (.getMessage e)})
        (throw e)))))

(defn vacuum-analyze!
  "Run VACUUM ANALYZE on database or specific table.
   
   Args:
     datasource: Database connection or datasource
     table-name: Optional table name (if nil, vacuums entire database)
     
   Returns:
     nil"
  [datasource & {:keys [table-name]}]
  (let [sql (if table-name 
             (str "VACUUM ANALYZE " (name table-name))
             "VACUUM ANALYZE")]
    (log/info "Running VACUUM ANALYZE on PostgreSQL" 
             {:table (or table-name "entire database")})
    (try
      (jdbc/execute! datasource [sql])
      (log/info "VACUUM ANALYZE completed successfully" {:table table-name})
      (catch Exception e
        (log/error "VACUUM ANALYZE failed" 
                  {:table table-name :error (.getMessage e)})
        (throw e)))))