(ns ecommerce.payment.shell.service
  "Payment service - orchestrates payment operations."
  (:require [ecommerce.payment.ports :as ports]
            [ecommerce.payment.core.payment :as payment-core]
            [ecommerce.order.ports :as order-ports]))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord PaymentService [provider order-service]
  ports/IPaymentService
  
  (create-payment-intent [_ order-id]
    ;; Get order
    (let [order-result (order-ports/get-order order-service order-id)]
      (if-let [order (:ok order-result)]
        ;; Check order is pending payment
        (if (= :pending (:status order))
          ;; Create payment intent
          (let [intent-data (payment-core/create-payment-intent-data order)
                intent (ports/create-intent provider
                                            (:amount intent-data)
                                            (:currency intent-data)
                                            (:metadata intent-data))]
            ;; Update order with payment intent ID
            (order-ports/update-status order-service order-id :pending)
            {:ok {:payment-intent intent
                  :client-secret (:client-secret intent)}})
          {:error :invalid-state
           :message "Order is not pending payment"
           :order-status (:status order)})
        order-result)))
  
  (handle-webhook [_ event]
    (let [event-type (:type event)]
      (if (payment-core/should-process-event? event-type)
        ;; Extract info from event
        (let [payment-intent-id (payment-core/extract-payment-intent-id event)
              order-id (payment-core/extract-order-id event)
              action (payment-core/determine-order-action event-type)]
          (if order-id
            ;; Process based on event type
            (case action
              :mark-paid
              (let [result (order-ports/mark-paid order-service order-id payment-intent-id)]
                (if (:ok result)
                  {:ok {:action :order-marked-paid
                        :order-id order-id}}
                  result))
              
              :cancel
              (let [result (order-ports/cancel-order order-service order-id)]
                (if (:ok result)
                  {:ok {:action :order-cancelled
                        :order-id order-id}}
                  result))
              
              ;; :mark-failed - log but don't change order yet
              {:ok {:action :payment-failed
                    :order-id order-id}})
            {:error :invalid-event
             :message "Could not extract order ID from event"}))
        ;; Event type not handled
        {:ok {:action :ignored
              :event-type event-type}})))
  
  (get-payment-status [_ order-id]
    (let [order-result (order-ports/get-order order-service order-id)]
      (if-let [order (:ok order-result)]
        (if-let [intent-id (:payment-intent-id order)]
          (if-let [intent (ports/retrieve-intent provider intent-id)]
            {:ok (payment-core/payment-status->api intent order)}
            {:error :not-found :id intent-id})
          {:ok {:order-id (:id order)
                :order-status (name (:status order))
                :payment-intent-id nil
                :message "No payment intent created yet"}})
        order-result))))
