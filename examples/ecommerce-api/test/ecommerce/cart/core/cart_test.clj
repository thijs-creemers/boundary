(ns ecommerce.cart.core.cart-test
  (:require [clojure.test :refer [deftest is testing]]
            [ecommerce.cart.core.cart :as cart])
  (:import [java.time Instant]))

(def test-instant (Instant/parse "2026-01-17T12:00:00Z"))
(def product-id-1 (random-uuid))
(def product-id-2 (random-uuid))

;; =============================================================================
;; Cart Creation Tests
;; =============================================================================

(deftest create-cart-test
  (testing "creates empty cart"
    (let [cart (cart/create-cart "session-123" test-instant)]
      (is (uuid? (:id cart)))
      (is (= "session-123" (:session-id cart)))
      (is (empty? (:items cart)))
      (is (= test-instant (:created-at cart))))))

;; =============================================================================
;; Cart Item Operations Tests
;; =============================================================================

(deftest add-item-test
  (let [empty-cart (cart/create-cart "session" test-instant)]
    
    (testing "adds new item to empty cart"
      (let [cart (cart/add-item empty-cart product-id-1 2 test-instant)]
        (is (= 1 (count (:items cart))))
        (let [item (first (:items cart))]
          (is (= product-id-1 (:product-id item)))
          (is (= 2 (:quantity item))))))
    
    (testing "increases quantity for existing item"
      (let [cart (-> empty-cart
                     (cart/add-item product-id-1 2 test-instant)
                     (cart/add-item product-id-1 3 test-instant))]
        (is (= 1 (count (:items cart))))
        (is (= 5 (:quantity (first (:items cart)))))))
    
    (testing "adds multiple different items"
      (let [cart (-> empty-cart
                     (cart/add-item product-id-1 2 test-instant)
                     (cart/add-item product-id-2 1 test-instant))]
        (is (= 2 (count (:items cart))))))))

(deftest find-item-test
  (let [cart (-> (cart/create-cart "session" test-instant)
                 (cart/add-item product-id-1 2 test-instant))]
    
    (testing "finds existing item"
      (let [item (cart/find-item cart product-id-1)]
        (is (some? item))
        (is (= 2 (:quantity item)))))
    
    (testing "returns nil for non-existing item"
      (is (nil? (cart/find-item cart product-id-2))))))

(deftest update-item-quantity-test
  (let [cart (-> (cart/create-cart "session" test-instant)
                 (cart/add-item product-id-1 2 test-instant))]
    
    (testing "updates quantity"
      (let [updated (cart/update-item-quantity cart product-id-1 5 test-instant)]
        (is (= 5 (:quantity (cart/find-item updated product-id-1))))))
    
    (testing "returns nil for non-existing item"
      (is (nil? (cart/update-item-quantity cart product-id-2 5 test-instant))))))

(deftest remove-item-test
  (let [cart (-> (cart/create-cart "session" test-instant)
                 (cart/add-item product-id-1 2 test-instant)
                 (cart/add-item product-id-2 1 test-instant))]
    
    (testing "removes specific item"
      (let [updated (cart/remove-item cart product-id-1 test-instant)]
        (is (= 1 (count (:items updated))))
        (is (nil? (cart/find-item updated product-id-1)))
        (is (some? (cart/find-item updated product-id-2)))))
    
    (testing "no-op for non-existing item"
      (let [updated (cart/remove-item cart (random-uuid) test-instant)]
        (is (= 2 (count (:items updated))))))))

(deftest clear-items-test
  (let [cart (-> (cart/create-cart "session" test-instant)
                 (cart/add-item product-id-1 2 test-instant)
                 (cart/add-item product-id-2 1 test-instant))]
    
    (testing "removes all items"
      (let [cleared (cart/clear-items cart test-instant)]
        (is (empty? (:items cleared)))))))

;; =============================================================================
;; Cart Calculation Tests
;; =============================================================================

(deftest calculate-cart-totals-test
  (let [cart (-> (cart/create-cart "session" test-instant)
                 (cart/add-item product-id-1 2 test-instant)
                 (cart/add-item product-id-2 1 test-instant))
        products {product-id-1 {:id product-id-1 :name "Product 1" :price-cents 1000}
                  product-id-2 {:id product-id-2 :name "Product 2" :price-cents 500}}
        totals (cart/calculate-cart-totals cart products)]
    
    (testing "calculates subtotal"
      ;; (2 * 1000) + (1 * 500) = 2500
      (is (= 2500 (:subtotal-cents totals))))
    
    (testing "calculates item count"
      ;; 2 + 1 = 3
      (is (= 3 (:item-count totals))))
    
    (testing "enriches items with product info"
      (let [first-item (first (:items totals))]
        (is (some? (:product first-item)))
        (is (= 2000 (:line-total-cents first-item)))))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-quantity-test
  (testing "valid positive integer"
    (is (= {:ok 5} (cart/validate-quantity 5))))
  
  (testing "rejects zero"
    (is (= :validation (:error (cart/validate-quantity 0)))))
  
  (testing "rejects negative"
    (is (= :validation (:error (cart/validate-quantity -1)))))
  
  (testing "rejects non-integer"
    (is (= :validation (:error (cart/validate-quantity 1.5))))))

(deftest cart-empty?-test
  (let [empty-cart (cart/create-cart "session" test-instant)
        cart-with-items (cart/add-item empty-cart product-id-1 1 test-instant)]
    
    (testing "returns true for empty cart"
      (is (cart/cart-empty? empty-cart)))
    
    (testing "returns false for non-empty cart"
      (is (not (cart/cart-empty? cart-with-items))))))
