(ns ecommerce.payment.ports
  "Port definitions for the payment module.")

;; =============================================================================
;; Payment Provider Port
;; =============================================================================

(defprotocol IPaymentProvider
  "Interface for payment provider integration.
   
   This abstracts away the actual payment provider (Stripe, etc.)
   allowing easy swapping between mock and real implementations."
  
  (create-intent [this amount currency metadata]
    "Create a payment intent.
     Returns {:id, :amount, :currency, :status, :client-secret}")
  
  (retrieve-intent [this intent-id]
    "Retrieve a payment intent by ID.
     Returns the payment intent or nil.")
  
  (confirm-intent [this intent-id]
    "Confirm a payment intent (simulate payment).
     Returns updated intent with status :succeeded or :failed.")
  
  (cancel-intent [this intent-id]
    "Cancel a payment intent.
     Returns updated intent with status :canceled.")
  
  (verify-webhook-signature [this payload signature]
    "Verify webhook signature.
     Returns true if valid, false otherwise."))

;; =============================================================================
;; Payment Service Port
;; =============================================================================

(defprotocol IPaymentService
  "Service interface for payment operations."
  
  (create-payment-intent [this order-id]
    "Create payment intent for an order.
     Returns {:ok intent} or {:error ...}")
  
  (handle-webhook [this event]
    "Process a webhook event.
     Returns {:ok :processed} or {:error ...}")
  
  (get-payment-status [this order-id]
    "Get payment status for an order.
     Returns {:ok status-info} or {:error :not-found}"))
