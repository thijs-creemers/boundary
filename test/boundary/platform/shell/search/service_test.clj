(ns boundary.platform.shell.search.service-test
  "Tests for search service orchestration.
   
   Tests the coordination between search providers and core logic."
  {:kaocha.testable/meta {:integration true :search true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.platform.shell.search.service :as svc]
            [boundary.platform.search.ports :as ports]
            [boundary.platform.core.search.query :as query])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

;; ============================================================================
;; Mock Search Provider
;; ============================================================================

(defrecord MockSearchProvider [state]
  ports/ISearchProvider
  
  (search [_this query-map]
    ;; Return mock results based on query
    (let [index (:index query-map)
          from (:from query-map 0)
          size (:size query-map 20)]
      (case index
        :users
        {:results [{:id "user-1"
                   :name "John Doe"
                   :email "john@example.com"
                   :created_at (Instant/now)
                   :score 0.95}
                  {:id "user-2"
                   :name "Jane Doe"
                   :email "jane@example.com"
                   :created_at (.minus (Instant/now) 15 ChronoUnit/DAYS)
                   :score 0.85}
                  {:id "user-3"
                   :name "John Smith"
                   :email "john.smith@example.com"
                   :created_at (.minus (Instant/now) 60 ChronoUnit/DAYS)
                   :score 0.75}]
         :total 3
         :max-score 0.95
         :took-ms 10}
        
        :items
        {:results [{:id "item-1"
                   :name "Widget A"
                   :sku "WID-001"
                   :location "Warehouse 1"
                   :created_at (Instant/now)
                   :score 0.90}
                  {:id "item-2"
                   :name "Widget B"
                   :sku "WID-002"
                   :location "Warehouse 2"
                   :created_at (.minus (Instant/now) 30 ChronoUnit/DAYS)
                   :score 0.80}]
         :total 2
         :max-score 0.90
         :took-ms 8}
        
        ;; Default empty result
        {:results []
         :total 0
         :max-score 0.0
         :took-ms 0})))
  
  (index-document [_this index-name document]
    {:indexed true
     :id (:id document)
     :index index-name})
  
  (delete-document [_this index-name document-id]
    {:deleted true
     :id document-id
     :index index-name})
  
  (update-document [_this index-name document-id updates]
    {:updated true
     :id document-id
     :index index-name})
  
  (bulk-index [_this index-name documents]
    {:indexed-count (count documents)
     :failed-count 0
     :errors []})
  
  (create-index [_this index-name config]
    {:created true
     :index index-name})
  
  (delete-index [_this index-name]
    {:deleted true
     :index index-name})
  
  (get-index-stats [_this index-name]
    (case index-name
      :users {:index :users
              :document-count 1000
              :size-bytes 1048576
              :last-updated (Instant/now)}
      :items {:index :items
              :document-count 500
              :size-bytes 524288
              :last-updated (Instant/now)}
      {:index index-name
       :document-count 0
       :size-bytes 0
       :last-updated nil})))

(defn create-mock-provider
  []
  (->MockSearchProvider (atom {})))

;; ============================================================================
;; Test Configuration
;; ============================================================================

(def test-config
  {:ranking {:users {:recency-field :created_at
                    :recency-max-boost 2.0
                    :recency-decay-days 30}
            :items {:recency-field :created_at
                    :recency-max-boost 2.0
                    :recency-decay-days 30}}
   :highlighting {:pre-tag "<mark>"
                 :post-tag "</mark>"
                 :max-fragments 3
                 :fragment-size 150}})

;; ============================================================================
;; Service Creation Tests
;; ============================================================================

(deftest create-search-service-test
  (testing "creates search service with provider and config"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)]
      (is (some? service))
      (is (satisfies? ports/ISearchService service))))
  
  (testing "service implements all protocol methods"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)]
      (is (satisfies? ports/ISearchService service))
      ;; Verify protocol methods work
      (is (map? (ports/search-users service "test" {})))
      (is (map? (ports/search-items service "test" {})))
      (is (map? (ports/suggest service "t" :name {})))
      (is (map? (ports/reindex-all service :users)))
      (is (map? (ports/get-search-stats service))))))

;; ============================================================================
;; User Search Tests
;; ============================================================================

(deftest search-users-basic-test
  (testing "searches users with simple query"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {})]
      
      (is (map? result))
      (is (contains? result :results))
      (is (contains? result :total))
      (is (contains? result :took-ms))
      (is (= 3 (count (:results result))))
      (is (= 3 (:total result)))))
  
  (testing "adds rank positions to results"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {})]
      
      (is (every? #(contains? % :rank) (:results result)))
      (is (= [1 2 3] (map :rank (:results result))))))
  
  (testing "sorts results by score descending"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {})]
      
      (let [scores (map :score (:results result))]
        (is (= scores (sort > scores))))))
  
  (testing "includes pagination info"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {:from 10 :size 5})]
      
      (is (= {:from 10 :size 5} (:page result))))))

(deftest search-users-highlighting-test
  (testing "applies highlighting by default"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {})]
      
      (is (every? #(contains? % :_highlights) (:results result)))
      
      ;; Check first result has highlighted name
      (let [first-result (first (:results result))
            highlighted-name (get-in first-result [:_highlights :name])]
        (is (some? highlighted-name))
        (is (re-find #"<mark>John</mark>" highlighted-name)))))
  
  (testing "skips highlighting when disabled"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {:highlight? false})]
      
      ;; Results should not have _highlights
      (is (not-any? #(contains? % :_highlights) (:results result)))))
  
  (testing "highlights custom fields"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "example" 
                                    {:highlight-fields [:email]})]
      
      (let [first-result (first (:results result))]
        (is (contains? (:_highlights first-result) :email))))))

(deftest search-users-recency-boost-test
  (testing "applies recency boost by default"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {:boost-recent? true})]
      
      ;; Newer documents should have higher scores after boosting
      ;; Mock returns user-1 (0 days old, score 0.95)
      ;;             user-2 (15 days old, score 0.85)
      ;;             user-3 (60 days old, score 0.75)
      ;; After recency boost, user-1 should get 2x boost
      (let [scores (map :score (:results result))]
        (is (> (first scores) 0.95))  ; Boosted
        (is (= scores (sort > scores))))))
  
  (testing "skips recency boost when disabled"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "John" {:boost-recent? false})]
      
      ;; Scores should be original from provider
      (is (= 0.95 (:score (first (:results result))))))))

(deftest search-users-error-handling-test
  (testing "handles empty query gracefully"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-users service "" {})]
      
      ;; Should still return valid response structure
      (is (map? result))
      (is (contains? result :results))
      (is (contains? result :total))))
  
  (testing "propagates provider errors"
    (let [failing-provider (reify ports/ISearchProvider
                            (search [_this _query-map]
                              (throw (ex-info "Provider error" 
                                            {:type :provider-error}))))
          service (svc/create-search-service failing-provider test-config)]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo 
                           #"User search failed"
                           (ports/search-users service "John" {}))))))

;; ============================================================================
;; Item Search Tests
;; ============================================================================

(deftest search-items-basic-test
  (testing "searches items with simple query"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-items service "Widget" {})]
      
      (is (map? result))
      (is (contains? result :results))
      (is (contains? result :total))
      (is (= 2 (count (:results result))))
      (is (= 2 (:total result)))))
  
  (testing "uses item-specific highlight fields"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/search-items service "Widget" 
                                    {:highlight-fields [:name :sku :location]})]
      
      (let [first-result (first (:results result))]
        (is (contains? (:_highlights first-result) :name))
        ;; SKU and location may not match "Widget" in this test
        ))))

(deftest search-items-error-handling-test
  (testing "handles item search errors"
    (let [failing-provider (reify ports/ISearchProvider
                            (search [_this _query-map]
                              (throw (ex-info "Provider error" 
                                            {:type :provider-error}))))
          service (svc/create-search-service failing-provider test-config)]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo 
                           #"Item search failed"
                           (ports/search-items service "Widget" {}))))))

;; ============================================================================
;; Suggest (Autocomplete) Tests
;; ============================================================================

(deftest suggest-test
  (testing "returns suggestions for prefix"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/suggest service "Jo" :name {:index :users})]
      
      (is (map? result))
      (is (contains? result :suggestions))
      (is (contains? result :count))
      (is (vector? (:suggestions result)))))
  
  (testing "limits number of suggestions"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/suggest service "Jo" :name {:index :users :limit 2})]
      
      (is (<= (:count result) 2))))
  
  (testing "handles suggest errors"
    (let [failing-provider (reify ports/ISearchProvider
                            (search [_this _query-map]
                              (throw (ex-info "Provider error" 
                                            {:type :provider-error}))))
          service (svc/create-search-service failing-provider test-config)]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo 
                           #"Suggestion failed"
                           (ports/suggest service "Jo" :name {}))))))

;; ============================================================================
;; Reindex Tests
;; ============================================================================

(deftest reindex-all-test
  (testing "reindexes all documents in index"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/reindex-all service :users)]
      
      (is (map? result))
      (is (contains? result :reindexed-count))
      (is (contains? result :failed-count))
      (is (contains? result :duration-ms))
      (is (= 1000 (:reindexed-count result)))  ; From mock stats
      (is (= 0 (:failed-count result)))))
  
  (testing "handles reindex errors"
    (let [failing-provider (reify ports/ISearchProvider
                            (get-index-stats [_this _index-name]
                              (throw (ex-info "Provider error" 
                                            {:type :provider-error}))))
          service (svc/create-search-service failing-provider test-config)]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo 
                           #"Reindex failed"
                           (ports/reindex-all service :users))))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

(deftest get-search-stats-test
  (testing "returns statistics for all indexes"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/get-search-stats service)]
      
      (is (map? result))
      (is (contains? result :indices))
      (is (contains? result :total-documents))
      (is (vector? (:indices result)))
      (is (= 2 (count (:indices result))))  ; users and items
      (is (= 1500 (:total-documents result)))))  ; 1000 + 500
  
  (testing "handles stats errors gracefully"
    (let [failing-provider (reify ports/ISearchProvider
                            (get-index-stats [_this index-name]
                              (if (= index-name :users)
                                (throw (ex-info "Provider error" 
                                              {:type :provider-error}))
                                {:index index-name
                                 :document-count 500
                                 :size-bytes 1024
                                 :last-updated (Instant/now)})))
          service (svc/create-search-service failing-provider test-config)
          result (ports/get-search-stats service)]
      
      ;; Should still return stats, with error for failing index
      (is (map? result))
      (is (= 2 (count (:indices result))))
      (is (some #(contains? % :error) (:indices result)))))
  
  (testing "calculates total documents correctly"
    (let [provider (create-mock-provider)
          service (svc/create-search-service provider test-config)
          result (ports/get-search-stats service)]
      
      (is (= 1500 (:total-documents result))))))

;; ============================================================================
;; Configuration Tests
;; ============================================================================

(deftest configuration-handling-test
  (testing "uses default configuration when not provided"
    (let [provider (create-mock-provider)
          minimal-config {}
          service (svc/create-search-service provider minimal-config)
          result (ports/search-users service "John" {})]
      
      ;; Should still work with empty config
      (is (map? result))
      (is (seq (:results result)))))
  
  (testing "applies custom highlighting tags"
    (let [provider (create-mock-provider)
          custom-config {:ranking {}
                        :highlighting {:pre-tag "<em class=\"highlight\">"
                                      :post-tag "</em>"}}
          service (svc/create-search-service provider custom-config)
          result (ports/search-users service "John" {})]
      
      (let [highlighted-name (get-in (first (:results result)) 
                                    [:_highlights :name])]
        (is (re-find #"<em class=\"highlight\">John</em>" highlighted-name))))))
