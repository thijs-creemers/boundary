(ns boundary.shell.adapters.database.common.utils
  "Common database utilities and information functions."
  (:require [boundary.core.database.query :as core-query]
            [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shell.adapters.database.common.execution :as execution])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Database Information
;; =============================================================================

(defn database-info
  "Get database information and statistics.

   Args:
     ctx: Database context

   Returns:
     Map with database information

   Example:
     (database-info ctx)"
  [ctx]
  (execution/validate-context ctx)
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)]
    {:adapter dialect
     :dialect dialect
     :pool-info (when (instance? HikariDataSource (:datasource ctx))
                  (let [ds ^HikariDataSource (:datasource ctx)]
                    {:pool-name (.getPoolName ds)
                     :maximum-pool-size (.getMaximumPoolSize ds)
                     :minimum-idle (.getMinimumIdle ds)
                     :connection-timeout (.getConnectionTimeout ds)
                     :is-closed (.isClosed ds)}))}))

(defn list-tables
  "List all tables in the database.

   Args:
     ctx: Database context

   Returns:
     Vector of table name strings

   Example:
     (list-tables ctx)"
  [ctx]
  (execution/validate-context ctx)
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)
        query-map (case dialect
                    :sqlite {:select [:name]  ; SQLite uses sqlite_master
                             :from [:sqlite_master]
                             :where [:= :type "table"]}
                    :h2 {:select [:table_name]  ; H2 uses information_schema
                         :from [:information_schema.tables]
                         :where [:= :table_schema "PUBLIC"]}
                    :mysql {:select [:table_name]
                            :from [:information_schema.tables]
                            :where [:= :table_schema [:database]]}
                    ;; Default case for other dialects (PostgreSQL uses nil)
                    {:select [:table_name]
                     :from [:information_schema.tables]
                     :where [:= :table_schema "public"]})
        results (execution/execute-query! ctx query-map)]
    (mapv (fn [row]
            (or (:name row)
                (:table_name row)
                (:table-name row)))
          results)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn format-sql
  "Format HoneySQL query map using context's adapter dialect.
   
   Delegates to pure core function.

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Vector of [sql & params]

   Example:
     (format-sql ctx {:select [:*] :from [:users]})"
  [ctx query-map]
  (execution/validate-adapter ctx)
  (let [adapter-dialect (protocols/dialect (:adapter ctx))]
    (core-query/format-sql adapter-dialect query-map)))

(defn format-sql*
  "Format HoneySQL query map with custom options.
   
   Delegates to pure core function.

   Args:
     ctx: Database context
     query-map: HoneySQL query map
     opts: Additional formatting options

   Returns:
     Vector of [sql & params]"
  [ctx query-map opts]
  (execution/validate-adapter ctx)
  (let [adapter-dialect (protocols/dialect (:adapter ctx))]
    (core-query/format-sql-with-opts adapter-dialect query-map opts)))

(defn build-where-clause
  "Build dynamic WHERE clause from filter map with type and case conversion.
   
   Delegates to core query builder for database-agnostic WHERE clause construction.
   Handles kebab-case to snake_case conversion, type conversions, and adapter-specific
   boolean conversions automatically.

   Args:
     ctx: Database context with :adapter for database-specific conversions
     filters: Map of kebab-case field keywords -> value filters

   Returns:
     HoneySQL WHERE clause with snake_case fields or nil

   Example:
     (build-where-clause ctx {:name \"John\" :active true :role [:admin :user]})
     (build-where-clause ctx {:tenant-id #uuid\"...\" :deleted-at nil})"
  [ctx filters]
  (execution/validate-adapter ctx)
  (let [adapter (:adapter ctx)
        ;; Convert booleans to adapter-specific format (SQLite: 0/1, Postgres: true/false)
        convert-booleans (fn [m]
                          (reduce-kv (fn [acc k v]
                                       (assoc acc k
                                              (cond
                                                (boolean? v) (protocols/boolean->db adapter v)
                                                (and (sequential? v) (every? boolean? v))
                                                (mapv #(protocols/boolean->db adapter %) v)
                                                :else v)))
                                     {}
                                     m))
        filters-with-db-booleans (convert-booleans filters)]
    (core-query/build-where-filters filters-with-db-booleans)))

(defn build-pagination
  "Build LIMIT/OFFSET clause from pagination options with safe defaults.
   
   Delegates to pure core function.

   Args:
     options: Map with :limit and :offset keys

   Returns:
     Map with sanitized :limit and :offset values

   Example:
     (build-pagination {:limit 50 :offset 100})"
  [options]
  (core-query/build-pagination options))

(defn build-ordering
  "Build ORDER BY clause from sort options.
   
   Delegates to pure core function.

   Args:
     options: Map with :sort-by and :sort-direction keys
     default-field: Default field to sort by (keyword)

   Returns:
     Vector of [field direction] pairs

   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)"
  [options default-field]
  (core-query/build-ordering options default-field))
