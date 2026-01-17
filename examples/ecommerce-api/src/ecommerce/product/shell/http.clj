(ns ecommerce.product.shell.http
  "HTTP handlers for product API."
  (:require [ecommerce.product.ports :as ports]
            [ecommerce.product.core.product :as product-core]
            [ecommerce.shared.http.responses :as resp])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- str->uuid [s]
  (try (UUID/fromString s)
       (catch Exception _ nil)))

(defn- parse-query-params [params]
  {:limit (some-> (get params "limit") parse-long)
   :offset (some-> (get params "offset") parse-long)})

;; =============================================================================
;; Handlers
;; =============================================================================

(defn list-products-handler
  "GET /api/products - List active products."
  [product-service]
  (fn [request]
    (let [options (parse-query-params (:query-params request))
          result (ports/list-active-products product-service options)
          {:keys [products total]} (:ok result)]
      (resp/ok-list (product-core/products->api products)
                    {:total total
                     :limit (or (:limit options) 20)
                     :offset (or (:offset options) 0)}))))

(defn get-product-handler
  "GET /api/products/:slug - Get product by slug."
  [product-service]
  (fn [request]
    (let [slug (get-in request [:path-params :slug])
          result (ports/get-product-by-slug product-service slug)]
      (resp/handle-result result
                          :resource-type "Product"
                          :on-success #(resp/ok (product-core/product->api %))))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Product API routes."
  [product-service]
  [["/api/products" 
    {:get {:handler (list-products-handler product-service)
           :summary "List active products"}}]
   ["/api/products/:slug" 
    {:get {:handler (get-product-handler product-service)
           :summary "Get product by slug"}}]])
