(ns boundary.user.core.session
  "Functional Core - Pure session business logic.
   
   This namespace contains ONLY pure functions for session management:
   - No I/O operations (no database calls, no logging)
   - No external dependencies (time, random generation, etc.)
   - Deterministic behavior (same input always produces same output)
   - Immutable data structures only
   
   All business rules and domain logic for sessions belong here.
   The shell layer orchestrates I/O and calls these pure functions."
  (:require [boundary.user.schema :as schema]
            [malli.core :as m]))

;; =============================================================================
;; Session Creation Business Logic
;; =============================================================================

(defn validate-session-creation-request
  "Pure function: Validate session creation request.
   
   Args:
     session-data: Session creation request data
     
   Returns:
     {:valid? true :data session-data} or
     {:valid? false :errors validation-errors}
     
   Pure - schema validation only, no side effects."
  [session-data]
  ;; For now, use simple validation until CreateSessionRequest schema is fully defined
  (if (and (map? session-data)
           (:user-id session-data)
           (:tenant-id session-data))
    {:valid? true :data session-data}
    {:valid? false :errors {:user-id "required" :tenant-id "required"}}))

(defn calculate-session-expiry
  "Pure function: Calculate session expiry time based on policy.
   
   Args:
     current-time: Current time instant
     session-policy: Map with session duration rules
     
   Returns:
     Instant representing when session should expire
     
   Pure - time calculation based on input policy."
  [current-time session-policy]
  (let [duration-hours (get session-policy :duration-hours 24)]
    (.plusSeconds current-time (* duration-hours 3600))))

(defn prepare-session-for-creation
  "Pure function: Prepare session data for creation with business defaults.
   
   Args:
     session-data: Validated session creation request
     current-time: Current time instant
     session-id: UUID for new session
     session-token: Generated session token string
     session-policy: Session policy configuration
     
   Returns:
     Complete session entity ready for persistence
     
   Pure - takes all external dependencies as parameters."
  [session-data current-time session-id session-token session-policy]
  (-> session-data
      (assoc :id session-id)
      (assoc :session-token session-token)
      (assoc :created-at current-time)
      (assoc :expires-at (calculate-session-expiry current-time session-policy))
      (assoc :last-accessed-at nil)
      (assoc :revoked-at nil)))

;; =============================================================================
;; Session Validation Business Logic
;; =============================================================================

(defn is-session-valid?
  "Pure function: Check if session is valid based on business rules.
   
   Args:
     session: Session entity from database
     current-time: Current time instant
     
   Returns:
     {:valid? true} or
     {:valid? false :reason keyword :details map}
     
   Pure - validation logic based on session state and time."
  [session current-time]
  (cond
    (nil? session)
    {:valid? false :reason :session-not-found}
    
    (:revoked-at session)
    {:valid? false :reason :session-revoked :revoked-at (:revoked-at session)}
    
    (.isBefore (:expires-at session) current-time)
    {:valid? false :reason :session-expired :expires-at (:expires-at session)}
    
    :else
    {:valid? true}))

(defn should-update-access-time?
  "Pure function: Determine if session access time should be updated.
   
   Args:
     session: Current session entity
     current-time: Current time instant
     update-policy: Policy for access time updates
     
   Returns:
     Boolean indicating if access time should be updated
     
   Pure - policy evaluation based on time differences."
  [session current-time update-policy]
  (let [last-accessed (or (:last-accessed-at session) (:created-at session))
        minutes-since-access (/ (.between java.time.temporal.ChronoUnit/SECONDS
                                         last-accessed current-time) 60)
        update-threshold (:access-update-threshold-minutes update-policy 5)]
    (>= minutes-since-access update-threshold)))

(defn prepare-session-for-access-update
  "Pure function: Prepare session for access timestamp update.
   
   Args:
     session: Current session entity
     current-time: Current time instant
     
   Returns:
     Session entity with updated access time
     
   Pure - data transformation only."
  [session current-time]
  (assoc session :last-accessed-at current-time))

;; =============================================================================
;; Session Invalidation Business Logic
;; =============================================================================

(defn prepare-session-for-invalidation
  "Pure function: Prepare session for invalidation/logout.
   
   Args:
     session: Current session entity
     current-time: Current time instant
     
   Returns:
     Session entity marked as revoked
     
   Pure - data transformation only."
  [session current-time]
  (assoc session :revoked-at current-time))

(defn should-cleanup-session?
  "Pure function: Determine if expired session should be cleaned up.
   
   Args:
     session: Session entity
     current-time: Current time instant
     cleanup-policy: Cleanup policy configuration
     
   Returns:
     Boolean indicating if session should be deleted
     
   Pure - policy evaluation for cleanup decisions."
  [session current-time cleanup-policy]
  (let [grace-period-days (:cleanup-grace-period-days cleanup-policy 7)
        grace-period-seconds (* grace-period-days 24 3600)
        cleanup-threshold (.minusSeconds current-time grace-period-seconds)]
    (.isBefore (:expires-at session) cleanup-threshold)))

;; =============================================================================
;; Session Security Business Logic
;; =============================================================================

(defn validate-session-security-context
  "Pure function: Validate session security context against request.
   
   Args:
     session: Current session entity
     request-context: Map with :user-agent, :ip-address, etc.
     security-policy: Security validation policy
     
   Returns:
     {:valid? true} or
     {:valid? false :reason keyword :details map}
     
   Pure - security validation based on context comparison."
  [session request-context security-policy]
  (let [{:keys [user-agent ip-address]} request-context
        {:keys [strict-ip-validation? strict-user-agent-validation?]} security-policy]
    (cond
      ;; IP address validation
      (and strict-ip-validation? 
           (:ip-address session)
           (not= (:ip-address session) ip-address))
      {:valid? false 
       :reason :ip-address-mismatch
       :details {:session-ip (:ip-address session)
                :request-ip ip-address}}
      
      ;; User agent validation
      (and strict-user-agent-validation?
           (:user-agent session)
           (not= (:user-agent session) user-agent))
      {:valid? false
       :reason :user-agent-mismatch
       :details {:session-ua (:user-agent session)
                :request-ua user-agent}}
      
      :else
      {:valid? true})))

;; =============================================================================
;; Session Management Business Rules
;; =============================================================================

(defn should-extend-session?
  "Pure function: Determine if session should be extended based on activity.
   
   Args:
     session: Current session entity
     current-time: Current time instant
     extension-policy: Policy for session extension
     
   Returns:
     Boolean indicating if session should be extended
     
   Pure - policy evaluation for session extension."
  [session current-time extension-policy]
  (let [time-until-expiry (.between java.time.temporal.ChronoUnit/SECONDS
                                   current-time
                                   (:expires-at session))
        extension-threshold (* (:extend-threshold-hours extension-policy 2) 3600)]
    (< time-until-expiry extension-threshold)))

(defn calculate-extended-session-expiry
  "Pure function: Calculate new expiry time for session extension.
   
   Args:
     session: Current session entity
     current-time: Current time instant
     extension-policy: Extension policy configuration
     
   Returns:
     New expiry instant for extended session
     
   Pure - time calculation based on policy."
  [session current-time extension-policy]
  (let [extension-hours (:extension-hours extension-policy 24)]
    (.plusSeconds current-time (* extension-hours 3600))))

(defn prepare-session-for-extension
  "Pure function: Prepare session for expiry extension.
   
   Args:
     session: Current session entity
     current-time: Current time instant
     extension-policy: Extension policy
     
   Returns:
     Session entity with extended expiry time
     
   Pure - data transformation only."
  [session current-time extension-policy]
  (assoc session :expires-at (calculate-extended-session-expiry session current-time extension-policy)))

;; =============================================================================
;; Session Analysis Functions
;; =============================================================================

(defn analyze-session-usage
  "Pure function: Analyze session usage patterns.
   
   Args:
     sessions: Vector of session entities for a user
     analysis-period: Time period to analyze
     
   Returns:
     Map with usage analysis results
     
   Pure - data analysis based on input sessions."
  [sessions analysis-period]
  (let [active-sessions (filter #(nil? (:revoked-at %)) sessions)
        expired-sessions (filter #(and (nil? (:revoked-at %))
                                       (.isBefore (:expires-at %) (java.time.Instant/now))) sessions)
        total-sessions (count sessions)
        concurrent-sessions (count active-sessions)]
    {:total-sessions total-sessions
     :active-sessions concurrent-sessions
     :expired-sessions (count expired-sessions)
     :revoked-sessions (- total-sessions concurrent-sessions (count expired-sessions))
     :avg-session-duration-hours (if (> total-sessions 0)
                                   (/ (reduce + (map #(.between java.time.temporal.ChronoUnit/SECONDS
                                                               (:created-at %)
                                                               (or (:revoked-at %) (:expires-at %))) sessions))
                                      (* total-sessions 3600))
                                   0)}))

(defn categorize-session-by-duration
  "Pure function: Categorize session by its duration.
   
   Args:
     session: Session entity
     
   Returns:
     Keyword category (:short :medium :long :very-long)
     
   Pure - categorization based on session data."
  [session]
  (let [duration-hours (/ (.between java.time.temporal.ChronoUnit/SECONDS
                                   (:created-at session)
                                   (or (:revoked-at session) (:expires-at session))) 3600)]
    (cond
      (<= duration-hours 1) :short
      (<= duration-hours 8) :medium  
      (<= duration-hours 24) :long
      :else :very-long)))