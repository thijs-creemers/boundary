(ns boundary.user.schema
  (:require
   [clojure.set :as set]
   [malli.core :as m]
   [malli.transform :as mt]
   [malli.util :as mu]
   [boundary.shared.utils.type-conversion :as type-conversion]
   [boundary.shared.utils.case-conversion :as case-conversion])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def User
  "Schema for User entity."
  [:map {:title "User"}
   [:id :uuid]
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:active :boolean]
   [:login-count {:optional true} :int]
   [:last-login {:optional true} :string]
   [:date-format {:optional true} [:enum :iso :us :eu]]
   [:time-format {:optional true} [:enum :12h :24h]]
   [:tenant-id :uuid]
   [:avatar-url {:optional true} :string]
   [:created-at :string]
   [:updated-at {:optional true} :string]
   [:deleted-at {:optional true} :string]])

(def UserPreferences
  "Schema for user preferences."
  [:map {:title "User Preferences"}
   [:notifications
    [:map
     [:email :boolean]
     [:push :boolean]
     [:sms :boolean]]]
   [:theme [:enum :light :dark :auto]]
   [:language :string]
   [:timezone :string]
   [:date-format [:enum :iso :us :eu]]
   [:time-format [:enum :12h :24h]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateUserRequest
  "Schema for create user API requests."
  [:map {:title "Create User Request"}
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:active {:optional true} :boolean]
   [:tenant-id :uuid]
   [:send-welcome {:optional true} :boolean]])

(def UpdateUserRequest
  "Schema for update user API requests."
  [:map {:title "Update User Request"}
   [:name {:optional true} :string]
   [:role {:optional true} [:enum :admin :user :viewer]]
   [:active {:optional true} :boolean]])

(def LoginRequest
  "Schema for login API requests."
  [:map {:title "Login Request"}
   [:email :string]
   [:password :string]
   [:remember {:optional true} :boolean]])

;; =============================================================================
;; API Response Schemas
;; =============================================================================

(def UserResponse
  "Schema for user responses."
  [:map {:title "User Response"}
   [:id :string] ; UUID as string
   [:email :string]
   [:name :string]
   [:role :string] ; Enum as string
   [:active :boolean]
   [:loginCount {:optional true} :int]
   [:lastLogin {:optional true} :string] ; Instant as ISO string
   [:tenantId :string] ; UUID as string
   [:avatarUrl {:optional true} :string]
   [:createdAt :string] ; Instant as ISO string
   [:updatedAt {:optional true} :string]]) ; Instant as ISO string

;; =============================================================================
;; User-Specific Transformation Functions  
;; =============================================================================

(defn user-specific-camel->kebab
  "Transforms user-specific camelCase API keys to kebab-case internal keys."
  [value]
  (-> value
      case-conversion/camel-case->kebab-case-map
      (cond->
        (:send-welcome value) (assoc :send-welcome (:sendWelcome value))
        (:sendWelcome value) (dissoc :sendWelcome))))

(defn user-specific-kebab->camel
  "Transforms user-specific kebab-case internal keys to camelCase API keys."
  [value]
  (-> value
      case-conversion/kebab-case->camel-case-map
      (cond->
        (:id value) (assoc :id (type-conversion/uuid->string (:id value)))
        (:tenant-id value) (assoc :tenantId (type-conversion/uuid->string (:tenant-id value)))
        (:created-at value) (assoc :createdAt (type-conversion/instant->string (:created-at value)))
        (:updated-at value) (assoc :updatedAt (type-conversion/instant->string (:updated-at value)))
        (:last-login value) (assoc :lastLogin (type-conversion/instant->string (:last-login value)))
        (:date-format value) (assoc :dateFormat (type-conversion/keyword->string (:date-format value)))
        (:time-format value) (assoc :timeFormat (type-conversion/keyword->string (:time-format value)))
        (:role value) (assoc :role (type-conversion/keyword->string (:role value)))
        (:theme value) (assoc :theme (type-conversion/keyword->string (:theme value))))))

;; =============================================================================
;; Schema Transformers
;; =============================================================================

(def user-request-transformer
  "Transforms external API data to internal domain format."
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :user-request
    :transformers
    {:map {:compile (fn [_schema _options] user-specific-camel->kebab)}
     :enum {:compile (fn [_schema _options] type-conversion/string->enum)}
     :boolean {:compile (fn [_schema _options] type-conversion/string->boolean)}}}))

(def user-response-transformer
  "Transforms internal domain data to external API format."
  (mt/transformer
   {:name :user-response
    :transformers
    {:map {:compile (fn [_schema _options] user-specific-kebab->camel)}
     :uuid {:compile (fn [_schema _options] type-conversion/uuid->string)}
     :inst {:compile (fn [_schema _options] type-conversion/instant->string)}
     :enum {:compile (fn [_schema _options] type-conversion/keyword->string)}}}))

;; =============================================================================
;; Data Transformation Utilities
;; =============================================================================

(defn user-entity->response
  "Transforms a user entity into an API response."
  [user-data]
  (user-specific-kebab->camel user-data))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-user
  "Validates a user entity against the User schema."
  [user-data]
  (m/validate User user-data))

(defn explain-user
  "Provides detailed validation errors for user data."
  [user-data]
  (m/explain User user-data))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all user module schemas for easy access."
  {:domain-entities
   {:user User
    :user-preferences UserPreferences}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))