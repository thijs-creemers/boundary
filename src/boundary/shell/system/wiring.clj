(ns boundary.shell.system.wiring
  "Integrant system lifecycle management for Boundary application.
   
   This namespace defines init-key and halt-key! multimethods for all
   system components, providing proper lifecycle management and dependency
   injection for the entire application.
   
   Components:
   - :boundary/db-context - Database connection pool and adapter
   - :boundary/user-repository - User data persistence
   - :boundary/session-repository - Session data persistence
   - :boundary/user-service - User business logic orchestration
   - :boundary/logging - Structured logging and audit trails
   - :boundary/metrics - Application and business metrics collection
   - :boundary/error-reporting - Error tracking and alerting
   - :boundary/http-handler - HTTP request routing and handling
   - :boundary/http-server - Jetty HTTP server
   
   Usage:
     (require '[boundary.config :as config])
     (require '[integrant.core :as ig])
     (def cfg (config/ig-config (config/load-config)))
     (def system (ig/init cfg))
     (ig/halt! system)"
  (:require [boundary.shell.adapters.database.factory :as db-factory]
            [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.logging.shell.adapters.no-op :as logging-no-op]
            [boundary.metrics.shell.adapters.no-op :as metrics-no-op]
            [boundary.error-reporting.shell.adapters.no-op :as error-reporting-no-op]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]))

;; =============================================================================
;; Database Context
;; =============================================================================

(defmethod ig/init-key :boundary/db-context
  [_ config]
  (log/info "Initializing database context" {:adapter (:adapter config)})
  (let [ctx (db-factory/db-context config)]

    ;; Initialize user module schema
    (log/info "Initializing user module database schema")
    (user-persistence/initialize-user-schema! ctx)

    (log/info "Database context initialized successfully"
              {:adapter (:adapter config)})
    ctx))

(defmethod ig/halt-key! :boundary/db-context
  [_ ctx]
  (log/info "Halting database context")
  (db-factory/close-db-context! ctx)
  (log/info "Database context halted"))

;; =============================================================================
;; User Repository
;; =============================================================================

(defmethod ig/init-key :boundary/user-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing user repository")
  (let [repo (user-persistence/create-user-repository ctx)]
    (log/info "User repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/user-repository
  [_ _repo]
  (log/info "User repository halted (no cleanup needed)"))

;; =============================================================================
;; Session Repository
;; =============================================================================

(defmethod ig/init-key :boundary/session-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing session repository")
  (let [repo (user-persistence/create-session-repository ctx)]
    (log/info "Session repository initialized")
    repo))

(defmethod ig/halt-key! :boundary/session-repository
  [_ _repo]
  (log/info "Session repository halted (no cleanup needed)"))

;; =============================================================================
;; User Service
;; =============================================================================

(defmethod ig/init-key :boundary/user-service
  [_ {:keys [user-repository session-repository logger metrics error-reporting]}]
  (log/info "Initializing user service")
  (let [service (user-service/create-user-service user-repository session-repository logger metrics error-reporting)]
    (log/info "User service initialized")
    service))

(defmethod ig/halt-key! :boundary/user-service
  [_ _service]
  (log/info "User service halted (no cleanup needed)"))

;; =============================================================================
;; HTTP Handler
;; =============================================================================

(defmethod ig/init-key :boundary/http-handler
  [_ {:keys [user-service config]}]
  (log/info "Initializing HTTP handler with Reitit router")
  (let [_user-http (require 'boundary.user.shell.http)
        create-handler (ns-resolve 'boundary.user.shell.http 'create-handler)
        handler (create-handler user-service (or config {}))]
    (log/info "HTTP handler initialized successfully")
    handler))

(defmethod ig/halt-key! :boundary/http-handler
  [_ _handler]
  (log/info "HTTP handler halted"))

;; =============================================================================
;; Logging Component
;; =============================================================================

(defmethod ig/init-key :boundary/logging
  [_ config]
  (log/info "Initializing logging component" {:provider (:provider config)})
  (let [logger (case (:provider config)
                 :no-op (logging-no-op/create-logging-component config)
                 ;; Future providers will be added here:
                 ;; :stdout (stdout-adapter/create-logging-component config)
                 ;; :json (json-adapter/create-logging-component config)
                 ;; :datadog (datadog-adapter/create-logging-component config)
                 (do
                   (log/warn "Unknown logging provider, falling back to no-op"
                             {:provider (:provider config)})
                   (logging-no-op/create-logging-component config)))]
    (log/info "Logging component initialized" {:provider (:provider config)})
    logger))

(defmethod ig/halt-key! :boundary/logging
  [_ _logger]
  (log/info "Logging component halted"))

;; =============================================================================
;; Metrics Component
;; =============================================================================

(defmethod ig/init-key :boundary/metrics
  [_ config]
  (log/info "Initializing metrics component" {:provider (:provider config)})
  (let [metrics (case (:provider config)
                  :no-op (metrics-no-op/create-metrics-component config)
                  ;; Future providers will be added here:
                  ;; :prometheus (prometheus-adapter/create-metrics-component config)
                  ;; :datadog (datadog-adapter/create-metrics-component config)
                  ;; :cloudwatch (cloudwatch-adapter/create-metrics-component config)
                  (do
                    (log/warn "Unknown metrics provider, falling back to no-op"
                              {:provider (:provider config)})
                    (metrics-no-op/create-metrics-component config)))]
    (log/info "Metrics component initialized" {:provider (:provider config)})
    metrics))

(defmethod ig/halt-key! :boundary/metrics
  [_ _metrics]
  (log/info "Metrics component halted"))

;; =============================================================================
;; Error Reporting Component
;; =============================================================================

(defmethod ig/init-key :boundary/error-reporting
  [_ config]
  (log/info "Initializing error reporting component" {:provider (:provider config)})
  (let [error-reporter (case (:provider config)
                         :no-op (error-reporting-no-op/create-error-reporting-component config)
                         ;; Future providers will be added here:
                         ;; :sentry (sentry-adapter/create-error-reporting-component config)
                         ;; :rollbar (rollbar-adapter/create-error-reporting-component config)
                         ;; :bugsnag (bugsnag-adapter/create-error-reporting-component config)
                         (do
                           (log/warn "Unknown error reporting provider, falling back to no-op"
                                     {:provider (:provider config)})
                           (error-reporting-no-op/create-error-reporting-component config)))]
    (log/info "Error reporting component initialized" {:provider (:provider config)})
    error-reporter))

(defmethod ig/halt-key! :boundary/error-reporting
  [_ _error-reporter]
  (log/info "Error reporting component halted"))

;; =============================================================================
;; HTTP Server (Jetty)
;; =============================================================================

(defmethod ig/init-key :boundary/http-server
  [_ {:keys [handler port host join?]}]
  (log/info "Starting HTTP server" {:port port :host host})
  (let [server (jetty/run-jetty handler
                                {:port port
                                 :host host
                                 :join? (or join? false)})]
    (log/info "HTTP server started successfully"
              {:port port
               :host host
               :url (str "http://" host ":" port)})
    server))

(defmethod ig/halt-key! :boundary/http-server
  [_ server]
  (log/info "Stopping HTTP server")
  (when server
    (.stop server)
    (log/info "HTTP server stopped")))

;; =============================================================================
;; REPL Utilities
;; =============================================================================

(defonce ^:private system-state (atom nil))

(declare stop!)

(defn start!
  "Start the system using default configuration.
   
   Returns:
     Started system map
   
   Example:
     (start!)"
  []
  (when @system-state
    (log/warn "System already started, halting existing system first")
    (stop!))
  (log/info "Starting Boundary system")
  (let [_config (require 'boundary.config)
        load-config (ns-resolve 'boundary.config 'load-config)
        ig-config (ns-resolve 'boundary.config 'ig-config)
        cfg (ig-config (load-config))
        system (ig/init cfg)]
    (reset! system-state system)
    (log/info "Boundary system started successfully")
    system))

(defn stop!
  "Stop the running system.
   
   Returns:
     nil
   
   Example:
     (stop!)"
  []
  (when-let [system @system-state]
    (log/info "Stopping Boundary system")
    (ig/halt! system)
    (reset! system-state nil)
    (log/info "Boundary system stopped"))
  nil)

(defn restart!
  "Restart the system (stop then start).
   
   Returns:
     Started system map
   
   Example:
     (restart!)"
  []
  (stop!)
  (start!))

;; =============================================================================
;; Integrant REPL Setup
;; =============================================================================

(comment
  ;; For use with integrant.repl
  ;; In your user.clj or REPL, set up integrant.repl:
  ;;
  ;; (require '[integrant.repl :as ig-repl])
  ;; (require '[boundary.config :as config])
  ;; (ig-repl/set-prep! #(config/ig-config (config/load-config)))
  ;; (ig-repl/go)     ; Start system
  ;; (ig-repl/reset)  ; Reload and restart
  ;; (ig-repl/halt)   ; Stop system

  ;; Or use the convenience functions:
  (start!)
  (stop!)
  (restart!)

  ;; Check system state
  @system-state

  ...)
