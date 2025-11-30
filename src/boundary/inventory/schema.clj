(ns boundary.inventory.schema
  "Schema definitions for inventory module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def Item
  "Schema for Item entity."
  [:map {:title "Item"}
   [:id :uuid]
   [:name :string]
   [:sku :string]
   [:quantity :int]
   [:location :string]
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateItemRequest
  "Schema for create item API requests."
  [:map {:title "Create Item Request"}
   [:name :string]
   [:sku :string]
   [:quantity :int]
   [:location :string]])

(def UpdateItemRequest
  "Schema for update item API requests."
  [:map {:title "Update Item Request"}
   [:name :string]
   [:sku :string]
   [:quantity :int]
   [:location :string]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-item
  "Validates a item entity against the Item schema."
  [item-data]
  (m/validate Item item-data))

(defn explain-item
  "Provides detailed validation errors for item data."
  [item-data]
  (m/explain Item item-data))
