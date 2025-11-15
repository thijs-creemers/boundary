(ns boundary.shared.core.interceptors
  "Universal interceptors for cross-cutting concerns.
   
   These interceptors handle common infrastructure concerns like logging,
   metrics, error handling, and context management across all modules."
  (:require [boundary.shared.core.interceptor :as interceptor]
            [boundary.logging.ports :as logging]
            [boundary.metrics.ports :as metrics]
            [boundary.error-reporting.ports :as error-reporting])
  (:import [java.util UUID]
           [java.time Instant]))

;; Context Management Interceptors

(def context-interceptor
  "Establishes basic context for request processing.
   
   Adds:
   - :correlation-id - UUID for request tracing
   - :now - Current timestamp
   - Ensures :op is present for operation identification"
  {:name :context
   :enter (fn [ctx]
            (-> ctx
                (assoc :correlation-id
                       (or (get-in ctx [:request :headers "x-correlation-id"])
                           (get-in ctx [:request :correlation-id])
                           (str (UUID/randomUUID))))
                (assoc :now (Instant/now))
                (update :op #(or % :unknown-operation))))})

;; Logging Interceptors

(def logging-start
  "Logs the start of an operation with structured data."
  {:name :logging-start
   :enter (fn [{:keys [op correlation-id system] :as ctx}]
            (when-let [logger (:logger system)]
              (logging/info logger "operation-start"
                            {:op op
                             :correlation-id correlation-id
                             :timestamp (:now ctx)}))
            ctx)})

(def logging-complete
  "Logs successful completion of an operation with timing information."
  {:name :logging-complete
   :leave (fn [{:keys [op correlation-id system] :as ctx}]
            (when-let [logger (:logger system)]
              (let [duration-ms (when-let [start (get-in ctx [:timing :start])]
                                  (/ (- (System/nanoTime) start) 1e6))
                    success? (= :success (get-in ctx [:result :status]))
                    log-data {:op op
                              :correlation-id correlation-id
                              :duration-ms duration-ms
                              :status (if success? "success" "completed-with-errors")
                              :effect-errors (count (:effect-errors ctx))}]
                (if success?
                  (logging/info logger "operation-success" log-data)
                  (logging/warn logger "operation-completed-with-errors" log-data))))
            ctx)})

(def logging-error
  "Logs operation failures with error details."
  {:name :logging-error
   :error (fn [{:keys [op correlation-id system exception] :as ctx}]
            (when-let [logger (:logger system)]
              (logging/error logger "operation-error"
                             {:op op
                              :correlation-id correlation-id
                              :error-message (ex-message exception)
                              :error-type (type exception)}))
            ctx)})

;; Metrics Interceptors

(def metrics-start
  "Records operation attempt and starts timing measurement."
  {:name :metrics-start
   :enter (fn [{:keys [op system] :as ctx}]
            (when-let [metric-collector (:metrics system)]
              (metrics/increment metric-collector (str (name op) ".attempt") {}))
            (assoc-in ctx [:timing :start] (System/nanoTime)))})

(def metrics-complete
  "Records operation completion metrics including success/failure and latency."
  {:name :metrics-complete
   :leave (fn [{:keys [op system] :as ctx}]
            (when-let [metric-collector (:metrics system)]
              (let [start-time (get-in ctx [:timing :start])
                    duration-ms (when start-time
                                  (/ (- (System/nanoTime) start-time) 1e6))
                    success? (= :success (get-in ctx [:result :status]))
                    op-name (name op)]

                ;; Record latency
                (when duration-ms
                  (metrics/observe metric-collector (str op-name ".latency.ms") duration-ms {}))

                ;; Record success/failure
                (if success?
                  (metrics/increment metric-collector (str op-name ".success") {})
                  (metrics/increment metric-collector (str op-name ".error") {}))))
            ctx)})

(def metrics-error
  "Records operation failure metrics when exceptions occur."
  {:name :metrics-error
   :error (fn [{:keys [op system] :as ctx}]
            (when-let [metric-collector (:metrics system)]
              (metrics/increment metric-collector (str (name op) ".failure") {}))
            ctx)})

;; Error Handling Interceptors

(def error-capture
  "Captures exceptions in error reporting system."
  {:name :error-capture
   :error (fn [{:keys [op correlation-id system exception] :as ctx}]
            (when-let [error-reporter (:error-reporter system)]
              (error-reporting/capture-exception
               error-reporter
               exception
               {:operation (name op)
                :correlation-id correlation-id
                :context-keys (keys (dissoc ctx :system :exception))}))
            ctx)})

(def error-normalize
  "Normalizes exceptions into standard error response format."
  {:name :error-normalize
   :error (fn [{:keys [correlation-id exception] :as ctx}]
            (assoc ctx :response
                   {:status 500
                    :body {:type "internal-server-error"
                           :title "Internal Server Error"
                           :status 500
                           :detail "An unexpected error occurred while processing your request"
                           :correlation-id correlation-id
                           :timestamp (.toString (Instant/now))}}))})

;; Effects Execution Interceptor

(def effects-dispatch
  "Executes side effects returned by core functions.
   
   Processes the :effects vector from core function results and executes
   each effect, collecting any errors for later handling."
  {:name :effects-dispatch
   :enter (fn [ctx]
            (if-let [effects (get-in ctx [:result :effects])]
              (reduce
               (fn [acc-ctx effect]
                 (try
                   (case (:type effect)
                     :persist-user
                     (do
                       (when-let [repo (get-in acc-ctx [:system :user-repository])]
                          ;; Execute persistence effect
                         (.create-user repo (:user effect)))
                       acc-ctx)

                     :send-welcome-email
                     (do
                       (when-let [notification-service (get-in acc-ctx [:system :notification-service])]
                          ;; Execute notification effect
                         (.send-welcome-email notification-service (:user effect)))
                       acc-ctx)

                     :send-notification
                     (do
                       (when-let [notification-service (get-in acc-ctx [:system :notification-service])]
                         (.send-notification notification-service (:notification effect)))
                       acc-ctx)

                      ;; Default: log unknown effect type
                     (do
                       (when-let [logger (get-in acc-ctx [:system :logger])]
                         (logging/warn logger "unknown-effect-type"
                                       {:effect-type (:type effect)
                                        :effect effect}))
                       acc-ctx))
                   (catch Throwable e
                     (update acc-ctx :effect-errors (fnil conj [])
                             {:effect effect
                              :error (ex-message e)
                              :error-type (type e)}))))
               (assoc ctx :effect-errors [])
               effects)
              ;; No effects to process
              ctx))})

;; Request/Response Transformation Interceptors

(def response-shape-http
  "Shapes successful results into HTTP response format."
  {:name :response-shape-http
   :leave (fn [ctx]
            (if (:response ctx)
              ;; Response already set (probably by validation or error)
              ctx
              ;; Shape successful result
              (let [result (:result ctx)]
                (case (:status result)
                  :success
                  (assoc ctx :response
                         {:status 201
                          :body (:data result)
                          :headers {"X-Correlation-ID" (:correlation-id ctx)}})

                  :error
                  (assoc ctx :response
                         {:status 400
                          :body {:type "domain-error"
                                 :title "Business Rule Violation"
                                 :status 400
                                 :errors (:errors result)
                                 :correlation-id (:correlation-id ctx)
                                 :timestamp (.toString (:now ctx))}
                          :headers {"X-Correlation-ID" (:correlation-id ctx)}})

                  ;; Default case
                  (assoc ctx :response
                         {:status 500
                          :body {:type "unknown-result-status"
                                 :title "Unknown Result Status"
                                 :status 500
                                 :correlation-id (:correlation-id ctx)}
                          :headers {"X-Correlation-ID" (:correlation-id ctx)}})))))})

(def response-shape-cli
  "Shapes results into CLI response format with exit codes.
   
   This interceptor handles both :result format (with :status/:data/:errors)
   and :response format (direct CLI response) to maintain compatibility."
  {:name :response-shape-cli
   :leave (fn [ctx]
            (if (:response ctx)
              ;; Response already set by domain-specific interceptors
              ctx
              ;; Shape result for CLI (fallback)
              (let [result (:result ctx)]
                (case (:status result)
                  :success
                  (assoc ctx :response
                         {:exit 0
                          :stdout (str "Success: " (pr-str (:data result)))})

                  :error
                  (assoc ctx :response
                         {:exit 1
                          :stderr (str "Error: " (pr-str (:errors result)))})

                  ;; Default case
                  (assoc ctx :response
                         {:exit 2
                          :stderr "Unknown error occurred"})))))})

;; Common Pipeline Templates

(def base-observability-pipeline
  "Base pipeline with essential observability interceptors.
   Suitable for most operations that don't need specialized handling."
  [context-interceptor
   logging-start
   metrics-start
   logging-complete
   metrics-complete])

(def error-handling-pipeline
  "Error handling interceptors for exception management."
  [error-capture
   logging-error
   metrics-error
   error-normalize])

(def http-response-pipeline
  "HTTP-specific pipeline with request/response handling."
  (conj base-observability-pipeline response-shape-http))

(def cli-response-pipeline
  "CLI-specific pipeline with command/response handling."
  (conj base-observability-pipeline response-shape-cli))

;; Pipeline Assembly Utilities

(defn create-http-pipeline
  "Creates a complete HTTP pipeline with custom interceptors inserted at the appropriate point."
  [& custom-interceptors]
  (vec (concat [context-interceptor
                logging-start
                metrics-start]
               custom-interceptors
               [effects-dispatch
                logging-complete
                metrics-complete
                response-shape-http])))

(defn create-cli-pipeline
  "Creates a complete CLI pipeline with custom interceptors."
  [& custom-interceptors]
  (vec (concat [context-interceptor
                logging-start
                metrics-start]
               custom-interceptors
               [effects-dispatch
                logging-complete
                metrics-complete
                response-shape-cli])))

(defn add-error-handling
  "Adds error handling interceptors to any pipeline."
  [pipeline]
  (vec (concat pipeline error-handling-pipeline)))