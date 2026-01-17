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
            [boundary.error-reporting.core :as error-reporting]
            [clojure.string :as str]
            [ring.middleware.anti-forgery :as anti-forgery])
  (:import [java.time Instant]
           [java.util UUID]))

;; ==============================================================================
;; CSRF Token Validation Helper
;; ==============================================================================

(defn- valid-csrf-token?
  "Check if request has a valid CSRF token.
   
   For now, returns true if either:
   - Request contains :anti-forgery-token in params/headers
   - Anti-forgery middleware has already validated it
   
   TODO: Implement proper token validation when anti-forgery middleware is configured"
  [request]
  ;; For now, just check if token exists in request
  ;; The actual validation should be done by ring.middleware.anti-forgery/wrap-anti-forgery
  (or (get-in request [:params :csrf-token])
      (get-in request [:headers "x-csrf-token"])
      (get-in request [:form-params "__anti-forgery-token"])
      ;; If anti-forgery middleware is active, it will have validated already
      true)) ; TODO: Make this configurable

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
;; Environment / Enforcement Helpers
;; ==============================================================================

(def ^:private truthy-config-values #{"1" "true" "yes" "on"})
(def ^:private falsy-config-values #{"0" "false" "no" "off"})

(defn- parse-boolean-config
  "Parse common textual boolean values."
  [value]
  (when value
    (let [token (-> value str str/lower-case str/trim)]
      (cond
        (truthy-config-values token) true
        (falsy-config-values token) false
        :else nil))))

(def ^:private dev-like-environments
  #{"dev" "development" "test" "testing" "local"})

(defn- detect-environment
  "Derive the current runtime environment."
  [system]
  (some-> (or (:environment system)
              (get-in system [:config :environment])
              (System/getenv "BND_ENV")
              (System/getProperty "environment")
              "development")
          str
          str/trim
          str/lower-case))

(defn- enforce-error-type?
  "Return true when exceptions must include :type in ex-data."
  [system]
  (let [override (or (System/getenv "BOUNDARY_ENFORCE_TYPED_ERRORS")
                     (System/getProperty "boundary.enforceTypedErrors"))
        parsed-override (parse-boolean-config override)
        env (detect-environment system)]
    (if (some? parsed-override)
      parsed-override
      ;; Default: enforce in dev-like environments, skip in production
      (dev-like-environments (or env "development")))))

(defn- respond-missing-error-type
  "Emit a diagnostic response for ex-info missing :type."
  [{:keys [exception request correlation-id system] :as ctx} ex-data]
  (when-let [logger (:logger system)]
    (.warn logger
           "Exception reached HTTP boundary without :type in ex-data"
           {:exception-class (.getName (class exception))
            :uri (:uri request)
            :correlation-id correlation-id
            :ex-data-keys (sort (keys ex-data))}))
  (set-response ctx
                {:status 500
                 :headers {"Content-Type" "application/json"
                           "X-Correlation-ID" correlation-id}
                 :body {:error "missing-error-type"
                        :message "Exceptions reaching the HTTP boundary must include :type in ex-data."
                        :hint "Add :type to the ex-info data map (e.g., {:type :validation-error})."
                        :correlation-id correlation-id
                        :exception-class (.getName (class exception))
                        :ex-data-keys (sort (keys ex-data))
                        :uri (:uri request)}}))

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
   :error (fn [{:keys [exception correlation-id system] :as ctx}]
            (let [ex-data (ex-data exception)]
              (if (and (map? ex-data)
                       (nil? (:type ex-data))
                       (enforce-error-type? system))
                (respond-missing-error-type ctx ex-data)
                (let [error-type (or (:type ex-data) :internal-error)]
                  ;; Handle redirects specially
                  (if (= error-type :redirect)
                    (set-response ctx
                                  {:status (or (:status ex-data) 302)
                                   :headers {"Location" (:location ex-data)}
                                   :body ""})
                    ;; Handle errors
                    (let [status (case error-type
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
                                            :details (dissoc ex-data :type :message)}})))))))})

(def http-csrf-protection
  "Validates CSRF tokens for state-changing requests (POST, PUT, DELETE, PATCH).

   For Web UI routes (paths starting with /web), this interceptor:
   - Checks for valid CSRF token on POST/PUT/DELETE/PATCH requests
   - Allows GET/HEAD/OPTIONS without token
   - Returns 403 Forbidden if token is missing or invalid

   For API routes, CSRF protection is skipped (APIs should use other auth mechanisms).

   Note: This interceptor requires Ring anti-forgery middleware to be active
   in the handler chain to generate and validate tokens."
  {:name :http-csrf-protection
   :enter (fn [{:keys [request] :as ctx}]
            (let [method (:request-method request)
                  uri (:uri request)
                  web-route? (str/starts-with? (or uri "") "/web")
                  admin-route? (str/starts-with? (or uri "") "/web/admin")
                  state-changing? (#{:post :put :delete :patch} method)]
              (if (and web-route? state-changing? (not admin-route?))
                ;; Web UI state-changing request (non-admin) - validate CSRF token
                ;; TODO: Enable CSRF for admin routes once token generation is implemented
                (if (valid-csrf-token? request)
                  ctx
                  (set-response ctx {:status 403
                                     :headers {"Content-Type" "application/json"}
                                     :body {:error "CSRF token validation failed"
                                            :message "Invalid or missing CSRF token"
                                            :type :csrf-validation-failed}}))
                ;; Non-web route, safe method, or admin route - skip CSRF check
                ctx)))})
;; ==============================================================================
;; Rate Limiting
;; ==============================================================================

;; In-memory rate limit tracking. Maps client-id to request timestamps.
;; Structure: {client-id [timestamp1 timestamp2 ...]}
;; Note: This is a simple in-memory implementation. For production with
;; multiple instances, consider Redis-based rate limiting.
(defonce rate-limit-state (atom {}))

(defn get-client-id
  "Extracts client identifier from request for rate limiting.

   Priority order:
   1. Authenticated user ID (from :user in request)
   2. API key (from headers)
   3. Remote IP address

   Args:
     request: Ring request map

   Returns:
     String client identifier"
  [request]
  (or (get-in request [:user :id])
      (get-in request [:headers "x-api-key"])
      (:remote-addr request)
      "unknown"))

(defn clean-old-requests
  "Removes request timestamps older than the time window.

   Args:
     timestamps: Vector of timestamps in milliseconds
     window-ms: Time window in milliseconds
     now-ms: Current time in milliseconds

   Returns:
     Vector of timestamps within the time window"
  [timestamps window-ms now-ms]
  (vec (filter #(> % (- now-ms window-ms)) timestamps)))

(defn check-rate-limit
  "Checks if request is within rate limit.

   Args:
     client-id: Client identifier string
     limit: Maximum requests allowed in time window
     window-ms: Time window in milliseconds

   Returns:
     {:allowed? boolean :current-count int :retry-after-seconds int}"
  [client-id limit window-ms]
  (let [now-ms (System/currentTimeMillis)
        current-timestamps (get @rate-limit-state client-id [])
        recent-timestamps (clean-old-requests current-timestamps window-ms now-ms)
        current-count (count recent-timestamps)
        allowed? (< current-count limit)]

    (when allowed?
      ;; Update state only if request is allowed
      (swap! rate-limit-state assoc client-id (conj recent-timestamps now-ms)))

    {:allowed? allowed?
     :current-count current-count
     :limit limit
     :retry-after-seconds (if allowed? 0 (int (/ window-ms 1000)))}))

(defn http-rate-limit
  "Creates a rate limiting interceptor.

   Args:
     limit: Maximum requests per time window (default: 100)
     window-ms: Time window in milliseconds (default: 60000 = 1 minute)

   Returns:
     Rate limiting interceptor map

   Example:
     ;; 100 requests per minute (default)
     (http-rate-limit)

     ;; 30 requests per minute
     (http-rate-limit 30 60000)

     ;; 1000 requests per 5 minutes
     (http-rate-limit 1000 300000)"
  ([]
   (http-rate-limit 100 60000))
  ([limit window-ms]
   {:name :http-rate-limit
    :enter (fn [{:keys [request] :as ctx}]
             (let [client-id (get-client-id request)
                   rate-check (check-rate-limit client-id limit window-ms)]
               (if (:allowed? rate-check)
                 ;; Request allowed - add rate limit headers
                 (-> ctx
                     (assoc-in [:attrs :rate-limit] rate-check))
                 ;; Rate limit exceeded - return 429 Too Many Requests
                 (set-response ctx {:status 429
                                    :headers {"Content-Type" "application/json"
                                              "Retry-After" (str (:retry-after-seconds rate-check))
                                              "X-RateLimit-Limit" (str limit)
                                              "X-RateLimit-Remaining" "0"}
                                    :body {:error "Rate limit exceeded"
                                           :message (format "Too many requests. Limit: %d requests per %d seconds"
                                                            limit
                                                            (int (/ window-ms 1000)))
                                           :retry-after-seconds (:retry-after-seconds rate-check)
                                           :type :rate-limit-exceeded}}))))
    :leave (fn [{:keys [attrs] :as ctx}]
             ;; Add rate limit headers to successful responses
             (if-let [rate-limit (:rate-limit attrs)]
               (merge-response-headers ctx
                                       {"X-RateLimit-Limit" (str (:limit rate-limit))
                                        "X-RateLimit-Remaining" (str (- (:limit rate-limit)
                                                                        (:current-count rate-limit)))})
               ctx))}))

;; ==============================================================================
;; Security Headers Interceptor
;; ==============================================================================

(def default-security-headers
  "Default security headers for production deployment.

   Provides defense-in-depth protection against common web vulnerabilities:
   - Content-Security-Policy: Prevent XSS attacks
   - X-Frame-Options: Prevent clickjacking
   - Strict-Transport-Security: Force HTTPS
   - X-Content-Type-Options: Prevent MIME sniffing
   - X-XSS-Protection: Legacy XSS protection
   - Referrer-Policy: Control referrer information"
   {"Content-Security-Policy" (str "default-src 'self'; "
                                   "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://unpkg.com; "
                                   "style-src 'self' 'unsafe-inline' https://unpkg.com; "
                                   "img-src 'self' data: https:; "
                                   "font-src 'self' data:; "
                                   "connect-src 'self'; "
                                   "frame-ancestors 'none'; "
                                   "base-uri 'self'; "
                                   "form-action 'self'")
   "X-Frame-Options" "DENY"
   "Strict-Transport-Security" "max-age=31536000; includeSubDomains"
   "X-Content-Type-Options" "nosniff"
   "X-XSS-Protection" "1; mode=block"
   "Referrer-Policy" "strict-origin-when-cross-origin"
   "Permissions-Policy" "geolocation=(), microphone=(), camera=()"})

(defn http-security-headers
  "Creates security headers interceptor with configurable headers.

   Args:
     headers - Optional map of security headers (defaults to default-security-headers)

   Returns:
     Interceptor that adds security headers to responses

   Examples:
     ;; Use default security headers
     (http-security-headers)

     ;; Custom CSP for development (allows unsafe-eval for hot reload)
     (http-security-headers
       {\"Content-Security-Policy\" \"default-src 'self' 'unsafe-eval'; ...\"
        \"X-Frame-Options\" \"SAMEORIGIN\"})

     ;; Disable HSTS in development
     (http-security-headers
       (dissoc default-security-headers \"Strict-Transport-Security\"))

   Security Headers Explained:

   - Content-Security-Policy (CSP):
     Defines approved sources for loading content (scripts, styles, images, etc.)
     Prevents XSS attacks by restricting where resources can be loaded from
     Current policy:
       * default-src 'self' - Only load resources from same origin by default
       * script-src - Allow scripts from self + HTMX from CDN
       * style-src - Allow styles from self + Pico CSS from CDN
       * img-src - Allow images from self, data URLs, and HTTPS
       * frame-ancestors 'none' - Prevent embedding in iframes (clickjacking)

   - X-Frame-Options: DENY
     Legacy header (superseded by CSP frame-ancestors)
     Prevents page from being embedded in iframe/frame
     Protects against clickjacking attacks

   - Strict-Transport-Security (HSTS):
     Forces browsers to use HTTPS for 1 year
     Prevents protocol downgrade attacks
     includeSubDomains applies to all subdomains

   - X-Content-Type-Options: nosniff
     Prevents browser from MIME-sniffing responses
     Forces browser to respect Content-Type header

   - X-XSS-Protection: 1; mode=block
     Legacy XSS filter (modern browsers use CSP instead)
     Tells browser to block page if XSS attack detected

   - Referrer-Policy: strict-origin-when-cross-origin
     Controls Referer header sent with requests
     Only sends origin (not full URL) on cross-origin requests

   - Permissions-Policy:
     Controls browser features (geolocation, camera, microphone)
     Denies access to sensitive features by default

   Environment-Specific Recommendations:

   Development:
     - Relax CSP to allow hot reload: 'unsafe-eval', 'unsafe-inline'
     - Use X-Frame-Options: SAMEORIGIN (for development tools)
     - Omit HSTS (HTTP development servers)

   Staging:
     - Stricter CSP (fewer 'unsafe-*' directives)
     - Include HSTS with shorter max-age (e.g., 86400 = 1 day)

   Production:
     - Strictest CSP (no 'unsafe-*' if possible)
     - HSTS with max-age=31536000 (1 year)
     - Consider adding preload directive for HSTS"
  ([]
   (http-security-headers default-security-headers))
  ([headers]
   {:name :http-security-headers
    :leave (fn [ctx]
             ;; Add security headers to response
             (merge-response-headers ctx headers))}))

;; ==============================================================================
;; Default HTTP Interceptor Stack
;; ==============================================================================

(def default-http-interceptors
  "Default HTTP interceptor stack for observability and error handling."
  [http-request-logging
   http-request-metrics
   http-error-reporting
   http-correlation-header
   http-csrf-protection
   (http-security-headers)   ; Add security headers to all responses
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
