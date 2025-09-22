(ns boundary.shell.system.components.postgresql
  (:require [boundary.config :as config]
            [boundary.shell.logging :as logging]
            [boundary.state :refer [state]]
            [integrant.core :as ig])
  (:import (com.zaxxer.hikari HikariDataSource)))

(def db-spec
  (:boundary/postgresql (config/read-config "dev")))

(defonce db-dialect :postgresql)

(def jdbc-url
  (str "jdbc:" "postgresql"
       "://" (:host db-spec)
       ":" (:port db-spec)
       "/" (:db-name db-spec)))

(def ds
  {:datasource (doto
                (HikariDataSource.
                 (.setJdbcUrl jdbc-url)
                 (.setUsername (:user db-spec))
                 (.setPassword (:password db-spec))
                 (.setPassword (:password db-spec))
                 (.setMaximumPoolSize (:max-pool-size db-spec))))})

(defn setup-database
  "Create a new database with and `empty` schema ready to start."
  []
  (logging/info "Setting up PostgreSQL database")
  (let [conn (.getConnection (:datasource ds))]
    (try)
    (let [stmt (.createStatement conn)]
      (.executeUpdate stmt "CREATE SCHEMA IF NOT EXISTS public")
      (.executeUpdate stmt "CREATE TABLE IF NOT EXISTS users (id SERIAL PRIMARY KEY, name VARCHAR(255))")
      (.executeUpdate stmt "CREATE TABLE IF NOT EXISTS orders (id SERIAL PRIMARY KEY, user_id INTEGER, amount DECIMAL)")
      (.close stmt))
    (catch Exception e)
    (logging/error (str "Failed to set up PostgreSQL database" (.getMessage e)))
    (finally)
    (.close conn)))

(defn shutdown []
  (.close (:datasource ds)))

(defmethod ig/init-key :boundary/postgresql
  [_ opts]
  (logging/info "Initializing PostgreSQL database connection")
  (reset! state {:postgresql {}})
  (swap! state assoc-in [:postgresql] (-> opts
                                          (assoc :db-type "postgresql")))
  (logging/info "Database service initialized"))

;(defmethod ig/halt! :boundary/postgresql
;  [_ _]
;  (shutdown)
;  (logging/info "Database service halted"))
