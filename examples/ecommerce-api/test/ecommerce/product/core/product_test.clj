(ns ecommerce.product.core.product-test
  (:require [clojure.test :refer [deftest is testing]]
            [ecommerce.product.core.product :as product])
  (:import [java.time Instant]))

(def test-instant (Instant/parse "2026-01-17T12:00:00Z"))

;; =============================================================================
;; Slug Generation Tests
;; =============================================================================

(deftest generate-slug-test
  (testing "basic slug generation"
    (is (= "hello-world" (product/generate-slug "Hello World"))))
  
  (testing "removes special characters"
    (is (= "clojure-mug-large" (product/generate-slug "Clojure Mug (Large)"))))
  
  (testing "handles multiple spaces"
    (is (= "foo-bar" (product/generate-slug "foo   bar"))))
  
  (testing "removes leading/trailing dashes"
    (is (= "product" (product/generate-slug "-product-")))))

;; =============================================================================
;; Product Creation Tests
;; =============================================================================

(deftest create-product-test
  (testing "creates product with all fields"
    (let [input {:name "Test Product"
                 :description "A test product"
                 :price-cents 2999
                 :currency "USD"
                 :stock 100
                 :active true}
          product (product/create-product input test-instant)]
      (is (uuid? (:id product)))
      (is (= "Test Product" (:name product)))
      (is (= "test-product" (:slug product)))
      (is (= "A test product" (:description product)))
      (is (= 2999 (:price-cents product)))
      (is (= "USD" (:currency product)))
      (is (= 100 (:stock product)))
      (is (true? (:active product)))
      (is (= test-instant (:created-at product)))))
  
  (testing "uses defaults for optional fields"
    (let [input {:name "Minimal Product"
                 :price-cents 999}
          product (product/create-product input test-instant)]
      (is (= "EUR" (:currency product)))
      (is (= 0 (:stock product)))
      (is (true? (:active product))))))

;; =============================================================================
;; Product Update Tests
;; =============================================================================

(deftest update-product-test
  (let [product {:id (random-uuid)
                 :name "Original"
                 :slug "original"
                 :price-cents 1000
                 :stock 10
                 :active true
                 :updated-at (Instant/parse "2026-01-01T00:00:00Z")}]
    
    (testing "updates name and regenerates slug"
      (let [updated (product/update-product product {:name "New Name"} test-instant)]
        (is (= "New Name" (:name updated)))
        (is (= "new-name" (:slug updated)))
        (is (= test-instant (:updated-at updated)))))
    
    (testing "updates price"
      (let [updated (product/update-product product {:price-cents 2000} test-instant)]
        (is (= 2000 (:price-cents updated)))))
    
    (testing "can set description to nil"
      (let [updated (product/update-product product {:description nil} test-instant)]
        (is (nil? (:description updated)))))
    
    (testing "partial update preserves other fields"
      (let [updated (product/update-product product {:stock 50} test-instant)]
        (is (= "Original" (:name updated)))
        (is (= 50 (:stock updated)))))))

;; =============================================================================
;; Stock Management Tests
;; =============================================================================

(deftest adjust-stock-test
  (let [product {:id (random-uuid) :stock 10}]
    
    (testing "increases stock"
      (let [result (product/adjust-stock product 5 test-instant)]
        (is (= 15 (get-in result [:ok :stock])))))
    
    (testing "decreases stock"
      (let [result (product/adjust-stock product -3 test-instant)]
        (is (= 7 (get-in result [:ok :stock])))))
    
    (testing "prevents negative stock"
      (let [result (product/adjust-stock product -15 test-instant)]
        (is (= :insufficient-stock (:error result)))
        (is (= 10 (:available result)))
        (is (= 15 (:requested result)))))))

(deftest check-stock-test
  (let [product {:id (random-uuid) :stock 10}]
    
    (testing "sufficient stock"
      (is (= {:ok true} (product/check-stock product 5))))
    
    (testing "exact stock"
      (is (= {:ok true} (product/check-stock product 10))))
    
    (testing "insufficient stock"
      (let [result (product/check-stock product 15)]
        (is (= :insufficient-stock (:error result)))))))

;; =============================================================================
;; Price Formatting Tests
;; =============================================================================

(deftest format-price-test
  (testing "EUR formatting"
    (is (= "€29.99" (product/format-price 2999 "EUR"))))
  
  (testing "USD formatting"
    (is (= "$14.99" (product/format-price 1499 "USD"))))
  
  (testing "GBP formatting"
    (is (= "£10.00" (product/format-price 1000 "GBP"))))
  
  (testing "unknown currency uses code"
    (is (= "CHF12.50" (product/format-price 1250 "CHF")))))

(deftest calculate-line-total-test
  (testing "single item"
    (is (= 2999 (product/calculate-line-total 2999 1))))
  
  (testing "multiple items"
    (is (= 8997 (product/calculate-line-total 2999 3)))))

;; =============================================================================
;; Serialization Tests
;; =============================================================================

(deftest product->api-test
  (let [product {:id (random-uuid)
                 :name "Test"
                 :slug "test"
                 :price-cents 2999
                 :currency "EUR"
                 :stock 10
                 :active true
                 :created-at test-instant
                 :updated-at test-instant}
        api (product/product->api product)]
    
    (testing "adds formatted price"
      (is (= "€29.99" (:price-formatted api))))
    
    (testing "removes timestamps"
      (is (nil? (:created-at api)))
      (is (nil? (:updated-at api))))))
