(ns boundary.user.service-test
  "Integration tests for UserService layer.
   
   Tests service orchestration between core functions and repositories:
   - Service coordinates between core and persistence correctly
   - External dependencies (time, UUIDs, tokens) handled properly
   - Business rules are enforced
   - Error handling and exceptional cases"
  (:require [boundary.user.shell.service :as user-service]
            [boundary.user.ports :as ports]
            [boundary.logging.ports]
            [boundary.logging.core :as logging]
            [boundary.metrics.ports]
            [boundary.error-reporting.ports]
            [clojure.test :refer [deftest testing is]])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Mock Repositories
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
          ;; Apply role filter if provided
          filtered-users (if-let [role (:filter-role options)]
                           (filter #(= (:role %) role) users)
                           users)
          ;; Apply active filter if provided
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
      (let [updated-user (assoc user-entity :updated-at (Instant/now))]
        (swap! state assoc-in [:users (:id user-entity)] updated-user)
        updated-user)
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

  (hard-delete-user [_ user-id]
    (if (get-in @state [:users user-id])
      (do
        (swap! state update :users dissoc user-id)
        true)
      (throw (ex-info "User not found"
                      {:type :user-not-found
                       :user-id user-id}))))

  ;; Minimal implementations for other required methods
  (find-active-users-by-role [_ tenant-id role]
    (->> (get-in @state [:users])
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                       (= (:role %) role)
                       (nil? (:deleted-at %))))))

  (count-users-by-tenant [_ tenant-id]
    (->> (get-in @state [:users])
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                       (nil? (:deleted-at %))))
         count))

  (find-users-created-since [_ tenant-id since-date]
    (->> (get-in @state [:users])
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                       (.isAfter (:created-at %) since-date)
                       (nil? (:deleted-at %))))))

  (find-users-by-email-domain [_ tenant-id email-domain]
    (->> (get-in @state [:users])
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                       (.endsWith (:email %) (str "@" email-domain))
                       (nil? (:deleted-at %))))))

  (create-users-batch [_ user-entities]
    (doseq [user user-entities]
      (swap! state assoc-in [:users (:id user)] user))
    user-entities)

  (update-users-batch [_ user-entities]
    (doseq [user user-entities]
      (if (get-in @state [:users (:id user)])
        (swap! state assoc-in [:users (:id user)] user)
        (throw (ex-info "User not found in batch"
                        {:type :user-not-found
                         :user-id (:id user)}))))
    user-entities))

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

  (cleanup-expired-sessions [_ before-timestamp]
    (let [sessions (get-in @state [:sessions])
          expired (filter #(.isBefore (:expires-at (val %)) before-timestamp) sessions)
          count (count expired)]
      (doseq [[token _] expired]
        (swap! state update :sessions dissoc token))
      count))

  (update-session [_ session-entity]
    (if (get-in @state [:sessions (:session-token session-entity)])
      (do
        (swap! state assoc-in [:sessions (:session-token session-entity)] session-entity)
        session-entity)
      (throw (ex-info "Session not found"
                      {:type :session-not-found
                       :session-token (:session-token session-entity)}))))

  (find-all-sessions [_]
    (vals (get-in @state [:sessions])))

  (delete-session [_ session-id]
    (let [session (->> (get-in @state [:sessions])
                       (filter #(= (:id (val %)) session-id))
                       first)]
      (if session
        (do
          (swap! state update :sessions dissoc (key session))
          true)
        false))))

(defn create-mock-repositories
  []
  (let [state (atom {:users {} :sessions {}})]
    {:user-repository (->MockUserRepository state)
     :session-repository (->MockUserSessionRepository state)}))

(defn create-test-service-with-mocks
  "Create a UserService with mock repositories and observability services for testing."
  []
  (let [{:keys [user-repository session-repository]} (create-mock-repositories)
        ;; Create mock observability services for testing
        mock-logger (reify
                      boundary.logging.ports/ILogger
                      (log* [_ level message context exception] nil)
                      (trace [_ message] nil) (trace [_ message context] nil)
                      (debug [_ message] nil) (debug [_ message context] nil)
                      (info [_ message] nil) (info [_ message context] nil)
                      (warn [_ message] nil) (warn [_ message context] nil) (warn [_ message context exception] nil)
                      (error [_ message] nil) (error [_ message context] nil) (error [_ message context exception] nil)
                      (fatal [_ message] nil) (fatal [_ message context] nil) (fatal [_ message context exception] nil)

                      boundary.logging.ports/IAuditLogger
                      (audit-event [_ event-type actor resource action result context] nil)
                      (security-event [_ event-type severity details context] nil))
        mock-metrics (reify boundary.metrics.ports/IMetricsEmitter
                       (inc-counter! [_ handle] nil) (inc-counter! [_ handle value] nil) (inc-counter! [_ handle value tags] nil)
                       (set-gauge! [_ handle value] nil) (set-gauge! [_ handle value tags] nil)
                       (observe-histogram! [_ handle value] nil) (observe-histogram! [_ handle value tags] nil)
                       (observe-summary! [_ handle value] nil) (observe-summary! [_ handle value tags] nil)
                       (time-histogram! [_ handle f] (f)) (time-histogram! [_ handle tags f] (f))
                       (time-summary! [_ handle f] (f)) (time-summary! [_ handle tags f] (f)))
        mock-error-reporter (reify
                              boundary.error-reporting.ports/IErrorReporter
                              (capture-exception [_ exception] nil) (capture-exception [_ exception context] nil) (capture-exception [_ exception context tags] nil)
                              (capture-message [_ message level] nil) (capture-message [_ message level context] nil) (capture-message [_ message level context tags] nil)
                              (capture-event [_ event-map] nil)

                              boundary.error-reporting.ports/IErrorContext
                              (with-context [_ context-map f] (f))
                              (add-breadcrumb! [_ breadcrumb] nil)
                              (clear-breadcrumbs! [_] nil)
                              (set-user! [_ user-info] nil)
                              (set-tags! [_ tags] nil)
                              (set-extra! [_ extra] nil)
                              (current-context [_] {}))]
    (user-service/create-user-service user-repository session-repository
                                      mock-logger mock-metrics mock-error-reporter)))

(defn create-test-service-with-capturing-observability
  "Create a UserService with mock repositories and capturing observability services for testing."
  []
  (let [{:keys [user-repository session-repository]} (create-mock-repositories)
        metrics-state (atom [])
        gauge-state (atom [])
        mock-metrics (reify
                       boundary.metrics.ports/IMetricsEmitter
                       (inc-counter! [_ handle]
                         (swap! metrics-state conj {:name handle :value 1 :tags {}}))
                       (inc-counter! [_ handle value]
                         (swap! metrics-state conj {:name handle :value value :tags {}}))
                       (inc-counter! [_ handle value tags]
                         (swap! metrics-state conj {:name handle :value value :tags tags}))
                       (set-gauge! [_ handle value]
                         (swap! gauge-state conj {:name handle :value value :tags {}}))
                       (set-gauge! [_ handle value tags]
                         (swap! gauge-state conj {:name handle :value value :tags tags}))
                       (observe-histogram! [_ _ _] nil)
                       (observe-histogram! [_ _ _ _] nil)
                       (observe-summary! [_ _ _] nil)
                       (observe-summary! [_ _ _ _] nil)
                       (time-histogram! [_ handle f] (f))
                       (time-histogram! [_ handle _ f] (f))
                       (time-summary! [_ _ f] (f))
                       (time-summary! [_ _ _ f] (f)))
        breadcrumbs-state (atom [])
        mock-error-reporter (reify
                              boundary.error-reporting.ports/IErrorReporter
                              (capture-exception [_ exception]
                                (swap! breadcrumbs-state conj {:type :exception :exception exception})
                                nil)
                              (capture-exception [_ exception context]
                                (swap! breadcrumbs-state conj {:type :exception :exception exception :context context})
                                nil)
                              (capture-exception [_ exception context tags]
                                (swap! breadcrumbs-state conj {:type :exception :exception exception :context context :tags tags})
                                nil)
                              (capture-message [_ _ _] nil)
                              (capture-message [_ _ _ _] nil)
                              (capture-message [_ _ _ _ _] nil)
                              (capture-event [_ _] nil)

                              boundary.error-reporting.ports/IErrorContext
                              (with-context [_ _ f] (f))
                              (add-breadcrumb! [_ breadcrumb]
                                (swap! breadcrumbs-state conj breadcrumb))
                              (clear-breadcrumbs! [_] (reset! breadcrumbs-state []))
                              (set-user! [_ _] nil)
                              (set-tags! [_ _] nil)
                              (set-extra! [_ _] nil)
                              (current-context [_] {}))
        mock-logger (reify
                      boundary.logging.ports/ILogger
                      (log* [_ _ _ _ _] nil)
                      (trace [_ _] nil) (trace [_ _ _] nil)
                      (debug [_ _] nil) (debug [_ _ _] nil)
                      (info [_ _] nil) (info [_ _ _] nil)
                      (warn [_ _] nil) (warn [_ _ _] nil) (warn [_ _ _ _] nil)
                      (error [_ _] nil) (error [_ _ _] nil) (error [_ _ _ _] nil)
                      (fatal [_ _] nil) (fatal [_ _ _] nil) (fatal [_ _ _ _] nil)

                      boundary.logging.ports/IAuditLogger
                      (audit-event [_ _ _ _ _ _ _] nil)
                      (security-event [_ _ _ _ _] nil))
        service (user-service/create-user-service user-repository session-repository
                                                  mock-logger mock-metrics mock-error-reporter)]
    {:service service
     :metrics metrics-state
     :gauges gauge-state
     :breadcrumbs breadcrumbs-state}))

(defn create-noop-delete-service-with-capturing-observability
  "Create a UserService whose delete operations are no-ops, with capturing observability for testing."
  []
  (let [;; Repository that treats delete operations as no-ops (returns false)
        user-repository (reify
                          ports/IUserRepository
                          (find-user-by-id [_ _] nil)
                          (find-user-by-email [_ _ _] nil)
                          (find-users-by-tenant [_ _ _] {:users [] :total-count 0})
                          (create-user [_ user] user)
                          (update-user [_ user] user)
                          (soft-delete-user [_ _] false)
                          (hard-delete-user [_ _] false)
                          (find-active-users-by-role [_ _ _] [])
                          (count-users-by-tenant [_ _] 0)
                          (find-users-created-since [_ _ _] [])
                          (find-users-by-email-domain [_ _ _] [])
                          (create-users-batch [_ users] users)
                          (update-users-batch [_ users] users))
        ;; Session repository only needs to support find-all-sessions for gauge refresh
        session-state (atom {:sessions {}})
        session-repository (->MockUserSessionRepository session-state)
        {:keys [metrics gauges breadcrumbs]} (create-test-service-with-capturing-observability)
        ;; Recreate service with our custom user/session repositories but shared observability
        mock-metrics @metrics
        mock-gauges @gauges
        mock-breadcrumbs @breadcrumbs]
    (let [metrics-state (atom mock-metrics)
          gauge-state (atom mock-gauges)
          breadcrumbs-state (atom mock-breadcrumbs)
          mock-metrics-emitter (reify
                                 boundary.metrics.ports/IMetricsEmitter
                                 (inc-counter! [_ handle]
                                   (swap! metrics-state conj {:name handle :value 1 :tags {}}))
                                 (inc-counter! [_ handle value]
                                   (swap! metrics-state conj {:name handle :value value :tags {}}))
                                 (inc-counter! [_ handle value tags]
                                   (swap! metrics-state conj {:name handle :value value :tags tags}))
                                 (set-gauge! [_ handle value]
                                   (swap! gauge-state conj {:name handle :value value :tags {}}))
                                 (set-gauge! [_ handle value tags]
                                   (swap! gauge-state conj {:name handle :value value :tags tags}))
                                 (observe-histogram! [_ _ _] nil)
                                 (observe-histogram! [_ _ _ _] nil)
                                 (observe-summary! [_ _ _] nil)
                                 (observe-summary! [_ _ _ _] nil)
                                 (time-histogram! [_ _ f] (f))
                                 (time-histogram! [_ _ _ f] (f))
                                 (time-summary! [_ _ f] (f))
                                 (time-summary! [_ _ _ f] (f)))
          mock-error-reporter (reify
                                boundary.error-reporting.ports/IErrorReporter
                                (capture-exception [_ _] nil)
                                (capture-exception [_ _ _] nil)
                                (capture-exception [_ _ _ _] nil)
                                (capture-message [_ _ _] nil)
                                (capture-message [_ _ _ _] nil)
                                (capture-message [_ _ _ _ _] nil)
                                (capture-event [_ _] nil)

                                boundary.error-reporting.ports/IErrorContext
                                (with-context [_ _ f] (f))
                                (add-breadcrumb! [_ breadcrumb]
                                  (swap! breadcrumbs-state conj breadcrumb))
                                (clear-breadcrumbs! [_] (reset! breadcrumbs-state []))
                                (set-user! [_ _] nil)
                                (set-tags! [_ _] nil)
                                (set-extra! [_ _] nil)
                                (current-context [_] {}))
          mock-logger (reify
                        boundary.logging.ports/ILogger
                        (log* [_ _ _ _ _] nil)
                        (trace [_ _] nil) (trace [_ _ _] nil)
                        (debug [_ _] nil) (debug [_ _ _] nil)
                        (info [_ _] nil) (info [_ _ _] nil)
                        (warn [_ _] nil) (warn [_ _ _] nil) (warn [_ _ _ _] nil)
                        (error [_ _] nil) (error [_ _ _] nil) (error [_ _ _ _] nil)
                        (fatal [_ _] nil) (fatal [_ _ _] nil) (fatal [_ _ _ _] nil)

                        boundary.logging.ports/IAuditLogger
                        (audit-event [_ _ _ _ _ _ _] nil)
                        (security-event [_ _ _ _ _] nil))
          service (user-service/create-user-service user-repository session-repository
                                                    mock-logger mock-metrics-emitter mock-error-reporter)]
      {:service service
       :metrics metrics-state
       :gauges gauge-state
       :breadcrumbs breadcrumbs-state})))

;; =============================================================================
;; User Service Tests
;; =============================================================================

(deftest test-create-user
  (testing "Create user successfully with business rules"
    (let [service (create-test-service-with-mocks)
          tenant-id (UUID/randomUUID)
          user-data {:email "test@example.com"
                     :name "Test User"
                     :role :user
                     :tenant-id tenant-id}
          result (ports/register-user service user-data)]

      (is (some? (:id result)))
      (is (= "test@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (= :user (:role result)))
      (is (true? (:active result)))
      (is (some? (:created-at result)))
      (is (nil? (:updated-at result)))
      (is (nil? (:deleted-at result)))))

  (testing "Reject duplicate user creation"
    (let [service (create-test-service-with-mocks)
          tenant-id (UUID/randomUUID)
          user-data {:email "duplicate@example.com"
                     :name "First User"
                     :role :user
                     :tenant-id tenant-id}
          _ (ports/register-user service user-data)]

      ;; Attempt to create duplicate
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User already exists"
                            (ports/register-user service user-data)))))

  (testing "Validation error for invalid user data"
    (let [service (create-test-service-with-mocks)
          invalid-data {:email "not-an-email" ; Invalid email
                        :name "Test"
                        :role :invalid-role
                        :tenant-id (UUID/randomUUID)}]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid user data"
                            (ports/register-user service invalid-data))))))

(deftest test-find-user
  (testing "Find user by ID"
    (let [service (create-test-service-with-mocks)
          tenant-id (UUID/randomUUID)
          created-user (ports/register-user service
                                            {:email "find@example.com"
                                             :name "Find User"
                                             :role :user
                                             :tenant-id tenant-id})
          found-user (ports/get-user-by-id service (:id created-user))]

      (is (some? found-user))
      (is (= (:id created-user) (:id found-user)))
      (is (= "find@example.com" (:email found-user)))))

  (testing "Find user by email"
    (let [service (create-test-service-with-mocks)
          tenant-id (UUID/randomUUID)
          _ (ports/register-user service
                                 {:email "email@example.com"
                                  :name "Email User"
                                  :role :user
                                  :tenant-id tenant-id})
          found-user (ports/get-user-by-email service "email@example.com" tenant-id)]

      (is (some? found-user))
      (is (= "email@example.com" (:email found-user))))))

(deftest test-update-user
  (testing "Update user successfully"
    (let [service (create-test-service-with-mocks)
          tenant-id (UUID/randomUUID)
          created-user (ports/register-user service
                                            {:email "update@example.com"
                                             :name "Original Name"
                                             :role :user
                                             :tenant-id tenant-id})
          updated-user (assoc created-user :name "Updated Name" :role :admin)
          result (ports/update-user-profile service updated-user)]

      (is (= "Updated Name" (:name result)))
      (is (= :admin (:role result)))
      (is (some? (:updated-at result)))))

  (testing "Update non-existent user throws error"
    (let [service (create-test-service-with-mocks)
          fake-user {:id (UUID/randomUUID)
                     :email "fake@example.com"
                     :name "Fake"
                     :role :user
                     :active true
                     :tenant-id (UUID/randomUUID)
                     :created-at (Instant/now)}]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User not found"
                            (ports/update-user-profile service fake-user))))))

(deftest test-soft-delete-user
  (testing "Soft delete user successfully"
    (let [service (create-test-service-with-mocks)
          tenant-id (UUID/randomUUID)
          created-user (ports/register-user service
                                            {:email "delete@example.com"
                                             :name "Delete User"
                                             :role :user
                                             :tenant-id tenant-id})
          result (ports/deactivate-user service (:id created-user))]

      (is (true? result))

      ;; Verify user is marked as deleted
      (let [deleted-user (ports/get-user-by-id service (:id created-user))]
        (is (false? (:active deleted-user)))
        (is (some? (:deleted-at deleted-user))))))

  (testing "Soft delete non-existent user throws error"
    (let [service (create-test-service-with-mocks)
          fake-id (UUID/randomUUID)]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User not found"
                            (ports/deactivate-user service fake-id))))))

;; =============================================================================
;; Session Service Tests
;; =============================================================================

(deftest test-create-session
  (testing "Create session successfully"
    (let [service (create-test-service-with-mocks)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          session-data {:user-id user-id
                        :tenant-id tenant-id
                        :user-agent "Mozilla/5.0"
                        :ip-address "***********"}
          result (ports/authenticate-user service session-data)]

      (is (some? (:id result)))
      (is (some? (:session-token result)))
      (is (= user-id (:user-id result)))
      (is (= tenant-id (:tenant-id result)))
      (is (some? (:created-at result)))
      (is (some? (:expires-at result)))
      (is (nil? (:revoked-at result)))))

  (testing "Session has proper expiration"
    (let [service (create-test-service-with-mocks)
          session-data {:user-id (UUID/randomUUID)
                        :tenant-id (UUID/randomUUID)}
          result (ports/authenticate-user service session-data)
          now (Instant/now)]

      ;; Session should expire in the future (24 hours by default)
      (is (.isAfter (:expires-at result) now)))))

(deftest test-find-session-by-token
  (testing "Find valid session"
    (let [service (create-test-service-with-mocks)
          session (ports/authenticate-user service
                                           {:user-id (UUID/randomUUID)
                                            :tenant-id (UUID/randomUUID)})
          found-session (ports/validate-session service (:session-token session))]

      (is (some? found-session))
      (is (= (:id session) (:id found-session)))
      (is (= (:session-token session) (:session-token found-session)))))

  (testing "Invalid token returns nil"
    (let [service (create-test-service-with-mocks)
          found-session (ports/validate-session service "invalid-token-123")]

      (is (nil? found-session)))))

(deftest test-invalidate-session
  (testing "Invalidate session successfully"
    (let [service (create-test-service-with-mocks)
          session (ports/authenticate-user service
                                           {:user-id (UUID/randomUUID)
                                            :tenant-id (UUID/randomUUID)})
          result (ports/logout-user service (:session-token session))]

      (is (= true (:invalidated result)))
      (is (some? (:session-id result)))

      ;; Verify session is invalidated
      (let [invalidated-session (ports/validate-session service (:session-token session))]
        (is (nil? invalidated-session)))))

  (testing "Invalidate non-existent session"
    (let [service (create-test-service-with-mocks)
          result (ports/logout-user service "fake-token-123")]

      (is (= false (:invalidated result))))))

(deftest test-invalidate-all-user-sessions
  (testing "Invalidate all sessions for a user"
    (let [service (create-test-service-with-mocks)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          ;; Create multiple sessions for the same user
          session1 (ports/authenticate-user service {:user-id user-id :tenant-id tenant-id})
          session2 (ports/authenticate-user service {:user-id user-id :tenant-id tenant-id})
          session3 (ports/authenticate-user service {:user-id user-id :tenant-id tenant-id})
          result (ports/logout-user-everywhere service user-id)]

      (is (= 3 result))

      ;; Verify all sessions are invalidated
      (is (nil? (ports/validate-session service (:session-token session1))))
      (is (nil? (ports/validate-session service (:session-token session2))))
      (is (nil? (ports/validate-session service (:session-token session3))))))

  (testing "Invalidate sessions for user with no sessions"
    (let [service (create-test-service-with-mocks)
          user-id (UUID/randomUUID)
          result (ports/logout-user-everywhere service user-id)]

      (is (= 0 result)))))

(deftest test-update-active-sessions-gauge-logs-exceptions
  (testing "update-active-sessions-gauge logs exceptions via logging/log-exception"
    (let [called (atom nil)
          session-repository (reify
                               ports/IUserSessionRepository
                               (create-session [_ _] (throw (RuntimeException. "not-used")))
                               (find-session-by-token [_ _] nil)
                               (find-sessions-by-user [_ _] [])
                               (invalidate-session [_ _] false)
                               (invalidate-all-user-sessions [_ _] 0)
                               (cleanup-expired-sessions [_ _] 0)
                               (update-session [_ _] (throw (RuntimeException. "not-used")))
                               (find-all-sessions [_] (throw (RuntimeException. "session-repo-failure")))
                               (delete-session [_ _] false))
          logger :test-logger]
      (with-redefs [logging/log-exception (fn [logger' level message exception context]
                                            (reset! called {:logger logger'
                                                            :level level
                                                            :message message
                                                            :exception exception
                                                            :context context}))]
        (user-service/update-active-sessions-gauge logger nil session-repository))
      (is (= :test-logger (:logger @called)))
      (is (= :warn (:level @called)))
      (is (= "Failed to update active sessions gauge" (:message @called)))
      (is (= {:event :metrics :metric "active-sessions-gauge"}
             (select-keys (:context @called) [:event :metric])))
      (is (instance? Exception (:exception @called))))))

(deftest test-deactivate-user-metrics-and-breadcrumbs
  (testing "deactivate-user increments attempted and successful metrics on success and adds start/success breadcrumbs"
    (let [{:keys [service metrics breadcrumbs]} (create-test-service-with-capturing-observability)
          tenant-id (UUID/randomUUID)
          user (ports/register-user service {:email "metrics-deactivate@example.com"
                                             :name "Metrics Deactivate"
                                             :role :user
                                             :tenant-id tenant-id})
          user-id (:id user)]
      (reset! metrics [])
      (reset! breadcrumbs [])
      (let [result (ports/deactivate-user service user-id)
            recorded @metrics
            crumbs @breadcrumbs]
        (is (true? result))
        (is (some #(and (= "user-deactivations-attempted" (:name %))
                        (= 1 (:value %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-deactivations-successful" (:name %))
                        (= 1 (:value %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (let [messages (set (map :message crumbs))]
          (is (contains? messages "Starting user deactivation"))
          (is (contains? messages "User deactivation successful")))
        (let [start-crumb (first (filter #(= "Starting user deactivation" (:message %)) crumbs))
              success-crumb (first (filter #(= "User deactivation successful" (:message %)) crumbs))]
          (is (= "service.user" (:category start-crumb)))
          (is (= :info (:level start-crumb)))
          (is (= "service.user" (:category success-crumb)))
          (is (= :info (:level success-crumb)))))))
  (testing "deactivate-user increments attempted and failed(no-op) metrics and adds start/no-op breadcrumbs when repository returns no-op"
    (let [{:keys [service metrics breadcrumbs]} (create-noop-delete-service-with-capturing-observability)
          user-id (UUID/randomUUID)]
      (reset! metrics [])
      (reset! breadcrumbs [])
      (let [result (ports/deactivate-user service user-id)
            recorded @metrics
            crumbs @breadcrumbs]
        (is (false? result))
        (is (some #(and (= "user-deactivations-attempted" (:name %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-deactivations-failed" (:name %))
                        (= {:user-id user-id :reason "no-op"} (:tags %)))
                  recorded))
        (let [messages (set (map :message crumbs))]
          (is (contains? messages "Starting user deactivation"))
          (is (contains? messages "User deactivation no-op")))
        (let [noop-crumb (first (filter #(= "User deactivation no-op" (:message %)) crumbs))]
          (is (= "service.user" (:category noop-crumb)))
          (is (= :warning (:level noop-crumb)))))))
  (testing "deactivate-user increments attempted and failed(system-error) metrics when repository throws"
    (let [{:keys [service metrics]} (create-test-service-with-capturing-observability)
          fake-id (UUID/randomUUID)]
      (reset! metrics [])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User not found"
                            (ports/deactivate-user service fake-id)))
      (let [recorded @metrics]
        (is (some #(and (= "user-deactivations-attempted" (:name %))
                        (= {:user-id fake-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-deactivations-failed" (:name %))
                        (= {:user-id fake-id :reason "system-error"} (:tags %)))
                  recorded))))))

(deftest test-permanently-delete-user-metrics
  (testing "permanently-delete-user increments attempted and successful metrics on success"
    (let [{:keys [service metrics]} (create-test-service-with-capturing-observability)
          tenant-id (UUID/randomUUID)
          user (ports/register-user service {:email "delete-metrics@example.com"
                                             :name "Delete Metrics"
                                             :role :user
                                             :tenant-id tenant-id})
          user-id (:id user)]
      (reset! metrics [])
      (let [result (ports/permanently-delete-user service user-id)
            recorded @metrics]
        (is (true? result))
        (is (some #(and (= "user-deletions-attempted" (:name %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-deletions-successful" (:name %))
                        (= {:user-id user-id} (:tags %)))
                  recorded)))))
  (testing "permanently-delete-user increments attempted and failed(no-op) metrics when repository returns no-op"
    (let [{:keys [service metrics]} (create-noop-delete-service-with-capturing-observability)
          user-id (UUID/randomUUID)]
      (reset! metrics [])
      (let [result (ports/permanently-delete-user service user-id)
            recorded @metrics]
        (is (false? result))
        (is (some #(and (= "user-deletions-attempted" (:name %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-deletions-failed" (:name %))
                        (= {:user-id user-id :reason "no-op"} (:tags %)))
                  recorded)))))
  (testing "permanently-delete-user increments attempted and failed(system-error) metrics when repository throws"
    (let [{:keys [service metrics]} (create-test-service-with-capturing-observability)
          fake-id (UUID/randomUUID)]
      (reset! metrics [])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User not found"
                            (ports/permanently-delete-user service fake-id)))
      (let [recorded @metrics]
        (is (some #(and (= "user-deletions-attempted" (:name %))
                        (= {:user-id fake-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-deletions-failed" (:name %))
                        (= {:user-id fake-id :reason "system-error"} (:tags %)))
                  recorded))))))

(deftest test-logout-user-everywhere-metrics
  (testing "logout-user-everywhere increments attempted/successful metrics and tracks invalidated sessions"
    (let [{:keys [service metrics]} (create-test-service-with-capturing-observability)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)]
      ;; Create multiple sessions for the same user
      (ports/authenticate-user service {:user-id user-id :tenant-id tenant-id})
      (ports/authenticate-user service {:user-id user-id :tenant-id tenant-id})
      (ports/authenticate-user service {:user-id user-id :tenant-id tenant-id})
      (reset! metrics [])
      (let [invalidated-count (ports/logout-user-everywhere service user-id)
            recorded @metrics]
        (is (= 3 invalidated-count))
        (is (some #(and (= "user-logout-everywhere-attempted" (:name %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-logout-everywhere-successful" (:name %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))
        (is (some #(and (= "user-sessions-invalidated" (:name %))
                        (= 3 (:value %))
                        (= {:user-id user-id} (:tags %)))
                  recorded))))))
