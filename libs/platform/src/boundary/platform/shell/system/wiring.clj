(ns boundary.platform.shell.system.wiring
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
  (:require [boundary.platform.shell.adapters.database.factory :as db-factory]
            [boundary.observability.logging.shell.adapters.no-op :as logging-no-op]
            [boundary.observability.metrics.shell.adapters.no-op :as metrics-no-op]
            [boundary.observability.metrics.shell.adapters.datadog :as metrics-datadog]
            [boundary.observability.logging.shell.adapters.stdout :as logging-stdout]
            [boundary.observability.logging.shell.adapters.slf4j :as logging-slf4j]
            [boundary.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [boundary.observability.errors.shell.adapters.sentry :as error-reporting-sentry]
            [boundary.platform.shell.http.reitit-router :as reitit-router]
            [boundary.platform.shell.http.versioning :as http-versioning]
            [boundary.platform.shell.utils.port-manager :as port-manager]
            ;; todo: need to find a way to decouple these dependencies an inject them in another way.
            [boundary.cache.shell.module-wiring] ;; Load cache module init/halt methods
            [boundary.user.shell.module-wiring] ;; Load user module init/halt methods
            [boundary.admin.shell.module-wiring] ;; Load admin module init/halt methods
            [boundary.tenant.shell.module-wiring] ;; Load tenant module init/halt methods
            [boundary.workflow.shell.module-wiring] ;; Load workflow module init/halt methods
            [boundary.search.shell.module-wiring] ;; Load search module init/halt methods
            [boundary.external.shell.module-wiring] ;; Load external adapters init/halt methods
            [boundary.payments.shell.module-wiring] ;; Load payments module init/halt methods
            [boundary.i18n.shell.module-wiring] ;; Load i18n module init/halt methods
            [boundary.i18n.shell.middleware :as i18n-middleware]
            [cheshire.core]
            [clojure.string :as str]
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
;; HTTP Router Adapter
;; =============================================================================

(defmethod ig/init-key :boundary/router
  [_ {:keys [adapter]}]
  (log/info "Initializing HTTP router adapter" {:adapter adapter})
  (let [router (case adapter
                 :reitit (reitit-router/create-reitit-router)
                 ;; Future routers:
                 ;; :pedestal (pedestal-router/create-pedestal-router)
                 ;; :compojure (compojure-router/create-compojure-router)
                 (do
                   (log/warn "Unknown router adapter, falling back to reitit"
                             {:adapter adapter})
                   (reitit-router/create-reitit-router)))]
    (log/info "HTTP router adapter initialized" {:adapter adapter})
    router))

(defmethod ig/halt-key! :boundary/router
  [_ _router]
  (log/info "HTTP router adapter halted"))

;; =============================================================================
;; HTTP Handler
;; =============================================================================

(defn- build-test-reset-routes
  "Return a one-element vector containing the POST /test/reset reitit route
   when :test/reset-endpoint-enabled? is true in `config`, otherwise nil.

   Loudly throws at startup if the flag is enabled in a non-test/non-dev
   profile — this is a production safety net, not graceful degradation.

   The handler is looked up via `requiring-resolve` so that libs/platform
   does NOT have a compile-time dependency on the monolith test-support
   namespace (which would reverse the library dependency direction)."
  [{:keys [config user-service tenant-service db-context]}]
  (when (true? (:test/reset-endpoint-enabled? config))
    (let [profile (:boundary/profile config)]
      (when-not (contains? #{:test :dev} profile)
        (throw (ex-info "test/reset endpoint cannot be enabled outside :test/:dev"
                        {:profile profile
                         :flag    :test/reset-endpoint-enabled?}))))
    (let [make-handler (requiring-resolve 'boundary.test-support.shell.handler/make-reset-handler)
          truncate!    (requiring-resolve 'boundary.test-support.shell.reset/truncate-all!)
          seed!        (requiring-resolve 'boundary.test-support.shell.reset/seed-baseline!)
          datasource   (:datasource db-context)
          deps         {:user-service   user-service
                        :tenant-service tenant-service
                        :datasource     datasource
                        :truncate!      (fn [_] (truncate! datasource))
                        :seed!          (fn [d] (seed! d))}
          handler      (make-handler deps)]
      (log/warn "Mounting /test/reset endpoint — test-profile only, DO NOT enable in production")
      [{:path "/test/reset"
        :methods {:post {:handler handler
                         :summary "Playwright e2e reset + seed endpoint"
                         :no-doc  true}}}])))

(defmethod ig/init-key :boundary/http-handler
  [_ {:keys [user-routes admin-routes tenant-routes membership-routes workflow-routes search-routes router logger metrics-emitter error-reporter config tenant-service membership-service db-context cache i18n user-service]}]
  (log/info "Initializing top-level HTTP handler with normalized routing and API versioning")
  (require 'boundary.platform.ports.http)
  (require 'boundary.platform.shell.interfaces.http.common)
  (require 'boundary.platform.shell.interfaces.http.tenant-middleware)
  (require 'boundary.tenant.shell.membership-middleware)
  (let [;; Import compile-routes function
        compile-routes (ns-resolve 'boundary.platform.ports.http 'compile-routes)

        ;; Import tenant middleware
        tenant-mw-ns 'boundary.platform.shell.interfaces.http.tenant-middleware
        wrap-multi-tenant (ns-resolve tenant-mw-ns 'wrap-multi-tenant)
        wrap-tenant-membership (ns-resolve 'boundary.tenant.shell.membership-middleware
                                           'wrap-tenant-membership)

        ;; Create health check handler
        health-handler (let [health-fn (ns-resolve 'boundary.platform.shell.interfaces.http.common 'health-check-handler)]
                         (health-fn
                          (get-in config [:active :boundary/settings :name] "boundary")
                          (get-in config [:active :boundary/settings :version] "unknown")
                          nil))

        ;; Create readiness handler with dependency checks
        readiness-handler-fn (ns-resolve 'boundary.platform.shell.interfaces.http.common 'readiness-handler)
        ready-handler (readiness-handler-fn db-context cache)

        ;; Define platform routes (health checks, etc.) in normalized format
        platform-routes [{:path "/"
                          :methods {:get {:handler (fn [request]
                                                     ;; Redirect to login if not authenticated, users if authenticated
                                                     (if (:user request)
                                                       {:status 302
                                                        :headers {"Location" "/web/users"}}
                                                       {:status 302
                                                        :headers {"Location" "/web/login"}}))
                                          :summary "Home page (redirects to login or users)"
                                          :no-doc true}}}
                         {:path "/health"
                          :methods {:get {:handler health-handler
                                          :summary "Health check endpoint"
                                          :no-doc true}}}
                         {:path "/health/ready"
                          :methods {:get {:handler ready-handler
                                          :summary "Readiness check with dependency health"
                                          :no-doc true}}}
                         {:path "/health/live"
                          :methods {:get {:handler (fn [_] {:status 200
                                                            :headers {"Content-Type" "application/json"}
                                                            :body (cheshire.core/generate-string {:status "alive"})})
                                          :summary "Liveness check"
                                          :no-doc true}}}]

        ;; Extract user module routes (normalized format)
        user-static-routes (or (:static user-routes) [])
        user-web-routes (or (:web user-routes) [])
        user-api-routes (or (:api user-routes) [])

        ;; User routes are in normalized format - use directly
        user-normalized-static (when (seq user-static-routes) user-static-routes)
        user-normalized-web (when (seq user-web-routes)
                             ;; Add /web prefix to web routes and merge :meta into route root.
                             ;; Always set :no-doc true — web UI routes never belong in API docs.
                              (mapv (fn [{:keys [path meta] :as route}]
                                      (-> route
                                          (dissoc :meta)
                                          (merge {:no-doc true} meta)
                                          (assoc :path (str "/web" path))))
                                    user-web-routes))
        user-normalized-api (when (seq user-api-routes) user-api-routes)

        ;; Extract admin module routes (normalized format) - may be nil if admin disabled
        admin-static-routes (or (:static admin-routes) [])
        admin-web-routes (or (:web admin-routes) [])
        admin-api-routes (or (:api admin-routes) [])

        ;; Admin routes are in normalized format - use directly
        admin-normalized-static (when (seq admin-static-routes) admin-static-routes)
        admin-normalized-web (when (seq admin-web-routes)
                              ;; Add /web/admin prefix to web routes and merge :meta into route root.
                              ;; Always set :no-doc true — web UI routes never belong in API docs.
                               (let [transformed (mapv (fn [{:keys [path meta] :as route}]
                                                         (let [result (-> route
                                                                          (dissoc :meta)
                                                                          (merge {:no-doc true} meta)
                                                                          (assoc :path (str "/web/admin" path)))]
                                                           (log/info "Admin route transformation"
                                                                     {:original-path path
                                                                      :new-path (:path result)
                                                                      :had-meta (some? meta)
                                                                      :has-middleware (contains? result :middleware)
                                                                      :result-keys (keys result)})
                                                           result))
                                                       admin-web-routes)]
                                 (log/info "Total admin web routes transformed" {:count (count transformed)})
                                 transformed))
        admin-normalized-api (when (seq admin-api-routes) admin-api-routes)

        ;; Extract tenant module routes (normalized format)
        tenant-api-routes (or (:api tenant-routes) [])
        tenant-normalized-api (when (seq tenant-api-routes) tenant-api-routes)

        ;; Extract tenant membership module routes (normalized format)
        membership-api-routes (or (:api membership-routes) [])
        membership-normalized-api (when (seq membership-api-routes) membership-api-routes)

        ;; Extract workflow module routes (normalized format) — may be nil if workflow disabled
        workflow-web-routes-raw (or (:web workflow-routes) [])
        workflow-api-routes (or (:api workflow-routes) [])

        ;; Workflow web routes — mounted under /web/admin (same prefix as admin routes)
        workflow-normalized-web (when (seq workflow-web-routes-raw)
                                  (mapv (fn [{:keys [path meta] :as route}]
                                          (-> route
                                              (dissoc :meta)
                                              (merge {:no-doc true} meta)
                                              (assoc :path (str "/web/admin" path))))
                                        workflow-web-routes-raw))
        workflow-normalized-api (when (seq workflow-api-routes) workflow-api-routes)

        ;; Extract search module routes (normalized format) — may be nil if search disabled
        search-web-routes-raw (or (:web search-routes) [])
        search-api-routes     (or (:api search-routes) [])

        ;; Search web routes — mounted under /web/admin (same prefix as admin/workflow routes)
        search-normalized-web (when (seq search-web-routes-raw)
                                (mapv (fn [{:keys [path meta] :as route}]
                                        (-> route
                                            (dissoc :meta)
                                            (merge {:no-doc true} meta)
                                            (assoc :path (str "/web/admin" path))))
                                      search-web-routes-raw))
        search-normalized-api (when (seq search-api-routes) search-api-routes)

        ;; Combine all API routes (unversioned at this point)
        all-api-routes (concat (or user-normalized-api [])
                               (or admin-normalized-api [])
                               (or tenant-normalized-api [])
                               (or membership-normalized-api [])
                               (or workflow-normalized-api [])
                               (or search-normalized-api []))

        ;; Apply API versioning to all API routes
        ;; This wraps routes with /api/v1 prefix and creates backward compatibility redirects
        versioned-api-routes (if (seq all-api-routes)
                               (http-versioning/apply-versioning all-api-routes config)
                               [])

        ;; Conditionally build /test/reset route (test profile only).
        ;; Deliberately NOT routed through apply-versioning — we want the
        ;; literal path /test/reset, not /api/v1/test/reset.
        test-reset-routes (build-test-reset-routes
                           {:config         config
                            :user-service   user-service
                            :tenant-service tenant-service
                            :db-context     db-context})

        ;; Combine all routes: platform, static, web, and versioned API
        all-normalized-routes (concat platform-routes
                                      (or user-normalized-static [])
                                      (or user-normalized-web [])
                                      (or admin-normalized-static [])
                                      (or admin-normalized-web [])
                                      (or workflow-normalized-web [])
                                      (or search-normalized-web [])
                                      versioned-api-routes
                                      (or test-reset-routes []))

        ;; Build system services map for HTTP interceptors
        system {:logger logger
                :metrics-emitter metrics-emitter
                :error-reporter error-reporter}

        ;; Build i18n middleware (always present — falls back to identity t-fn if not configured)
        i18n-middleware-fn (when i18n
                             (fn [handler]
                               (i18n-middleware/wrap-i18n handler i18n)))

        ;; Build middleware chain with tenant support
        ;; Tenant middleware is added ONLY if tenant-service is provided
        tenant-middleware (when (and tenant-service db-context)
                            [(fn [handler]
                               (log/info "Adding multi-tenant middleware to HTTP pipeline")
                               (wrap-multi-tenant handler tenant-service db-context
                                                  {:require-tenant? false}))])
        membership-middleware (when membership-service
                                [(fn [handler]
                                   (log/info "Adding tenant membership middleware to HTTP pipeline")
                                   (wrap-tenant-membership membership-service handler))])

        ;; Compile routes using router adapter with system services
        ;; Add method override middleware for HTML form PUT/DELETE support
        ;; Add tenant middleware to the chain (before method override)
        router-config {:middleware (concat
                                    tenant-middleware
                                    membership-middleware
                                    (when i18n-middleware-fn [i18n-middleware-fn])
                                    [(fn [handler]
                                       (fn [request]
                                         (if (= :post (:request-method request))
                                           (let [method (or (get-in request [:form-params "_method"])
                                                            (get-in request [:params "_method"]))]
                                             (if method
                                               (let [override-method (keyword (str/lower-case method))]
                                                 (handler (assoc request :request-method override-method)))
                                               (handler request)))
                                           (handler request))))])
                       :system system}
        handler (compile-routes router all-normalized-routes router-config)

        ;; Wrap handler with version headers middleware
        versioned-handler (http-versioning/wrap-handler-with-version-headers handler config)]

    (log/info "Top-level HTTP handler initialized successfully"
              {:user-routes {:static (count (or user-static-routes []))
                             :web (count (or user-web-routes []))
                             :api (count (or user-api-routes []))}
               :admin-routes {:static (count (or admin-static-routes []))
                              :web (count (or admin-web-routes []))
                              :api (count (or admin-api-routes []))}
               :tenant-routes {:api (count (or tenant-api-routes []))}
               :membership-routes {:api (count (or membership-api-routes []))}
               :workflow-routes {:web (count (or workflow-web-routes-raw []))
                                 :api (count (or workflow-api-routes []))}
               :search-routes {:web (count (or search-web-routes-raw []))
                               :api (count (or search-api-routes []))}
               :versioned-api-routes (count versioned-api-routes)
               :total-normalized-routes (count all-normalized-routes)
               :router-adapter (class router)
               :system-services (keys system)
               :api-versioning-enabled true})
    versioned-handler))

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
                 :slf4j (logging-slf4j/create-logging-component config)
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
                  :no-op          (metrics-no-op/create-metrics-component config)
                  :datadog-statsd (metrics-datadog/create-datadog-metrics-component config)
                  ;; Future providers will be added here:
                  ;; :prometheus (prometheus-adapter/create-metrics-component config)
                  ;; :statsd (statsd-adapter/create-metrics-component config)
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
