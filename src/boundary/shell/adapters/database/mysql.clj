(ns boundary.shell.adapters.database.mysql
  "MySQL database adapter implementing the DBAdapter protocol.
   
   This namespace provides MySQL-specific functionality for production
   database deployments. MySQL is a widely-used, reliable relational
   database system with good performance and broad compatibility.
   
   Key Features:
   - LIKE-based string matching (case-insensitive by default)
   - Boolean values stored as TINYINT(1)
   - Robust connection handling with proper SSL configuration
   - Timezone and SQL mode configuration for consistency
   - Connection pool tuning for server workloads
   
   MySQL-Specific Optimizations:
   - SQL mode configuration for strict compliance
   - Timezone normalization to UTC
   - SSL and connection parameter tuning
   - Connection pool settings for server environments"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shared.utils.type-conversion :as tc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; MySQL Adapter Implementation
;; =============================================================================

(defrecord MySQLAdapter []
  protocols/DBAdapter
  
  (dialect [_]
    :mysql)
  
  (jdbc-driver [_]
    "com.mysql.cj.jdbc.Driver")
  
  (jdbc-url [_ db-config]
    (let [{:keys [host port name]} db-config
          base-url (str "jdbc:mysql://" host ":" port "/" name)
          ;; Common MySQL connection parameters for consistency and security
          default-params {:serverTimezone "UTC"
                         :useSSL "true"
                         :requireSSL "false"
                         :verifyServerCertificate "false"
                         :useUnicode "true"
                         :characterEncoding "utf8"
                         :zeroDateTimeBehavior "convertToNull"}
          custom-params (:connection-params db-config {})
          all-params (merge default-params custom-params)
          param-str (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) all-params))]
      (str base-url "?" param-str)))
  
  (pool-defaults [_]
    {:minimum-idle 5
     :maximum-pool-size 15
     :connection-timeout-ms 30000
     :idle-timeout-ms 600000
     :max-lifetime-ms 1800000})
  
  (init-connection! [_ datasource db-config]
    (log/debug "Initializing MySQL connection settings")
    (try
      ;; Set SQL mode for strict behavior (configurable)
      (let [sql-mode (get db-config :sql-mode "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION")]
        (when (seq sql-mode)
          (jdbc/execute! datasource [(str "SET SESSION sql_mode = '" sql-mode "'")])))
      
      ;; Set timezone to UTC for consistency
      (jdbc/execute! datasource ["SET SESSION time_zone = '+00:00'"])
      
      ;; Set character set to UTF8 for proper Unicode handling
      (jdbc/execute! datasource ["SET NAMES utf8mb4"])
      
      (log/debug "MySQL connection initialized successfully")
      (catch Exception e
        (log/warn "Failed to initialize some MySQL settings" {:error (.getMessage e)}))))
  
  (build-where [_ filters]
    (when (seq filters)
      (let [conditions (for [[field value] filters
                              :when (some? value)]
                         (cond
                           (string? value) [:like field (str "%" value "%")] ; MySQL LIKE is case-insensitive by default
                           (vector? value) [:in field value]
                           (boolean? value) [:= field (tc/boolean->int value)] ; MySQL uses TINYINT(1) for booleans
                           :else [:= field value]))]
        (when (seq conditions)
          (if (= 1 (count conditions))
            (first conditions)
            (cons :and conditions))))))
  
  (boolean->db [_ boolean-value]
    ;; MySQL typically uses TINYINT(1) for boolean values
    (tc/boolean->int boolean-value))
  
  (db->boolean [_ db-value]
    ;; Convert MySQL TINYINT back to boolean
    (tc/int->boolean db-value))
  
  (table-exists? [_ datasource table-name]
    (let [table-str (name table-name)
          query {:select [:%count.*]
                 :from [:information_schema.tables]
                 :where [:and
                         [:= :table_schema [:database]]
                         [:= :table_name table-str]]}
          result (first (jdbc/execute! datasource 
                                       (sql/format query {:dialect :mysql})
                                       {:builder-fn rs/as-unqualified-lower-maps}))]
      (> (:count result 0) 0)))
  
  (get-table-info [_ datasource table-name]
    (let [table-str (name table-name)
          ;; Get column information
          columns-query {:select [:column_name :data_type :is_nullable :column_default :extra]
                        :from [:information_schema.columns]
                        :where [:and
                                [:= :table_schema [:database]]
                                [:= :table_name table-str]]
                        :order-by [:ordinal_position]}
          ;; Get primary key information
          pk-query {:select [:kcu.column_name]
                   :from [[:information_schema.table_constraints :tc]]
                   :join [[:information_schema.key_column_usage :kcu]
                          [:and
                           [:= :tc.constraint_name :kcu.constraint_name]
                           [:= :tc.table_schema :kcu.table_schema]]]
                   :where [:and
                           [:= :tc.table_schema [:database]]
                           [:= :tc.table_name table-str]
                           [:= :tc.constraint_type "PRIMARY KEY"]]}
          
          columns (jdbc/execute! datasource 
                                (sql/format columns-query {:dialect :mysql})
                                {:builder-fn rs/as-unqualified-lower-maps})
          pk-columns (set (map :column_name 
                              (jdbc/execute! datasource
                                            (sql/format pk-query {:dialect :mysql})
                                            {:builder-fn rs/as-unqualified-lower-maps})))]
      
      (mapv (fn [col]
              {:name (:column_name col)
               :type (:data_type col)
               :not-null (= "NO" (:is_nullable col))
               :default (:column_default col)
               :primary-key (contains? pk-columns (:column_name col))
               :auto-increment (str/includes? (str (:extra col)) "auto_increment")})
            columns))))

(defn new-adapter
  "Create new MySQL adapter instance.
   
   Returns:
     MySQL adapter implementing DBAdapter protocol"
  []
  (->MySQLAdapter))

;; =============================================================================
;; MySQL-Specific Utilities
;; =============================================================================

(defn create-database-url
  "Create MySQL JDBC URL from components.
   
   Args:
     host: Database hostname
     port: Database port
     database: Database name
     options: Optional map of connection parameters
     
   Returns:
     JDBC URL string
     
   Example:
     (create-database-url \"localhost\" 3306 \"mydb\" {:useSSL \"false\"})"
  [host port database & [options]]
  (let [base-url (str "jdbc:mysql://" host ":" port "/" database)
        default-params {:serverTimezone "UTC"
                       :useSSL "true"
                       :requireSSL "false"}
        params (merge default-params options)
        param-str (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))]
    (if (seq params)
      (str base-url "?" param-str)
      base-url)))

;; =============================================================================
;; DDL Helpers for MySQL
;; =============================================================================

(defn boolean-column-type
  "Get MySQL boolean column type definition.
   
   MySQL doesn't have native boolean, uses TINYINT(1).
   
   Returns:
     String - 'TINYINT(1)' for MySQL"
  []
  "TINYINT(1)")

(defn uuid-column-type
  "Get MySQL UUID column type definition.
   
   MySQL doesn't have native UUID support, use CHAR for performance.
   
   Returns:
     String - 'CHAR(36)' for UUID storage in MySQL"
  []
  "CHAR(36)")

(defn varchar-uuid-column-type
  "Get MySQL varchar UUID column type definition.
   
   Alternative to CHAR(36) if variable length is preferred.
   
   Returns:
     String - 'VARCHAR(36)' for UUID storage"
  []
  "VARCHAR(36)")

(defn timestamp-column-type
  "Get MySQL timestamp column type definition.
   
   Returns:
     String - 'TIMESTAMP' for MySQL"
  []
  "TIMESTAMP")

(defn datetime-column-type
  "Get MySQL datetime column type definition.
   
   DATETIME has better range than TIMESTAMP in MySQL.
   
   Returns:
     String - 'DATETIME' for MySQL"
  []
  "DATETIME")

(defn auto-increment-primary-key
  "Get MySQL auto-increment primary key definition.
   
   Returns:
     String - MySQL auto-increment syntax"
  []
  "BIGINT AUTO_INCREMENT PRIMARY KEY")

;; =============================================================================
;; MySQL-Specific Query Optimizations
;; =============================================================================

(defn explain-query
  "Get MySQL query execution plan.
   
   Args:
     datasource: MySQL datasource
     query-map: HoneySQL query map
     format-type: Optional format (:traditional, :json, :tree)
     
   Returns:
     Vector of execution plan rows
     
   Example:
     (explain-query ds {:select [:*] :from [:users]} :json)"
  ([datasource query-map]
   (explain-query datasource query-map :traditional))
  ([datasource query-map format-type]
   (let [sql-query (sql/format query-map {:dialect :mysql})
         format-clause (case format-type
                        :json "FORMAT=JSON"
                        :tree "FORMAT=TREE"
                        "")
         explain-sql (str "EXPLAIN " format-clause " " (first sql-query))
         params (rest sql-query)]
     (jdbc/execute! datasource (cons explain-sql params)
                   {:builder-fn rs/as-unqualified-lower-maps}))))

(defn analyze-table
  "Update MySQL table statistics for better query planning.
   
   Args:
     datasource: MySQL datasource
     table-name: Table name to analyze
     
   Example:
     (analyze-table ds :users)"
  [datasource table-name]
  (let [table-str (name table-name)]
    (jdbc/execute! datasource [(str "ANALYZE TABLE " table-str)])
    (log/debug "Analyzed MySQL table" {:table table-str})))

(defn optimize-table
  "Optimize MySQL table to reclaim space and update indexes.
   
   Args:
     datasource: MySQL datasource
     table-name: Table name to optimize
     
   Example:
     (optimize-table ds :users)"
  [datasource table-name]
  (let [table-str (name table-name)]
    (jdbc/execute! datasource [(str "OPTIMIZE TABLE " table-str)])
    (log/debug "Optimized MySQL table" {:table table-str})))

;; =============================================================================
;; MySQL Server Information
;; =============================================================================

(defn server-info
  "Get MySQL server information.
   
   Args:
     datasource: MySQL datasource
     
   Returns:
     Map with server details
     
   Example:
     (server-info ds)"
  [datasource]
  (let [version-result (jdbc/execute-one! datasource ["SELECT VERSION() as version"] 
                                         {:builder-fn rs/as-unqualified-lower-maps})
        vars-result (jdbc/execute-one! datasource 
                                      ["SELECT @@hostname as hostname, @@port as port, DATABASE() as current_db, USER() as current_user"]
                                      {:builder-fn rs/as-unqualified-lower-maps})]
    {:version (:version version-result)
     :hostname (:hostname vars-result)
     :port (:port vars-result)
     :current-database (:current_db vars-result)
     :current-user (:current_user vars-result)}))

(defn show-engines
  "List available MySQL storage engines.
   
   Args:
     datasource: MySQL datasource
     
   Returns:
     Vector of engine information
     
   Example:
     (show-engines ds)"
  [datasource]
  (let [results (jdbc/execute! datasource ["SHOW ENGINES"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:engine (:engine row)
             :support (:support row)
             :comment (:comment row)
             :transactions (:transactions row)
             :xa (:xa row)
             :savepoints (:savepoints row)})
          results)))

(defn show-variables
  "Show MySQL system variables.
   
   Args:
     datasource: MySQL datasource
     pattern: Optional LIKE pattern to filter variables
     
   Returns:
     Vector of variable information
     
   Example:
     (show-variables ds \"innodb%\")"
  ([datasource]
   (show-variables datasource nil))
  ([datasource pattern]
   (let [query (if pattern
                 (str "SHOW VARIABLES LIKE '" pattern "'")
                 "SHOW VARIABLES")
         results (jdbc/execute! datasource [query]
                               {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:name (:variable_name row)
              :value (:value row)})
           results))))

;; =============================================================================
;; Connection and Performance Utilities
;; =============================================================================

(defn show-processlist
  "Show active MySQL connections and queries.
   
   Args:
     datasource: MySQL datasource
     
   Returns:
     Vector of process information
     
   Example:
     (show-processlist ds)"
  [datasource]
  (let [results (jdbc/execute! datasource ["SHOW PROCESSLIST"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:id (:id row)
             :user (:user row)
             :host (:host row)
             :db (:db row)
             :command (:command row)
             :time (:time row)
             :state (:state row)
             :info (:info row)})
          results)))

(defn show-table-status
  "Show MySQL table status and statistics.
   
   Args:
     datasource: MySQL datasource
     table-name: Optional table name to filter (shows all if nil)
     
   Returns:
     Vector of table status information
     
   Example:
     (show-table-status ds :users)"
  ([datasource]
   (show-table-status datasource nil))
  ([datasource table-name]
   (let [query (if table-name
                 (str "SHOW TABLE STATUS LIKE '" (name table-name) "'")
                 "SHOW TABLE STATUS")
         results (jdbc/execute! datasource [query]
                               {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:name (:name row)
              :engine (:engine row)
              :rows (:rows row)
              :avg-row-length (:avg_row_length row)
              :data-length (:data_length row)
              :index-length (:index_length row)
              :auto-increment (:auto_increment row)
              :collation (:collation row)})
           results))))
