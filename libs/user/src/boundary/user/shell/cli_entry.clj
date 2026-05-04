(ns boundary.user.shell.cli-entry
  "User module CLI entrypoint wrapper.

  Encapsulates user-specific CLI startup so that the top-level CLI can
  remain as module-agnostic as possible and delegate into this module.

  Note: boundary.config is loaded lazily via requiring-resolve so this
  namespace compiles cleanly when boundary/src is not on the classpath
  (e.g. when using zzp-guard's :dev-local alias during REPL development)."
  (:require [boundary.user.shell.cli :as user-cli]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.user.shell.auth :as user-auth]
            [boundary.user.shell.mfa :as user-mfa]
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            [boundary.observability.logging.shell.adapters.no-op :as no-op-logging]
            [boundary.observability.metrics.shell.adapters.no-op :as no-op-metrics]
            [boundary.observability.errors.shell.adapters.no-op :as no-op-error-reporting]
            [clojure.tools.logging :as log]))

(defn run-cli!
  "Run the user module CLI for the given command-line arguments.

  Returns an integer exit status. Does not call System/exit."
  [args]
  (let [exit-status (atom 1)]
    (try
      (log/info "Starting Boundary User CLI" {:args args})

      ;; Load configuration — boundary.config is loaded lazily so this lib
      ;; compiles even when boundary/src is not on the classpath.
      (let [load-config        (requiring-resolve 'boundary.config/load-config)
            db-spec            (requiring-resolve 'boundary.config/db-spec)
            user-val-config    (requiring-resolve 'boundary.config/user-validation-config)
            cfg                (load-config)
            ;; Derive database configuration for the active adapter
            db-conf            (db-spec cfg)
            db-ctx             (db-factory/db-context db-conf)]

        (try
          ;; Initialize database schema
          (user-persistence/initialize-user-schema! db-ctx)

          ;; Create repositories
          (let [pagination-cfg (get-in cfg [:active :boundary/pagination] {:default-limit 20})
                user-repo (user-persistence/create-user-repository db-ctx)
                session-repo (user-persistence/create-session-repository db-ctx)
                audit-repo (user-persistence/create-audit-repository db-ctx pagination-cfg)

                ;; Create no-op observability services for CLI
                ;; (available for future use if needed)
                _logger (no-op-logging/create-no-op-logger nil)
                _metrics (no-op-metrics/create-metrics-emitter nil)
                _error-reporter (no-op-error-reporting/create-error-reporter nil)

                ;; Validation and auth configuration
                validation-cfg (user-val-config cfg)
                auth-cfg {} ; no special auth config for CLI yet

                ;; Create MFA service (required by auth service)
                mfa-svc (user-mfa/create-mfa-service user-repo {})

                ;; Create auth service with MFA support
                auth-svc (user-auth/create-authentication-service
                          user-repo session-repo mfa-svc auth-cfg)

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

(defn -main
  "CLI main entry point for direct invocation via clojure -M:user-cli.
  Delegates to run-cli! and exits with the returned status code."
  [& args]
  (System/exit (run-cli! (vec args))))
