(ns boundary.shell.adapters.database.postgresql
  "PostgreSQL database adapter implementing the DBAdapter protocol.
   
   This namespace provides PostgreSQL-specific functionality for production
   database deployments. PostgreSQL is a powerful, open-source relational
   database system with advanced features and excellent performance.
   
   Key Features:
   - Case-insensitive string matching with ILIKE
   - Native boolean support
   - Advanced SQL features and data types
   - Robust transaction support
   - Excellent performance and scalability
   
   PostgreSQL-Specific Optimizations:
   - Application name setting for connection identification
   - Timezone configuration for UTC consistency
   - Statement timeout configuration for query safety
   - Connection pool tuning for server workloads"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; PostgreSQL Adapter Implementation
;; =============================================================================

(defrecord PostgreSQLAdapter []
  protocols/DBAdapter

  (dialect [_]
    :postgresql)

  (jdbc-driver [_]
    "org.postgresql.Driver")

  (jdbc-url [_ db-config]
    (let [{:keys [host port name]} db-config
          base-url (str "jdbc:postgresql://" host ":" port "/" name)]
      ;; Add common PostgreSQL connection parameters
      (str base-url "?stringtype=unspecified")))

  (pool-defaults [_]
    {:minimum-idle          5
     :maximum-pool-size     20
     :connection-timeout-ms 30000
     :idle-timeout-ms       600000
     :max-lifetime-ms       1800000})

  (init-connection! [_ datasource db-config]
    (log/debug "Initializing PostgreSQL connection settings")
    (try
      ;; Set application name for connection identification
      (let [app-name (or (:application-name db-config) "boundary-app")]
        (jdbc/execute! datasource [(str "SET application_name = '" app-name "'")]))

      ;; Set timezone to UTC for consistency
      (jdbc/execute! datasource ["SET TIME ZONE 'UTC'"])

      ;; Optionally set statement timeout (configurable, default 30 seconds)
      (let [timeout (get db-config :statement-timeout-ms 30000)]
        (when (pos? timeout)
          (jdbc/execute! datasource [(str "SET statement_timeout = " timeout)])))

      (log/debug "PostgreSQL connection initialized successfully")
      (catch Exception e
        (log/warn "Failed to initialize some PostgreSQL settings" {:error (.getMessage e)}))))

  (build-where [_ filters]
    (when (seq filters)
      (let [conditions (for [[field value] filters
                             :when (some? value)]
                         (cond
                           (string? value) [:ilike field (str "%" value "%")] ; PostgreSQL ILIKE for case-insensitive
                           (vector? value) [:in field value]
                           (boolean? value) [:= field value] ; PostgreSQL supports native booleans
                           :else [:= field value]))]
        (when (seq conditions)
          (if (= 1 (count conditions))
            (first conditions)
            (cons :and conditions))))))

  (boolean->db [_ boolean-value]
    ;; PostgreSQL supports native boolean values
    boolean-value)

  (db->boolean [_ db-value]
    ;; PostgreSQL returns native boolean values
    db-value)

  (table-exists? [_ datasource table-name]
    (let [table-str (str/lower-case (name table-name))
          schema    (or "public")                           ; Could be configurable in the future
          query     {:select [:%count.*]
                     :from   [:information_schema.tables]
                     :where  [:and
                              [:= :table_schema schema]
                              [:= :table_name table-str]]}
          result    (first (jdbc/execute! datasource
                                          (sql/format query {:dialect :postgresql})
                                          {:builder-fn rs/as-unqualified-lower-maps}))]
      (> (:count result 0) 0)))

  (get-table-info [_ datasource table-name]
    (let [table-str     (str/lower-case (name table-name))
          schema        (or "public")
          ;; Get column information
          columns-query {:select [:column_name :data_type :is_nullable :column_default]}
          :from [:information_schema.columns]
          :where [:and
                  [:= :table_schema schema]
                  [:= :table_name table-str]]
          :order-by [:ordinal_position]
          ;; Get primary key information
          pk-query {:select [:kcu.column_name]}
          :from [[:information_schema.table_constraints :tc]]
          :join [[:information_schema.key_column_usage :kcu]
                 [:and
                  [:= :tc.constraint_name :kcu.constraint_name]
                  [:= :tc.table_schema :kcu.table_schema]]]
          :where [:and
                  [:= :tc.table_schema schema]
                  [:= :tc.table_name table-str]
                  [:= :tc.constraint_type "PRIMARY KEY"]]

          columns (jdbc/execute! datasource
                                 (sql/format columns-query {:dialect :postgresql})
                                 {:builder-fn rs/as-unqualified-lower-maps})
          pk-columns (set (map :column_name
                               (jdbc/execute! datasource
                                               (sql/format pk-query {:dialect :postgresql})
                                               {:builder-fn rs/as-unqualified-lower-maps})))]

      (mapv (fn [col]
              {:name        (:column_name col)
               :type        (:data_type col)
               :not-null    (= "NO" (:is_nullable col))
               :default     (:column_default col)
               :primary-key (contains? pk-columns (:column_name col))})
            columns))))

(defn new-adapter
      "Create new PostgreSQL adapter instance.

       Returns:
         PostgreSQL adapter implementing DBAdapter protocol"
  []
  (->PostgreSQLAdapter))

;; =============================================================================
;; PostgreSQL-Specific Utilities
;; =============================================================================

(defn create-database-url
      "Create PostgreSQL JDBC URL from components.

       Args:
         host: Database hostname
         port: Database port
         database: Database name
         options: Optional map of connection parameters

       Returns:
         JDBC URL string

       Example:
         (create-database-url \"localhost\" 5432 \"mydb\" {:ssl true})"
  [host port database & [options]]
  (let [base-url  (str "jdbc:postgresql://" host ":" port "/" database)
        params    (merge {:stringtype "unspecified"} options)
        param-str (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))]
    (if (seq params)
      (str base-url "?" param-str)
      base-url)))

;; =============================================================================
;; DDL Helpers for PostgreSQL
;; =============================================================================

(defn boolean-column-type
      "Get PostgreSQL boolean column type definition.

       Returns:
         String - 'BOOLEAN' for PostgreSQL"
  []
  "BOOLEAN")

(defn uuid-column-type
      "Get PostgreSQL UUID column type definition.

       PostgreSQL has native UUID support.

       Returns:
         String - 'UUID' for PostgreSQL (requires uuid extension)"
  []
  "UUID")

(defn varchar-uuid-column-type
      "Get PostgreSQL varchar UUID column type definition for compatibility.

       Use this when you don't want to enable the UUID extension.

       Returns:
         String - 'VARCHAR(36)' for UUID storage"
  []
  "VARCHAR(36)")

(defn timestamp-column-type
      "Get PostgreSQL timestamp column type definition.

       Returns:
         String - 'TIMESTAMP WITH TIME ZONE' for PostgreSQL"
  []
  "TIMESTAMP WITH TIME ZONE")

(defn serial-primary-key
      "Get PostgreSQL serial primary key definition.

       Returns:
         String - PostgreSQL serial syntax"
  []
  "SERIAL PRIMARY KEY")

(defn bigserial-primary-key
      "Get PostgreSQL bigserial primary key definition.

       Returns:
         String - PostgreSQL bigserial syntax"
  []
  "BIGSERIAL PRIMARY KEY")

;; =============================================================================
;; PostgreSQL-Specific Query Optimizations
;; =============================================================================

(defn explain-query
      "Get PostgreSQL query execution plan.

       Args:
         datasource: PostgreSQL datasource
         query-map: HoneySQL query map
         options: Optional analysis options (:analyze, :buffers, :verbose, :costs)

       Returns:
         Vector of execution plan rows

       Example:
         (explain-query ds {:select [:*] :from [:users]} {:analyze true})"
  ([datasource query-map]
   (explain-query datasource query-map {}))
  ([datasource query-map options]
   (let [sql-query    (sql/format query-map {:dialect :postgresql})
         explain-opts (str/join ", "
                                (for [[k v] options :when v]
                                  (str/upper-case (name k))))
         explain-sql  (str "EXPLAIN "
                            (when (seq explain-opts) (str "(" explain-opts ") "))
                            (first sql-query))
         params       (rest sql-query)]
     (jdbc/execute! datasource (cons explain-sql params)
                    {:builder-fn rs/as-unqualified-lower-maps}))))

(defn analyze-table
      "Update PostgreSQL table statistics for better query planning.

       Args:
         datasource: PostgreSQL datasource
         table-name: Table name to analyze (optional, analyzes all if nil)

       Example:
         (analyze-table ds :users)
         (analyze-table ds) ; Analyze all tables"
  ([datasource]
   (jdbc/execute! datasource ["ANALYZE"])
   (log/debug "Analyzed all PostgreSQL tables"))
  ([datasource table-name]
   (let [table-str (name table-name)]
     (jdbc/execute! datasource [(str "ANALYZE " table-str)])
     (log/debug "Analyzed PostgreSQL table" {:table table-str}))))

(defn vacuum-table
      "Vacuum PostgreSQL table to reclaim space and update statistics.

       Args:
         datasource: PostgreSQL datasource
         table-name: Table name to vacuum
         options: Optional vacuum options (:full, :analyze, :verbose)

       Example:
         (vacuum-table ds :users {:analyze true})"
  ([datasource table-name]
   (vacuum-table datasource table-name {}))
  ([datasource table-name options]
   (let [table-str   (name table-name)
         vacuum-opts (str/join ", "
                               (for [[k v] options :when v]
                                 (str/upper-case (name k))))
         vacuum-sql  (str "VACUUM "
                           (when (seq vacuum-opts) (str "(" vacuum-opts ") "))
                           table-str)]
     (jdbc/execute! datasource [vacuum-sql])
     (log/debug "Vacuumed PostgreSQL table" {:table table-str :options options}))))

;; =============================================================================
;; PostgreSQL Extensions and Features
;; =============================================================================

(defn enable-extension
      "Enable PostgreSQL extension.

       Args:
         datasource: PostgreSQL datasource
         extension-name: Name of extension to enable

       Example:
         (enable-extension ds \"uuid-ossp\")
         (enable-extension ds \"pg_stat_statements\")"
  [datasource extension-name]
  (let [sql (str "CREATE EXTENSION IF NOT EXISTS \"" extension-name "\"")]
    (jdbc/execute! datasource [sql])
    (log/info "Enabled PostgreSQL extension" {:extension extension-name})))

(defn list-extensions
      "List installed PostgreSQL extensions.

       Args:
         datasource: PostgreSQL datasource

       Returns:
         Vector of extension information

       Example:
         (list-extensions ds)"
  [datasource]
  (let [results (jdbc/execute! datasource
                               ["SELECT extname, extversion FROM pg_extension"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:name    (:extname row)
             :version (:extversion row)})
          results)))

;; =============================================================================
;; Connection and Performance Utilities
;; =============================================================================

(defn connection-info
      "Get PostgreSQL connection and server information.

       Args:
         datasource: PostgreSQL datasource

       Returns:
         Map with connection details

       Example:
         (connection-info ds)"
  [datasource]
  (let [version-result (jdbc/execute-one! datasource ["SELECT version()"]
                                          {:builder-fn rs/as-unqualified-lower-maps})
        stats-result   (jdbc/execute-one! datasource
                                          ["SELECT current_database(), current_user, inet_server_addr(), inet_server_port()"]
                                          {:builder-fn rs/as-unqualified-lower-maps})]
    {:version     (:version version-result)
     :database    (:current_database stats-result)
     :user        (:current_user stats-result)
     :server-addr (:inet_server_addr stats-result)
     :server-port (:inet_server_port stats-result)}))

(defn active-connections
      "Get information about active PostgreSQL connections.

       Args:
         datasource: PostgreSQL datasource

       Returns:
         Vector of connection information

       Example:
         (active-connections ds)"
  [datasource]
  (let [results (jdbc/execute! datasource
                               ["SELECT pid, usename, application_name, client_addr, state, query_start FROM pg_stat_activity WHERE state = 'active'"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:pid              (:pid row)
             :username         (:usename row)
             :application-name (:application_name row)
             :client-addr      (:client_addr row)
             :state            (:state row)
             :query-start      (:query_start row)})
          results)))
