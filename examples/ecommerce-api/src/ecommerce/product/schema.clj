(ns ecommerce.product.schema
  "Malli schemas for product module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Product Entity
;; =============================================================================

(def Product
  "Complete product entity."
  [:map
   [:id uuid?]
   [:name [:string {:min 1 :max 200}]]
   [:slug [:string {:min 1 :max 200}]]
   [:description {:optional true} [:maybe :string]]
   [:price-cents pos-int?]
   [:currency [:enum "EUR" "USD" "GBP"]]
   [:stock nat-int?]
   [:active :boolean]
   [:created-at inst?]
   [:updated-at inst?]])

;; =============================================================================
;; API Schemas
;; =============================================================================

(def CreateProductRequest
  "Request body for creating a product."
  [:map
   [:name [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 2000}]]
   [:price-cents pos-int?]
   [:currency {:optional true} [:enum "EUR" "USD" "GBP"]]
   [:stock {:optional true} nat-int?]
   [:active {:optional true} :boolean]])

(def UpdateProductRequest
  "Request body for updating a product."
  [:map
   [:name {:optional true} [:string {:min 1 :max 200}]]
   [:description {:optional true} [:maybe [:string {:max 2000}]]]
   [:price-cents {:optional true} pos-int?]
   [:stock {:optional true} nat-int?]
   [:active {:optional true} :boolean]])

(def ListProductsParams
  "Query parameters for listing products."
  [:map
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} nat-int?]
   [:active {:optional true} :boolean]])

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate
  "Validate data against schema. Returns {:ok data} or {:error :validation :details ...}"
  [schema data]
  (if (m/validate schema data)
    {:ok data}
    {:error :validation
     :details (m/explain schema data)}))
