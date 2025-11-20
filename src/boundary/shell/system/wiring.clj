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
            [boundary.logging.shell.adapters.no-op :as logging-no-op]
            [boundary.metrics.shell.adapters.no-op :as metrics-no-op]
            [boundary.logging.shell.adapters.stdout :as logging-stdout]
            [boundary.error-reporting.shell.adapters.no-op :as error-reporting-no-op]
            [boundary.error-reporting.shell.adapters.sentry :as error-reporting-sentry]
            [boundary.shell.utils.port-manager :as port-manager]
            ;; todo: need to find a way to decouple these dependencies an inject them in another way.
            [boundary.user.shell.module-wiring] ;; Load user module init/halt methods
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
    (log/info "Database context initialized successfully"
              {:adapter (:adapter config)})
    ctx))

(defmethod ig/halt-key! :boundary/db-context
  [_ ctx]
  (log/info "Halting database context")
  (db-factory/close-db-context! ctx)
  (log/info "Database context halted"))

;; =============================================================================
;; User and Session Repositories
;;
;; NOTE: The actual Integrant wiring for :boundary/user-repository and
;; :boundary/session-repository lives in
;; `boundary.user.shell.module-wiring` to avoid system wiring depending
;; directly on user shell namespaces.
;; =============================================================================

;; =============================================================================
;; HTTP Handler
;; =============================================================================

(defmethod ig/init-key :boundary/http-handler
  [_ {:keys [config user-routes]}]
  (log/info "Initializing top-level HTTP handler with structured route composition")
  (let [;; Import routing utilities
        _ (require 'boundary.shell.interfaces.http.routes)
        routes-create-router (ns-resolve 'boundary.shell.interfaces.http.routes 'create-router)
        routes-create-handler (ns-resolve 'boundary.shell.interfaces.http.routes 'create-handler)
        
        ;; Extract route vectors from structured format
        static-routes-vec (or (:static user-routes) [])
        web-routes-vec (or (:web user-routes) [])
        api-routes-vec (or (:api user-routes) [])
        
        ;; Add prefixes to web and api routes since routes/create-router
        ;; grouping logic expects them to already have prefixes for /web
        ;; Static routes stay at root, web gets /web prefix, api gets /api prefix from router
        web-routes-prefixed (when (seq web-routes-vec)
                              (mapv (fn [[path opts]]
                                      [(str "/web" path) opts])
                                    web-routes-vec))
        
        ;; Combine all routes - routes/create-router will handle the rest
        ;; It groups based on path prefixes: /css /js /modules /docs /web stay at root
        ;; Everything else goes under /api
        all-routes (concat static-routes-vec
                           (or web-routes-prefixed [])
                           api-routes-vec)
        
        ;; Create router and handler using common infrastructure
        ;; This will apply /api prefix to api-routes-vec automatically
        router (routes-create-router config all-routes)
        handler (routes-create-handler router)]
    
    (log/info "Top-level HTTP handler initialized successfully"
              {:static-routes (count static-routes-vec)
               :web-routes (count web-routes-vec)
               :api-routes (count api-routes-vec)
               :total-routes (count all-routes)})
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
                 :stdout (logging-stdout/create-logging-component config)
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
                         :sentry (error-reporting-sentry/create-sentry-error-reporting-component config)
                         ;; Future providers will be added here:
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
  [_ {:keys [handler port host join? config]}]
  (let [http-config (or config {})
        port-allocation (port-manager/allocate-port port http-config)
        allocated-port (:port port-allocation)]

    (port-manager/log-port-allocation port allocated-port http-config "HTTP Server")

    (let [server (jetty/run-jetty handler
                                  {:port allocated-port
                                   :host host
                                   :join? (or join? false)})]
      (log/info "HTTP server started successfully"
                {:port allocated-port
                 :host host
                 :url (str "http://" host ":" allocated-port)
                 :allocation-message (:message port-allocation)})
      server)))

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
