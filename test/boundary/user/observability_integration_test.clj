(ns boundary.user.observability-integration-test
  "Integration tests for UserService observability components.
   
   Tests complete user registration flow with metrics and logging capture:
   - Service coordinates between core and persistence correctly  
   - Logging calls are captured with proper context
   - Metrics are emitted for counters, histograms, and gauges
   - Business events and audit logs are properly recorded"
  (:require [boundary.user.shell.service :as user-service]
            [boundary.user.ports :as ports]
            [boundary.logging.ports :as logging-ports]
            [boundary.metrics.ports :as metrics-ports]
            [boundary.error-reporting.ports :as error-reporting-ports]
            [clojure.test :refer [deftest testing is]])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Mock Repositories (Reusing existing working pattern)
;; =============================================================================

(defrecord MockUserRepository [state]
  ports/IUserRepository

  (find-user-by-id [_ user-id]
    (get-in @state [:users user-id]))

  (find-user-by-email [_ email tenant-id]
    (->> (get-in @state [:users])
         vals
         (filter #(and (= (:email %) email)
                       (= (:tenant-id %) tenant-id)
                       (nil? (:deleted-at %))))
         first))

  (find-users-by-tenant [_ tenant-id options]
    (let [users (->> (get-in @state [:users])
                     vals
                     (filter #(= (:tenant-id %) tenant-id))
                     (filter #(nil? (:deleted-at %))))
          filtered-users (if-let [role (:filter-role options)]
                           (filter #(= (:role %) role) users)
                           users)
          final-users (if (contains? options :filter-active)
                        (filter #(= (:active %) (:filter-active options)) filtered-users)
                        filtered-users)
          total-count (count final-users)
          limit (or (:limit options) 20)
          offset (or (:offset options) 0)
          page (take limit (drop offset final-users))]
      {:users page
       :total-count total-count}))

  (create-user [_ user-entity]
    (swap! state assoc-in [:users (:id user-entity)] user-entity)
    user-entity)

  (update-user [_ user-entity]
    (if (get-in @state [:users (:id user-entity)])
      (do
        (swap! state assoc-in [:users (:id user-entity)] user-entity)
        user-entity)
      (throw (ex-info "User not found"
                      {:type :user-not-found
                       :user-id (:id user-entity)}))))

  (soft-delete-user [_ user-id]
    (if (get-in @state [:users user-id])
      (do
        (swap! state update-in [:users user-id]
               #(assoc % :deleted-at (Instant/now) :active false))
        true)
      (throw (ex-info "User not found"
                      {:type :user-not-found
                       :user-id user-id}))))

  ;; Minimal implementations for other required methods
  (hard-delete-user [_ user-id] true)
  (find-active-users-by-role [_ tenant-id role] [])
  (count-users-by-tenant [_ tenant-id] 0)
  (find-users-created-since [_ tenant-id since-date] [])
  (find-users-by-email-domain [_ tenant-id email-domain] [])
  (create-users-batch [_ user-entities] user-entities)
  (update-users-batch [_ user-entities] user-entities))

(defrecord MockUserSessionRepository [state]
  ports/IUserSessionRepository

  (create-session [_ session-entity]
    (swap! state assoc-in [:sessions (:session-token session-entity)] session-entity)
    session-entity)

  (find-session-by-token [_ session-token]
    (let [session (get-in @state [:sessions session-token])
          now (Instant/now)]
      (when (and session
                 (nil? (:revoked-at session))
                 (.isAfter (:expires-at session) now))
        session)))

  (find-sessions-by-user [_ user-id]
    (let [now (Instant/now)]
      (->> (get-in @state [:sessions])
           vals
           (filter #(and (= (:user-id %) user-id)
                         (nil? (:revoked-at %))
                         (.isAfter (:expires-at %) now))))))

  (invalidate-session [_ session-token]
    (if (get-in @state [:sessions session-token])
      (do
        (swap! state assoc-in [:sessions session-token :revoked-at] (Instant/now))
        true)
      false))

  (invalidate-all-user-sessions [_ user-id]
    (let [sessions (->> (get-in @state [:sessions])
                        (filter #(= (:user-id (val %)) user-id)))]
      (doseq [[token _] sessions]
        (swap! state assoc-in [:sessions token :revoked-at] (Instant/now)))
      (count sessions)))

  ;; Minimal implementations for other required methods
  (cleanup-expired-sessions [_ before-timestamp] 0)
  (update-session [_ session-entity] session-entity)
  (find-all-sessions [_] [])
  (delete-session [_ session-id] true))

;; =============================================================================
;; Simple Function-Based Observability Mocks
;; =============================================================================

(defn create-mock-logger
  "Creates a simple atom-based mock logger that captures all log calls."
  []
  (let [log-entries (atom [])]
    {:logger-instance
     (reify
       logging-ports/ILogger
       ;; Core low-level method
       (log* [_ level message context exception]
         (swap! log-entries conj {:level level :message message :context context :exception exception}))

       ;; Single-arity methods
       (trace [_ message]
         (swap! log-entries conj {:level :trace :message message :context nil}))
       (debug [_ message]
         (swap! log-entries conj {:level :debug :message message :context nil}))
       (info [_ message]
         (swap! log-entries conj {:level :info :message message :context nil}))
       (warn [_ message]
         (swap! log-entries conj {:level :warn :message message :context nil}))
       (error [_ message]
         (swap! log-entries conj {:level :error :message message :context nil}))
       (fatal [_ message]
         (swap! log-entries conj {:level :fatal :message message :context nil}))

       ;; Two-arity methods with context
       (trace [_ message context]
         (swap! log-entries conj {:level :trace :message message :context context}))
       (debug [_ message context]
         (swap! log-entries conj {:level :debug :message message :context context}))
       (info [_ message context]
         (swap! log-entries conj {:level :info :message message :context context}))
       (warn [_ message context]
         (swap! log-entries conj {:level :warn :message message :context context}))
       (error [_ message context]
         (swap! log-entries conj {:level :error :message message :context context}))
       (fatal [_ message context]
         (swap! log-entries conj {:level :fatal :message message :context context}))

       ;; Three-arity methods with context and exception
       (warn [_ message context exception]
         (swap! log-entries conj {:level :warn :message message :context context :exception exception}))
       (error [_ message context exception]
         (swap! log-entries conj {:level :error :message message :context context :exception exception}))
       (fatal [_ message context exception]
         (swap! log-entries conj {:level :fatal :message message :context context :exception exception}))

       logging-ports/IAuditLogger
       ;; Audit event logging
       (audit-event [_ event-type actor resource action result context]
         (swap! log-entries conj {:type :audit-event
                                  :event-type event-type
                                  :actor actor
                                  :resource resource
                                  :action action
                                  :result result
                                  :context context}))

       ;; Security event logging
       (security-event [_ event-type severity details context]
         (swap! log-entries conj {:type :security-event
                                  :event-type event-type
                                  :severity severity
                                  :details details
                                  :context context})))

     :log-entries log-entries}))

(defn create-mock-metrics
  "Creates a simple atom-based mock metrics emitter that captures all metric calls."
  []
  (let [metrics-calls (atom [])]
    {:metrics-instance
     (reify metrics-ports/IMetricsEmitter
       ;; Counter methods
       (inc-counter! [_ metric-name]
         (swap! metrics-calls conj {:type :counter :metric metric-name :value 1 :tags nil}))
       (inc-counter! [_ metric-name value]
         (swap! metrics-calls conj {:type :counter :metric metric-name :value value :tags nil}))
       (inc-counter! [_ metric-name value tags]
         (swap! metrics-calls conj {:type :counter :metric metric-name :value value :tags tags}))

       ;; Gauge methods
       (set-gauge! [_ metric-name value]
         (swap! metrics-calls conj {:type :gauge :metric metric-name :value value :tags nil}))
       (set-gauge! [_ metric-name value tags]
         (swap! metrics-calls conj {:type :gauge :metric metric-name :value value :tags tags}))

       ;; Histogram methods
       (observe-histogram! [_ metric-name value]
         (swap! metrics-calls conj {:type :histogram :metric metric-name :value value :tags nil}))
       (observe-histogram! [_ metric-name value tags]
         (swap! metrics-calls conj {:type :histogram :metric metric-name :value value :tags tags}))

       ;; Summary methods
       (observe-summary! [_ metric-name value]
         (swap! metrics-calls conj {:type :summary :metric metric-name :value value :tags nil}))
       (observe-summary! [_ metric-name value tags]
         (swap! metrics-calls conj {:type :summary :metric metric-name :value value :tags tags}))

       ;; Timer methods
       (time-histogram! [_ metric-name f]
         (let [start-time (System/nanoTime)
               result (f)
               duration-ns (- (System/nanoTime) start-time)
               duration-ms (/ duration-ns 1000000.0)]
           (swap! metrics-calls conj {:type :histogram :metric metric-name :value duration-ms :tags nil})
           result))
       (time-histogram! [_ metric-name tags f]
         (let [start-time (System/nanoTime)
               result (f)
               duration-ns (- (System/nanoTime) start-time)
               duration-ms (/ duration-ns 1000000.0)]
           (swap! metrics-calls conj {:type :histogram :metric metric-name :value duration-ms :tags tags})
           result))

       (time-summary! [_ metric-name f]
         (let [start-time (System/nanoTime)
               result (f)
               duration-ns (- (System/nanoTime) start-time)
               duration-ms (/ duration-ns 1000000.0)]
           (swap! metrics-calls conj {:type :summary :metric metric-name :value duration-ms :tags nil})
           result))
       (time-summary! [_ metric-name tags f]
         (let [start-time (System/nanoTime)
               result (f)
               duration-ns (- (System/nanoTime) start-time)
               duration-ms (/ duration-ns 1000000.0)]
           (swap! metrics-calls conj {:type :summary :metric metric-name :value duration-ms :tags tags})
           result)))

     :metrics-calls metrics-calls}))

(defn create-mock-error-reporter
  "Creates a simple atom-based mock error reporter that captures all error reporting calls."
  []
  (let [error-reports (atom [])]
    {:error-reporter-instance
     (reify error-reporting-ports/IErrorReporter
       (capture-exception [_ exception]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj {:type :exception :exception exception :context nil :tags nil :event-id event-id})
           event-id))
       (capture-exception [_ exception context]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj {:type :exception :exception exception :context context :tags nil :event-id event-id})
           event-id))
       (capture-exception [_ exception context tags]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj {:type :exception :exception exception :context context :tags tags :event-id event-id})
           event-id))
       (capture-message [_ message level]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj {:type :message :message message :level level :context nil :tags nil :event-id event-id})
           event-id))
       (capture-message [_ message level context]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj {:type :message :message message :level level :context context :tags nil :event-id event-id})
           event-id))
       (capture-message [_ message level context tags]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj {:type :message :message message :level level :context context :tags tags :event-id event-id})
           event-id))
       (capture-event [_ event-map]
         (let [event-id (str (UUID/randomUUID))]
           (swap! error-reports conj (assoc event-map :event-id event-id))
           event-id))

       error-reporting-ports/IErrorContext
       (with-context [_ context-map f]
         (f))
       (add-breadcrumb! [_ breadcrumb]
         (swap! error-reports conj {:type :breadcrumb :breadcrumb breadcrumb}))
       (clear-breadcrumbs! [_]
         nil)
       (set-user! [_ user-info]
         (swap! error-reports conj {:type :set-user :user-info user-info}))
       (set-tags! [_ tags]
         (swap! error-reports conj {:type :set-tags :tags tags}))
       (set-extra! [_ extra]
         (swap! error-reports conj {:type :set-extra :extra extra}))
       (current-context [_]
         {}))

     :error-reports error-reports}))

(defn create-mock-repositories
  []
  (let [state (atom {:users {} :sessions {}})]
    {:user-repository (->MockUserRepository state)
     :session-repository (->MockUserSessionRepository state)}))

;; =============================================================================
;; Observability Integration Tests
;; =============================================================================

(deftest test-user-registration-observability
  (testing "Complete user registration flow captures all observability data"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          {:keys [logger-instance log-entries]} (create-mock-logger)
          {:keys [metrics-instance metrics-calls]} (create-mock-metrics)
          {:keys [error-reporter-instance error-reports]} (create-mock-error-reporter)
          service (user-service/create-user-service user-repository session-repository logger-instance metrics-instance error-reporter-instance)
          tenant-id (UUID/randomUUID)
          user-data {:email "observability@example.com"
                     :name "Observability Test User"
                     :role :user
                     :tenant-id tenant-id}]

      ;; Perform user registration
      (let [result (ports/register-user service user-data)]

        ;; Verify the user was created successfully
        (is (some? (:id result)))
        (is (= "observability@example.com" (:email result)))
        (is (= "Observability Test User" (:name result)))
        (is (= :user (:role result)))
        (is (true? (:active result)))

        ;; Verify metrics were captured
        (let [captured-metrics @metrics-calls]
          (println "\\n=== CAPTURED METRICS ===")
          (doseq [metric captured-metrics]
            (println metric))

          ;; Check for expected metrics
          (is (some #(and (= (:type %) :counter)
                          (= (:metric %) "user-registrations-attempted")) captured-metrics)
              "Should capture user-registrations-attempted counter")

          (is (some #(and (= (:type %) :counter)
                          (= (:metric %) "user-registrations-successful")) captured-metrics)
              "Should capture user-registrations-successful counter")

          (is (some #(and (= (:type %) :histogram)
                          (= (:metric %) "user-operation-duration")) captured-metrics)
              "Should capture user-operation-duration histogram"))

        ;; Verify logs were captured  
        (let [captured-logs @log-entries]
          (println "\\n=== CAPTURED LOGS ===")
          (doseq [log captured-logs]
            (println log))

          ;; Check for expected log entries
          (is (some #(and (= (:level %) :info)
                          (re-find #"Registering user" (:message %))) captured-logs)
              "Should capture user registration info log")

          (is (some #(and (= (:level %) :debug)
                          (or (re-find #"Entering register-user" (:message %))
                              (re-find #"Exiting register-user" (:message %)))) captured-logs)
              "Should capture debug logs during registration process"))))))

(deftest test-user-registration-failure-observability
  (testing "Failed user registration also captures observability data"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          {:keys [logger-instance log-entries]} (create-mock-logger)
          {:keys [metrics-instance metrics-calls]} (create-mock-metrics)
          {:keys [error-reporter-instance error-reports]} (create-mock-error-reporter)
          service (user-service/create-user-service user-repository session-repository logger-instance metrics-instance error-reporter-instance)
          tenant-id (UUID/randomUUID)
          invalid-data {:email "not-an-email" ; Invalid email format
                        :name "Test"
                        :role :invalid-role ; Invalid role
                        :tenant-id tenant-id}]

      ;; Attempt user registration with invalid data
      (is (thrown? Exception
                   (ports/register-user service invalid-data)))

      ;; Verify metrics were still captured for the attempt
      (let [captured-metrics @metrics-calls]
        (println "\\n=== FAILURE METRICS ===")
        (doseq [metric captured-metrics]
          (println metric))

        (is (some #(and (= (:type %) :counter)
                        (= (:metric %) "user-registrations-attempted")) captured-metrics)
            "Should capture user-registrations-attempted even on failure"))

      ;; Verify error logs were captured
      (let [captured-logs @log-entries]
        (println "\\n=== FAILURE LOGS ===")
        (doseq [log captured-logs]
          (println log))

        (is (some #(or (= (:level %) :error)
                       (= (:level %) :warn)) captured-logs)
            "Should capture error or warning logs on validation failure")))))

(deftest test-session-creation-observability
  (testing "Session creation captures metrics and logs"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          {:keys [logger-instance log-entries]} (create-mock-logger)
          {:keys [metrics-instance metrics-calls]} (create-mock-metrics)
          {:keys [error-reporter-instance error-reports]} (create-mock-error-reporter)
          service (user-service/create-user-service user-repository session-repository logger-instance metrics-instance error-reporter-instance)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          session-data {:user-id user-id
                        :tenant-id tenant-id
                        :user-agent "Test Browser"
                        :ip-address "192.168.1.1"}]

      ;; Create session
      (let [result (ports/authenticate-user service session-data)]

        ;; Verify session was created
        (is (some? (:id result)))
        (is (some? (:session-token result)))
        (is (= user-id (:user-id result)))

        ;; Check metrics
        (let [captured-metrics @metrics-calls]
          (println "\\n=== SESSION METRICS ===")
          (doseq [metric captured-metrics]
            (println metric))

          ;; Should have session-related metrics
          (is (some #(re-find #"session" (:metric %)) captured-metrics)
              "Should capture session-related metrics"))

        ;; Check logs
        (let [captured-logs @log-entries]
          (println "\\n=== SESSION LOGS ===")
          (doseq [log captured-logs]
            (println log))

          ;; Should have session-related logs
          (is (some #(re-find #"[Ss]ession" (:message %)) captured-logs)
              "Should capture session-related logs"))))))