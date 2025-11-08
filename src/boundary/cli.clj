(ns boundary.cli
  "Main CLI entry point for Boundary framework.
   
   Provides command-line interface for all modules.
   Currently supports user and session management."
  (:require [boundary.config :as config]
            [boundary.shell.adapters.database.config :as db-config]
            [boundary.user.shell.cli :as user-cli]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.shell.adapters.database.factory :as db-factory]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  "CLI main entry point.
   
   Loads system configuration, initializes services, and dispatches CLI commands."
  [& args]
  (let [exit-status (atom 1)]
    (try
      (log/info "Starting Boundary CLI" {:args args})

      ;; Load configuration
      (let [config (config/load-config)
            
            ;; Get database config and convert to factory format
            sqlite-config (get-in config [:active :boundary/sqlite])
            db-config (db-config/config->db-config :boundary/sqlite sqlite-config)
            db-ctx (db-factory/db-context db-config)]
        
        (try
          ;; Initialize database schema
          (user-persistence/initialize-user-schema! db-ctx)
          
          ;; Create repositories
          (let [user-repo (user-persistence/create-user-repository db-ctx)
                session-repo (user-persistence/create-session-repository db-ctx)
                
                ;; Create user service
                user-service (user-service/create-user-service user-repo session-repo)
                
                ;; Dispatch CLI commands and capture exit status
                status (user-cli/run-cli! user-service args)]
            
            (reset! exit-status status))
          
          (finally
            ;; Always close database connections
            (when-let [datasource (:datasource db-ctx)]
              (try
                (.close datasource)
                (catch Exception e
                  (log/warn "Failed to close database connection" {:error (.getMessage e)})))))))

      (catch Exception e
        (log/error "CLI execution failed" {:error (.getMessage e)})
        (binding [*out* *err*]
          (println "Fatal error:" (.getMessage e)))
        (reset! exit-status 1))
      
      (finally
        (System/exit @exit-status)))))
