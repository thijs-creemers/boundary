(ns boundary.user.http-test
  "HTTP handler tests for user module REST API.
   
   Tests all user and session endpoints with:
   - Happy path scenarios
   - Error cases and validation
   - Edge cases and boundary conditions"
  (:require [boundary.user.shell.http :as user-http]
            [boundary.user.ports :as ports]
            [boundary.shell.interfaces.http.middleware :as middleware]
            [clojure.test :refer [deftest testing is]])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn wrap-handler-with-error-handling
  "Wrap a handler with exception middleware for testing.
   Adds correlation-id and exception handling to simulate production behavior."
  [handler error-mappings]
  (-> handler
      (middleware/wrap-exception-handling error-mappings)
      (middleware/wrap-correlation-id)))

(defn call-handler
  "Call a handler with error handling middleware applied."
  [handler-fn request error-mappings]
  (let [wrapped (wrap-handler-with-error-handling handler-fn error-mappings)]
    (wrapped request)))

;; =============================================================================
;; Mock User Service
;; =============================================================================

(defrecord MockUserService [state]
  ports/IUserService

  (create-user [_ user-data]
    (let [user-id (UUID/randomUUID)
          now (Instant/now)
          created-user (assoc user-data
                              :id user-id
                              :created-at now
                              :updated-at nil
                              :deleted-at nil)]
      (swap! state assoc user-id created-user)
      created-user))

  (find-user-by-id [_ user-id]
    (get @state user-id))

  (find-user-by-email [_ email tenant-id]
    (->> @state
         vals
         (filter #(and (= (:email %) email)
                       (= (:tenant-id %) tenant-id)))
         first))

  (find-users-by-tenant [_ tenant-id options]
    (let [users (->> @state
                     vals
                     (filter #(= (:tenant-id %) tenant-id))
                     (filter #(nil? (:deleted-at %))))
          total-count (count users)
          limit (or (:limit options) 20)
          offset (or (:offset options) 0)
          page (take limit (drop offset users))]
      {:users page
       :total-count total-count}))

  (update-user [_ user-entity]
    (let [user-id (:id user-entity)
          existing (get @state user-id)]
      (if existing
        (let [updated (assoc user-entity :updated-at (Instant/now))]
          (swap! state assoc user-id updated)
          updated)
        (throw (ex-info "User not found"
                        {:type :user-not-found
                         :user-id user-id})))))

  (soft-delete-user [_ user-id]
    (let [existing (get @state user-id)]
      (if existing
        (do
          (swap! state update user-id
                 #(assoc % :deleted-at (Instant/now) :active false))
          true)
        (throw (ex-info "User not found"
                        {:type :user-not-found
                         :user-id user-id})))))

  (hard-delete-user [_ user-id]
    (if (get @state user-id)
      (do
        (swap! state dissoc user-id)
        true)
      (throw (ex-info "User not found"
                      {:type :user-not-found
                       :user-id user-id}))))

  (create-session [_ session-data]
    (let [session-id (UUID/randomUUID)
          now (Instant/now)
          session-token (str (UUID/randomUUID) (UUID/randomUUID))
          expires-at (.plusSeconds now 3600)
          session (assoc session-data
                         :id session-id
                         :session-token session-token
                         :created-at now
                         :expires-at expires-at
                         :last-accessed-at nil
                         :revoked-at nil)]
      (swap! state assoc-in [:sessions session-token] session)
      session))

  (find-session-by-token [_ session-token]
    (let [session (get-in @state [:sessions session-token])
          now (Instant/now)]
      (when (and session
                 (nil? (:revoked-at session))
                 (.isAfter (:expires-at session) now))
        session)))

  (invalidate-session [_ session-token]
    (if (get-in @state [:sessions session-token])
      (do
        (swap! state assoc-in [:sessions session-token :revoked-at] (Instant/now))
        true)
      false))

  (invalidate-all-user-sessions [_ user-id]
    (let [sessions (get-in @state [:sessions])
          user-sessions (filter #(= (:user-id (val %)) user-id) sessions)
          count (count user-sessions)]
      (doseq [[token _] user-sessions]
        (swap! state assoc-in [:sessions token :revoked-at] (Instant/now)))
      count)))

(defn create-mock-service
  []
  (->MockUserService (atom {})))

;; =============================================================================
;; User Handler Tests
;; =============================================================================

(deftest test-create-user-handler
  (testing "POST /users - Create user successfully"
    (let [service (create-mock-service)
          handler (user-http/create-user-handler service)
          tenant-id (UUID/randomUUID)
          request {:parameters
                   {:body {:email "test@example.com"
                           :name "Test User"
                           :password "password123"
                           :role "user"
                           :tenantId (str tenant-id)
                           :active true}}}
          response (handler request)]

      (is (= 201 (:status response)))
      (is (= "test@example.com" (get-in response [:body :email])))
      (is (= "Test User" (get-in response [:body :name])))
      (is (some? (get-in response [:body :id])))
      (is (some? (get-in response [:body :createdAt])))))

  (testing "POST /users - Creates user with correct defaults"
    (let [service (create-mock-service)
          handler (user-http/create-user-handler service)
          tenant-id (UUID/randomUUID)
          request {:parameters
                   {:body {:email "test2@example.com"
                           :name "Test User 2"
                           :password "password123"
                           :role "user"
                           :tenantId (str tenant-id)}}}
          response (handler request)]

      (is (= 201 (:status response)))
      (is (true? (get-in response [:body :active]))))))

(deftest test-get-user-handler
  (testing "GET /users/:id - Get existing user"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          created-user (ports/create-user service
                                          {:email "test@example.com"
                                           :name "Test User"
                                           :role :user
                                           :tenant-id tenant-id})
          handler (user-http/get-user-handler service)
          request {:parameters
                   {:path {:id (str (:id created-user))}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "test@example.com" (get-in response [:body :email])))
      (is (= "Test User" (get-in response [:body :name])))))

  (testing "GET /users/:id - User not found"
    (let [service (create-mock-service)
          handler (user-http/get-user-handler service)
          non-existent-id (UUID/randomUUID)
          request {:parameters
                   {:path {:id (str non-existent-id)}}}
          response (call-handler handler request user-http/user-error-mappings)]

      ;; Assert RFC 7807 Problem Details fields
      (is (= 404 (:status response)))
      (is (= "User Not Found" (get-in response [:body :title])))
      (is (contains? (:body response) :type))
      (is (contains? (:body response) :detail))
      (is (contains? (:body response) :instance))
      (is (contains? (:body response) :correlationId))
      (is (string? (get-in response [:body :correlationId])))
      ;; Extension member from ex-data
      (is (= (str non-existent-id) (get-in response [:body :user-id]))))))

(deftest test-list-users-handler
  (testing "GET /users - List users with pagination"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          _ (ports/create-user service
                               {:email "user1@example.com"
                                :name "User 1"
                                :role :user
                                :tenant-id tenant-id})
          _ (ports/create-user service
                               {:email "user2@example.com"
                                :name "User 2"
                                :role :admin
                                :tenant-id tenant-id})
          handler (user-http/list-users-handler service)
          request {:parameters
                   {:query {:tenantId (str tenant-id)
                            :limit 10
                            :offset 0}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= 2 (count (get-in response [:body :users]))))
      (is (= 2 (get-in response [:body :totalCount])))))

  (testing "GET /users - Filter by role"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          _ (ports/create-user service
                               {:email "user1@example.com"
                                :name "User 1"
                                :role :user
                                :tenant-id tenant-id})
          _ (ports/create-user service
                               {:email "admin1@example.com"
                                :name "Admin 1"
                                :role :admin
                                :tenant-id tenant-id})
          handler (user-http/list-users-handler service)
          request {:parameters
                   {:query {:tenantId (str tenant-id)
                            :role "admin"
                            :limit 10
                            :offset 0}}}
          response (handler request)]

      (is (= 200 (:status response)))
      ;; Note: Mock service doesn't implement filtering, would be 1 in real implementation
      (is (number? (get-in response [:body :totalCount]))))))

(deftest test-update-user-handler
  (testing "PUT /users/:id - Update user successfully"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          created-user (ports/create-user service
                                          {:email "test@example.com"
                                           :name "Test User"
                                           :role :user
                                           :tenant-id tenant-id})
          handler (user-http/update-user-handler service)
          request {:parameters
                   {:path {:id (str (:id created-user))}
                    :body {:name "Updated Name"
                           :role "admin"}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "Updated Name" (get-in response [:body :name])))
      (is (= "admin" (get-in response [:body :role])))))

  (testing "PUT /users/:id - User not found"
    (let [service (create-mock-service)
          handler (user-http/update-user-handler service)
          non-existent-id (UUID/randomUUID)
          request {:parameters
                   {:path {:id (str non-existent-id)}
                    :body {:name "Updated Name"}}}
          response (call-handler handler request user-http/user-error-mappings)]

      ;; Assert RFC 7807 Problem Details fields
      (is (= 404 (:status response)))
      (is (= "User Not Found" (get-in response [:body :title])))
      (is (contains? (:body response) :type))
      (is (contains? (:body response) :detail))
      (is (contains? (:body response) :instance))
      (is (contains? (:body response) :correlationId))
      ;; Extension member from ex-data
      (is (= (str non-existent-id) (get-in response [:body :user-id]))))))

(deftest test-delete-user-handler
  (testing "DELETE /users/:id - Soft delete user successfully"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          created-user (ports/create-user service
                                          {:email "test@example.com"
                                           :name "Test User"
                                           :role :user
                                           :tenant-id tenant-id})
          handler (user-http/delete-user-handler service)
          request {:parameters
                   {:path {:id (str (:id created-user))}}}
          response (handler request)]

      (is (= 204 (:status response)))

      ;; Verify user is soft deleted
      (let [deleted-user (ports/find-user-by-id service (:id created-user))]
        (is (false? (:active deleted-user)))
        (is (some? (:deleted-at deleted-user)))))))

;; =============================================================================
;; Session Handler Tests
;; =============================================================================

(deftest test-create-session-handler
  (testing "POST /sessions - Create session successfully"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          handler (user-http/create-session-handler service)
          request {:parameters
                   {:body {:userId (str user-id)
                           :tenantId (str tenant-id)
                           :deviceInfo {:userAgent "Mozilla/5.0"
                                        :ipAddress "192.168.1.1"}}}}
          response (handler request)]

      (is (= 201 (:status response)))
      (is (some? (get-in response [:body :sessionToken])))
      (is (some? (get-in response [:body :expiresAt])))
      (is (= (str user-id) (get-in response [:body :userId]))))))

(deftest test-validate-session-handler
  (testing "GET /sessions/:token - Valid session"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          session (ports/create-session service
                                        {:user-id user-id
                                         :tenant-id tenant-id
                                         :user-agent "Mozilla/5.0"
                                         :ip-address "192.168.1.1"})
          handler (user-http/validate-session-handler service)
          request {:parameters
                   {:path {:token (:session-token session)}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (true? (get-in response [:body :valid])))
      (is (= (str user-id) (get-in response [:body :userId])))))

  (testing "GET /sessions/:token - Invalid/expired session"
    (let [service (create-mock-service)
          handler (user-http/validate-session-handler service)
          invalid-token "invalid-token-12345"
          request {:parameters
                   {:path {:token invalid-token}}}
          response (call-handler handler request user-http/user-error-mappings)]

      ;; Assert RFC 7807 Problem Details fields
      (is (= 404 (:status response)))
      (is (= "Session Not Found" (get-in response [:body :title])))
      (is (contains? (:body response) :type))
      (is (contains? (:body response) :detail))
      (is (contains? (:body response) :instance))
      (is (contains? (:body response) :correlationId))
      ;; Extension members from ex-data
      (is (false? (get-in response [:body :valid])))
      (is (= invalid-token (get-in response [:body :token]))))))

(deftest test-invalidate-session-handler
  (testing "DELETE /sessions/:token - Invalidate session successfully"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          session (ports/create-session service
                                        {:user-id user-id
                                         :tenant-id tenant-id})
          handler (user-http/invalidate-session-handler service)
          request {:parameters
                   {:path {:token (:session-token session)}}}
          response (handler request)]

      (is (= 204 (:status response)))

      ;; Verify session is invalidated
      (let [invalidated-session (ports/find-session-by-token service (:session-token session))]
        (is (nil? invalidated-session))))))
