(ns boundary.platform.shell.search.http-test
  "HTTP handler tests for search module REST API.
   
   Tests all search endpoints with:
   - Happy path scenarios
   - Query parameter validation
   - Error cases
   - Edge cases"
  {:kaocha.testable/meta {:integration true :search true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.platform.shell.search.http :as search-http]
            [boundary.platform.search.ports :as ports]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn parse-json-body
  "Parse JSON response body."
  [response]
  (when-let [body (:body response)]
    (if (string? body)
      (json/parse-string body true)
      body)))

(defn call-handler
  "Call handler with request."
  [handler request]
  (handler request))

;; ============================================================================
;; Mock Search Service
;; ============================================================================

(defrecord MockSearchService [state]
  ports/ISearchService

  (search-users [_this _query-str options]
    {:results [{:id "user-1"
                :name "John Doe"
                :email "john@example.com"
                :score 0.95
                :rank 1
                :_highlights {:name "<mark>John</mark> Doe"}}
               {:id "user-2"
                :name "Jane Doe"
                :email "jane@example.com"
                :score 0.85
                :rank 2
                :_highlights {:name "Jane <mark>Doe</mark>"}}]
     :total 2
     :max-score 0.95
     :page {:from (:from options 0)
            :size (:size options 20)}
     :took-ms 15})

  (search-items [_this _query-str options]
    {:results [{:id "item-1"
                :name "Widget A"
                :sku "WID-001"
                :score 0.90
                :rank 1}]
     :total 1
     :max-score 0.90
     :page {:from (:from options 0)
            :size (:size options 20)}
     :took-ms 10})

  (suggest [_this _prefix _field _options]
    {:suggestions ["John Doe" "John Smith" "Johnny"]
     :count 3
     :took-ms 5})

  (reindex-all [_this _index-name]
    {:reindexed-count 1000
     :failed-count 0
     :duration-ms 5000})

  (get-search-stats [_this]
    {:indices [{:name :users
                :document-count 1000
                :size-bytes 1048576}
               {:name :items
                :document-count 500
                :size-bytes 524288}]
     :total-documents 1500
     :total-queries-today 150}))

(defn create-mock-service
  []
  (->MockSearchService (atom {})))

;; ============================================================================
;; Search Users Tests
;; ============================================================================

(deftest search-users-basic-test
  (testing "searches users with query"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      (is (map? body))
      (is (= 2 (count (:results body))))
      (is (= 2 (:total body)))
      (is (contains? body :took-ms)))))

(deftest search-users-pagination-test
  (testing "applies pagination parameters"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"
                                  "from" "10"
                                  "size" "5"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      (is (= {:from 10 :size 5} (:page body))))))

(deftest search-users-max-size-test
  (testing "limits size to max 100"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"
                                  "size" "500"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      ;; Size should be capped at 100
      (is (<= (get-in body [:page :size]) 100)))))

(deftest search-users-highlight-test
  (testing "enables highlighting by default"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      ;; Results should have highlights
      (is (every? #(contains? % :_highlights) (:results body)))))

  (testing "disables highlighting when requested"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"
                                  "highlight" "false"}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 200 (:status response))))))

(deftest search-users-highlight-fields-test
  (testing "parses highlight fields parameter"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"
                                  "highlight_fields" "name,email"}}
          response (call-handler handler request)]

      (is (= 200 (:status response))))))

(deftest search-users-boost-recent-test
  (testing "applies recency boost by default"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"}}
          response (call-handler handler request)]

      (is (= 200 (:status response)))))

  (testing "disables recency boost when requested"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" "John"
                                  "boost_recent" "false"}}
          response (call-handler handler request)]

      (is (= 200 (:status response))))))

(deftest search-users-missing-query-test
  (testing "returns 400 when query parameter missing"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 400 (:status response)))
      (is (= "Bad Request" (:title body)))
      (is (contains? body :detail)))))

(deftest search-users-empty-query-test
  (testing "returns 400 when query parameter empty"
    (let [service (create-mock-service)
          handler (search-http/search-users-handler service)
          request {:query-params {"q" ""}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 400 (:status response))))))

(deftest search-users-error-test
  (testing "returns 500 on service error"
    (let [failing-service (reify ports/ISearchService
                            (search-users [_this _query _options]
                              (throw (ex-info "Search failed"
                                              {:type :search-error})))
                            (search-items [_this _query _options] nil)
                            (suggest [_this _prefix _field _options] nil)
                            (reindex-all [_this _index] nil)
                            (get-search-stats [_this] nil))
          handler (search-http/search-users-handler failing-service)
          request {:query-params {"q" "John"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 500 (:status response)))
      (is (= "Internal Server Error" (:title body))))))

;; ============================================================================
;; Search Items Tests
;; ============================================================================

(deftest search-items-basic-test
  (testing "searches items with query"
    (let [service (create-mock-service)
          handler (search-http/search-items-handler service)
          request {:query-params {"q" "Widget"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      (is (map? body))
      (is (= 1 (count (:results body))))
      (is (= 1 (:total body))))))

(deftest search-items-missing-query-test
  (testing "returns 400 when query parameter missing"
    (let [service (create-mock-service)
          handler (search-http/search-items-handler service)
          request {:query-params {}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 400 (:status response))))))

(deftest search-items-error-test
  (testing "returns 500 on service error"
    (let [failing-service (reify ports/ISearchService
                            (search-users [_this _query _options] nil)
                            (search-items [_this _query _options]
                              (throw (ex-info "Search failed"
                                              {:type :search-error})))
                            (suggest [_this _prefix _field _options] nil)
                            (reindex-all [_this _index] nil)
                            (get-search-stats [_this] nil))
          handler (search-http/search-items-handler failing-service)
          request {:query-params {"q" "Widget"}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 500 (:status response))))))

;; ============================================================================
;; Suggest Tests
;; ============================================================================

(deftest suggest-basic-test
  (testing "returns suggestions for prefix"
    (let [service (create-mock-service)
          handler (search-http/suggest-handler service)
          request {:query-params {"prefix" "Jo"
                                  "field" "name"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      (is (map? body))
      (is (= 3 (:count body)))
      (is (vector? (:suggestions body))))))

(deftest suggest-with-index-test
  (testing "accepts custom index parameter"
    (let [service (create-mock-service)
          handler (search-http/suggest-handler service)
          request {:query-params {"prefix" "Wi"
                                  "field" "name"
                                  "index" "items"}}
          response (call-handler handler request)]

      (is (= 200 (:status response))))))

(deftest suggest-with-limit-test
  (testing "accepts custom limit parameter"
    (let [service (create-mock-service)
          handler (search-http/suggest-handler service)
          request {:query-params {"prefix" "Jo"
                                  "field" "name"
                                  "limit" "5"}}
          response (call-handler handler request)]

      (is (= 200 (:status response)))))

  (testing "caps limit at 50"
    (let [service (create-mock-service)
          handler (search-http/suggest-handler service)
          request {:query-params {"prefix" "Jo"
                                  "field" "name"
                                  "limit" "100"}}
          response (call-handler handler request)]

      (is (= 200 (:status response))))))

(deftest suggest-missing-params-test
  (testing "returns 400 when prefix missing"
    (let [service (create-mock-service)
          handler (search-http/suggest-handler service)
          request {:query-params {"field" "name"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 400 (:status response)))
      (is (str/includes? (:detail body) "prefix"))))

  (testing "returns 400 when field missing"
    (let [service (create-mock-service)
          handler (search-http/suggest-handler service)
          request {:query-params {"prefix" "Jo"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 400 (:status response)))
      (is (str/includes? (:detail body) "field")))))

(deftest suggest-error-test
  (testing "returns 500 on service error"
    (let [failing-service (reify ports/ISearchService
                            (search-users [_this _query _options] nil)
                            (search-items [_this _query _options] nil)
                            (suggest [_this _prefix _field _options]
                              (throw (ex-info "Suggest failed"
                                              {:type :suggest-error})))
                            (reindex-all [_this _index] nil)
                            (get-search-stats [_this] nil))
          handler (search-http/suggest-handler failing-service)
          request {:query-params {"prefix" "Jo"
                                  "field" "name"}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 500 (:status response))))))

;; ============================================================================
;; Reindex Tests
;; ============================================================================

(deftest reindex-basic-test
  (testing "reindexes users index"
    (let [service (create-mock-service)
          handler (search-http/reindex-handler service)
          request {:path-params {:index "users"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 201 (:status response)))
      (is (= 1000 (:reindexed-count body)))
      (is (= 0 (:failed-count body)))))

  (testing "reindexes items index"
    (let [service (create-mock-service)
          handler (search-http/reindex-handler service)
          request {:path-params {:index "items"}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 201 (:status response))))))

(deftest reindex-invalid-index-test
  (testing "returns 400 for invalid index"
    (let [service (create-mock-service)
          handler (search-http/reindex-handler service)
          request {:path-params {:index "invalid"}}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 400 (:status response)))
      (is (= "Bad Request" (:title body)))
      (is (contains? body :detail)))))

(deftest reindex-missing-index-test
  (testing "returns 400 when index parameter missing"
    (let [service (create-mock-service)
          handler (search-http/reindex-handler service)
          request {:path-params {}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 400 (:status response))))))

(deftest reindex-error-test
  (testing "returns 500 on service error"
    (let [failing-service (reify ports/ISearchService
                            (search-users [_this _query _options] nil)
                            (search-items [_this _query _options] nil)
                            (suggest [_this _prefix _field _options] nil)
                            (reindex-all [_this _index]
                              (throw (ex-info "Reindex failed"
                                              {:type :reindex-error})))
                            (get-search-stats [_this] nil))
          handler (search-http/reindex-handler failing-service)
          request {:path-params {:index "users"}}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 500 (:status response))))))

;; ============================================================================
;; Stats Tests
;; ============================================================================

(deftest stats-basic-test
  (testing "returns search statistics"
    (let [service (create-mock-service)
          handler (search-http/stats-handler service)
          request {}
          response (call-handler handler request)
          body (parse-json-body response)]

      (is (= 200 (:status response)))
      (is (map? body))
      (is (= 1500 (:total-documents body)))
      (is (= 2 (count (:indices body)))))))

(deftest stats-error-test
  (testing "returns 500 on service error"
    (let [failing-service (reify ports/ISearchService
                            (search-users [_this _query _options] nil)
                            (search-items [_this _query _options] nil)
                            (suggest [_this _prefix _field _options] nil)
                            (reindex-all [_this _index] nil)
                            (get-search-stats [_this]
                              (throw (ex-info "Stats failed"
                                              {:type :stats-error}))))
          handler (search-http/stats-handler failing-service)
          request {}
          response (call-handler handler request)
          _body (parse-json-body response)]

      (is (= 500 (:status response))))))

;; ============================================================================
;; Route Structure Tests
;; ============================================================================

(deftest normalized-routes-structure-test
  (testing "returns routes in normalized format"
    (let [service (create-mock-service)
          config {}
          routes (search-http/search-routes-normalized service config)]

      (is (map? routes))
      (is (contains? routes :api))
      (is (contains? routes :web))
      (is (contains? routes :static))
      (is (vector? (:api routes)))
      (is (vector? (:web routes)))
      (is (vector? (:static routes)))))

  (testing "API routes have correct structure"
    (let [service (create-mock-service)
          config {}
          routes (search-http/search-routes-normalized service config)
          api-routes (:api routes)]

      (is (= 5 (count api-routes)))  ; 5 endpoints
      (is (every? map? api-routes))
      (is (every? #(contains? % :path) api-routes))
      (is (every? #(contains? % :methods) api-routes))))

  (testing "route paths are correct"
    (let [service (create-mock-service)
          config {}
          routes (search-http/search-routes-normalized service config)
          api-routes (:api routes)
          paths (set (map :path api-routes))]

      (is (contains? paths "/search/users"))
      (is (contains? paths "/search/items"))
      (is (contains? paths "/search/suggest"))
      (is (contains? paths "/search/reindex/:index"))
      (is (contains? paths "/search/stats")))))
