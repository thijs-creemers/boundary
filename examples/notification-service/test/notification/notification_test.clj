(ns notification.notification-test
  "Tests for notification logic and templates."
  (:require [clojure.test :refer [deftest testing is]]
            [notification.notification.core.notification :as notif-core])
  (:import [java.time Instant]))

;; =============================================================================
;; Price Formatting Tests
;; =============================================================================

(deftest format-price-test
  (testing "formats EUR prices"
    (is (= "€10.00" (notif-core/format-price 1000 "EUR")))
    (is (= "€99.99" (notif-core/format-price 9999 "EUR")))
    (is (= "€0.50" (notif-core/format-price 50 "EUR"))))
  
  (testing "formats USD prices"
    (is (= "$10.00" (notif-core/format-price 1000 "USD")))
    (is (= "$1234.56" (notif-core/format-price 123456 "USD"))))
  
  (testing "formats GBP prices"
    (is (= "£10.00" (notif-core/format-price 1000 "GBP"))))
  
  (testing "handles unknown currencies"
    (is (= "JPY10.00" (notif-core/format-price 1000 "JPY")))))

;; =============================================================================
;; Template Rendering Tests
;; =============================================================================

(deftest render-template-test
  (testing "renders order confirmation template"
    (let [context {:order-number "ORD-001"
                   :customer-name "John Doe"
                   :total "€99.99"}
          result (notif-core/render-template :order-confirmation context)]
      (is (:ok result))
      (is (clojure.string/includes? (get-in result [:ok :subject]) "ORD-001"))
      (is (clojure.string/includes? (get-in result [:ok :body]) "John Doe"))))
  
  (testing "renders payment receipt template"
    (let [context {:order-number "ORD-002"
                   :amount "€50.00"
                   :payment-method "Credit Card"}
          result (notif-core/render-template :payment-receipt context)]
      (is (:ok result))
      (is (clojure.string/includes? (get-in result [:ok :body]) "€50.00"))))
  
  (testing "returns error for unknown template"
    (let [result (notif-core/render-template :unknown-template {})]
      (is (= :template-not-found (:error result))))))

;; =============================================================================
;; Status Transition Tests
;; =============================================================================

(deftest mark-sent-test
  (testing "marks notification as sent"
    (let [now (Instant/now)
          notification {:id (random-uuid)
                        :status :pending
                        :attempts 0
                        :sent-at nil}
          result (notif-core/mark-sent notification now)]
      (is (= :sent (:status result)))
      (is (= 1 (:attempts result)))
      (is (= now (:sent-at result)))
      (is (= now (:last-attempt-at result))))))

(deftest mark-failed-test
  (testing "marks notification as failed with error"
    (let [now (Instant/now)
          notification {:id (random-uuid)
                        :status :pending
                        :attempts 0
                        :error nil}
          result (notif-core/mark-failed notification "Connection timeout" now)]
      (is (= :failed (:status result)))
      (is (= 1 (:attempts result)))
      (is (= "Connection timeout" (:error result))))))

(deftest reset-for-retry-test
  (testing "resets notification for retry"
    (let [now (Instant/now)
          notification {:id (random-uuid)
                        :status :failed
                        :attempts 2
                        :error "Previous error"}
          result (notif-core/reset-for-retry notification now)]
      (is (= :pending (:status result)))
      (is (= 2 (:attempts result)))  ;; Attempts not reset
      (is (nil? (:error result))))))

;; =============================================================================
;; Retry Logic Tests
;; =============================================================================

(deftest can-retry?-test
  (let [config {:max-attempts 3}]
    (testing "can retry failed notification under max attempts"
      (let [notification {:status :failed :attempts 1}]
        (is (true? (notif-core/can-retry? notification config)))))
    
    (testing "cannot retry if max attempts reached"
      (let [notification {:status :failed :attempts 3}]
        (is (false? (notif-core/can-retry? notification config)))))
    
    (testing "cannot retry sent notification"
      (let [notification {:status :sent :attempts 1}]
        (is (false? (notif-core/can-retry? notification config)))))
    
    (testing "cannot retry pending notification"
      (let [notification {:status :pending :attempts 0}]
        (is (false? (notif-core/can-retry? notification config)))))))

;; =============================================================================
;; Notification Creation Tests
;; =============================================================================

(deftest create-notification-test
  (testing "creates notification with all fields"
    (let [now (Instant/now)
          event {:id (random-uuid)
                 :type :order/placed
                 :payload {:order-number "ORD-001"
                           :customer-name "Jane"}
                 :metadata {:timestamp now}}
          notification (notif-core/create-notification 
                        event :email :order-confirmation "jane@example.com" now)]
      (is (uuid? (:id notification)))
      (is (= (:id event) (:event-id notification)))
      (is (= :email (:channel notification)))
      (is (= "jane@example.com" (:recipient notification)))
      (is (= :order-confirmation (:template notification)))
      (is (= :pending (:status notification)))
      (is (= 0 (:attempts notification))))))

;; =============================================================================
;; API Transformation Tests
;; =============================================================================

(deftest notification->api-test
  (testing "transforms notification for API response"
    (let [notification {:id (random-uuid)
                        :channel :email
                        :template :order-confirmation
                        :status :sent}
          api (notif-core/notification->api notification)]
      (is (= "email" (:channel api)))
      (is (= "order-confirmation" (:template api)))
      (is (= "sent" (:status api))))))
