(ns boundary.shell.adapters.database.common.utils
  "Common database utilities and information functions."
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shell.adapters.database.common.execution :as execution]
            [boundary.shell.adapters.database.common.query :as query])
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

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Vector of [sql & params]

   Example:
     (format-sql ctx {:select [:*] :from [:users]})"
  [ctx query-map]
  (execution/validate-adapter ctx)
  (query/format-sql (:adapter ctx) query-map))

(defn format-sql*
  "Format HoneySQL query map with custom options.

   Args:
     ctx: Database context
     query-map: HoneySQL query map
     opts: Additional formatting options

   Returns:
     Vector of [sql & params]"
  [ctx query-map opts]
  (execution/validate-adapter ctx)
  (query/format-sql* (:adapter ctx) query-map opts))

(defn build-where-clause
  "Build dynamic WHERE clause from filter map using adapter-specific logic.

   Args:
     ctx: Database context
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause or nil

   Example:
     (build-where-clause ctx {:name \"John\" :active true :role [:admin :user]})"
  [ctx filters]
  (execution/validate-adapter ctx)
  (query/build-where-clause (:adapter ctx) filters))

(defn build-pagination
  "Build LIMIT/OFFSET clause from pagination options with safe defaults.

   Args:
     options: Map with :limit and :offset keys

   Returns:
     Map with sanitized :limit and :offset values

   Example:
     (build-pagination {:limit 50 :offset 100})"
  [options]
  (query/build-pagination options))

(defn build-ordering
  "Build ORDER BY clause from sort options.

   Args:
     options: Map with :sort-by and :sort-direction keys
     default-field: Default field to sort by (keyword)

   Returns:
     Vector of [field direction] pairs

   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)"
  [options default-field]
  (query/build-ordering options default-field))
