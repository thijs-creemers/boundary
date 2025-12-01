(ns boundary.user.shell.cli-entry
  "User module CLI entrypoint wrapper.

  Encapsulates user-specific CLI startup so that the top-level CLI can
  remain as module-agnostic as possible and delegate into this module."
  (:require [boundary.config :as config]
            [boundary.shell.adapters.database.config :as db-config]
            [boundary.user.shell.cli :as user-cli]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.user.shell.auth :as user-auth]
            [boundary.shell.adapters.database.factory :as db-factory]
            [boundary.logging.shell.adapters.no-op :as no-op-logging]
            [boundary.metrics.shell.adapters.no-op :as no-op-metrics]
            [boundary.error-reporting.shell.adapters.no-op :as no-op-error-reporting]
            [clojure.tools.logging :as log]))

(defn run!
  "Run the user module CLI for the given command-line arguments.

  Returns an integer exit status. Does not call System/exit."
  [args]
  (let [exit-status (atom 1)]
    (try
      (log/info "Starting Boundary User CLI" {:args args})

      ;; Load configuration
      (let [cfg (config/load-config)
            ;; Derive database configuration for the active adapter
            db-conf (config/db-spec cfg)
            db-ctx (db-factory/db-context db-conf)]

        (try
          ;; Initialize database schema
          (user-persistence/initialize-user-schema! db-ctx)

          ;; Create repositories
          (let [user-repo (user-persistence/create-user-repository db-ctx)
                session-repo (user-persistence/create-session-repository db-ctx)
                audit-repo (user-persistence/create-audit-repository db-ctx)

                ;; Create no-op observability services for CLI
                logger (no-op-logging/create-no-op-logger nil)
                metrics (no-op-metrics/create-metrics-emitter nil)
                error-reporter (no-op-error-reporting/create-error-reporter nil)

                ;; Validation and auth configuration
                validation-cfg (config/user-validation-config cfg)
                auth-cfg {} ; no special auth config for CLI yet
                auth-svc (user-auth/create-authentication-service
                          user-repo session-repo auth-cfg)

                ;; Create user service with full dependencies
                user-svc (user-service/create-user-service
                          user-repo session-repo audit-repo validation-cfg auth-svc)

                ;; Dispatch CLI commands and capture exit status
                status (user-cli/run-cli! user-svc args)]

            ;; Ensure we always store an integer exit status
            (reset! exit-status (if (integer? status) status 1)))

          (finally
            ;; Always close database connections
            (when-let [datasource (:datasource db-ctx)]
              (try
                (.close ^java.lang.AutoCloseable datasource)
                (catch Exception e
                  (log/warn "Failed to close database connection" {:error (.getMessage e)})))))))

      (catch Exception e
        (log/error "User CLI execution failed" {:error (.getMessage e)})
        (binding [*out* *err*]
          (println "Fatal error:" (.getMessage e)))
        (reset! exit-status 1)))
    @exit-status))
