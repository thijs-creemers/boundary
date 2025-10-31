(ns boundary.user.service-test
  "Integration tests for UserService layer.
   
   Tests service orchestration between core functions and repositories:
   - Service coordinates between core and persistence correctly
   - External dependencies (time, UUIDs, tokens) handled properly
   - Business rules are enforced
   - Error handling and exceptional cases"
  (:require [boundary.user.shell.service :as user-service]
            [boundary.user.ports :as ports]
            [clojure.test :refer [deftest testing is use-fixtures]])
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

;; =============================================================================
;; User Service Tests
;; =============================================================================

(deftest test-create-user
  (testing "Create user successfully with business rules"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          tenant-id (UUID/randomUUID)
          user-data {:email "test@example.com"
                    :name "Test User"
                    :role :user
                    :tenant-id tenant-id}
          result (ports/create-user service user-data)]
      
      (is (some? (:id result)))
      (is (= "test@example.com" (:email result)))
      (is (= "Test User" (:name result)))
      (is (= :user (:role result)))
      (is (true? (:active result)))
      (is (some? (:created-at result)))
      (is (nil? (:updated-at result)))
      (is (nil? (:deleted-at result)))))
  
  (testing "Reject duplicate user creation"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          tenant-id (UUID/randomUUID)
          user-data {:email "duplicate@example.com"
                    :name "First User"
                    :role :user
                    :tenant-id tenant-id}
          _ (ports/create-user service user-data)]
      
      ;; Attempt to create duplicate
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"User already exists"
                           (ports/create-user service user-data)))))
  
  (testing "Validation error for invalid user data"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          invalid-data {:email "not-an-email"  ; Invalid email
                       :name "Test"
                       :role :invalid-role
                       :tenant-id (UUID/randomUUID)}]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"Invalid user data"
                           (ports/create-user service invalid-data))))))

(deftest test-find-user
  (testing "Find user by ID"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          tenant-id (UUID/randomUUID)
          created-user (ports/create-user service
                                          {:email "find@example.com"
                                           :name "Find User"
                                           :role :user
                                           :tenant-id tenant-id})
          found-user (ports/find-user-by-id service (:id created-user))]
      
      (is (some? found-user))
      (is (= (:id created-user) (:id found-user)))
      (is (= "find@example.com" (:email found-user)))))
  
  (testing "Find user by email"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          tenant-id (UUID/randomUUID)
          _ (ports/create-user service
                               {:email "email@example.com"
                                :name "Email User"
                                :role :user
                                :tenant-id tenant-id})
          found-user (ports/find-user-by-email service "email@example.com" tenant-id)]
      
      (is (some? found-user))
      (is (= "email@example.com" (:email found-user))))))

(deftest test-update-user
  (testing "Update user successfully"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          tenant-id (UUID/randomUUID)
          created-user (ports/create-user service
                                          {:email "update@example.com"
                                           :name "Original Name"
                                           :role :user
                                           :tenant-id tenant-id})
          updated-user (assoc created-user :name "Updated Name" :role :admin)
          result (ports/update-user service updated-user)]
      
      (is (= "Updated Name" (:name result)))
      (is (= :admin (:role result)))
      (is (some? (:updated-at result)))))
  
  (testing "Update non-existent user throws error"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          fake-user {:id (UUID/randomUUID)
                    :email "fake@example.com"
                    :name "Fake"
                    :role :user
                    :tenant-id (UUID/randomUUID)}]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"User not found"
                           (ports/update-user service fake-user))))))

(deftest test-soft-delete-user
  (testing "Soft delete user successfully"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          tenant-id (UUID/randomUUID)
          created-user (ports/create-user service
                                          {:email "delete@example.com"
                                           :name "Delete User"
                                           :role :user
                                           :tenant-id tenant-id})
          result (ports/soft-delete-user service (:id created-user))]
      
      (is (true? result))
      
      ;; Verify user is marked as deleted
      (let [deleted-user (ports/find-user-by-id service (:id created-user))]
        (is (false? (:active deleted-user)))
        (is (some? (:deleted-at deleted-user))))))
  
  (testing "Soft delete non-existent user throws error"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          fake-id (UUID/randomUUID)]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"User not found"
                           (ports/soft-delete-user service fake-id))))))

;; =============================================================================
;; Session Service Tests
;; =============================================================================

(deftest test-create-session
  (testing "Create session successfully"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          session-data {:user-id user-id
                       :tenant-id tenant-id
                       :user-agent "Mozilla/5.0"
                       :ip-address "***********"}
          result (ports/create-session service session-data)]
      
      (is (some? (:id result)))
      (is (some? (:session-token result)))
      (is (= user-id (:user-id result)))
      (is (= tenant-id (:tenant-id result)))
      (is (some? (:created-at result)))
      (is (some? (:expires-at result)))
      (is (nil? (:revoked-at result)))))
  
  (testing "Session has proper expiration"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          session-data {:user-id (UUID/randomUUID)
                       :tenant-id (UUID/randomUUID)}
          result (ports/create-session service session-data)
          now (Instant/now)]
      
      ;; Session should expire in the future (24 hours by default)
      (is (.isAfter (:expires-at result) now)))))

(deftest test-find-session-by-token
  (testing "Find valid session"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          session (ports/create-session service
                                        {:user-id (UUID/randomUUID)
                                         :tenant-id (UUID/randomUUID)})
          found-session (ports/find-session-by-token service (:session-token session))]
      
      (is (some? found-session))
      (is (= (:id session) (:id found-session)))
      (is (= (:session-token session) (:session-token found-session)))))
  
  (testing "Invalid token returns nil"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          found-session (ports/find-session-by-token service "invalid-token-123")]
      
      (is (nil? found-session)))))

(deftest test-invalidate-session
  (testing "Invalidate session successfully"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          session (ports/create-session service
                                        {:user-id (UUID/randomUUID)
                                         :tenant-id (UUID/randomUUID)})
          result (ports/invalidate-session service (:session-token session))]
      
      (is (true? result))
      
      ;; Verify session is invalidated
      (let [invalidated-session (ports/find-session-by-token service (:session-token session))]
        (is (nil? invalidated-session)))))
  
  (testing "Invalidate non-existent session"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          result (ports/invalidate-session service "fake-token-123")]
      
      (is (false? result)))))

(deftest test-invalidate-all-user-sessions
  (testing "Invalidate all sessions for a user"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          ;; Create multiple sessions for the same user
          session1 (ports/create-session service {:user-id user-id :tenant-id tenant-id})
          session2 (ports/create-session service {:user-id user-id :tenant-id tenant-id})
          session3 (ports/create-session service {:user-id user-id :tenant-id tenant-id})
          result (ports/invalidate-all-user-sessions service user-id)]
      
      (is (= 3 result))
      
      ;; Verify all sessions are invalidated
      (is (nil? (ports/find-session-by-token service (:session-token session1))))
      (is (nil? (ports/find-session-by-token service (:session-token session2))))
      (is (nil? (ports/find-session-by-token service (:session-token session3))))))
  
  (testing "Invalidate sessions for user with no sessions"
    (let [{:keys [user-repository session-repository]} (create-mock-repositories)
          service (user-service/create-user-service user-repository session-repository)
          user-id (UUID/randomUUID)
          result (ports/invalidate-all-user-sessions service user-id)]
      
      (is (= 0 result)))))
