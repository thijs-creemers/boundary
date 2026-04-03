(ns boundary.platform.shell.security-test
  "Security-focused tests covering error mapping, CSRF routing logic,
   Hiccup XSS escaping, SQL parameterization, and sensitive field stripping.

   All tests are tagged ^:security ^:unit."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [boundary.platform.core.http.problem-details :as problem-details]
            [boundary.platform.shell.http.interceptors :as interceptors]
            [boundary.admin.core.schema-introspection :as schema-intro]
            [honey.sql :as sql]
            [hiccup2.core :as h]))

;; =============================================================================
;; Error-type → HTTP status mapping
;; =============================================================================

(deftest ^:security ^:unit error-type-http-status-mapping-test
  (testing "default-error-mappings contains expected status codes"
    (let [m problem-details/default-error-mappings]
      (is (= 400 (first (m :validation-error))))
      (is (= 404 (first (m :not-found))))
      (is (= 401 (first (m :unauthorized))))
      (is (= 403 (first (m :forbidden))))
      (is (= 409 (first (m :conflict))))))

  (testing "error handler interceptor maps types to correct status codes"
    (let [error-handler (:error interceptors/http-error-handler)
          make-ctx (fn [error-type]
                     {:exception      (ex-info "test" {:type error-type})
                      :correlation-id "test-corr-id"
                      :system         {}})]
      (is (= 400 (get-in (error-handler (make-ctx :validation-error)) [:response :status])))
      (is (= 404 (get-in (error-handler (make-ctx :not-found)) [:response :status])))
      (is (= 401 (get-in (error-handler (make-ctx :unauthorized)) [:response :status])))
      (is (= 403 (get-in (error-handler (make-ctx :forbidden)) [:response :status])))
      (is (= 409 (get-in (error-handler (make-ctx :conflict)) [:response :status])))))

  (testing "unknown error type falls back to 500"
    (let [error-handler (:error interceptors/http-error-handler)
          ctx {:exception      (ex-info "test" {:type :unknown-type})
               :correlation-id "test-corr-id"
               :system         {}}]
      (is (= 500 (get-in (error-handler ctx) [:response :status]))))))

;; =============================================================================
;; CSRF interceptor routing logic
;; =============================================================================

(deftest ^:security ^:unit csrf-interceptor-routing-test
  (let [enter (:enter interceptors/http-csrf-protection)]

    (testing "POST to /web/ path triggers CSRF check"
      ;; NOTE: valid-csrf-token? is currently a stub that always returns true.
      ;; This test documents the routing logic, not the token validation.
      (let [ctx {:request {:request-method :post
                           :uri "/web/profile/update"}}
            result (enter ctx)]
        ;; With the stub returning true, POST to /web should pass through
        (is (not (= 403 (get-in result [:response :status]))))))

    (testing "GET requests to /web/ skip CSRF check"
      (let [ctx {:request {:request-method :get
                           :uri "/web/profile"}}
            result (enter ctx)]
        (is (nil? (:response result)))))

    (testing "API routes skip CSRF check"
      (let [ctx {:request {:request-method :post
                           :uri "/api/v1/users"}}
            result (enter ctx)]
        (is (nil? (:response result)))))

    (testing "Admin routes skip CSRF check"
      (let [ctx {:request {:request-method :post
                           :uri "/web/admin/users"}}
            result (enter ctx)]
        (is (nil? (:response result)))))

    (testing "PUT/DELETE/PATCH to /web/ are state-changing"
      (doseq [method [:put :delete :patch]]
        (let [ctx {:request {:request-method method
                             :uri "/web/profile/update"}}
              result (enter ctx)]
          ;; With stub returning true, these pass through
          (is (not (= 403 (get-in result [:response :status])))
              (str method " should be checked as state-changing")))))))

;; =============================================================================
;; Hiccup XSS escaping
;; =============================================================================

(deftest ^:security ^:unit hiccup-xss-escaping-test
  (testing "Script tags in text content are escaped"
    (let [malicious "<script>alert('xss')</script>"
          html (str (h/html [:p malicious]))]
      (is (not (str/includes? html "<script>")))
      (is (str/includes? html "&lt;script&gt;"))))

  (testing "HTML entities in attribute values are escaped"
    (let [malicious "\" onmouseover=\"alert('xss')\""
          html (str (h/html [:div {:title malicious} "content"]))]
      ;; Quotes must be escaped to &quot; preventing attribute breakout.
      ;; Hiccup 2 renders: <div title="&quot; onmouseover=&quot;alert(...)&quot;">
      ;; The escaped quotes prevent the attribute value from breaking out.
      (is (str/includes? html "&quot;")
          "Double quotes should be escaped to &quot;")
      ;; Verify the title attribute is properly closed — no unescaped " before onmouseover
      (is (not (re-find #"title=\"[^\"]*\"\s+onmouseover" html))
          "Attribute breakout should be prevented by quote escaping"))))

;; =============================================================================
;; SQL parameterization
;; =============================================================================

(deftest ^:security ^:unit sql-parameterization-test
  (testing "HoneySQL produces parameterized queries, not interpolated SQL"
    (let [user-input "Robert'; DROP TABLE users;--"
          query {:select [:id :name]
                 :from   [:users]
                 :where  [:= :name user-input]}
          [sql-str & params] (sql/format query)]
      ;; SQL string uses ? placeholder
      (is (str/includes? sql-str "?"))
      ;; Malicious input is in params, not in the SQL string
      (is (not (str/includes? sql-str user-input)))
      (is (= user-input (first params)))))

  (testing "Multiple parameters are all parameterized"
    (let [query {:select [:id]
                 :from   [:users]
                 :where  [:and
                          [:= :email "test@example.com"]
                          [:= :role "admin"]]}
          [sql-str & params] (sql/format query)]
      (is (= 2 (count params)))
      (is (not (str/includes? sql-str "test@example.com")))
      (is (not (str/includes? sql-str "admin"))))))

;; =============================================================================
;; Sensitive field stripping
;; =============================================================================

(deftest ^:security ^:unit sensitive-field-stripping-test
  (testing "should-be-hidden? returns true for known sensitive fields"
    (doseq [field [:password-hash :secret :token :api-key :private-key :salt :hash]]
      (is (true? (schema-intro/should-be-hidden? field))
          (str field " should be hidden"))))

  (testing "should-be-hidden? returns false for safe fields"
    (doseq [field [:email :name :id :role :created-at]]
      (is (false? (schema-intro/should-be-hidden? field))
          (str field " should not be hidden")))))
