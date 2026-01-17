(ns ecommerce.product.shell.service
  "Product service - orchestrates business logic and persistence."
  (:require [ecommerce.product.ports :as ports]
            [ecommerce.product.schema :as schema]
            [ecommerce.product.core.product :as product-core])
  (:import [java.time Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- now []
  (Instant/now))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord ProductService [repository]
  ports/IProductService
  
  (get-product [_ product-id]
    (if-let [product (ports/find-by-id repository product-id)]
      {:ok product}
      {:error :not-found :id product-id}))
  
  (get-product-by-slug [_ slug]
    (if-let [product (ports/find-by-slug repository slug)]
      {:ok product}
      {:error :not-found :id slug}))
  
  (list-active-products [_ options]
    (let [result (ports/list-products repository (assoc options :active true))]
      {:ok result}))
  
  (create-product [_ input]
    (let [validation (schema/validate schema/CreateProductRequest input)]
      (if (:error validation)
        validation
        (let [product (product-core/create-product input (now))
              saved (ports/save! repository product)]
          {:ok saved}))))
  
  (update-product [_ product-id input]
    (let [validation (schema/validate schema/UpdateProductRequest input)]
      (if (:error validation)
        validation
        (if-let [existing (ports/find-by-id repository product-id)]
          (let [updated (product-core/update-product existing input (now))
                saved (ports/save! repository updated)]
            {:ok saved})
          {:error :not-found :id product-id}))))
  
  (update-stock [_ product-id quantity-delta]
    (if-let [product (ports/find-by-id repository product-id)]
      (let [result (product-core/adjust-stock product quantity-delta (now))]
        (if (:ok result)
          (let [saved (ports/save! repository (:ok result))]
            {:ok saved})
          result))
      {:error :not-found :id product-id}))
  
  (deactivate-product [_ product-id]
    (if-let [product (ports/find-by-id repository product-id)]
      (let [updated (product-core/update-product product {:active false} (now))
            saved (ports/save! repository updated)]
        {:ok saved})
      {:error :not-found :id product-id})))
