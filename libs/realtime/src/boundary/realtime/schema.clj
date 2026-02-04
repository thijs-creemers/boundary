(ns boundary.realtime.schema
  "Malli schemas for realtime module data structures.
  
  Schemas for:
  - Connection state
  - WebSocket messages
  - Authentication tokens
  - Routing metadata"
  (:require [malli.core :as m]))

;; Connection Schemas

(def Connection
  "Schema for WebSocket connection state."
  [:map
   [:id :uuid]
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:metadata [:map]]
   [:created-at inst?]])

(def ConnectionInput
  "Schema for creating new connection."
  [:map
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:metadata {:optional true} [:map]]])

;; Message Schemas

(def MessageType
  "Valid message types for routing."
  [:enum :broadcast :user :role :connection])

(def Message
  "Schema for WebSocket message."
  [:map
   [:type MessageType]
   [:payload :map]
   [:target {:optional true} [:or :uuid :keyword]]
   [:timestamp {:optional true} inst?]])

(def MessageInput
  "Schema for creating new message."
  [:map
   [:type MessageType]
   [:payload :map]
   [:target {:optional true} [:or :uuid :keyword]]])

;; Authentication Schemas

(def QueryParams
  "Schema for WebSocket connection query parameters."
  [:map
   [:token :string]])

(def JWTClaims
  "Schema for JWT token claims."
  [:map
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:permissions {:optional true} [:set :keyword]]
   [:exp {:optional true} int?]
   [:iat {:optional true} int?]])

;; Routing Schemas

(def RoutingTarget
  "Schema for message routing targets."
  [:map
   [:connection-ids [:vector :uuid]]])

;; Validation functions

(defn valid-connection?
  "Check if connection data is valid."
  [connection]
  (m/validate Connection connection))

(defn valid-message?
  "Check if message data is valid."
  [message]
  (m/validate Message message))

(defn valid-query-params?
  "Check if query params are valid."
  [params]
  (m/validate QueryParams params))

(defn valid-jwt-claims?
  "Check if JWT claims are valid."
  [claims]
  (m/validate JWTClaims claims))

;; Explain functions for better error messages

(defn explain-connection
  "Explain why connection validation failed."
  [connection]
  (m/explain Connection connection))

(defn explain-message
  "Explain why message validation failed."
  [message]
  (m/explain Message message))
