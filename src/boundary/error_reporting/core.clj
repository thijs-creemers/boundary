(ns boundary.error-reporting.core
  "Core error reporting functions and utilities.
   
   This namespace provides pure functions and higher-level abstractions over
   the error reporting protocols, making it easier for feature modules to
   report errors without dealing with protocol details directly."
  (:require
   [boundary.error-reporting.ports :as ports]))

;; =============================================================================
;; Context Management Utilities
;; =============================================================================

(defn build-error-context
  "Builds a comprehensive error context map.
   
   Args:
     base-context - Base context map
     extra-data   - Additional context data
   
   Returns:
     Complete error context map"
  [base-context extra-data]
  (merge base-context
         {:timestamp (System/currentTimeMillis)
          :extra extra-data}))

(defn with-user-context
  "Adds user information to error context.
   
   Args:
     context - Existing context map
     user-id - User identifier
     user-info - Additional user information
   
   Returns:
     Updated context map"
  [context user-id user-info]
  (assoc context
         :user-id user-id
         :user-info user-info))

(defn with-request-context
  "Adds HTTP request information to error context.
   
   Args:
     context        - Existing context map
     request-id     - Request identifier
     method         - HTTP method
     path           - Request path
     headers        - Request headers (sensitive data filtered)
   
   Returns:
     Updated context map"
  [context request-id method path headers]
  (assoc context
         :request-id request-id
         :http-method (clojure.string/upper-case (name method))
         :http-path (str path)
         :http-headers headers))

(defn with-operation-context
  "Adds operation/business context to error reporting.
   
   Args:
     context   - Existing context map
     operation - Operation being performed
     entity    - Business entity involved
     action    - Specific action
   
   Returns:
     Updated context map"
  [context operation entity action]
  (assoc context
         :operation (str operation)
         :entity (str entity)
         :action (str action)))

(defn sanitize-context
  "Removes sensitive information from error context.
   
   Args:
     context        - Context map
     sensitive-keys - Set of keys to remove or mask
   
   Returns:
     Sanitized context map"
  [context sensitive-keys]
  (reduce (fn [ctx key]
            (if (contains? ctx key)
              (assoc ctx key "[REDACTED]")
              ctx))
          context
          sensitive-keys))

;; =============================================================================
;; Error Classification Utilities
;; =============================================================================

(defn classify-exception
  "Classifies an exception by type and characteristics.
   
   Args:
     exception - Exception instance
   
   Returns:
     Classification map with :type, :category, :severity"
  [exception]
  (let [ex-type (-> exception class .getSimpleName)
        ex-message (.getMessage exception)]
    {:type ex-type
     :category (cond
                 (re-find #"(?i)validation|invalid|malformed" ex-message) :validation
                 (re-find #"(?i)auth|permission|forbidden" ex-message) :security
                 (re-find #"(?i)timeout|connection|network" ex-message) :connectivity
                 (re-find #"(?i)database|sql|transaction" ex-message) :database
                 (re-find #"(?i)service|external|api" ex-message) :external-service
                 :else :application)
     :severity (cond
                 (instance? java.lang.OutOfMemoryError exception) :fatal
                 (instance? java.lang.SecurityException exception) :high
                 (instance? java.net.SocketTimeoutException exception) :medium
                 (instance? java.lang.IllegalArgumentException exception) :low
                 :else :medium)}))

(defn should-report-exception?
  "Determines if an exception should be reported based on classification.
   
   Args:
     exception - Exception instance
     config    - Error reporting configuration
   
   Returns:
     Boolean indicating whether to report"
  [exception config]
  (let [{:keys [category severity]} (classify-exception exception)
        min-severity (get config :min-severity :medium)
        excluded-categories (get config :excluded-categories #{})]
    (and (not (contains? excluded-categories category))
         (>= (get {:low 1 :medium 2 :high 3 :fatal 4} severity 2)
             (get {:low 1 :medium 2 :high 3 :fatal 4} min-severity 2)))))

;; =============================================================================
;; High-Level Error Reporting Functions
;; =============================================================================

(defn report-application-error
  "Reports an application error with full context.
   
   Args:
     reporter  - IErrorReporter instance
     exception - Exception instance
     message   - Descriptive error message
     context   - Error context map
   
   Returns:
     Error ID string"
  [reporter exception message context]
  (let [classification (classify-exception exception)
        enriched-context (merge context
                                classification
                                {:error-type :application
                                 :timestamp (System/currentTimeMillis)})]
    (ports/capture-exception reporter exception enriched-context)))

(defn report-validation-error
  "Reports validation errors in a structured format.
   
   Args:
     reporter - IErrorReporter instance
     errors   - Validation error data
     context  - Error context map
   
   Returns:
     Error ID string"
  [reporter errors context]
  (let [error-context (merge context
                             {:error-type :validation
                              :validation-errors errors
                              :error-count (count errors)})]
    (ports/capture-message reporter "Validation failed" :warning error-context {})))

(defn report-external-service-error
  "Reports external service errors.
   
   Args:
     reporter     - IErrorReporter instance
     service-name - Name of external service
     operation    - Operation that failed
     error        - Error information
     context      - Error context map
   
   Returns:
     Error ID string"
  [reporter service-name operation error context]
  (let [service-context (merge context
                               {:error-type :external-service
                                :service service-name
                                :operation operation
                                :service-error error})]
    (ports/capture-message reporter
                           (str "External service error: " service-name)
                           :error
                           service-context {})))

(defn report-security-incident
  "Reports security-related incidents.
   
   Args:
     reporter   - IErrorReporter instance
     incident   - Incident type
     details    - Incident details
     context    - Error context map
   
   Returns:
     Error ID string"
  [reporter incident details context]
  (let [security-context (merge context
                                {:error-type :security
                                 :incident-type incident
                                 :security-details details
                                 :severity :high})]
    (ports/capture-message reporter
                           (str "Security incident: " incident)
                           :error
                           security-context {})))

;; =============================================================================
;; Exception Handling Utilities
;; =============================================================================

(defn with-error-reporting
  "Wraps a function with automatic error reporting.
   
   Args:
     reporter - IErrorReporter instance
     context  - Base error context
     f        - Function to wrap
   
   Returns:
     Result of function f, or re-throws exception after reporting"
  [reporter context f]
  (try
    (f)
    (catch Exception e
      (report-application-error reporter e "Unhandled exception in operation" context)
      (throw e))))

(defn with-fallback-error-reporting
  "Wraps a function with error reporting and fallback value.
   
   Args:
     reporter      - IErrorReporter instance
     context       - Base error context
     fallback      - Fallback value to return on error
     f             - Function to wrap
   
   Returns:
     Result of function f, or fallback value if exception occurs"
  [reporter context fallback f]
  (try
    (f)
    (catch Exception e
      (report-application-error reporter e "Exception caught with fallback" context)
      fallback)))

(defn report-and-continue
  "Reports an exception but doesn't re-throw it.
   
   Args:
     reporter  - IErrorReporter instance
     exception - Exception to report
     message   - Error message
     context   - Error context
   
   Returns:
     Error ID string"
  [reporter exception message context]
  (try
    (report-application-error reporter exception message context)
    (catch Exception report-error
      ;; If error reporting itself fails, log it but don't fail the application
      (println "Failed to report error:" (.getMessage report-error))
      "error-reporting-failed")))

;; =============================================================================
;; Breadcrumb Management
;; =============================================================================

(defn add-breadcrumb
  "Adds a breadcrumb to the error context for tracking user actions.
   
   Args:
     context   - IErrorContext instance
     message   - Breadcrumb message
     category  - Breadcrumb category
     level     - Breadcrumb level (:debug, :info, :warning, :error)
     data      - Additional breadcrumb data
   
   Returns:
     nil"
  [context message category level data]
  (let [breadcrumb {:message message
                    :category category
                    :level level
                    :timestamp (java.time.Instant/now)
                    :data data}]
    (ports/add-breadcrumb! context breadcrumb)))

(defn track-user-navigation
  "Tracks user navigation as breadcrumbs.
   
   Args:
     context - IErrorContext instance
     path    - Navigation path
     method  - HTTP method (for API calls)
   
   Returns:
     nil"
  [context path method]
  (add-breadcrumb context
                  (str (clojure.string/upper-case (name method)) " " path)
                  "navigation"
                  :info
                  {:path path :method method}))

(defn track-user-action
  "Tracks user actions as breadcrumbs.
   
   Args:
     context - IErrorContext instance
     action  - Action performed
     target  - Target of the action
   
   Returns:
     nil"
  [context action target]
  (add-breadcrumb context
                  (str "User " action " " target)
                  "user-action"
                  :info
                  {:action action :target target}))

;; =============================================================================
;; Error Aggregation and Filtering
;; =============================================================================

(defn create-error-fingerprint
  "Creates a fingerprint for error grouping.
   
   Args:
     exception - Exception instance
     context   - Error context
   
   Returns:
     Fingerprint string for grouping similar errors"
  [exception context]
  (let [ex-type (-> exception class .getSimpleName)
        ex-message (.getMessage exception)
        stack-trace-hash (hash (take 5 (.getStackTrace exception)))
        operation (get context :operation "unknown")]
    (str ex-type ":" operation ":" stack-trace-hash)))

(defn should-suppress-duplicate?
  "Determines if a duplicate error should be suppressed.
   
   Args:
     fingerprint    - Error fingerprint
     recent-reports - Map of recent error reports
     suppress-window - Time window for suppression in ms
   
   Returns:
     Boolean indicating whether to suppress"
  [fingerprint recent-reports suppress-window]
  (let [last-report-time (get recent-reports fingerprint 0)
        current-time (System/currentTimeMillis)]
    (< (- current-time last-report-time) suppress-window)))

;; =============================================================================
;; Error Metrics Integration
;; =============================================================================

(defn track-error-metrics
  "Tracks error reporting metrics (requires metrics emitter).
   
   Args:
     metrics-emitter - Metrics emitter instance (optional)
     error-type      - Type of error
     severity        - Error severity
     service         - Service name
   
   Returns:
     nil"
  [metrics-emitter error-type severity service]
  (when metrics-emitter
    ;; This would require the metrics component to be available
    ;; Implementation would depend on the metrics core functions
    (comment
      "Track error count, error rate, and severity distribution")))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn create-default-sensitive-keys
  "Creates a default set of sensitive keys to filter from error reports.
   
   Returns:
     Set of sensitive key names"
  []
  #{:password :token :secret :key :authorization :api-key
    :client-secret :private-key :credential :auth-token})

(defn merge-error-configs
  "Merges multiple error reporting configurations.
   
   Args:
     configs - Variable number of configuration maps
   
   Returns:
     Merged configuration map"
  [& configs]
  (apply merge-with (fn [a b] (if (coll? b) b a)) configs))

;; =============================================================================
;; Testing and Development Utilities
;; =============================================================================

(defn create-test-exception
  "Creates a test exception for development and testing.
   
   Args:
     message - Exception message
     cause   - Optional cause exception
   
   Returns:
     RuntimeException instance"
  ([message]
   (RuntimeException. message))
  ([message cause]
   (RuntimeException. message cause)))

(defn simulate-error-scenarios
  "Simulates various error scenarios for testing error reporting.
   
   Args:
     reporter - IErrorReporter instance
     context  - Base error context
   
   Returns:
     Map of scenario results"
  [reporter context]
  {:validation-error
   (report-validation-error reporter
                            [{:field "email" :message "Invalid format"}]
                            context)

   :application-error
   (report-application-error reporter
                             (create-test-exception "Test application error")
                             "Simulated application error"
                             context)

   :external-service-error
   (report-external-service-error reporter
                                  "payment-service"
                                  "process-payment"
                                  {:status 500 :message "Service unavailable"}
                                  context)})