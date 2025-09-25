(ns boundary.shell.persistence
  "DEPRECATED: This namespace is deprecated in favor of the new multi-database system.
   
   Please use:
   - boundary.shell.adapters.database.core for database operations
   - boundary.shell.adapters.database.factory for datasource creation
   - boundary.shell.adapters.database.{sqlite,postgresql,mysql,h2} for specific adapters
   
   This namespace provides backward compatibility but will be removed in a future version.
   All functions delegate to the new core database system using PostgreSQL adapter as default."
  (:require [clojure.tools.logging :as log]
            [boundary.shell.adapters.database.core :as db]
            [boundary.shell.adapters.database.factory :as dbf]
            [boundary.shell.adapters.database.postgresql :as pg])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Backward Compatibility Functions - DEPRECATED
;; =============================================================================

(defn ^:deprecated create-connection-pool
  "DEPRECATED: Creates HikariCP connection pool with configuration.
   
   Use boundary.shell.adapters.database.factory/create-datasource instead.
   
   This function assumes PostgreSQL adapter for backward compatibility."
  [db-config]
  (log/warn "boundary.shell.persistence/create-connection-pool is deprecated. Use boundary.shell.adapters.database.factory/create-datasource instead.")
  (let [normalized-config (-> db-config
                             (assoc :adapter :postgresql)
                             (update :name #(or % (:dbname db-config)))
                             (update :username #(or % (:user db-config))))
        adapter (pg/new-adapter)]
    (db/create-connection-pool adapter normalized-config)))

(defn ^:deprecated with-transaction*
  "DEPRECATED: Execute function within database transaction context.
   
   Use boundary.shell.adapters.database.core/with-transaction* instead."
  [datasource f]
  (log/warn "boundary.shell.persistence/with-transaction* is deprecated. Use boundary.shell.adapters.database.core/with-transaction* instead.")
  (let [ctx {:datasource datasource :adapter (pg/new-adapter)}]
    (db/with-transaction* ctx f)))

(defmacro ^:deprecated with-transaction
  "DEPRECATED: Macro for transaction management with consistent error handling.
   
   Use boundary.shell.adapters.database.core/with-transaction instead."
  [binding & body]
  `(with-transaction* ~(second binding)
     (fn [~(first binding)]
       ~@body)))

(defn ^:deprecated execute-query!
  "DEPRECATED: Execute SELECT query with structured logging.
   
   Use boundary.shell.adapters.database.core/execute-query! instead."
  [datasource query-map]
  (log/warn "boundary.shell.persistence/execute-query! is deprecated. Use boundary.shell.adapters.database.core/execute-query! instead.")
  (let [ctx {:datasource datasource :adapter (pg/new-adapter)}]
    (db/execute-query! ctx query-map)))

(defn ^:deprecated execute-one!
  "DEPRECATED: Execute query expecting single result.
   
   Use boundary.shell.adapters.database.core/execute-one! instead."
  [datasource query-map]
  (log/warn "boundary.shell.persistence/execute-one! is deprecated. Use boundary.shell.adapters.database.core/execute-one! instead.")
  (let [ctx {:datasource datasource :adapter (pg/new-adapter)}]
    (db/execute-one! ctx query-map)))

(defn ^:deprecated execute-update!
  "DEPRECATED: Execute UPDATE/INSERT/DELETE query with affected row count.
   
   Use boundary.shell.adapters.database.core/execute-update! instead."
  [datasource query-map]
  (log/warn "boundary.shell.persistence/execute-update! is deprecated. Use boundary.shell.adapters.database.core/execute-update! instead.")
  (let [ctx {:datasource datasource :adapter (pg/new-adapter)}]
    (db/execute-update! ctx query-map)))

(defn ^:deprecated build-where-clause
  "DEPRECATED: Build dynamic WHERE clause from filter map.
   
   Use boundary.shell.adapters.database.core/build-where-clause instead."
  [filters]
  (log/warn "boundary.shell.persistence/build-where-clause is deprecated. Use boundary.shell.adapters.database.core/build-where-clause instead.")
  (let [ctx {:adapter (pg/new-adapter)}]
    (db/build-where-clause ctx filters)))

(defn ^:deprecated build-pagination
  "DEPRECATED: Build LIMIT/OFFSET clause from pagination options.
   
   Use boundary.shell.adapters.database.core/build-pagination instead."
  [options]
  (log/warn "boundary.shell.persistence/build-pagination is deprecated. Use boundary.shell.adapters.database.core/build-pagination instead.")
  (db/build-pagination options))

(defn ^:deprecated build-ordering
  "DEPRECATED: Build ORDER BY clause from sort options.
   
   Use boundary.shell.adapters.database.core/build-ordering instead."
  [options default-order]
  (log/warn "boundary.shell.persistence/build-ordering is deprecated. Use boundary.shell.adapters.database.core/build-ordering instead.")
  (db/build-ordering options default-order))

;; =============================================================================
;; Migration Helper Functions
;; =============================================================================

(defn migrate-to-new-system
  "Helper function to migrate from old persistence functions to new database system.
   
   Takes a database configuration and returns a database context that can be used
   with the new core database functions.
   
   Example:
     (def ctx (migrate-to-new-system {:adapter :postgresql
                                      :host 'localhost'
                                      :port 5432
                                      :name 'mydb'
                                      :username 'user'
                                      :password 'pass'}))
     
     (db/execute-query! ctx {:select [:*] :from [:users]})"
  [db-config]
  (dbf/db-context db-config))

(defn migration-example
  "Shows how to migrate from old to new database system.
   
   Returns a map with :old and :new showing equivalent operations."
  []
  {:old-system
   '{;; Old way - DEPRECATED
     :create-pool    (create-connection-pool db-config)
     :query          (execute-query! datasource query-map)
     :query-one      (execute-one! datasource query-map)
     :update         (execute-update! datasource query-map)
     :where-clause   (build-where-clause filters)
     :pagination     (build-pagination options)
     :ordering       (build-ordering options default-order)
     :transaction    (with-transaction [tx datasource] ...)}
   
   :new-system
   '{;; New way - RECOMMENDED
     :create-context (dbf/db-context db-config)
     :query          (db/execute-query! ctx query-map)
     :query-one      (db/execute-one! ctx query-map) 
     :update         (db/execute-update! ctx query-map)
     :where-clause   (db/build-where-clause ctx filters)
     :pagination     (db/build-pagination options)
     :ordering       (db/build-ordering options default-order)
     :transaction    (db/with-transaction [tx ctx] ...)}})
