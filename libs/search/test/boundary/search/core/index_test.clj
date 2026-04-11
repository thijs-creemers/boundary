(ns boundary.search.core.index-test
  "Unit tests for the search index registry and document construction."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.search.core.index :as index])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Test fixture
;; =============================================================================

(defn- registry-fixture [f]
  (index/clear-registry!)
  (f)
  (index/clear-registry!))

(use-fixtures :each registry-fixture)

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private sample-def
  {:id          :product-search
   :entity-type :product
   :language    :english
   :fields      [{:name :title       :weight :a}
                 {:name :description :weight :b}]
   :options     {:highlight? true}})

(deftest ^:unit register-and-get-test
  (testing "registers and retrieves a definition by id"
    (index/register-search! sample-def)
    (let [found (index/get-search :product-search)]
      (is (= :product-search (:id found)))
      (is (= :product (:entity-type found)))))

  (testing "returns nil for unknown index"
    (is (nil? (index/get-search :unknown-index))))

  (testing "list-searches returns registered ids"
    (index/register-search! sample-def)
    (is (contains? (set (index/list-searches)) :product-search)))

  (testing "clear-registry! removes all definitions"
    (index/register-search! sample-def)
    (index/clear-registry!)
    (is (empty? (index/list-searches)))))

;; =============================================================================
;; defsearch macro
;; =============================================================================

(deftest ^:unit defsearch-macro-test
  (testing "defsearch binds a var and registers the definition"
    (index/defsearch order-search
      {:id          :order-search
       :entity-type :order
       :language    :english
       :fields      [{:name :status :weight :a}]})
    (is (= :order-search (:id order-search)))
    (is (some? (index/get-search :order-search)))))

;; =============================================================================
;; build-document
;; =============================================================================

(def ^:private fixed-document-id "search-doc-001")
(def ^:private fixed-updated-at (Instant/parse "2026-04-10T12:00:00Z"))

(deftest ^:unit build-document-test
  (testing "maps fields to correct weight buckets"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :english
                      :fields      [{:name :title       :weight :a}
                                    {:name :description :weight :b}
                                    {:name :tags        :weight :c}]}
          entity-id  (UUID/fromString "11111111-1111-1111-1111-111111111111")
          doc        (index/build-document* definition entity-id
                                            {:title "Widget Pro"
                                             :description "A great widget"
                                             :tags "tools hardware"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= fixed-document-id (:id doc)))
      (is (= :product (:entity-type doc)))
      (is (= entity-id (:entity-id doc)))
      (is (= "Widget Pro" (:weight-a doc)))
      (is (= "A great widget" (:weight-b doc)))
      (is (= "tools hardware" (:weight-c doc)))
      (is (= "" (:weight-d doc)))
      (is (string? (:content-all doc)))
      (is (.contains (:content-all doc) "Widget Pro"))
      (is (.contains (:content-all doc) "A great widget"))
      (is (= fixed-updated-at (:updated-at doc)))))

  (testing "joins seq values with space"
    (let [definition {:id          :article-search
                      :entity-type :article
                      :language    :english
                      :fields      [{:name :tags :weight :c}]}
          entity-id  (UUID/fromString "22222222-2222-2222-2222-222222222222")
          doc        (index/build-document* definition entity-id
                                            {:tags ["clojure" "functional" "search"]}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "clojure functional search" (:weight-c doc)))
      (is (.contains (:content-all doc) "clojure"))))

  (testing "handles nil field values gracefully"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :english
                      :fields      [{:name :title       :weight :a}
                                    {:name :description :weight :b}]}
          entity-id  (UUID/fromString "33333333-3333-3333-3333-333333333333")
          doc        (index/build-document* definition entity-id {:title "Widget"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "Widget" (:weight-a doc)))
      (is (= "" (:weight-b doc)))))

  (testing "attaches metadata when provided"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :english
                      :fields      [{:name :title :weight :a}]}
          entity-id  (UUID/fromString "44444444-4444-4444-4444-444444444444")
          doc        (index/build-document* definition entity-id {:title "Widget"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at
                                             :metadata {:price 9.99 :sku "WGT-001"}})]
      (is (= 9.99 (get-in doc [:metadata :price])))
      (is (= "WGT-001" (get-in doc [:metadata :sku])))))

  (testing "uses :english as default language when not specified"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :fields      [{:name :title :weight :a}]}
          entity-id  (UUID/fromString "55555555-5555-5555-5555-555555555555")
          doc        (index/build-document* definition entity-id {:title "test"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "english" (:language doc)))))

  (testing "uses definition language when specified"
    (let [definition {:id          :product-search
                      :entity-type :product
                      :language    :dutch
                      :fields      [{:name :title :weight :a}]}
          entity-id  (UUID/fromString "66666666-6666-6666-6666-666666666666")
          doc        (index/build-document* definition entity-id {:title "Widget"}
                                            {:id fixed-document-id
                                             :updated-at fixed-updated-at})]
      (is (= "dutch" (:language doc))))))

(deftest ^:unit build-document-deprecated-test
  (testing "legacy API fails loudly to force explicit id and timestamp injection"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"build-document is deprecated"
         (index/build-document sample-def
                               (UUID/fromString "77777777-7777-7777-7777-777777777777")
                               {:title "Widget"})))))
