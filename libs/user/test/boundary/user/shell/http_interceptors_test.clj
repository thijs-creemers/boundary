(ns boundary.user.shell.http-interceptors-test
  "Tests for HTTP-level user authentication/authorization interceptors."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [boundary.user.shell.http-interceptors :as http-int]))

;; =============================================================================
;; Mock Observability Services
;; =============================================================================

(defn create-mock-system []
  {:logger nil  ; Interceptors should handle missing logger gracefully
   :metrics-emitter nil})

;; =============================================================================
;; Test Helper: Create HTTP Context
;; =============================================================================

(defn create-test-context
  "Create test HTTP context. Response is optional - only add if provided."
  ([request]
   {:request request
    :system (create-mock-system)
    :correlation-id "test-correlation-id"
    :path-params {}
    :attrs {}})
  ([request response]
   {:request request
    :response response
    :system (create-mock-system)
    :correlation-id "test-correlation-id"
    :path-params {}
    :attrs {}}))

;; =============================================================================
;; Authentication Interceptor Tests
;; =============================================================================

(deftest require-authenticated-test
  (testing "allows authenticated requests"
    (let [request {:session {:user {:id "user-123" :role "user"}}}
          ctx (create-test-context request)
          result ((:enter http-int/require-authenticated) ctx)]
      (is (nil? (:response result))
          "Should pass through without response")
      (is (= (:request result) request)
          "Should preserve request")))

  (testing "rejects unauthenticated requests with 401"
    (let [request {:session {}}
          ctx (create-test-context request)
          result ((:enter http-int/require-authenticated) ctx)]
      (is (some? (:response result))
          "Should set response")
      (is (= 401 (get-in result [:response :status]))
          "Should return 401 Unauthorized")
      (is (= "unauthorized" (get-in result [:response :body :error]))
          "Should have error type")
      (is (= "Authentication required" (get-in result [:response :body :message]))
          "Should have error message"))))

;; =============================================================================
;; Authorization Interceptor Tests
;; =============================================================================

(deftest require-admin-test
  (testing "allows admin users"
    (let [request {:session {:user {:id "admin-123" :role "admin"}}}
          ctx (create-test-context request)
          result ((:enter http-int/require-admin) ctx)]
      (is (nil? (:response result))
          "Should pass through without response")))

  (testing "rejects non-admin users with 403"
    (let [request {:session {:user {:id "user-123" :role "user"}}}
          ctx (create-test-context request)
          result ((:enter http-int/require-admin) ctx)]
      (is (some? (:response result))
          "Should set response")
      (is (= 403 (get-in result [:response :status]))
          "Should return 403 Forbidden")
      (is (= "forbidden" (get-in result [:response :body :error]))
          "Should have error type")
      (is (= "Admin role required" (get-in result [:response :body :message]))
          "Should have error message")))

  (testing "rejects missing user with 403"
    (let [request {:session {}}
          ctx (create-test-context request)
          result ((:enter http-int/require-admin) ctx)]
      (is (some? (:response result))
          "Should set response")
      (is (= 403 (get-in result [:response :status]))
          "Should return 403 Forbidden"))))

(deftest require-platform-admin-test
  (testing "allows platform admins"
    (let [request {:session {:user {:id "admin-123" :role :admin}}}
          ctx (create-test-context request)
          result ((:enter http-int/require-platform-admin) ctx)]
      (is (nil? (:response result))
          "Should pass through without response")))

  (testing "rejects tenant-scoped admins"
    (let [request {:session {:user {:id "admin-123" :role :admin :tenant-id "tenant-1"}}}
          ctx (create-test-context request)
          result ((:enter http-int/require-platform-admin) ctx)]
      (is (= 403 (get-in result [:response :status])))
      (is (= "Platform admin required" (get-in result [:response :body :message])))))

  (testing "rejects regular users"
    (let [request {:session {:user {:id "user-123" :role :user}}}
          ctx (create-test-context request)
          result ((:enter http-int/require-platform-admin) ctx)]
      (is (= 403 (get-in result [:response :status])))
      (is (= "Platform admin required" (get-in result [:response :body :message]))))))

(deftest require-unauthenticated-test
  (testing "allows anonymous requests"
    (let [ctx (create-test-context {:session {}})
          result ((:enter http-int/require-unauthenticated) ctx)]
      (is (nil? (:response result)))))

  (testing "rejects authenticated requests"
    (let [ctx (create-test-context {:session {:user {:id "user-123" :role :user}}})
          result ((:enter http-int/require-unauthenticated) ctx)]
      (is (= 403 (get-in result [:response :status])))
      (is (= "Already authenticated" (get-in result [:response :body :message]))))))

(deftest require-role-test
  (testing "supports keyword and string role normalization"
    (let [manager-check (http-int/require-role "manager")
          keyword-check (http-int/require-role :manager)]
      (is (nil? (:response ((:enter manager-check)
                            (create-test-context {:session {:user {:id "mgr-1" :role :manager}}})))))
      (is (nil? (:response ((:enter keyword-check)
                            (create-test-context {:session {:user {:id "mgr-2" :role "manager"}}})))))))

  (testing "rejects users with the wrong role"
    (let [manager-check (http-int/require-role "manager")
          result ((:enter manager-check)
                  (create-test-context {:session {:user {:id "user-123" :role :user}}}))]
      (is (= 403 (get-in result [:response :status])))
      (is (= "Role required: manager" (get-in result [:response :body :message]))))))

(deftest require-self-or-admin-test
  (testing "allows users to access their own resource"
    (let [ctx (assoc (create-test-context {:session {:user {:id #uuid "00000000-0000-0000-0000-000000000123"
                                                            :role :user}}})
                     :path-params {:id "00000000-0000-0000-0000-000000000123"})
          result ((:enter http-int/require-self-or-admin) ctx)]
      (is (nil? (:response result)))))

  (testing "allows admins to access other users"
    (let [ctx (assoc (create-test-context {:session {:user {:id "admin-123" :role "admin"}}})
                     :path-params {:id "user-456"})
          result ((:enter http-int/require-self-or-admin) ctx)]
      (is (nil? (:response result)))))

  (testing "rejects non-admins accessing another user"
    (let [ctx (assoc (create-test-context {:session {:user {:id "user-123" :role :user}}})
                     :path-params {:id "user-456"})
          result ((:enter http-int/require-self-or-admin) ctx)]
      (is (= 403 (get-in result [:response :status])))
      (is (= "Access denied" (get-in result [:response :body :message]))))))

;; =============================================================================
;; Audit Logging Interceptor Tests
;; =============================================================================

(deftest log-action-test
  (testing "logs successful actions (2xx)"
    (let [request {:uri "/api/users"
                   :request-method :post
                   :session {:user {:id "user-123" :role "admin"}}}
          response {:status 201 :body {:id "new-user"}}
          ctx (create-test-context request response)
          result ((:leave http-int/log-action) ctx)]
      (is (= ctx result)
          "Should return context unchanged")
      (is (= 201 (get-in result [:response :status]))
          "Should preserve response")))

  (testing "does not log failed actions (4xx, 5xx)"
    (let [request {:uri "/api/users"
                   :request-method :post
                   :session {:user {:id "user-123" :role "admin"}}}
          response {:status 400 :body {:error "Bad request"}}
          ctx (create-test-context request response)
          result ((:leave http-int/log-action) ctx)]
      (is (= ctx result)
          "Should return context unchanged")
      (is (= 400 (get-in result [:response :status]))
          "Should preserve error response"))))

(deftest log-all-actions-test
  (testing "preserves successful responses"
    (let [ctx (create-test-context {:uri "/api/admin/users"
                                    :request-method :delete
                                    :session {:user {:id "admin-123" :role :admin}}}
                                   {:status 204})
          result ((:leave http-int/log-all-actions) ctx)]
      (is (= 204 (get-in result [:response :status])))))

  (testing "preserves failing responses"
    (let [ctx (create-test-context {:uri "/api/admin/users"
                                    :request-method :delete
                                    :session {:user {:id "admin-123" :role :admin}}}
                                   {:status 409})
          result ((:leave http-int/log-all-actions) ctx)]
      (is (= 409 (get-in result [:response :status]))))))

;; =============================================================================
;; Interceptor Composition Tests
;; =============================================================================

(deftest admin-endpoint-stack-test
  (testing "admin-endpoint-stack has correct interceptors"
    (is (= 3 (count http-int/admin-endpoint-stack))
        "Should have 3 interceptors")
    (is (= :require-authenticated (:name (first http-int/admin-endpoint-stack)))
        "First interceptor should be require-authenticated")
    (is (= :require-admin (:name (second http-int/admin-endpoint-stack)))
        "Second interceptor should be require-admin")
    (is (= :log-action (:name (nth http-int/admin-endpoint-stack 2)))
        "Third interceptor should be log-action")))

(deftest user-endpoint-stack-test
  (testing "user-endpoint-stack has correct interceptors"
    (is (= 2 (count http-int/user-endpoint-stack))
        "Should have 2 interceptors")
    (is (= :require-authenticated (:name (first http-int/user-endpoint-stack)))
        "First interceptor should be require-authenticated")
    (is (= :log-action (:name (second http-int/user-endpoint-stack)))
        "Second interceptor should be log-action")))

;; =============================================================================
;; Integration: Multiple Interceptors
;; =============================================================================

(deftest multiple-interceptors-integration-test
  (testing "authentication + authorization chain"
    (let [request {:session {:user {:id "user-123" :role "user"}}}
          ctx (create-test-context request)

          ;; Run auth interceptor
          after-auth ((:enter http-int/require-authenticated) ctx)

          ;; Run authz interceptor
          after-authz ((:enter http-int/require-admin) after-auth)]

      (is (nil? (:response after-auth))
          "Auth should pass")
      (is (some? (:response after-authz))
          "Authz should fail")
      (is (= 403 (get-in after-authz [:response :status]))
          "Should return 403 Forbidden")))

  (testing "successful request through full stack"
    (let [request {:uri "/api/users"
                   :request-method :post
                   :session {:user {:id "admin-123" :role "admin"}}}
          response {:status 201 :body {:id "new-user"}}
          ctx (create-test-context request)

          ;; Run enter phase (auth + authz)
          after-auth ((:enter http-int/require-authenticated) ctx)
          after-authz ((:enter http-int/require-admin) after-auth)

          ;; Simulate handler execution
          after-handler (assoc after-authz :response response)

          ;; Run leave phase (audit)
          after-audit ((:leave http-int/log-action) after-handler)]

      (is (nil? (:response after-auth))
          "Auth should pass")
      (is (nil? (:response after-authz))
          "Authz should pass")
      (is (= 201 (get-in after-audit [:response :status]))
          "Should preserve successful response"))))

;; =============================================================================
;; Tenant Membership Interceptor Tests (ADR-016)
;; =============================================================================

(deftest require-web-tenant-admin-test
  (let [active-admin-membership {:status :active :role :admin}
        active-member-membership {:status :active :role :member}]

    (testing "allows active admin membership"
      (let [ctx (create-test-context {:uri "/web/tenants/t1/settings"
                                      :tenant-membership active-admin-membership})
            result ((:enter http-int/require-web-tenant-admin) ctx)]
        (is (nil? (:response result)))))

    (testing "redirects to /web/login for web routes when no membership"
      (let [ctx (create-test-context {:uri "/web/tenants/t1/settings"})
            result ((:enter http-int/require-web-tenant-admin) ctx)]
        (is (= 302 (get-in result [:response :status])))
        (is (clojure.string/starts-with? (get-in result [:response :headers "Location"])
                                         "/web/login?return-to="))))

    (testing "redirects to /web/login for web routes with insufficient role"
      (let [ctx (create-test-context {:uri "/web/tenants/t1/settings"
                                      :tenant-membership active-member-membership})
            result ((:enter http-int/require-web-tenant-admin) ctx)]
        (is (= 302 (get-in result [:response :status])))
        (is (clojure.string/starts-with? (get-in result [:response :headers "Location"])
                                         "/web/login?return-to="))))

    (testing "return-to URL is encoded in the redirect location"
      (let [ctx (create-test-context {:uri "/web/tenants/t1/settings?tab=users"})
            result ((:enter http-int/require-web-tenant-admin) ctx)]
        (is (= 302 (get-in result [:response :status])))
        (is (clojure.string/includes? (get-in result [:response :headers "Location"])
                                      "%3F"))))

    (testing "returns 403 JSON for non-web routes when no membership"
      (let [ctx (create-test-context {:uri "/api/tenants/t1/settings"})
            result ((:enter http-int/require-web-tenant-admin) ctx)]
        (is (= 403 (get-in result [:response :status])))
        (is (= "forbidden" (get-in result [:response :body :error])))))

    (testing "returns 403 JSON for non-web routes with insufficient role"
      (let [ctx (create-test-context {:uri "/api/tenants/t1/settings"
                                      :tenant-membership active-member-membership})
            result ((:enter http-int/require-web-tenant-admin) ctx)]
        (is (= 403 (get-in result [:response :status])))
        (is (= "forbidden" (get-in result [:response :body :error])))))))
