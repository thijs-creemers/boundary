(ns boundary.user.ports-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [boundary.user.ports :as ports]
    [boundary.user.schema :as schema]
    [clj-time.core :as time])
  (:import (java.util UUID)))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(defn thrown-with-msg?
  "Tests that a particular exception type is thrown with a message matching a pattern.

   Args:
     exception-class: Class of exception to expect
     msg-pattern: Regex pattern or string to match against exception message
     form: Form to evaluate that should throw the exception

   Returns:
     Boolean indicating if the expected exception with matching message was thrown"
  [exception-class msg-pattern form]
  (try
    (eval form)
    false
    (catch Exception e
      (and (instance? exception-class e)
           (if (instance? java.util.regex.Pattern msg-pattern)
             (re-find msg-pattern (.getMessage e))
             (.contains (.getMessage e) (str msg-pattern)))))))

;; =============================================================================
;; Test Data Fixtures
;; =============================================================================

(def test-tenant-id (UUID/fromString "123e4567-e89b-12d3-a456-426614174000"))
(def test-user-id (UUID/fromString "987fcdeb-51a2-43d7-b123-456789abcdef"))
(def test-session-token "abc123def456ghi789")

(def sample-user
  {:id test-user-id
   :email "test@example.com"
   :name "Test User"
   :role :user
   :active true
   :tenant-id test-tenant-id
   :created-at (time/now)})

(def sample-session
  {:id (UUID/randomUUID)
   :user-id test-user-id
   :tenant-id test-tenant-id
   :session-token test-session-token
   :expires-at (time/plus (time/now) (time/hours 24))
   :created-at (time/now)})

;; =============================================================================
;; Mock Port Implementations for Testing
;; =============================================================================

(defrecord MockUserRepository [users sessions]
  ports/IUserRepository
  
  (find-user-by-id [_ user-id]
    (get @users user-id))
  
  (find-user-by-email [_ email tenant-id]
    (->> @users
         vals
         (filter #(and (= (:email %) email)
                      (= (:tenant-id %) tenant-id)
                      (:active %)))
         first))
  
  (find-users-by-tenant [_ tenant-id options]
    (let [users-for-tenant (->> @users
                               vals
                               (filter #(= (:tenant-id %) tenant-id))
                               (filter :active))
          limit (get options :limit 20)
          offset (get options :offset 0)
          filtered-users (if-let [role-filter (:filter-role options)]
                          (filter #(= (:role %) role-filter) users-for-tenant)
                          users-for-tenant)
          paginated-users (->> filtered-users
                              (drop offset)
                              (take limit)
                              vec)]
      {:users paginated-users
       :total-count (count filtered-users)}))
  
  (create-user [_ user-entity]
    (let [new-user (assoc user-entity
                         :id (UUID/randomUUID)
                         :created-at (time/now)
                         :updated-at (time/now))]
      (swap! users assoc (:id new-user) new-user)
      new-user))
  
  (update-user [_ user-entity]
    (let [updated-user (assoc user-entity :updated-at (time/now))]
      (swap! users assoc (:id updated-user) updated-user)
      updated-user))
  
  (soft-delete-user [_ user-id]
    (when-let [user (get @users user-id)]
      (swap! users assoc user-id (assoc user :deleted-at (time/now)))
      true))
  
  (hard-delete-user [_ user-id]
    (swap! users dissoc user-id)
    true)
  
  (find-active-users-by-role [_ tenant-id role]
    (->> @users
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                      (= (:role %) role)
                      (:active %)
                      (nil? (:deleted-at %))))
         vec))
  
  (count-users-by-tenant [_ tenant-id]
    (->> @users
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                      (:active %)
                      (nil? (:deleted-at %))))
         count))
  
  (find-users-created-since [_ tenant-id since-date]
    (->> @users
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                      (time/after? (:created-at %) since-date)))
         vec))
  
  (find-users-by-email-domain [_ tenant-id email-domain]
    (->> @users
         vals
         (filter #(and (= (:tenant-id %) tenant-id)
                      (.endsWith (:email %) email-domain)))
         vec))
  
  (create-users-batch [_ user-entities]
    (mapv #(ports/create-user % %) user-entities))
  
  (update-users-batch [_ user-entities]
    (mapv #(ports/update-user % %) user-entities)))

(defrecord MockUserSessionRepository [sessions]
  ports/IUserSessionRepository
  
  (create-session [_ session-entity]
    (let [new-session (assoc session-entity
                            :id (UUID/randomUUID)
                            :session-token (or (:session-token session-entity)
                                               (.toString (UUID/randomUUID)))
                            :created-at (time/now))]
      (swap! sessions assoc (:session-token new-session) new-session)
      new-session))
  
  (find-session-by-token [_ session-token]
    (when-let [session (get @sessions session-token)]
      (when (time/after? (:expires-at session) (time/now))
        (let [updated-session (assoc session :last-accessed-at (time/now))]
          (swap! sessions assoc session-token updated-session)
          updated-session))))
  
  (find-sessions-by-user [_ user-id]
    (->> @sessions
         vals
         (filter #(and (= (:user-id %) user-id)
                      (time/after? (:expires-at %) (time/now))
                      (nil? (:revoked-at %))))
         vec))
  
  (invalidate-session [_ session-token]
    (when (get @sessions session-token)
      (swap! sessions update session-token assoc :revoked-at (time/now))
      true))
  
  (invalidate-all-user-sessions [_ user-id]
    (let [user-sessions (->> @sessions
                            vals
                            (filter #(= (:user-id %) user-id)))]
      (doseq [session user-sessions]
        (swap! sessions update (:session-token session) assoc :revoked-at (time/now)))
      (count user-sessions)))
  
  (cleanup-expired-sessions [_ before-timestamp]
    (let [expired-sessions (->> @sessions
                               vals
                               (filter #(time/before? (:expires-at %) before-timestamp)))]
      (doseq [session expired-sessions]
        (swap! sessions dissoc (:session-token session)))
      (count expired-sessions))))

;; =============================================================================
;; Port Contract Tests
;; =============================================================================

(deftest user-repository-protocol-test
  (let [users (atom {})
        repo (->MockUserRepository users (atom {}))]
    
    (testing "create-user generates ID and timestamps"
      (let [user-input {:email "new@example.com"
                       :name "New User"
                       :role :user
                       :active true
                       :tenant-id test-tenant-id}
            created-user (ports/create-user repo user-input)]
        (is (uuid? (:id created-user)))
        (is (inst? (:created-at created-user)))
        (is (inst? (:updated-at created-user)))
        (is (= "new@example.com" (:email created-user)))
        (is (= test-tenant-id (:tenant-id created-user)))))
    
    (testing "find-user-by-id returns created user"
      (let [created-user (ports/create-user repo sample-user)
            found-user (ports/find-user-by-id repo (:id created-user))]
        (is (= created-user found-user))))
    
    (testing "find-user-by-email with tenant isolation"
      (let [created-user (ports/create-user repo sample-user)
            found-user (ports/find-user-by-email repo (:email created-user) test-tenant-id)]
        (is (= created-user found-user)))
      
      (testing "returns nil for different tenant"
        (let [different-tenant-id (UUID/randomUUID)]
          (is (nil? (ports/find-user-by-email repo "test@example.com" different-tenant-id))))))
    
    (testing "update-user updates timestamp"
      (let [created-user (ports/create-user repo sample-user)
            updated-user (ports/update-user repo (assoc created-user :name "Updated Name"))]
        (is (= "Updated Name" (:name updated-user)))
        (is (time/after? (:updated-at updated-user) (:created-at updated-user)))))
    
    (testing "soft-delete-user marks user as deleted"
      (let [created-user (ports/create-user repo sample-user)]
        (is (true? (ports/soft-delete-user repo (:id created-user))))
        (let [deleted-user (get @users (:id created-user))]
          (is (inst? (:deleted-at deleted-user))))))
    
    (testing "hard-delete-user removes user completely"
      (let [created-user (ports/create-user repo sample-user)]
        (is (true? (ports/hard-delete-user repo (:id created-user))))
        (is (nil? (get @users (:id created-user))))))
    
    (testing "find-users-by-tenant with pagination"
      (let [_user1 (ports/create-user repo (assoc sample-user :email "user1@example.com"))
            _user2 (ports/create-user repo (assoc sample-user :email "user2@example.com" :role :admin))
            result (ports/find-users-by-tenant repo test-tenant-id {:limit 10 :offset 0})]
        (is (= 2 (count (:users result))))
        (is (= 2 (:total-count result)))
        
        (testing "with role filter"
          (let [admin-result (ports/find-users-by-tenant repo test-tenant-id {:filter-role :admin})]
            (is (= 1 (count (:users admin-result))))
            (is (= :admin (-> admin-result :users first :role)))))))
    
    (testing "find-active-users-by-role"
      (let [_admin-user (ports/create-user repo (assoc sample-user :email "admin@example.com" :role :admin))
            _regular-user (ports/create-user repo (assoc sample-user :email "user@example.com" :role :user))
            admin-users (ports/find-active-users-by-role repo test-tenant-id :admin)]
        (is (= 1 (count admin-users)))
        (is (= :admin (-> admin-users first :role)))))
    
    (testing "count-users-by-tenant"
      (let [_user1 (ports/create-user repo (assoc sample-user :email "count1@example.com"))
            _user2 (ports/create-user repo (assoc sample-user :email "count2@example.com"))
            count (ports/count-users-by-tenant repo test-tenant-id)]
        (is (>= count 2))))
    
    (testing "find-users-created-since"
      (let [yesterday (time/minus (time/now) (time/days 1))
            _user (ports/create-user repo (assoc sample-user :email "recent@example.com"))
            recent-users (ports/find-users-created-since repo test-tenant-id yesterday)]
        (is (some #(= (:email %) "recent@example.com") recent-users))))
    
    (testing "find-users-by-email-domain"
      (let [_company-user (ports/create-user repo (assoc sample-user :email "employee@company.com"))
            domain-users (ports/find-users-by-email-domain repo test-tenant-id "company.com")]
        (is (some #(= (:email %) "employee@company.com") domain-users))))))

(deftest user-session-repository-protocol-test
  (let [sessions (atom {})
        session-repo (->MockUserSessionRepository sessions)]
    
    (testing "create-session generates ID and token"
      (let [session-input {:user-id test-user-id
                          :tenant-id test-tenant-id
                          :expires-at (time/plus (time/now) (time/hours 24))}
            created-session (ports/create-session session-repo session-input)]
        (is (uuid? (:id created-session)))
        (is (string? (:session-token created-session)))
        (is (inst? (:created-at created-session)))
        (is (= test-user-id (:user-id created-session)))))
    
    (testing "find-session-by-token returns active session"
      (let [created-session (ports/create-session session-repo sample-session)
            found-session (ports/find-session-by-token session-repo (:session-token created-session))]
        (is (= (:user-id created-session) (:user-id found-session)))
        (is (inst? (:last-accessed-at found-session)))))
    
    (testing "find-session-by-token returns nil for expired session"
      (let [expired-session (assoc sample-session :expires-at (time/minus (time/now) (time/hours 1)))
            created-session (ports/create-session session-repo expired-session)]
        (is (nil? (ports/find-session-by-token session-repo (:session-token created-session))))))
    
    (testing "find-sessions-by-user returns active sessions"
      (let [_session1 (ports/create-session session-repo sample-session)
            _session2 (ports/create-session session-repo (assoc sample-session :session-token "different-token"))
            user-sessions (ports/find-sessions-by-user session-repo test-user-id)]
        (is (= 2 (count user-sessions)))
        (is (every? #(= (:user-id %) test-user-id) user-sessions))))
    
    (testing "invalidate-session marks session as revoked"
      (let [created-session (ports/create-session session-repo sample-session)]
        (is (true? (ports/invalidate-session session-repo (:session-token created-session))))
        (let [revoked-session (get @sessions (:session-token created-session))]
          (is (inst? (:revoked-at revoked-session))))))
    
    (testing "invalidate-all-user-sessions revokes all user sessions"
      (let [_session1 (ports/create-session session-repo sample-session)
            _session2 (ports/create-session session-repo (assoc sample-session :session-token "another-token"))
            revoked-count (ports/invalidate-all-user-sessions session-repo test-user-id)]
        (is (= 2 revoked-count))
        (is (every? #(inst? (:revoked-at %)) (vals @sessions)))))
    
    (testing "cleanup-expired-sessions removes old sessions"
      (let [old-session (assoc sample-session :expires-at (time/minus (time/now) (time/days 2)))
            _ (ports/create-session session-repo old-session)
            cleanup-cutoff (time/minus (time/now) (time/days 1))
            cleaned-count (ports/cleanup-expired-sessions session-repo cleanup-cutoff)]
        (is (>= cleaned-count 1))))))

(deftest port-utility-functions-test
  (testing "validate-user-input with valid data"
    (let [valid-input {:email "test@example.com"
                      :name "Test User"
                      :role "user"
                      :active "true"
                      :tenantId (str test-tenant-id)}
          result (ports/validate-user-input schema/CreateUserRequest valid-input)]
      (is (:valid? result))
      (is (= :user (-> result :data :role)))
      (is (= true (-> result :data :active)))
      (is (contains? (:data result) :tenant-id))))
  
  (testing "validate-user-input with invalid data"
    (let [invalid-input {:name "Test User"
                        :role "user"}
          result (ports/validate-user-input schema/CreateUserRequest invalid-input)]
      (is (false? (:valid? result)))
      (is (some? (:errors result)))))
  
  (testing "ensure-tenant-isolation with valid UUID"
    (let [result (ports/ensure-tenant-isolation test-tenant-id)]
      (is (= test-tenant-id result))))
  
  (testing "ensure-tenant-isolation throws on nil"
    (is (thrown-with-msg? Exception #"Tenant ID is required"
                         (ports/ensure-tenant-isolation nil))))
  
  (testing "ensure-tenant-isolation throws on invalid format"
    (is (thrown-with-msg? Exception #"Invalid tenant ID format"
                         (ports/ensure-tenant-isolation "not-a-uuid"))))
  
  (testing "create-correlation-id generates string UUID"
    (let [correlation-id (ports/create-correlation-id)]
      (is (string? correlation-id))
      (is (uuid? (UUID/fromString correlation-id)))))
  
  (testing "enrich-user-context adds context data"
    (let [user-data {:email "test@example.com" :name "Test User"}
          context {:tenant-id test-tenant-id
                  :correlation-id "test-correlation-id"
                  :timestamp (time/now)}
          enriched (ports/enrich-user-context user-data context)]
      (is (= test-tenant-id (:tenant-id enriched)))
      (is (= "test-correlation-id" (:correlation-id enriched)))
      (is (= (:timestamp context) (:updated-at enriched)))
      (is (= "test@example.com" (:email enriched))))))

;; =============================================================================
;; Port Protocol Compliance Tests
;; =============================================================================

(deftest port-protocol-compliance-test
  (testing "IUserRepository protocol methods exist"
    (let [repo (->MockUserRepository (atom {}) (atom {}))]
      (is (satisfies? ports/IUserRepository repo))
      (is (some? (resolve 'boundary.user.ports/find-user-by-id)))
      (is (some? (resolve 'boundary.user.ports/find-user-by-email)))
      (is (some? (resolve 'boundary.user.ports/create-user)))
      (is (some? (resolve 'boundary.user.ports/update-user)))
      (is (some? (resolve 'boundary.user.ports/soft-delete-user)))
      (is (some? (resolve 'boundary.user.ports/hard-delete-user)))))
  
  (testing "IUserSessionRepository protocol methods exist"
    (let [session-repo (->MockUserSessionRepository (atom {}))]
      (is (satisfies? ports/IUserSessionRepository session-repo))
      (is (some? (resolve 'boundary.user.ports/create-session)))
      (is (some? (resolve 'boundary.user.ports/find-session-by-token)))
      (is (some? (resolve 'boundary.user.ports/invalidate-session)))))
  
  (testing "Service protocols are defined"
    (is (some? (resolve 'boundary.user.ports/IEmailService)))
    (is (some? (resolve 'boundary.user.ports/INotificationService)))
    (is (some? (resolve 'boundary.user.ports/IEventBus)))
    (is (some? (resolve 'boundary.user.ports/IAuditService)))
    (is (some? (resolve 'boundary.user.ports/ITimeService)))))

;; =============================================================================
;; Port Documentation and Contract Tests
;; =============================================================================

(deftest port-documentation-test
  (testing "protocol docstrings are comprehensive"
    (let [user-repo-meta (meta #'ports/IUserRepository)
          session-repo-meta (meta #'ports/IUserSessionRepository)]
      (is (string? (:doc user-repo-meta)))
      (is (string? (:doc session-repo-meta)))
      (is (> (count (:doc user-repo-meta)) 100))
      (is (> (count (:doc session-repo-meta)) 100))))
  
  (testing "method signatures are well-documented"
    (is (string? (:doc (meta #'ports/find-user-by-id))))
    (is (string? (:doc (meta #'ports/create-session))))
    (is (contains? (meta #'ports/find-user-by-id) :arglists))
    (is (contains? (meta #'ports/create-session) :arglists))))

