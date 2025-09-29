(ns boundary.shell.adapters.database.h2
  "H2 database adapter implementing the DBAdapter protocol.
   
   This namespace provides H2-specific functionality for both file-based and
   in-memory databases. H2 is excellent for development, testing, and embedded
   applications due to its lightweight nature and PostgreSQL compatibility mode.
   
   Key Features:
   - PostgreSQL compatibility mode for easier migration
   - In-memory databases for fast testing
   - File-based databases for development
   - Standard SQL features with good performance
   - Native boolean support (no conversion needed)
   
   H2-Specific Optimizations:
   - PostgreSQL compatibility mode by default
   - Proper timezone handling
   - Case-insensitive identifiers for compatibility
   - Optimized connection pool settings for embedded usage"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

;; =============================================================================
;; H2 Adapter Implementation
;; =============================================================================

(defrecord H2Adapter []
  protocols/DBAdapter

  (dialect [_]
    :ansi)  ; H2 uses HoneySQL's ANSI SQL dialect

  (jdbc-driver [_]
    "org.h2.Driver")

  (jdbc-url [_ db-config]
    (let [database-path (:database-path db-config)]
      (cond
        (= database-path "mem:testdb") "jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        (str/starts-with? database-path "mem:") (str "jdbc:h2:" database-path ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
        :else (str "jdbc:h2:" database-path ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"))))

  (pool-defaults [_]
    {:minimum-idle          1
     :maximum-pool-size     10
     :connection-timeout-ms 30000
     :idle-timeout-ms       600000
     :max-lifetime-ms       1800000})

  (init-connection! [_ datasource _db-config]
    (log/debug "Initializing H2 connection settings")
    (try
      ;; Set timezone to UTC for consistency
      (jdbc/execute! datasource ["SET TIME ZONE '+00:00'"])

      ;; Enable referential integrity (foreign keys)
      (jdbc/execute! datasource ["SET REFERENTIAL_INTEGRITY TRUE"])

      (log/debug "H2 connection initialized successfully")
      (catch Exception e
        (log/warn "Failed to initialize some H2 settings" {:error (.getMessage e)}))))

  (build-where [_ filters]
    (when (seq filters)
      (let [conditions (for [[field value] filters
                             :when (some? value)]
                         (cond
                           (string? value) [:like field (str "%" value "%")]
                           (vector? value) [:in field value]
                           (boolean? value) [:= field value] ; H2 supports native booleans
                           :else [:= field value]))]
        (when (seq conditions)
          (if (= 1 (count conditions))
            (first conditions)
            (cons :and conditions))))))

  (boolean->db [_ boolean-value]
    ;; H2 supports native boolean values
    boolean-value)

  (db->boolean [_ db-value]
    ;; H2 returns native boolean values
    db-value)

  (table-exists? [_ datasource table-name]
    (let [table-str (str/lower-case (name table-name))  ; Use lowercase for PostgreSQL compatibility
          query     {:select [:%count.*]
                     :from   [:information_schema.tables]
                     :where  [:and
                              [:= :table_schema "public"]  ; Use lowercase schema
                              [:= :table_name table-str]]}
          result    (first (jdbc/execute! datasource
                                          (sql/format query {:dialect :ansi})
                                          {:builder-fn rs/as-unqualified-lower-maps}))]
      (> (or (:count result) (get result :?column?) 0) 0)))

  (get-table-info [_ datasource table-name]
    (let [table-str     (str/lower-case (name table-name))  ; Use lowercase for PostgreSQL compatibility
          ;; Get column information
          columns-query {:select   [:column_name :data_type :is_nullable :column_default]
                         :from     [:information_schema.columns]
                         :where    [:and
                                    [:= :table_schema "public"]  ; Use lowercase schema
                                    [:= :table_name table-str]]
                         :order-by [:ordinal_position]}
          ;; Get primary key information
          pk-query      {:select [:kcu.column_name]
                         :from   [[:information_schema.table_constraints :tc]]
                         :join   [[:information_schema.key_column_usage :kcu]
                                  [:and
                                   [:= :tc.constraint_name :kcu.constraint_name]
                                   [:= :tc.table_schema :kcu.table_schema]]]
                         :where  [:and
                                  [:= :tc.table_schema "public"]  ; Use lowercase schema
                                  [:= :tc.table_name table-str]
                                  [:= :tc.constraint_type "PRIMARY KEY"]]}

          columns       (jdbc/execute! datasource
                                       (sql/format columns-query {:dialect :ansi})
                                       {:builder-fn rs/as-unqualified-lower-maps})
          pk-columns    (set (map :column_name
                                  (jdbc/execute! datasource
                                                 (sql/format pk-query {:dialect :ansi})
                                                 {:builder-fn rs/as-unqualified-lower-maps})))]

      (mapv (fn [col]
              {:name        (str/lower-case (:column_name col))
               :type        (:data_type col)
               :not-null    (= "NO" (:is_nullable col))
               :default     (:column_default col)
               :primary-key (contains? pk-columns (:column_name col))})
            columns))))

(defn new-adapter
      "Create new H2 adapter instance.

       Returns:
         H2 adapter implementing DBAdapter protocol"
  []
  (->H2Adapter))

;; =============================================================================
;; H2-Specific Utilities
;; =============================================================================

(defn in-memory-url
      "Create H2 in-memory database URL.

       Args:
         db-name: Database name (optional, defaults to 'testdb')

       Returns:
         JDBC URL for H2 in-memory database

       Example:
         (in-memory-url) ;; => \"jdbc:h2:mem:testdb;...\""
  ([]
   (in-memory-url "testdb"))
  ([db-name]
   (str "jdbc:h2:mem:" db-name ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")))

(defn file-url
      "Create H2 file-based database URL.

       Args:
         file-path: Path to database file (without .mv.db extension)

       Returns:
         JDBC URL for H2 file database

       Example:
         (file-url \"./data/app\") ;; => \"jdbc:h2:./data/app;...\""
  [file-path]
  (str "jdbc:h2:" file-path ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"))

(defn create-test-context
      "Create H2 in-memory database context for testing.

       Args:
         db-name: Optional database name (defaults to random UUID)

       Returns:
         Database context ready for testing

       Example:
         (def test-ctx (create-test-context))
         (core/execute-query! test-ctx {:select [1]})"
  ([]
   (create-test-context (str "test_" (UUID/randomUUID))))
  ([db-name]
   (let [adapter   (new-adapter)
         db-config {:adapter       :h2
                    :database-path (str "mem:" db-name)}]
     {:adapter    adapter
      :datasource ((requiring-resolve 'boundary.shell.adapters.database.core/create-connection-pool)
                   adapter db-config)})))

;; =============================================================================
;; DDL Helpers for H2
;; =============================================================================

(defn boolean-column-type
      "Get H2 boolean column type definition.

       Returns:
         String - 'BOOLEAN' for H2"
  []
  "BOOLEAN")

(defn uuid-column-type
      "Get H2 UUID column type definition.

       H2 has native UUID support, but we use VARCHAR for compatibility.

       Returns:
         String - 'VARCHAR(36)' for UUID storage"
  []
  "VARCHAR(36)")

(defn timestamp-column-type
      "Get H2 timestamp column type definition.

       Returns:
         String - 'TIMESTAMP' for H2"
  []
  "TIMESTAMP")

(defn auto-increment-primary-key
      "Get H2 auto-increment primary key definition.

       Returns:
         String - H2 auto-increment syntax"
  []
  "BIGINT AUTO_INCREMENT PRIMARY KEY")

;; =============================================================================
;; H2-Specific Query Optimizations
;; =============================================================================

(defn explain-query
      "Get H2 query execution plan.

       Args:
         datasource: H2 datasource
         query-map: HoneySQL query map

       Returns:
         Vector of execution plan rows

       Example:
         (explain-query ds {:select [:*] :from [:users]})"
  [datasource query-map]
  (let [sql-query   (sql/format query-map {:dialect :h2})
        explain-sql (str "EXPLAIN " (first sql-query))
        params      (rest sql-query)]
    (jdbc/execute! datasource (cons explain-sql params)
                   {:builder-fn rs/as-unqualified-lower-maps})))

(defn analyze-table
      "Update H2 table statistics for better query planning.

       Args:
         datasource: H2 datasource
         table-name: Table name to analyze

       Example:
         (analyze-table ds :users)"
  [datasource table-name]
  (let [table-str (name table-name)]
    (jdbc/execute! datasource [(str "ANALYZE TABLE " table-str)])
    (log/debug "Analyzed H2 table" {:table table-str})))

;; =============================================================================
;; Development and Testing Utilities
;; =============================================================================

(defn show-tables
      "List all tables in H2 database.

       Args:
         datasource: H2 datasource

       Returns:
         Vector of table names

       Example:
         (show-tables ds) ;; => [\"users\" \"sessions\"]"
  [datasource]
  (let [results (jdbc/execute! datasource
                               ["SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv #(str/lower-case (:table_name %)) results)))

(defn show-indexes
      "List all indexes in H2 database.

       Args:
         datasource: H2 datasource
         table-name: Optional table name to filter indexes

       Returns:
         Vector of index information

       Example:
         (show-indexes ds) ;; => [{:index-name \"..\" :table-name \"..\" ...}]"
  ([datasource]
   (show-indexes datasource nil))
  ([datasource table-name]
   (let [base-query "SELECT INDEX_NAME, TABLE_NAME, NON_UNIQUE, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'"
         query      (if table-name
                      (str base-query " AND TABLE_NAME = ?")
                      base-query)
         params     (if table-name [(str/upper-case (name table-name))] [])
         results    (jdbc/execute! datasource (cons query params)
                                   {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:index-name  (str/lower-case (:index_name row))
              :table-name  (str/lower-case (:table_name row))
              :unique      (not (:non_unique row))
              :column-name (str/lower-case (:column_name row))})
           results))))
