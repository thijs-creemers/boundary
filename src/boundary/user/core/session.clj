(ns boundary.user.core.session
  "Functional Core - Pure session business logic - Minimal working version"
  (:require [clojure.string :as str]))

;; Basic session validation
(defn validate-session-creation-request
  [session-data]
  (if (and (map? session-data) (:user-id session-data))
    {:valid? true :data session-data}
    {:valid? false :errors {:user-id "required"}}))

(defn is-session-valid?
  [session current-time]
  (cond
    (nil? session) {:valid? false :reason :not-found}
    (:revoked-at session) {:valid? false :reason :inactive}
    (.isBefore (:expires-at session) current-time) {:valid? false :reason :expired}
    :else {:valid? true}))

;; Session creation helpers  
(defn generate-session-token
  []
  (str "token-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn calculate-session-expiry
  ([created-at remember-me?]
   (let [hours (if remember-me? (* 30 24) 24)]
     (.plusSeconds created-at (* hours 3600))))
  ([created-at remember-me? custom-hours]
   (let [hours (or custom-hours (if remember-me? (* 30 24) 24))]
     (.plusSeconds created-at (* hours 3600)))))

(defn prepare-session-for-creation
  ([session-data current-time session-id token]
   (-> session-data
       (assoc :id session-id)
       (assoc :session-token token)
       (assoc :created-at current-time)
       (assoc :last-accessed-at current-time)
       (assoc :expires-at (calculate-session-expiry current-time (get session-data :remember-me false)))
       (assoc :active true)
       (assoc :ip-address (get-in session-data [:device-info :ip-address]))
       (assoc :user-agent (get-in session-data [:device-info :user-agent]))))
  ([session-data current-time session-id token session-policy]
   (let [duration-hours (get session-policy :duration-hours 24)]
     (-> session-data
         (assoc :id session-id)
         (assoc :session-token token)
         (assoc :created-at current-time)
         (assoc :last-accessed-at current-time)
         (assoc :expires-at (.plusSeconds current-time (* duration-hours 3600)))
         (assoc :active true)
         (assoc :ip-address (get-in session-data [:device-info :ip-address]))
         (assoc :user-agent (get-in session-data [:device-info :user-agent]))))))

;; Session management
(defn should-extend-session?
  [session current-time extension-policy]
  (let [time-until-expiry (.between java.time.temporal.ChronoUnit/SECONDS
                                    current-time (:expires-at session))
        extension-threshold (* (get extension-policy :extend-threshold-hours 2) 3600)]
    (< time-until-expiry extension-threshold)))

(defn update-session-access
  [session current-time]
  (let [updated-session (assoc session :last-accessed-at current-time)]
    (if (should-extend-session? session current-time {:extend-threshold-hours 2})
      (assoc updated-session :expires-at (calculate-session-expiry current-time false))
      updated-session)))

(defn should-cleanup-session?
  [session current-time cleanup-policy]
  (let [grace-period-days (get cleanup-policy :cleanup-grace-period-days 7)
        grace-period-seconds (* grace-period-days 24 3600)
        cleanup-threshold (.minusSeconds current-time grace-period-seconds)]
    (.isBefore (:expires-at session) cleanup-threshold)))

(defn mark-session-for-cleanup
  [session current-time]
  (assoc session :active false))

(defn filter-sessions-for-cleanup
  [sessions current-time]
  (filter #(should-cleanup-session? % current-time {:cleanup-grace-period-days 7}) sessions))

;; Security functions
(defn detect-suspicious-activity?
  [session previous-sessions]
  (let [ip-changes (and (seq previous-sessions)
                        (not= (:ip-address session) (:ip-address (first previous-sessions))))
        concurrent-sessions (> (count previous-sessions) 0)
        reasons (cond-> []
                  ip-changes (conj :ip-change)
                  concurrent-sessions (conj :concurrent-sessions))]
    {:suspicious? (seq reasons)
     :reasons reasons}))

(defn calculate-session-risk-score
  [session previous-sessions]
  (let [suspicious-result (detect-suspicious-activity? session previous-sessions)
        base-score 0.1
        ip-change-score (if (some #(= % :ip-change) (:reasons suspicious-result)) 0.4 0)
        concurrent-score (if (some #(= % :concurrent-sessions) (:reasons suspicious-result)) 0.3 0)]
    (+ base-score ip-change-score concurrent-score)))

(defn should-require-additional-verification?
  [session previous-sessions]
  (> (calculate-session-risk-score session previous-sessions) 0.5))

;; Analytics functions
(defn analyze-session-duration
  [session]
  (let [start-time (:created-at session)
        end-time (or (:last-accessed-at session) (java.time.Instant/now))
        duration-hours (/ (.between java.time.temporal.ChronoUnit/SECONDS start-time end-time) 3600.0)]
    {:session-id (:id session)
     :duration-hours duration-hours}))

(defn group-sessions-by-device-type
  [sessions]
  (group-by #(cond
               (.contains (:user-agent %) "Mobile") :mobile
               (.contains (:user-agent %) "Desktop") :desktop
               :else :other) sessions))

(defn calculate-user-session-stats
  [sessions user-id]
  (let [user-sessions (filter #(= (:user-id %) user-id) sessions)
        active-sessions (filter :active user-sessions)
        inactive-sessions (filter #(not (:active %)) user-sessions)]
    {:user-id user-id
     :total-sessions (count user-sessions)
     :active-sessions (count active-sessions)
     :inactive-sessions (count inactive-sessions)}))

;; Device management
(defn extract-device-info
  [user-agent ip-address]
  {:device-type (cond
                  (.contains user-agent "iPhone") :mobile
                  (.contains user-agent "Mobile") :mobile
                  :else :desktop)
   :ip-address ip-address
   :user-agent user-agent})

(defn is-same-device?
  [device1 device2]
  (and (= (:ip-address device1) (:ip-address device2))
       (or (= (:user-agent device1) (:user-agent device2))
           ;; Allow minor version differences in user agent
           (let [ua1-base (clojure.string/replace (:user-agent device1) #"/[0-9.]+" "")
                 ua2-base (clojure.string/replace (:user-agent device2) #"/[0-9.]+" "")]
             (= ua1-base ua2-base)))))

;; =============================================================================
;; Additional Service Layer Functions
;; =============================================================================

(defn should-update-access-time?
  "Pure function: Determine if session access time should be updated.
   
   Args:
     session: Current session entity
     current-time: Current timestamp
     update-policy: Map with access update configuration
     
   Returns:
     Boolean indicating if access time should be updated
     
   Pure - business rule evaluation based on policy and time difference."
  [session current-time update-policy]
  (let [threshold-minutes (get update-policy :access-update-threshold-minutes 5)
        threshold-seconds (* threshold-minutes 60)
        last-accessed (:last-accessed-at session)
        time-since-access (.between java.time.temporal.ChronoUnit/SECONDS
                                    last-accessed current-time)]
    (>= time-since-access threshold-seconds)))

(defn prepare-session-for-access-update
  "Pure function: Prepare session for access time update.
   
   Args:
     session: Current session entity
     current-time: Current timestamp
     
   Returns:
     Session entity with updated access time
     
   Pure - data transformation only."
  [session current-time]
  (assoc session :last-accessed-at current-time))

(defn prepare-session-for-invalidation
  "Pure function: Prepare session for invalidation.
   
   Args:
     session: Current session entity
     current-time: Current timestamp
     
   Returns:
     Session entity marked as revoked
     
   Pure - data transformation only."
  [session current-time]
  (-> session
      (assoc :revoked-at current-time)
      (assoc :active false)))
