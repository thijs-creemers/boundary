(ns ecommerce.cart.ports
  "Port definitions for the cart module.")

;; =============================================================================
;; Repository Port
;; =============================================================================

(defprotocol ICartRepository
  "Repository interface for cart persistence."
  
  (find-by-id [this cart-id]
    "Find cart by ID. Returns cart with items or nil.")
  
  (find-by-session [this session-id]
    "Find cart by session ID. Returns cart with items or nil.")
  
  (save-cart! [this cart]
    "Save cart (create or update). Returns saved cart.")
  
  (save-item! [this cart-id item]
    "Save cart item. Returns saved item.")
  
  (delete-item! [this cart-id product-id]
    "Remove item from cart. Returns true if deleted.")
  
  (clear-cart! [this cart-id]
    "Remove all items from cart. Returns true."))

;; =============================================================================
;; Service Port
;; =============================================================================

(defprotocol ICartService
  "Service interface for cart operations."
  
  (get-cart [this session-id]
    "Get or create cart for session. Returns {:ok cart-with-products}")
  
  (add-item [this session-id product-id quantity]
    "Add item to cart. Returns {:ok cart} or {:error ...}")
  
  (update-item [this session-id product-id quantity]
    "Update item quantity. Returns {:ok cart} or {:error ...}")
  
  (remove-item [this session-id product-id]
    "Remove item from cart. Returns {:ok cart}")
  
  (clear-cart [this session-id]
    "Remove all items. Returns {:ok cart}")
  
  (get-cart-summary [this session-id]
    "Get cart with calculated totals. Returns {:ok summary}"))
