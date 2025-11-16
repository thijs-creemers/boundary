(ns boundary.shared.core.interceptor
  "Core interceptor pipeline execution engine.
   
   Provides a lightweight interceptor chain pattern for handling cross-cutting
   concerns like logging, metrics, validation, and error handling in a
   declarative way.
   
   Interceptors have three lifecycle functions:
   - :enter - executed in forward order during pipeline execution
   - :leave - executed in reverse order during successful completion
   - :error - executed in reverse order when an exception occurs
   
   The context map is threaded through all interceptors and contains:
   - :request - original request data
   - :op - operation identifier (keyword)
   - :system - dependency injection map
   - :correlation-id - request correlation ID
   - :halt? - flag to short-circuit pipeline execution
   - other keys added by interceptors during execution"
  (:require [clojure.set :as set]))

(defn execute-interceptor-fn
  "Safely executes an interceptor function with error handling."
  [interceptor-fn ctx interceptor-name]
  (try
    (if interceptor-fn
      (interceptor-fn ctx)
      ctx)
    (catch Throwable t
      (throw (ex-info (str "Error in interceptor: " interceptor-name)
                      {:interceptor interceptor-name
                       :context-keys (keys ctx)
                       :original-exception (.getMessage t)}
                      t)))))

(defn run-enter-phase
  "Executes the :enter phase of interceptors in forward order.
   Returns [final-context executed-stack exception] where executed-stack contains
   interceptors that successfully executed their :enter function.
   If an exception occurs, it returns [partial-context partial-executed-stack exception]."
  [initial-ctx interceptors]
  (reduce
   (fn [[ctx executed exception] interceptor]
     (if (or exception (:halt? ctx))
       ;; Already halted or errored, just pass through
       [ctx executed exception]
       (let [{:keys [name enter]} interceptor]
         (try
           (let [new-ctx (execute-interceptor-fn enter ctx name)]
             [new-ctx (conj executed interceptor) nil])
           (catch Throwable t
             ;; Return current state with exception
             [ctx executed t])))))
   [initial-ctx [] nil]
   interceptors))

(defn run-leave-phase
  "Executes the :leave phase of interceptors in reverse order."
  [ctx executed-stack]
  (reduce (fn [current-ctx interceptor]
            (let [{:keys [name leave]} interceptor]
              (execute-interceptor-fn leave current-ctx name)))
          ctx
          (reverse executed-stack)))

(defn run-error-phase
  "Executes the :error phase of interceptors in reverse order when an exception occurs."
  [ctx executed-stack exception]
  (let [error-ctx (assoc ctx :exception exception)]
    (reduce (fn [current-ctx interceptor]
              (let [{:keys [name error]} interceptor]
                (execute-interceptor-fn error current-ctx name)))
            error-ctx
            (reverse executed-stack))))

(defn run-pipeline
  "Executes a pipeline of interceptors with proper lifecycle management.
   
   Args:
   - initial-ctx: Initial context map
   - interceptors: Vector of interceptor maps with :name, :enter, :leave, :error keys
   
   Returns:
   - Final context map after all interceptors have executed
   
   Execution flow:
   1. Execute :enter functions in forward order until halt or completion
   2. If successful, execute :leave functions in reverse order
   3. If exception occurs, execute :error functions in reverse order
   
   Short-circuiting:
   - Any interceptor can set :halt? true in context to stop forward execution
   - :leave functions will still execute for already-executed interceptors"
  [initial-ctx interceptors]
  (let [[ctx executed-stack exception] (run-enter-phase initial-ctx interceptors)]
    (if exception
      ;; Exception during enter phase - run error phase
      (run-error-phase ctx executed-stack exception)
      ;; Success - run leave phase
      (try
        (run-leave-phase ctx executed-stack)
        (catch Throwable t
          ;; Exception during leave phase - run error phase
          (run-error-phase ctx executed-stack t))))))

(defn validate-interceptor
  "Validates that an interceptor has the required structure."
  [interceptor]
  (when-not (map? interceptor)
    (throw (ex-info "Interceptor must be a map" {:interceptor interceptor})))

  (when-not (:name interceptor)
    (throw (ex-info "Interceptor must have a :name" {:interceptor interceptor})))

  (let [{:keys [enter leave error]} interceptor
        valid-keys #{:name :enter :leave :error}
        interceptor-keys (set (keys interceptor))
        invalid-keys (set/difference interceptor-keys valid-keys)]

    (when (seq invalid-keys)
      (throw (ex-info "Interceptor contains invalid keys"
                      {:interceptor interceptor
                       :invalid-keys invalid-keys
                       :valid-keys valid-keys})))

    (doseq [[phase-key phase-fn] [[:enter enter] [:leave leave] [:error error]]]
      (when (and phase-fn (not (fn? phase-fn)))
        (throw (ex-info (str "Interceptor " phase-key " must be a function")
                        {:interceptor interceptor
                         :phase phase-key
                         :value phase-fn})))))

  interceptor)

(defn validate-pipeline
  "Validates a pipeline of interceptors."
  [interceptors]
  (when-not (vector? interceptors)
    (throw (ex-info "Pipeline must be a vector" {:pipeline interceptors})))

  (doseq [interceptor interceptors]
    (validate-interceptor interceptor))

  ;; Check for duplicate names
  (let [names (map :name interceptors)
        duplicates (filter #(> (count (filter #{%} names)) 1) (distinct names))]
    (when (seq duplicates)
      (throw (ex-info "Pipeline contains duplicate interceptor names"
                      {:duplicates duplicates
                       :all-names names}))))

  interceptors)

(defn create-pipeline
  "Creates and validates a pipeline of interceptors."
  [interceptors]
  (validate-pipeline interceptors))

;; Utility functions for common interceptor patterns

(defn halt-pipeline
  "Helper function to halt pipeline execution with optional response data."
  ([ctx]
   (assoc ctx :halt? true))
  ([ctx response-data]
   (-> ctx
       (assoc :halt? true)
       (assoc :response response-data))))

(defn update-context
  "Helper function to safely update context with validation."
  [ctx key-path value]
  (if (vector? key-path)
    (assoc-in ctx key-path value)
    (assoc ctx key-path value)))

(defn get-from-context
  "Helper function to safely get values from context."
  ([ctx key-path]
   (if (vector? key-path)
     (get-in ctx key-path)
     (get ctx key-path)))
  ([ctx key-path default]
   (if (vector? key-path)
     (get-in ctx key-path default)
     (get ctx key-path default))))