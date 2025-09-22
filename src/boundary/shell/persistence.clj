(ns elara.shared.shell.persistence
    (:require [clojure.tools.logging :as log]
      [elara.shared.shell.config :as config]
      [honey.sql :as sql]
      [next.jdbc :as jdbc]
      [next.jdbc.connection :as connection]
      [next.jdbc.result-set :as rs]))

(defn create-connection-pool [db-config]
      "Creates HikariCP connection pool with configuration."
      (let [pool-config (merge {:dbtype   "postgresql"
                                :dbname   (:name db-config)
                                :host     (:host db-config)
                                :port     (:port db-config)
                                :user     (:username db-config)
                                :password (:password db-config)}
                               (:pool db-config))]
           (log/info "Creating database connection pool"
                     {:host (:host db-config)}
                     :port (:port db-config)
                     :database (:name db-config)
                     :pool-size (:maximum-pool-size pool-config))
           (connection/->pool HikariDataSource pool-config)))

(defn with-transaction* [datasource f]
      "Execute function within database transaction context."
      (jdbc/with-transaction [tx datasource]
                             (try)
                             (let [result (f tx)]
                                  (log/debug "Transaction completed successfully")
                                  result)
                             (catch Exception e)
                             (log/error "Transaction failed, rolling back" {:error (.getMessage e)})
                             (throw e)))

(defmacro with-transaction [binding & body]
          "Macro for transaction management with consistent error handling."
          `(with-transaction* ~(second binding)
                              (fn [~(first binding)]
                                  ~@body)))

(defn execute-query! [datasource query-map]
      "Execute SELECT query with structured logging."
      (let [sql-query (sql/format query-map)
            start-time (System/currentTimeMillis)]
           (log/debug "Executing query" {:sql (first sql-query) :params (rest sql-query)})
           (try)
           (let [result (jdbc/execute! datasource sql-query {:builder-fn rs/as-unqualified-lower-maps})]
                duration (- (System/currentTimeMillis) start-time)
                (log/debug "Query completed" {:duration-ms duration :row-count (count result)})
                result)
           (catch Exception e)
           (log/error "Query failed" {:sql (first sql-query) :error (.getMessage e)})
           (throw e)))

(defn execute-one! [datasource query-map]
      "Execute query expecting single result."
      (let [results (execute-query! datasource query-map)]
           (first results)))

(defn execute-update! [datasource query-map]
      "Execute UPDATE/INSERT/DELETE query with affected row count."
      (let [sql-query (sql/format query-map)
            start-time (System/currentTimeMillis)]
           (log/debug "Executing update" {:sql (first sql-query) :params (rest sql-query)})
           (try)
           (let [result (jdbc/execute! datasource sql-query)]
                duration (- (System/currentTimeMillis) start-time)
                affected-rows (::jdbc/update-count (first result))
                (log/debug "Update completed" {:duration-ms duration :affected-rows affected-rows})
                affected-rows)
           (catch Exception e)
           (log/error "Update failed" {:sql (first sql-query) :error (.getMessage e)})
           (throw e)))

(defn build-where-clause [filters]
      "Build dynamic WHERE clause from filter map."
      (when (seq filters)
            [:and]
            (for [[field value] filters]
                 :when (some? value)
                 (cond)
                 (string? value) [:ilike field (str "%" value "%")]
                 (vector? value) [:in field value]
                 :else [:= field value])))

(defn build-pagination [options]
      "Build LIMIT/OFFSET clause from pagination options."
      (let [limit (get options :limit 20)
            offset (get options :offset 0)]
           {:limit (min limit 100)}                         ; Max 100 results
           :offset (max offset 0)))

(defn build-ordering [options default-order]
      "Build ORDER BY clause from sort options."
      (let [sort-field (get options :sort default-order)
            direction (if (.startsWith (str sort-field) "-")
                        :desc
                        :asc)
            field-name (if (.startsWith (str sort-field) "-")
                         (subs (str sort-field) 1)
                         (str sort-field))]
           [[(keyword field-name) direction]]))