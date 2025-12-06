(ns boundary.platform.shell.http.interceptors
  "HTTP interceptor pipeline for Ring request/response processing.
   
   This namespace provides an HTTP-specific interceptor chain that runs over Ring handlers,
   giving Pedestal-like enter/leave/error semantics while maintaining Ring compatibility.
   
   Key Benefits:
   - Consistent cross-layer observability (HTTP → service → persistence)
   - Declarative per-route policies (auth, rate-limit, auditing)
   - Clean separation of concerns (routing vs cross-cutting logic)
   - Reusable interceptor components across routes
   
   HTTP Context Model:
   {:request       Ring request map
    :response      Ring response map (built up through pipeline)
    :route         Route metadata from Reitit match
    :path-params   Extracted path parameters
    :query-params  Extracted query parameters
    :system        Observability services {:logger :metrics-emitter :error-reporter}
    :attrs         Additional attributes set by interceptors
    :correlation-id Unique request ID
    :started-at    Request start timestamp}
   
   Interceptor Shape:
   {:name   :my-interceptor
    :enter  (fn [context] ...) ; Process request, modify context
    :leave  (fn [context] ...) ; Process response, modify context
    :error  (fn [context] ...)} ; Handle exceptions, produce safe response
   
   Usage:
   ;; As Ring middleware (global)
   (def handler
     (-> app-handler
         (wrap-http-interceptors [logging metrics error-reporting])))
   
   ;; Per-route via Reitit :middleware
   {:get {:handler my-handler
          :middleware [(interceptor-middleware [auth rate-limit])]}}
   
   Integration with Normalized Routes:
   - Normalized routes can specify :interceptors vector
   - Reitit adapter translates :interceptors → :middleware with this runner"
  (:require [boundary.shared.core.interceptor :as interceptor]
            [boundary.metrics.ports :as metrics-ports]
            [boundary.error-reporting.core :as error-reporting])
  (:import [java.time Instant]
           [java.util UUID]))

;; ==============================================================================
;; HTTP Context Management
;; ==============================================================================

(defn create-http-context
  "Creates an HTTP interceptor context from a Ring request.
   
   Args:
     request: Ring request map
     system: Map containing observability services {:logger :metrics-emitter :error-reporter}
     route-data: Optional Reitit route match data
   
   Returns:
     HTTP context map with request details and observability services"
  [request system & [route-data]]
  {:request request
   :response nil
   :route route-data
   :path-params (:path-params request)
   :query-params (:query-params request)
   :system system
   :attrs {}
   :correlation-id (or (get-in request [:headers "x-correlation-id"])
                       (str (UUID/randomUUID)))
   :started-at (Instant/now)
   :timing {}})

(defn extract-response
  "Extracts the Ring response from an HTTP context.
   
   If :response exists in context, returns it.
   If :response is nil, returns a safe 500 error response.
   
   Args:
     context: HTTP interceptor context
   
   Returns:
     Ring response map"
  [context]
  (or (:response context)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body {:error "Internal server error"
              :message "No response generated"
              :correlation-id (:correlation-id context)}}))

(defn set-response
  "Sets the Ring response in the HTTP context.
   
   Args:
     context: HTTP interceptor context
     response: Ring response map
   
   Returns:
     Updated context with response"
  [context response]
  (assoc context :response response))

(defn merge-response-headers
  "Merges additional headers into the response.
   
   Args:
     context: HTTP interceptor context
     headers: Map of headers to merge
   
   Returns:
     Updated context with merged headers"
  [context headers]
  (update-in context [:response :headers] merge headers))

(defn update-response-body
  "Updates the response body using a function.
   
   Args:
     context: HTTP interceptor context
     f: Function to apply to existing body
   
   Returns:
     Updated context with modified body"
  [context f]
  (update-in context [:response :body] f))

;; ==============================================================================
;; HTTP-Specific Interceptors
;; ==============================================================================

(def http-request-logging
  "Logs HTTP requests at entry and completion."
  {:name :http-request-logging
   :enter (fn [{:keys [request system correlation-id] :as ctx}]
            (when-let [logger (:logger system)]
              (.info logger "HTTP request received"
                     {:method (:request-method request)
                      :uri (:uri request)
                      :correlation-id correlation-id}))
            ctx)
   :leave (fn [{:keys [request response system correlation-id] :as ctx}]
            (when-let [logger (:logger system)]
              (.info logger "HTTP request completed"
                     {:method (:request-method request)
                      :uri (:uri request)
                      :status (:status response)
                      :correlation-id correlation-id}))
            ctx)
   :error (fn [{:keys [request system correlation-id exception] :as ctx}]
            (when-let [logger (:logger system)]
              (.error logger "HTTP request failed"
                      {:method (:request-method request)
                       :uri (:uri request)
                       :error (.getMessage ^Throwable exception)
                       :correlation-id correlation-id}))
            ctx)})

(def http-request-metrics
  "Collects HTTP request metrics (timing, status codes)."
  {:name :http-request-metrics
   :enter (fn [ctx]
            (assoc-in ctx [:timing :start] (System/nanoTime)))
   :leave (fn [{:keys [request response system timing] :as ctx}]
            (when-let [metrics-emitter (:metrics-emitter system)]
              ;; Calculate duration but don't use it yet (metrics infrastructure is no-op)
              ;; Future: emit timing metric when metrics backend is configured
              (let [_duration-ms (/ (- (System/nanoTime) (:start timing)) 1e6)]
                (metrics-ports/increment metrics-emitter "http.requests"
                                        {:method (name (:request-method request))
                                         :status (str (:status response))})))
            ctx)
   :error (fn [{:keys [request system] :as ctx}]
            (when-let [metrics-emitter (:metrics-emitter system)]
              (metrics-ports/increment metrics-emitter "http.requests.errors"
                                      {:method (name (:request-method request))}))
            ctx)})

(def http-error-reporting
  "Captures HTTP exceptions and reports them to error tracking."
  {:name :http-error-reporting
   :enter (fn [{:keys [request system correlation-id] :as ctx}]
            (when-let [error-reporter (:error-reporter system)]
              (error-reporting/add-breadcrumb
               error-reporter
               "HTTP request started"
               "http"
               :info
               {:method (name (:request-method request))
                :uri (:uri request)
                :correlation-id correlation-id}))
            ctx)
   :error (fn [{:keys [request exception system correlation-id] :as ctx}]
            (when-let [error-reporter (:error-reporter system)]
              (error-reporting/report-application-error
               error-reporter
               exception
               "HTTP request failed"
               {:context "http-request"
                :method (name (:request-method request))
                :uri (:uri request)
                :correlation-id correlation-id}))
            ctx)})

(def http-correlation-header
  "Adds correlation ID to response headers."
  {:name :http-correlation-header
   :leave (fn [{:keys [correlation-id] :as ctx}]
            (merge-response-headers ctx {"X-Correlation-ID" correlation-id}))
   :error (fn [{:keys [correlation-id] :as ctx}]
            (merge-response-headers ctx {"X-Correlation-ID" correlation-id}))})

(def http-error-handler
  "Converts exceptions into safe HTTP error responses."
  {:name :http-error-handler
   :error (fn [{:keys [exception correlation-id] :as ctx}]
            (let [ex-data (ex-data exception)
                  error-type (or (:type ex-data) :internal-error)
                  status (case error-type
                           :validation-error 400
                           :not-found 404
                           :unauthorized 401
                           :forbidden 403
                           :conflict 409
                           500)]
              (set-response ctx
                            {:status status
                             :headers {"Content-Type" "application/json"
                                       "X-Correlation-ID" correlation-id}
                             :body {:error (name error-type)
                                    :message (or (:message ex-data)
                                                (.getMessage ^Throwable exception)
                                                "An error occurred")
                                    :correlation-id correlation-id
                                    :details (dissoc ex-data :type :message)}})))})

;; ==============================================================================
;; Default HTTP Interceptor Stack
;; ==============================================================================

(def default-http-interceptors
  "Default HTTP interceptor stack for observability and error handling."
  [http-request-logging
   http-request-metrics
   http-error-reporting
   http-correlation-header
   http-error-handler])

;; ==============================================================================
;; HTTP Interceptor Runner
;; ==============================================================================

(defn run-http-interceptors
  "Runs an HTTP interceptor pipeline around a Ring handler.
   
   Args:
     handler: Ring handler function (request → response)
     interceptors: Vector of interceptor maps
     request: Ring request map
     system: Map containing observability services
   
   Returns:
     Ring response map
   
   Pipeline execution:
   1. Create HTTP context from request
   2. Run :enter phase on all interceptors
   3. If successful, call handler and set :response in context
   4. Run :leave phase on all interceptors (reverse order)
   5. If exception occurs, run :error phase to produce safe response
   6. Extract and return final Ring response"
  [handler interceptors request system]
  (let [;; Create initial HTTP context
        initial-ctx (create-http-context request system)
        
        ;; Create handler interceptor that bridges to Ring handler
        handler-interceptor {:name :ring-handler
                             :enter (fn [ctx]
                                      (let [response (handler (:request ctx))]
                                        (set-response ctx response)))}
        
        ;; Combine interceptors with handler at the end
        full-pipeline (conj (vec interceptors) handler-interceptor)
        
        ;; Run the interceptor pipeline
        final-ctx (interceptor/run-pipeline initial-ctx full-pipeline)]
    
    ;; Extract and return the Ring response
    (extract-response final-ctx)))

;; ==============================================================================
;; Ring Middleware Wrapper
;; ==============================================================================

(defn wrap-http-interceptors
  "Ring middleware that runs HTTP interceptors around a handler.
   
   Usage:
     (def handler
       (-> my-handler
           (wrap-http-interceptors [auth logging metrics] system)))
   
   Args:
     handler: Ring handler function
     interceptors: Vector of interceptor maps
     system: Map containing observability services
   
   Returns:
     Wrapped Ring handler function"
  [handler interceptors system]
  (fn [request]
    (run-http-interceptors handler interceptors request system)))

(defn interceptor-middleware
  "Creates a Ring middleware function from interceptors.
   
   This is useful for Reitit per-route :middleware.
   
   Usage:
     {:get {:handler my-handler
            :middleware [(interceptor-middleware [auth rate-limit] system)]}}
   
   Args:
     interceptors: Vector of interceptor maps
     system: Map containing observability services
   
   Returns:
     Ring middleware function (handler → wrapped-handler)"
  [interceptors system]
  (fn [handler]
    (wrap-http-interceptors handler interceptors system)))

;; ==============================================================================
;; Helper Functions
;; ==============================================================================

(defn create-global-http-middleware
  "Creates global HTTP middleware with default observability stack.
   
   Args:
     system: Map containing observability services
     additional-interceptors: Optional vector of additional interceptors to prepend
   
   Returns:
     Ring middleware function"
  ([system]
   (create-global-http-middleware system []))
  ([system additional-interceptors]
   (let [interceptors (concat additional-interceptors default-http-interceptors)]
     (fn [handler]
       (wrap-http-interceptors handler interceptors system)))))

(defn validate-http-interceptor
  "Validates that an interceptor is suitable for HTTP use.
   
   HTTP interceptors should handle HTTP context properly and produce valid Ring responses."
  [interceptor]
  (interceptor/validate-interceptor interceptor)
  ;; Additional HTTP-specific validations could go here
  interceptor)
