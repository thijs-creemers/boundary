(ns boundary.core.http.problem-details-test
  "Tests for Problem Details context preservation functionality"
  (:require [boundary.platform.core.http.problem-details :as pd]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Test Helpers  
;; =============================================================================

(defn- create-test-request
  "Create a test HTTP request with context information"
  [& {:keys [user-id tenant-id headers uri method]}]
  {:request-method (or method :get)
   :uri (or uri "/api/users")
   :headers (merge {"user-agent" "test-agent/1.0"
                    "x-forwarded-for" "192.168.1.100"
                    "x-trace-id" (str (UUID/randomUUID))
                    "x-request-id" (str (UUID/randomUUID))}
                   (when user-id {"x-user-id" (str user-id)})
                   (when tenant-id {"x-tenant-id" (str tenant-id)})
                   (or headers {}))})

(defn- create-test-exception
  "Create a test exception with optional message and data"
  [& {:keys [message data]}]
  (ex-info (or message "Test exception") (or data {})))

;; =============================================================================
;; Context Extraction Tests
;; =============================================================================

(deftest test-request->context
  (testing "extracts context from HTTP request"
    (let [user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          request (create-test-request :user-id user-id :tenant-id tenant-id)
          context (pd/request->context request)]

      (is (= (str user-id) (:user-id context)))
      (is (= (str tenant-id) (:tenant-id context)))
      (is (contains? context :trace-id))
      (is (contains? context :request-id))
      (is (= "test-agent/1.0" (:user-agent context)))
      (is (= "192.168.1.100" (:ip-address context)))
      (is (= "/api/users" (:uri context)))
      (is (= "GET" (:method context)))))

  (testing "handles missing headers gracefully"
    (let [request {:request-method :post :uri "/api/test"}
          context (pd/request->context request)]

      (is (nil? (:user-id context)))
      (is (nil? (:tenant-id context)))
      (is (nil? (:trace-id context)))
      (is (= "POST" (:method context)))
      (is (= "/api/test" (:uri context)))))

  (testing "handles x-forwarded-for header variations"
    (let [request-single-ip (assoc-in (create-test-request) [:headers "x-forwarded-for"] "10.0.0.1")
          request-multiple-ips (assoc-in (create-test-request) [:headers "x-forwarded-for"] "10.0.0.1, 192.168.1.1")
          context-single (pd/request->context request-single-ip)
          context-multiple (pd/request->context request-multiple-ips)]

      (is (= "10.0.0.1" (:ip-address context-single)))
      (is (= "10.0.0.1" (:ip-address context-multiple))))))

(deftest test-cli-context
  (testing "creates CLI context with environment info"
    (let [context (pd/cli-context)]

      (is (contains? context :environment))
      (is (contains? context :timestamp))
      (is (contains? context :process-id))
      (is (inst? (:timestamp context)))))

  (testing "merges additional context"
    (let [additional {:user-id "test-user" :operation "create-user"}
          context (pd/cli-context additional)]

      (is (= "test-user" (:user-id context)))
      (is (= "create-user" (:operation context)))
      (is (contains? context :environment)))))

(deftest test-enrich-context
  (testing "enriches context with timestamp and environment"
    (let [base-context {:user-id "test-user"}
          enriched (pd/enrich-context base-context {:timestamp (java.time.Instant/now) :environment "test"})]

      (is (= "test-user" (:user-id enriched)))
      (is (contains? enriched :timestamp))
      (is (contains? enriched :environment))
      (is (inst? (:timestamp enriched)))))

  (testing "preserves existing timestamp"
    (let [existing-timestamp (Instant/parse "2023-01-01T12:00:00Z")
          base-context {:user-id "test-user" :timestamp existing-timestamp}
          enriched (pd/enrich-context base-context {:environment "test"})]

      (is (= existing-timestamp (:timestamp enriched))))))

;; =============================================================================
;; Problem Details Creation Tests
;; =============================================================================

(deftest test-exception->problem-body-with-context
  (testing "creates problem body with context"
    (let [exception (create-test-exception :message "Validation failed"
                                           :data {:field "email" :error "invalid"})
          context {:user-id "test-user" :tenant-id "test-tenant"}
          problem-body (pd/exception->problem-body exception nil nil {} context)]

      (is (= "Validation failed" (:title problem-body)))
      (is (= 500 (:status problem-body)))
      (is (= "Validation failed" (:detail problem-body)))
      (is (= "email" (:field problem-body)))
      (is (= "invalid" (:error problem-body)))
      ;; Check that context fields are present (timestamp will be added automatically)
      (is (= "test-user" (get-in problem-body [:errorContext :user-id])))
      (is (= "test-tenant" (get-in problem-body [:errorContext :tenant-id])))
      (is (contains? (:errorContext problem-body) :timestamp))
      (is (contains? problem-body :instance))))

  (testing "works without context"
    (let [exception (create-test-exception :message "Simple error")
          problem-body (pd/exception->problem-body exception nil nil {} {})]

      (is (= "Simple error" (:title problem-body)))
      (is (= 500 (:status problem-body)))
      (is (= "Simple error" (:detail problem-body)))
      ;; Should only have timestamp, no other context
      (is (= #{:timestamp} (set (keys (:errorContext problem-body)))))))

  (testing "handles exception with custom status"
    (let [exception (create-test-exception :message "Not found"
                                           :data {:status 404})
          context {:user-id "test-user"}
          problem-body (pd/exception->problem-body exception nil nil {} context)]

      (is (= 500 (:status problem-body)))
      (is (= "Not found" (:detail problem-body)))
      (is (= "test-user" (get-in problem-body [:errorContext :user-id])))
      (is (contains? (:errorContext problem-body) :timestamp)))))

(deftest test-exception->problem-response-with-context
  (testing "creates problem response with context"
    (let [exception (create-test-exception :message "Server error")
          context {:user-id "test-user" :operation "get-user"}
          response (pd/exception->problem-response exception "test-correlation-123" nil {} context)]

      (is (= 500 (:status response)))
      (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))

      ;; Parse the JSON body since it's a string
      (let [body (json/parse-string (:body response))]
        (is (= "Server error" (get body "title")))
        (is (= "Server error" (get body "detail")))
        ;; Check that context fields are present (timestamp will be added automatically)
        (is (= "test-user" (get-in body ["errorContext" "user-id"])))
        (is (contains? (get body "errorContext") "timestamp")))))

  (testing "includes correlation-id in headers when present in context"
    (let [exception (create-test-exception)
          context {:correlation-id "test-correlation-123"}
          response (pd/exception->problem-response exception "test-correlation-123" nil {} context)]

      ;; Parse the JSON body since it's a string
      (let [body (json/parse-string (:body response))]
        (is (= "test-correlation-123" (get body "correlationId"))))))

  (testing "sets correct content type"
    (let [exception (create-test-exception)
          context {:user-id "test-user"}
          response (pd/exception->problem-response exception nil nil {} context)]

      (is (= "application/problem+json" (get-in response [:headers "Content-Type"]))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-full-context-flow
  (testing "full flow from request to problem response"
    (let [request (create-test-request :user-id (UUID/randomUUID)
                                       :tenant-id (UUID/randomUUID))
          context (pd/request->context request)
          enriched-context (pd/enrich-context context {:environment "test"})
          exception (create-test-exception :message "Business logic error"
                                           :data {:code "BUSINESS_ERROR"})
          response (pd/exception->problem-response exception nil nil {} enriched-context)]

      (is (= 500 (:status response)))

      ;; Parse the JSON body since it's a string
      (let [body (json/parse-string (:body response))]
        (is (= "Business logic error" (get body "title")))
        (is (= "Business logic error" (get body "detail")))
        ;; Check that context fields are present using string keys
        (is (contains? (get body "errorContext") "user-id"))
        (is (contains? (get body "errorContext") "tenant-id"))
        (is (contains? (get body "errorContext") "timestamp"))
        (is (contains? (get body "errorContext") "environment"))))))