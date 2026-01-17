(ns notification.shared.retry
  "Retry logic with exponential backoff.
   
   All functions are PURE - no side effects.")

;; =============================================================================
;; Backoff Calculation
;; =============================================================================

(defn calculate-backoff
  "Calculate backoff delay for a given attempt number.
   
   Uses exponential backoff with jitter:
   delay = min(base * 2^attempt + jitter, max)
   
   Args:
     attempt      - Current attempt number (0-based)
     base-delay   - Base delay in milliseconds
     max-delay    - Maximum delay in milliseconds
   
   Returns:
     Delay in milliseconds"
  [attempt base-delay max-delay]
  (let [exponential (* base-delay (Math/pow 2 attempt))
        ;; Add 10% jitter
        jitter (* exponential 0.1 (rand))
        with-jitter (+ exponential jitter)]
    (long (min with-jitter max-delay))))

(defn should-retry?
  "Determine if operation should be retried.
   
   Args:
     attempts     - Number of attempts so far
     max-attempts - Maximum allowed attempts
     error-type   - Type of error (some may be non-retryable)
   
   Returns:
     true if should retry, false otherwise"
  [attempts max-attempts error-type]
  (and (< attempts max-attempts)
       ;; Some errors are not retryable
       (not (contains? #{:invalid-recipient
                         :template-not-found
                         :validation-error}
                       error-type))))

;; =============================================================================
;; Retry State
;; =============================================================================

(defn create-retry-state
  "Create initial retry state for an operation."
  []
  {:attempts 0
   :last-error nil
   :next-retry-at nil})

(defn update-retry-state
  "Update retry state after a failed attempt.
   
   Args:
     state       - Current retry state
     error       - Error from last attempt
     config      - Retry config with :base-delay-ms and :max-delay-ms
     now         - Current timestamp
   
   Returns:
     Updated retry state"
  [state error config now]
  (let [attempts (inc (:attempts state))
        backoff (calculate-backoff attempts 
                                   (:base-delay-ms config)
                                   (:max-delay-ms config))
        next-retry (.plusMillis now backoff)]
    {:attempts attempts
     :last-error error
     :next-retry-at next-retry}))

(defn ready-for-retry?
  "Check if operation is ready to be retried.
   
   Args:
     state - Retry state
     now   - Current timestamp
   
   Returns:
     true if ready for retry"
  [state now]
  (if-let [next-retry (:next-retry-at state)]
    (not (.isBefore now next-retry))
    true))

;; =============================================================================
;; Retry Result
;; =============================================================================

(defn retry-exhausted?
  "Check if all retries have been exhausted."
  [attempts max-attempts]
  (>= attempts max-attempts))

(defn format-retry-info
  "Format retry information for logging/display."
  [state config]
  {:attempts (:attempts state)
   :max-attempts (:max-attempts config)
   :last-error (:last-error state)
   :next-retry-at (some-> (:next-retry-at state) str)
   :exhausted? (retry-exhausted? (:attempts state) (:max-attempts config))})
