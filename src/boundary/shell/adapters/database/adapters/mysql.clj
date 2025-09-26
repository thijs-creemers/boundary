(ns boundary.shell.adapters.database.adapters.mysql
  "MySQL database adapter implementation.
   
   This adapter provides MySQL-specific functionality including:
   - InnoDB storage engine optimization
   - JSON support (MySQL 5.7+)
   - ON DUPLICATE KEY UPDATE for upserts
   - UTF8MB4 character set by default for full Unicode support
   - MySQL-specific connection settings and optimizations
   - INFORMATION_SCHEMA table introspection"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; MySQL Adapter Implementation
;; =============================================================================

(defrecord MySQLAdapter []
  protocols/DBAdapter
  
  (dialect [_]
    ;; MySQL uses :mysql dialect for HoneySQL
    :mysql)
  
  (jdbc-driver [_]
    "com.mysql.cj.jdbc.Driver")
  
  (jdbc-url [_ db-config]
    (let [host (:host db-config)
          port (:port db-config)
          dbname (:name db-config)
          ;; Add MySQL-specific connection parameters for optimal behavior
          params "?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4&useUnicode=true&rewriteBatchedStatements=true"]
      (str "jdbc:mysql://" host ":" port "/" dbname params)))
  
  (pool-defaults [_]
    ;; MySQL can handle reasonable connection pools
    {:minimum-idle 3
     :maximum-pool-size 20
     :connection-timeout-ms 30000
     :idle-timeout-ms 600000
     :max-lifetime-ms 1800000})
  
  (init-connection! [_ datasource db-config]
    ;; Set up MySQL-specific connection settings
    (try
      (with-open [conn (jdbc/get-connection datasource)]
        (log/debug "Initializing MySQL connection with optimal settings")
        
        ;; Set timezone to UTC for consistency
        (jdbc/execute! conn ["SET time_zone = '+00:00'"])
        
        ;; Set SQL mode for strict behavior and standards compliance
        (jdbc/execute! conn ["SET sql_mode = 'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION'"])
        
        ;; Set character set and collation to UTF8MB4 for full Unicode support
        (jdbc/execute! conn ["SET NAMES utf8mb4"])
        (jdbc/execute! conn ["SET CHARACTER SET utf8mb4"])
        (jdbc/execute! conn ["SET collation_connection = 'utf8mb4_unicode_ci'"])
        
        ;; Set transaction isolation level
        (jdbc/execute! conn ["SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED"])
        
        ;; Disable foreign key checks if specified (useful for bulk operations)
        (when (:disable-foreign-key-checks db-config)
          (jdbc/execute! conn ["SET foreign_key_checks = 0"]))
        
        ;; Set wait timeout
        (jdbc/execute! conn ["SET wait_timeout = 28800"])  ; 8 hours
        (jdbc/execute! conn ["SET interactive_timeout = 28800"])
        
        ;; Enable query cache if available (older MySQL versions)
        (try
          (jdbc/execute! conn ["SET query_cache_type = ON"])
          (catch Exception _
            ;; Query cache removed in MySQL 8.0, ignore error
            nil))
        
        (log/info "MySQL connection initialized with optimal settings"))
      (catch Exception e
        (log/warn "Failed to apply MySQL settings" {:error (.getMessage e)})
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
                               
                               ;; Handle string pattern matching (case-insensitive with LIKE)
                               (and (string? value) (str/includes? value "*"))
                               [:like field (str/replace value "*" "%")]
                               
                               ;; Handle JSON queries (MySQL 5.7+)
                               (and (string? value) (str/includes? (name field) "json"))
                               ;; MySQL JSON functions: JSON_EXTRACT, JSON_CONTAINS, etc.
                               [:= field value]  ; Simplified for now
                               
                               ;; Handle boolean values (MySQL stores as TINYINT)
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
    ;; MySQL stores booleans as TINYINT(1)
    (cond
      (true? boolean-value) 1
      (false? boolean-value) 0
      :else nil))
  
  (db->boolean [_ db-value]
    ;; Convert MySQL TINYINT values back to booleans
    (cond
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
      (let [table-str (name table-name)
            query "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
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
                     COLUMN_NAME,
                     DATA_TYPE,
                     IS_NULLABLE,
                     COLUMN_DEFAULT,
                     COLUMN_KEY
                   FROM INFORMATION_SCHEMA.COLUMNS 
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                   ORDER BY ORDINAL_POSITION"
            results (jdbc/execute! datasource [query table-str])]
        (mapv (fn [row]
                {:name (str/lower-case (:column_name row))
                 :type (:data_type row)
                 :not-null (= "NO" (:is_nullable row))
                 :default (:column_default row)
                 :primary-key (= "PRI" (:column_key row))})
              results))
      (catch Exception e
        (log/error "Failed to get table info" 
                  {:table table-name :error (.getMessage e)})
        []))))

;; =============================================================================
;; Constructor and Utilities
;; =============================================================================

(defn create-mysql-adapter
  "Create a new MySQL database adapter.
   
   Returns:
     MySQLAdapter instance implementing DBAdapter protocol"
  []
  (->MySQLAdapter))

;; Alias for compatibility with factory
(def new-adapter create-mysql-adapter)

;; =============================================================================
;; MySQL-Specific Utilities
;; =============================================================================

(defn get-mysql-version
  "Get MySQL version information.
   
   Args:
     datasource: Database connection or datasource
     
   Returns:
     String with MySQL version"
  [datasource]
  (try
    (let [result (jdbc/execute! datasource ["SELECT VERSION() as version"])]
      (:version (first result)))
    (catch Exception e
      (log/error "Failed to get MySQL version" {:error (.getMessage e)})
      "unknown")))

(defn get-mysql-status
  "Get MySQL status variables.
   
   Args:
     datasource: Database connection or datasource
     pattern: Optional LIKE pattern to filter status variables
     
   Returns:
     Map of status variables"
  [datasource & {:keys [pattern]}]
  (try
    (let [query (if pattern
                 (str "SHOW STATUS LIKE '" pattern "'")
                 "SHOW STATUS")
          results (jdbc/execute! datasource [query])]
      (into {} (map (fn [row]
                     [(keyword (str/lower-case (str/replace (:variable_name row) "_" "-")))
                      (:value row)])
                   results)))
    (catch Exception e
      (log/error "Failed to get MySQL status" {:error (.getMessage e)})
      {})))

(defn optimize-table!
  "Run OPTIMIZE TABLE on a MySQL table to reclaim space and update statistics.
   
   Args:
     datasource: Database connection or datasource
     table-name: Table to optimize
     
   Returns:
     Result of OPTIMIZE TABLE operation"
  [datasource table-name]
  (let [sql (str "OPTIMIZE TABLE " (name table-name))]
    (log/info "Running OPTIMIZE TABLE on MySQL table" {:table table-name})
    (try
      (let [result (jdbc/execute! datasource [sql])]
        (log/info "OPTIMIZE TABLE completed" {:table table-name :result result})
        result)
      (catch Exception e
        (log/error "OPTIMIZE TABLE failed" 
                  {:table table-name :error (.getMessage e)})
        (throw e)))))

(defn analyze-table!
  "Run ANALYZE TABLE on a MySQL table to update index statistics.
   
   Args:
     datasource: Database connection or datasource
     table-name: Table to analyze
     
   Returns:
     Result of ANALYZE TABLE operation"
  [datasource table-name]
  (let [sql (str "ANALYZE TABLE " (name table-name))]
    (log/info "Running ANALYZE TABLE on MySQL table" {:table table-name})
    (try
      (let [result (jdbc/execute! datasource [sql])]
        (log/info "ANALYZE TABLE completed" {:table table-name :result result})
        result)
      (catch Exception e
        (log/error "ANALYZE TABLE failed" 
                  {:table table-name :error (.getMessage e)})
        (throw e)))))

(defn upsert!
  "Perform upsert using MySQL's INSERT ... ON DUPLICATE KEY UPDATE.
   
   Args:
     datasource: Database connection or datasource
     table: Table name
     data: Map of column -> value data
     update-columns: Columns to update on duplicate key (optional, defaults to all columns)
     
   Returns:
     Result of the operation"
  [datasource table data & {:keys [update-columns]}]
  (let [table-str (name table)
        all-columns (keys data)
        update-cols (or update-columns all-columns)
        
        ;; Build INSERT part
        insert-columns (str/join ", " (map name all-columns))
        placeholders (str/join ", " (repeat (count all-columns) "?"))
        
        ;; Build ON DUPLICATE KEY UPDATE part
        update-clause (str/join ", " 
                               (map #(str (name %) " = VALUES(" (name %) ")") update-cols))
        
        sql (str "INSERT INTO " table-str " (" insert-columns ") "
                "VALUES (" placeholders ") "
                "ON DUPLICATE KEY UPDATE " update-clause)]
    
    (log/debug "Executing MySQL upsert" 
              {:table table :update-columns (map name update-cols)})
    (try
      (let [values (mapv data all-columns)
            result (jdbc/execute! datasource (into [sql] values))]
        (log/debug "MySQL upsert completed" {:table table :result result})
        result)
      (catch Exception e
        (log/error "MySQL upsert failed" 
                  {:table table :error (.getMessage e)})
        (throw e)))))

(defn show-create-table
  "Show the CREATE TABLE statement for a MySQL table.
   
   Args:
     datasource: Database connection or datasource
     table-name: Table name
     
   Returns:
     CREATE TABLE statement as string"
  [datasource table-name]
  (try
    (let [query (str "SHOW CREATE TABLE " (name table-name))
          result (jdbc/execute! datasource [query])]
      (:create_table (first result)))
    (catch Exception e
      (log/error "Failed to show CREATE TABLE" 
                {:table table-name :error (.getMessage e)})
      nil)))

(defn get-table-status
  "Get MySQL table status information.
   
   Args:
     datasource: Database connection or datasource
     table-name: Optional table name (if nil, shows all tables)
     
   Returns:
     Vector of table status maps"
  [datasource & {:keys [table-name]}]
  (try
    (let [query (if table-name
                 (str "SHOW TABLE STATUS LIKE '" (name table-name) "'")
                 "SHOW TABLE STATUS")
          results (jdbc/execute! datasource [query])]
      (mapv (fn [row]
              {:name (:name row)
               :engine (:engine row)
               :rows (:rows row)
               :data-length (:data_length row)
               :index-length (:index_length row)
               :auto-increment (:auto_increment row)
               :create-time (:create_time row)
               :update-time (:update_time row)
               :collation (:collation row)})
           results))
    (catch Exception e
      (log/error "Failed to get MySQL table status" 
                {:table table-name :error (.getMessage e)})
      [])))

(defn repair-table!
  "Run REPAIR TABLE on a MySQL table (MyISAM only).
   
   Args:
     datasource: Database connection or datasource
     table-name: Table to repair
     
   Returns:
     Result of REPAIR TABLE operation"
  [datasource table-name]
  (let [sql (str "REPAIR TABLE " (name table-name))]
    (log/info "Running REPAIR TABLE on MySQL table" {:table table-name})
    (try
      (let [result (jdbc/execute! datasource [sql])]
        (log/info "REPAIR TABLE completed" {:table table-name :result result})
        result)
      (catch Exception e
        (log/error "REPAIR TABLE failed" 
                  {:table table-name :error (.getMessage e)})
        (throw e)))))