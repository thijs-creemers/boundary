(ns boundary.e2e.api.auth-sessions-test
  "E2E tests for session management and security-related assertions.

   Discovered behaviour:
   - POST /api/v1/sessions creates a session (same as /api/v1/auth/login).
   - GET/DELETE /api/v1/sessions/:token expect the base64 session-token in the
     path, but tokens containing `/` or `+` cause Jetty 400 (\"Ambiguous URI
     path separator\").  Session validate/invalidate by path is therefore
     unreliable.  Tests document this limitation.
   - Lockout: enforced at both auth-shell and service layers after 5 failed
     attempts (15 min lockout window).
   - Unauthenticated access to protected endpoints returns 401 (empty body)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.users :as users]
            [boundary.e2e.helpers.reset :as reset]
            [clj-http.client :as http]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- login-resp
  "Login as seed admin via the sessions endpoint. Returns the full response."
  []
  (users/create-session {:email    (-> fx/*seed* :admin :email)
                         :password (-> fx/*seed* :admin :password)}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:e2e create-session-returns-token
  (testing "POST /api/v1/sessions with valid creds returns authenticated:true with session"
    (let [resp (login-resp)
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:authenticated body)))
      (is (string? (:sessionToken body)))
      (is (map? (:session body)))
      (is (string? (get-in body [:session :id]))
          "Session should have a UUID id"))))

(deftest ^:e2e session-validate-by-uuid-returns-not-found
  (testing "GET /api/v1/sessions/:id with session UUID returns 404 (uses token, not id)"
    ;; The endpoint expects the session-token (base64), not the UUID.
    ;; Passing a UUID demonstrates it doesn't match.
    (let [resp     (login-resp)
          sid      (get-in resp [:body :session :id])
          val-resp (users/validate-session sid)]
      (is (= 404 (:status val-resp))
          "Session validate looks up by token, not by UUID — expect 404"))))

(deftest ^:e2e delete-session-returns-204
  (testing "DELETE /api/v1/sessions/:id returns 204 (no-op when token doesn't match UUID)"
    (let [resp     (login-resp)
          sid      (get-in resp [:body :session :id])
          del-resp (users/invalidate-session sid)]
      ;; Returns 204 regardless (no error on missing token match)
      (is (= 204 (:status del-resp))))))

(deftest ^:e2e protected-endpoint-without-token-is-401
  (testing "GET /api/v1/auth/mfa/status without any credentials returns 401"
    (let [resp (http/get (str (reset/default-base-url) "/api/v1/auth/mfa/status")
                         {:accept           :json
                          :throw-exceptions false})]
      (is (= 401 (:status resp))))))

(deftest ^:e2e password-hash-never-appears-in-auth-responses
  (testing "Neither login nor register responses contain password-hash variants"
    ;; Login response
    (let [login-body (pr-str (:body (users/login
                                     {:email    (-> fx/*seed* :admin :email)
                                      :password (-> fx/*seed* :admin :password)})))]
      (is (not (str/includes? login-body "password-hash")))
      (is (not (str/includes? login-body "passwordHash")))
      (is (not (str/includes? login-body "password_hash"))))
    ;; Register response (web form — body is HTML string)
    (let [reg-body (str (:body (users/register
                                {:email    "security-check@acme.test"
                                 :password "Strong-Pass-1234!"
                                 :name     "Security Check"})))]
      (is (not (str/includes? reg-body "password-hash")))
      (is (not (str/includes? reg-body "passwordHash")))
      (is (not (str/includes? reg-body "password_hash"))))))

;; ---------------------------------------------------------------------------
;; Lockout — enforced at service level
;; ---------------------------------------------------------------------------

(deftest ^:e2e lockout-enforced-after-threshold
  (testing "Account is locked out after 5 failed login attempts"
    ;; Fire 6 failed login attempts (threshold is 5)
    (dotimes [_ 6]
      (users/login {:email    (-> fx/*seed* :admin :email)
                    :password "Wrong-Pass-1234!"}))
    ;; Correct password should now be rejected because account is locked
    (let [resp (users/login {:email    (-> fx/*seed* :admin :email)
                             :password (-> fx/*seed* :admin :password)})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (false? (:authenticated body))
          "Login must be rejected when account is locked out")
      (is (some? (:message body))
          "Lockout response should include a human-readable message"))))
