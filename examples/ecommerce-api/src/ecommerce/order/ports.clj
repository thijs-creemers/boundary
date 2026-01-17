(ns ecommerce.order.ports
  "Port definitions for the order module.")

;; =============================================================================
;; Repository Port
;; =============================================================================

(defprotocol IOrderRepository
  "Repository interface for order persistence."
  
  (find-by-id [this order-id]
    "Find order by ID. Returns order with items or nil.")
  
  (find-by-number [this order-number]
    "Find order by order number. Returns order or nil.")
  
  (find-by-payment-intent [this payment-intent-id]
    "Find order by payment intent ID. Returns order or nil.")
  
  (list-by-customer [this email options]
    "List orders for a customer email.
     Returns {:orders [...] :total n}")
  
  (save! [this order]
    "Save order (insert or update). Returns saved order.")
  
  (save-items! [this order-id items]
    "Save order items. Returns items."))

;; =============================================================================
;; Service Port
;; =============================================================================

(defprotocol IOrderService
  "Service interface for order operations."
  
  (get-order [this order-id]
    "Get order by ID. Returns {:ok order} or {:error :not-found}")
  
  (get-order-by-number [this order-number]
    "Get order by number. Returns {:ok order} or {:error :not-found}")
  
  (list-customer-orders [this email options]
    "List orders for customer. Returns {:ok {:orders [...] :total n}}")
  
  (create-order [this session-id customer-info]
    "Create order from cart. Returns {:ok order} or {:error ...}")
  
  (update-status [this order-id new-status]
    "Update order status. Returns {:ok order} or {:error :invalid-transition}")
  
  (mark-paid [this order-id payment-intent-id]
    "Mark order as paid. Returns {:ok order} or {:error ...}")
  
  (cancel-order [this order-id]
    "Cancel an order. Returns {:ok order} or {:error ...}"))
