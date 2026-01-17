(ns notification.event.schema
  "Malli schemas for domain events."
  (:require [malli.core :as m]))

;; =============================================================================
;; Event Types
;; =============================================================================

(def EventType
  "Supported event types."
  [:enum
   ;; Order events
   :order/placed
   :order/confirmed
   :order/cancelled
   ;; Payment events
   :payment/received
   :payment/failed
   :payment/refunded
   ;; Shipment events
   :shipment/sent
   :shipment/delivered
   :shipment/returned])

;; =============================================================================
;; Event Metadata
;; =============================================================================

(def EventMetadata
  "Metadata attached to all events for tracing."
  [:map
   [:correlation-id uuid?]           ;; Links related events
   [:causation-id {:optional true} uuid?]  ;; Event that caused this one
   [:timestamp inst?]
   [:source {:optional true} :string]])  ;; Source service

;; =============================================================================
;; Event Entity
;; =============================================================================

(def Event
  "Domain event schema."
  [:map
   [:id uuid?]
   [:type EventType]
   [:aggregate-id uuid?]             ;; ID of the aggregate (e.g., order-id)
   [:aggregate-type :keyword]        ;; Type of aggregate (:order, :payment, etc.)
   [:payload :map]                   ;; Event-specific data
   [:metadata EventMetadata]
   [:created-at inst?]])

;; =============================================================================
;; Event Payloads
;; =============================================================================

(def OrderPlacedPayload
  [:map
   [:order-number :string]
   [:customer-email :string]
   [:customer-name :string]
   [:total-cents pos-int?]
   [:currency :string]
   [:items [:vector [:map
                     [:name :string]
                     [:quantity pos-int?]
                     [:price-cents pos-int?]]]]])

(def PaymentReceivedPayload
  [:map
   [:order-id uuid?]
   [:order-number :string]
   [:customer-email :string]
   [:amount-cents pos-int?]
   [:currency :string]
   [:payment-method {:optional true} :string]])

(def ShipmentSentPayload
  [:map
   [:order-id uuid?]
   [:order-number :string]
   [:customer-email :string]
   [:tracking-number :string]
   [:carrier :string]
   [:estimated-delivery {:optional true} :string]])

;; =============================================================================
;; API Schemas
;; =============================================================================

(def PublishEventRequest
  "Request to publish an event."
  [:map
   [:type EventType]
   [:aggregate-id uuid?]
   [:aggregate-type :keyword]
   [:payload :map]
   [:correlation-id {:optional true} uuid?]])

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate
  "Validate data against schema."
  [schema data]
  (if (m/validate schema data)
    {:ok data}
    {:error :validation
     :details (m/explain schema data)}))
