(ns boundary.shell.adapters.database.common.execution
  "Common query execution utilities with logging and error handling."
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shell.adapters.database.common.query :as query]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Context Validation
;; =============================================================================

(defn db-context?
  "Check if value is a valid database context.

   Args:
     ctx: Value to check

   Returns:
     Boolean - true if valid database context"
  [ctx]
  (and (map? ctx)
       (:datasource ctx)
       (:adapter ctx)
       (satisfies? protocols/DBAdapter (:adapter ctx))))

(defn validate-context
  "Validate database context and throw if invalid.

   Args:
     ctx: Database context to validate

   Returns:
     ctx if valid

   Throws:
     IllegalArgumentException if invalid"
  [ctx]
  (if (db-context? ctx)
    ctx
    (throw (IllegalArgumentException.
            (str "Invalid database context. Expected map with :datasource and :adapter keys. Got: "
                 (type ctx))))))

(defn validate-adapter
  "Validate that context has a valid adapter (used for DDL generation).

   Args:
     ctx: Database context to validate

   Returns:
     ctx if valid

   Throws:
     IllegalArgumentException if invalid"
  [ctx]
  (if (and (map? ctx)
           (:adapter ctx)
           (satisfies? protocols/DBAdapter (:adapter ctx)))
    ctx
    (throw (IllegalArgumentException.
            (str "Invalid adapter context. Expected map with :adapter key implementing DBAdapter protocol. Got: "
                 (type ctx))))))

;; =============================================================================
;; Query Execution
;; =============================================================================

(defn execute-query!
  "Execute SELECT query and return results.

   Args:
     ctx: Database context {:datasource ds :adapter adapter}
     query-map: HoneySQL query map

   Returns:
     Vector of result maps

   Example:
     (execute-query! ctx {:select [:*] :from [:users] :where [:= :active true]})"
  [ctx query-map]
  (validate-context ctx)
  ;; Validate query-map before formatting to avoid wasting resources
  (when (or (nil? query-map) (not (map? query-map)) (empty? query-map))
    (throw (IllegalArgumentException. "Invalid or empty query map provided")))
  (let [sql-query (query/format-sql (:adapter ctx) query-map)
        start-time (System/currentTimeMillis)]
      (log/debug "Executing query"
                 {:adapter (protocols/dialect (:adapter ctx))
                  :sql (first sql-query)
                  :params (rest sql-query)})
      (try
        (let [result (jdbc/execute! (:datasource ctx) sql-query
                                    {:builder-fn rs/as-unqualified-lower-maps})
              duration (- (System/currentTimeMillis) start-time)]
          (log/debug "Query completed"
                     {:adapter (protocols/dialect (:adapter ctx))
                      :duration-ms duration
                      :row-count (count result)})
          result)
        (catch Exception e
          (log/error "Query failed"
                     {:adapter (protocols/dialect (:adapter ctx))
                      :sql (first sql-query)
                      :error (.getMessage e)})
          (throw (ex-info "Database query failed"
                          {:adapter (protocols/dialect (:adapter ctx))
                           :sql (first sql-query)
                           :params (rest sql-query)
                           :original-error (.getMessage e)}
                          e))))))

(defn execute-one!
  "Execute query expecting single result.

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Single result map or nil

   Example:
     (execute-one! ctx {:select [:*] :from [:users] :where [:= :id \"123\"]})"
  [ctx query-map]
  (first (execute-query! ctx query-map)))

(defn execute-update!
  "Execute UPDATE/INSERT/DELETE query.

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Number of affected rows

   Example:
     (execute-update! ctx {:update :users :set {:active false} :where [:= :id \"123\"]})"
  [ctx query-map]
  (validate-context ctx)
  (let [sql-query (query/format-sql (:adapter ctx) query-map)
        start-time (System/currentTimeMillis)]
    (log/debug "Executing update"
               {:adapter (protocols/dialect (:adapter ctx))
                :sql (first sql-query)
                :params (rest sql-query)})
    (try
      (let [result (jdbc/execute! (:datasource ctx) sql-query)
            duration (- (System/currentTimeMillis) start-time)
            affected-rows (::jdbc/update-count (first result))]
        (log/debug "Update completed"
                   {:adapter (protocols/dialect (:adapter ctx))
                    :duration-ms duration
                    :affected-rows affected-rows})
        affected-rows)
      (catch Exception e
        (log/error "Update failed"
                   {:adapter (protocols/dialect (:adapter ctx))
                    :sql (first sql-query)
                    :error (.getMessage e)})
        (throw (ex-info "Database update failed"
                        {:adapter (protocols/dialect (:adapter ctx))
                         :sql (first sql-query)
                         :params (rest sql-query)
                         :original-error (.getMessage e)}
                        e))))))

(defn execute-batch!
  "Execute multiple queries in a single transaction.

   Args:
     ctx: Database context
     query-maps: Vector of HoneySQL query maps

   Returns:
     Vector of results (for SELECTs) or affected row counts (for updates)

   Example:
     (execute-batch! ctx [{:insert-into :users :values [{:name \"John\"}]}
                         {:insert-into :users :values [{:name \"Jane\"}]}])"
  [ctx query-maps]
  (validate-context ctx)
  (log/debug "Executing batch operation"
             {:adapter (protocols/dialect (:adapter ctx))
              :query-count (count query-maps)})
  (jdbc/with-transaction [tx-conn (:datasource ctx)]
    (let [tx-ctx (assoc ctx :datasource tx-conn)
          start-time (System/currentTimeMillis)
          results (mapv (fn [query-map]
                          (if (contains? query-map :select)
                            (execute-query! tx-ctx query-map)
                            (execute-update! tx-ctx query-map)))
                        query-maps)
          duration (- (System/currentTimeMillis) start-time)]
      (log/info "Batch operation completed"
                {:adapter (protocols/dialect (:adapter ctx))
                 :query-count (count query-maps)
                 :duration-ms duration})
      results)))

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defn with-transaction*
  "Execute function within database transaction context.

   Args:
     ctx: Database context
     f: Function that takes transaction context and returns result

   Returns:
     Result of function execution

   Example:
     (with-transaction* ctx (fn [tx] (execute-update! tx query)))"
  [ctx f]
  (validate-context ctx)
  (jdbc/with-transaction [tx-conn (:datasource ctx)]
    (try
      (let [tx-ctx (assoc ctx :datasource tx-conn)
            result (f tx-ctx)]
        (log/debug "Transaction completed successfully"
                   {:adapter (protocols/dialect (:adapter ctx))})
        result)
      (catch Exception e
        (log/error "Transaction failed, rolling back"
                   {:adapter (protocols/dialect (:adapter ctx))
                    :error (.getMessage e)})
        (throw e)))))
