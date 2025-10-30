(ns boundary.shell.adapters.database.common.execution
  "Query execution with I/O, logging, and error handling.
   
   This is part of the imperative shell - it performs database I/O,
   manages transactions, and handles side effects like logging."
  (:require [boundary.core.database.query :as core-query]
            [boundary.core.database.validation :as core-validation]
            [boundary.shell.adapters.database.protocols :as protocols]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Context Validation (Re-exported from core)
;; =============================================================================

(def db-context?
  "Check if value is a valid database context.
   
   Delegates to pure core function."
  core-validation/db-context?)

(def validate-context
  "Validate database context and throw if invalid.
   
   Delegates to pure core function."
  core-validation/validate-db-context)

(def validate-adapter
  "Validate that context has a valid adapter.
   
   Delegates to pure core function."
  core-validation/validate-adapter-context)

;; =============================================================================
;; Query Execution
;; =============================================================================

(defn execute-query!
  "Execute SELECT query and return results.
   
   SHELL FUNCTION: Performs database I/O and logging (side effects).
   Uses pure core functions for query formatting.

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
  
  ;; Pure: format query using core function
  (let [adapter-dialect (protocols/dialect (:adapter ctx))
        sql-query (core-query/format-sql adapter-dialect query-map)
        start-time (System/currentTimeMillis)]
    
    ;; Side effect: log query
    (log/debug "Executing query"
               {:adapter adapter-dialect
                :sql (first sql-query)
                :params (rest sql-query)})
    
    (try
      ;; Side effect: database I/O
      (let [result (jdbc/execute! (:datasource ctx) sql-query
                                  {:builder-fn rs/as-unqualified-lower-maps})
            duration (- (System/currentTimeMillis) start-time)]
        
        ;; Side effect: log result
        (log/debug "Query completed"
                   {:adapter adapter-dialect
                    :duration-ms duration
                    :row-count (count result)})
        result)
      
      (catch Exception e
        ;; Side effect: error logging
        (log/error "Query failed"
                   {:adapter adapter-dialect
                    :sql (first sql-query)
                    :error (.getMessage e)})
        (throw (ex-info "Database query failed"
                        {:adapter adapter-dialect
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
   
   SHELL FUNCTION: Performs database I/O and logging (side effects).

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Number of affected rows

   Example:
     (execute-update! ctx {:update :users :set {:active false} :where [:= :id \"123\"]})"
  [ctx query-map]
  (validate-context ctx)
  
  ;; Pure: format query using core function
  (let [adapter-dialect (protocols/dialect (:adapter ctx))
        sql-query (core-query/format-sql adapter-dialect query-map)
        start-time (System/currentTimeMillis)]
    
    ;; Side effect: log update
    (log/debug "Executing update"
               {:adapter adapter-dialect
                :sql (first sql-query)
                :params (rest sql-query)})
    
    (try
      ;; Side effect: database I/O
      (let [result (jdbc/execute! (:datasource ctx) sql-query)
            duration (- (System/currentTimeMillis) start-time)
            affected-rows (::jdbc/update-count (first result))]
        
        ;; Side effect: log result
        (log/debug "Update completed"
                   {:adapter adapter-dialect
                    :duration-ms duration
                    :affected-rows affected-rows})
        affected-rows)
      
      (catch Exception e
        ;; Side effect: error logging
        (log/error "Update failed"
                   {:adapter adapter-dialect
                    :sql (first sql-query)
                    :error (.getMessage e)})
        (throw (ex-info "Database update failed"
                        {:adapter adapter-dialect
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
