(ns ecommerce.cart.shell.service
  "Cart service - orchestrates cart operations."
  (:require [ecommerce.cart.ports :as ports]
            [ecommerce.cart.core.cart :as cart-core]
            [ecommerce.product.ports :as product-ports])
  (:import [java.time Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- now []
  (Instant/now))

(defn- get-or-create-cart [cart-repo session-id]
  (if-let [cart (ports/find-by-session cart-repo session-id)]
    cart
    (let [new-cart (cart-core/create-cart session-id (now))]
      (ports/save-cart! cart-repo new-cart)
      new-cart)))

(defn- enrich-cart [cart product-repo]
  "Add product info to cart items."
  (let [product-ids (mapv :product-id (:items cart))
        products (if (empty? product-ids)
                   {}
                   (let [prods (product-ports/find-by-ids product-repo product-ids)]
                     (into {} (map (juxt :id identity) prods))))]
    (cart-core/calculate-cart-totals cart products)))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord CartService [cart-repository product-repository]
  ports/ICartService
  
  (get-cart [_ session-id]
    (let [cart (get-or-create-cart cart-repository session-id)
          summary (enrich-cart cart product-repository)]
      {:ok summary}))
  
  (add-item [_ session-id product-id quantity]
    (let [quantity (or quantity 1)]
      ;; Validate quantity
      (let [qty-result (cart-core/validate-quantity quantity)]
        (if (:error qty-result)
          qty-result
          ;; Check product exists and has stock
          (if-let [product (product-ports/find-by-id product-repository product-id)]
            (if (< (:stock product) quantity)
              {:error :insufficient-stock
               :product-id product-id
               :available (:stock product)
               :requested quantity}
              ;; Add to cart
              (let [cart (get-or-create-cart cart-repository session-id)
                    updated-cart (cart-core/add-item cart product-id quantity (now))
                    item (cart-core/find-item updated-cart product-id)]
                ;; Save item
                (ports/save-item! cart-repository (:id cart) item)
                (ports/save-cart! cart-repository updated-cart)
                ;; Return enriched cart
                {:ok (enrich-cart updated-cart product-repository)}))
            {:error :not-found :id product-id})))))
  
  (update-item [_ session-id product-id quantity]
    (let [qty-result (cart-core/validate-quantity quantity)]
      (if (:error qty-result)
        qty-result
        (if-let [cart (ports/find-by-session cart-repository session-id)]
          (if-let [_existing-item (cart-core/find-item cart product-id)]
            ;; Check stock
            (if-let [product (product-ports/find-by-id product-repository product-id)]
              (if (< (:stock product) quantity)
                {:error :insufficient-stock
                 :product-id product-id
                 :available (:stock product)
                 :requested quantity}
                ;; Update quantity
                (let [updated-cart (cart-core/update-item-quantity cart product-id quantity (now))
                      item (cart-core/find-item updated-cart product-id)]
                  (ports/save-item! cart-repository (:id cart) item)
                  (ports/save-cart! cart-repository updated-cart)
                  {:ok (enrich-cart updated-cart product-repository)}))
              {:error :not-found :id product-id})
            {:error :not-found :id product-id})
          {:error :not-found :id session-id}))))
  
  (remove-item [_ session-id product-id]
    (if-let [cart (ports/find-by-session cart-repository session-id)]
      (do
        (ports/delete-item! cart-repository (:id cart) product-id)
        (let [updated-cart (cart-core/remove-item cart product-id (now))]
          (ports/save-cart! cart-repository updated-cart)
          {:ok (enrich-cart updated-cart product-repository)}))
      {:error :not-found :id session-id}))
  
  (clear-cart [_ session-id]
    (if-let [cart (ports/find-by-session cart-repository session-id)]
      (do
        (ports/clear-cart! cart-repository (:id cart))
        (let [updated-cart (cart-core/clear-items cart (now))]
          (ports/save-cart! cart-repository updated-cart)
          {:ok (enrich-cart updated-cart product-repository)}))
      {:error :not-found :id session-id}))
  
  (get-cart-summary [this session-id]
    (ports/get-cart this session-id)))
