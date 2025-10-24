(ns boundary.cli
  "Main CLI entry point for Boundary framework.
   
   Provides command-line interface for all modules.
   Currently supports user and session management."
  (:require [boundary.config :as config]
            [boundary.user.shell.cli :as user-cli]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.shell.adapters.database.factory :as db-factory]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:gen-class))

(defn -main
  "CLI main entry point.
   
   Loads system configuration, initializes services, and dispatches CLI commands."
  [& args]
  (try
    (log/info "Starting Boundary CLI" {:args args})

    ;; Load configuration
    (let [config (config/load-config)
          
          ;; Initialize database context
          db-config (get-in config [:active :boundary/sqlite])
          db-ctx (db-factory/db-context db-config)
          
          ;; Initialize database schema
          _ (user-persistence/initialize-user-schema! db-ctx)
          
          ;; Create repositories
          user-repo (user-persistence/create-user-repository db-ctx)
          session-repo (user-persistence/create-session-repository db-ctx)
          
          ;; Create user service
          user-service (user-service/create-user-service user-repo session-repo)]

      ;; Dispatch CLI commands
      (user-cli/run-cli user-service args)

      ;; Close database connections
      (.close (:datasource db-ctx)))

    (catch Exception e
      (log/error "CLI execution failed" {:error (.getMessage e)})
      (println "Error:" (.getMessage e))
      (System/exit 1))))