(ns boundary.user.shell.web-handlers-test
  "Tests for user web UI handlers.
   
   Tests cover:
   - Page handlers (full HTML pages)
   - HTMX fragment handlers (partial updates)
   - Validation and error handling
   - Tenant resolution
   - HTML response structure"
  (:require [boundary.user.shell.web-handlers :as web-handlers]
            [boundary.user.ports :as ports]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- html-contains?
  "Check if HTML response contains a substring."
  [response text]
  (and (string? (:body response))
       (str/includes? (:body response) text)))

(defn- has-header?
  "Check if response has a header with specific value."
  [response header-name expected-value]
  (= expected-value (get-in response [:headers header-name])))

;; =============================================================================
;; Mock User Service
;; =============================================================================

(defrecord MockUserService [state]
  ports/IUserService
  
  (register-user [_ user-data]
    (let [user-id (UUID/randomUUID)
          now (Instant/now)
          created-user (assoc user-data
                              :id user-id
                              :created-at now
                              :updated-at nil
                              :deleted-at nil)]
      (swap! state assoc user-id created-user)
      created-user))
  
  (get-user-by-id [_ user-id]
    (get @state user-id))
  
  (list-users-by-tenant [_ tenant-id options]
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
  
  (update-user-profile [_ user-entity]
    (let [user-id (:id user-entity)
          existing (get @state user-id)]
      (if existing
        (let [updated (assoc user-entity :updated-at (Instant/now))]
          (swap! state assoc user-id updated)
          updated)
        (throw (ex-info "User not found"
                        {:type :user-not-found
                         :user-id user-id})))))
  
  (deactivate-user [_ user-id]
    (let [existing (get @state user-id)]
      (if existing
        (do
          (swap! state update user-id
                 #(assoc % :deleted-at (Instant/now) :active false))
          true)
        (throw (ex-info "User not found"
                        {:type :user-not-found
                         :user-id user-id}))))))

(defn create-mock-service
  "Create a mock user service with initial state."
  ([]
   (create-mock-service {}))
  ([initial-state]
   (->MockUserService (atom initial-state))))

(def test-tenant-id (UUID/randomUUID))

(defn create-test-user
  "Create a test user entity."
  [overrides]
  (merge {:id (UUID/randomUUID)
          :name "Test User"
          :email "test@example.com"
          :role :user
          :tenant-id test-tenant-id
          :active true
          :created-at (Instant/now)
          :updated-at nil
          :deleted-at nil}
         overrides))

;; =============================================================================
;; Page Handler Tests
;; =============================================================================

(deftest users-page-handler-test
  (testing "renders users page with users list"
    (let [user1 (create-test-user {:name "Alice" :email "alice@example.com"})
          user2 (create-test-user {:name "Bob" :email "bob@example.com"})
          service (create-mock-service {(:id user1) user1
                                        (:id user2) user2})
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Alice"))
      (is (html-contains? response "alice@example.com"))
      (is (html-contains? response "Bob"))
      (is (html-contains? response "bob@example.com"))))
  
  (testing "uses tenant ID from header when present"
    (let [user1 (create-test-user {:tenant-id "custom-tenant"})
          service (create-mock-service {(:id user1) user1})
          config {:active {:boundary/settings {:default-tenant-id (UUID/randomUUID)}}}
          handler (web-handlers/users-page-handler service config)
          request {:headers {"x-tenant-id" "custom-tenant"}}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (html-contains? response "Test User"))))
  
  (testing "handles empty users list"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (html-contains? response "No users found"))))
  
  (testing "handles service errors gracefully"
    (let [service (reify ports/IUserService
                    (list-users-by-tenant [_ _ _]
                      (throw (Exception. "Database connection failed"))))
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]
      
      (is (= 500 (:status response)))
      (is (html-contains? response "Database connection failed")))))

(deftest user-detail-page-handler-test
  (testing "renders user detail page for existing user"
    (let [user (create-test-user {:name "Alice" :email "alice@example.com"})
          service (create-mock-service {(:id user) user})
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Alice"))
      (is (html-contains? response "alice@example.com"))))
  
  (testing "returns 404 for non-existent user"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id (str (UUID/randomUUID))}}
          response (handler request)]
      
      (is (= 404 (:status response)))
      (is (html-contains? response "User Not Found"))))
  
  (testing "returns 400 for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id "not-a-uuid"}}
          response (handler request)]
      
      (is (= 400 (:status response)))
      (is (html-contains? response "Invalid User ID"))))
  
  (testing "handles service errors gracefully"
    (let [user (create-test-user {})
          service (reify ports/IUserService
                    (get-user-by-id [_ _]
                      (throw (Exception. "Database error"))))
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]
      
      (is (= 500 (:status response)))
      (is (html-contains? response "Database error")))))

(deftest create-user-page-handler-test
  (testing "renders create user page"
    (let [config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/create-user-page-handler config)
          request {}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Create New User"))
      (is (html-contains? response "form"))))
  
  (testing "includes flash messages when present"
    (let [config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/create-user-page-handler config)
          request {:flash {:error "Previous creation failed"}}
          response (handler request)]
      
      (is (= 200 (:status response))))))

;; =============================================================================
;; HTMX Fragment Handler Tests
;; =============================================================================

(deftest users-table-fragment-handler-test
  (testing "returns users table fragment"
    (let [user1 (create-test-user {:name "Alice"})
          user2 (create-test-user {:name "Bob"})
          service (create-mock-service {(:id user1) user1
                                        (:id user2) user2})
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/users-table-fragment-handler service config)
          request {}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Alice"))
      (is (html-contains? response "Bob"))
      (is (html-contains? response "users-table-container"))))
  
  (testing "handles errors"
    (let [service (reify ports/IUserService
                    (list-users-by-tenant [_ _ _]
                      (throw (Exception. "Connection timeout"))))
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/users-table-fragment-handler service config)
          request {}
          response (handler request)]
      
      (is (= 500 (:status response)))
      (is (html-contains? response "Connection timeout")))))

(deftest create-user-htmx-handler-test
  (testing "creates user successfully and returns success message"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/create-user-htmx-handler service config)
          request {:form-params {"name" "New User"
                                 "email" "newuser@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "active" "true"}}
          response (handler request)]
      
      (is (= 201 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (has-header? response "HX-Trigger" "userCreated"))
      (is (html-contains? response "User Created Successfully"))
      (is (html-contains? response "New User"))))
  
  (testing "returns validation errors for invalid data"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/create-user-htmx-handler service config)
          request {:form-params {"name" ""
                                 "email" "invalid-email"
                                 "password" "123"}}
          response (handler request)]
      
      (is (= 400 (:status response)))
      (is (html-contains? response "create-user-form"))))
  
  (testing "handles service errors"
    (let [service (reify ports/IUserService
                    (register-user [_ _]
                      (throw (Exception. "Email already exists"))))
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/create-user-htmx-handler service config)
          request {:form-params {"name" "Test User"
                                 "email" "test@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "active" "true"}}
          response (handler request)]
      
      (is (= 500 (:status response)))
      (is (html-contains? response "Email already exists")))))

(deftest update-user-htmx-handler-test
  (testing "updates user successfully and returns success message"
    (let [user (create-test-user {:name "Original Name"})
          service (create-mock-service {(:id user) user})
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}
                   :form-params {"name" "Updated Name"
                                 "email" "updated@example.com"
                                 "role" "admin"
                                 "active" "true"}}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (has-header? response "HX-Trigger" "userUpdated"))
      (is (html-contains? response "User Updated Successfully"))
      (is (html-contains? response "Updated Name"))))
  
  (testing "returns validation errors for invalid data"
    (let [user (create-test-user {})
          service (create-mock-service {(:id user) user})
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}
                   :form-params {"name" ""
                                 "email" "invalid-email"}}
          response (handler request)]
      
      (is (= 400 (:status response)))))
  
  (testing "returns error for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id "not-a-uuid"}
                   :form-params {"name" "Test"
                                 "email" "test@example.com"}}
          response (handler request)]
      
      (is (= 400 (:status response)))
      (is (html-contains? response "Invalid user ID"))))
  
  (testing "handles service errors"
    (let [user (create-test-user {})
          service (reify ports/IUserService
                    (update-user-profile [_ _]
                      (throw (Exception. "Database error"))))
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}
                   :form-params {"name" "Test User"
                                 "email" "test@example.com"
                                 "role" "user"
                                 "active" "true"}}
          response (handler request)]
      
      (is (= 500 (:status response)))
      (is (html-contains? response "Database error")))))

(deftest delete-user-htmx-handler-test
  (testing "deactivates user successfully and returns success message"
    (let [user (create-test-user {})
          service (create-mock-service {(:id user) user})
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/delete-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]
      
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (has-header? response "HX-Trigger" "userDeleted"))
      (is (html-contains? response "User Deleted Successfully"))
      (is (html-contains? response (str (:id user))))))
  
  (testing "returns error for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/delete-user-htmx-handler service config)
          request {:path-params {:id "not-a-uuid"}}
          response (handler request)]
      
      (is (= 400 (:status response)))
      (is (html-contains? response "Invalid user ID"))))
  
  (testing "handles service errors"
    (let [user (create-test-user {})
          service (reify ports/IUserService
                    (deactivate-user [_ _]
                      (throw (Exception. "Cannot delete user with active sessions"))))
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          handler (web-handlers/delete-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]
      
      (is (= 500 (:status response)))
      (is (html-contains? response "Cannot delete user with active sessions")))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest web-handlers-integration-test
  (testing "complete workflow: list -> create -> view -> update -> delete"
    (let [service (create-mock-service)
          config {:active {:boundary/settings {:default-tenant-id test-tenant-id}}}
          
          ;; 1. List users (empty)
          list-handler (web-handlers/users-page-handler service config)
          list-response (list-handler {})
          
          ;; 2. Create user
          create-handler (web-handlers/create-user-htmx-handler service config)
          create-response (create-handler {:form-params {"name" "Integration User"
                                                         "email" "integration@example.com"
                                                         "password" "password123"
                                                         "role" "user"
                                                         "active" "true"}})
          
          ;; Extract user ID from response (simplified - in reality would parse HTML)
          created-users (:users (ports/list-users-by-tenant service test-tenant-id {}))
          user-id (str (:id (first created-users)))
          
          ;; 3. View user detail
          detail-handler (web-handlers/user-detail-page-handler service config)
          detail-response (detail-handler {:path-params {:id user-id}})
          
          ;; 4. Update user
          update-handler (web-handlers/update-user-htmx-handler service config)
          update-response (update-handler {:path-params {:id user-id}
                                          :form-params {"name" "Updated Integration User"
                                                        "email" "integration@example.com"
                                                        "role" "admin"
                                                        "active" "true"}})
          
          ;; 5. Delete user
          delete-handler (web-handlers/delete-user-htmx-handler service config)
          delete-response (delete-handler {:path-params {:id user-id}})]
      
      ;; Verify each step
      (is (= 200 (:status list-response)))
      (is (= 201 (:status create-response)))
      (is (has-header? create-response "HX-Trigger" "userCreated"))
      (is (= 200 (:status detail-response)))
      (is (html-contains? detail-response "Integration User"))
      (is (= 200 (:status update-response)))
      (is (has-header? update-response "HX-Trigger" "userUpdated"))
      (is (= 200 (:status delete-response)))
      (is (has-header? delete-response "HX-Trigger" "userDeleted")))))
