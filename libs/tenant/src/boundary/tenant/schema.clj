(ns boundary.tenant.schema
  (:require
   [malli.core :as m]
   [malli.transform :as mt]
   [boundary.core.utils.type-conversion :as type-conversion]
   [boundary.core.utils.case-conversion :as case-conversion]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def slug-regex
  "Regular expression for valid tenant slugs.
   Must be lowercase alphanumeric with hyphens, 2-100 chars."
  #"^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$")

(def Tenant
  "Schema for Tenant entity.
   
   Represents a tenant in the multi-tenant system. Each tenant gets:
   - A unique slug (URL-friendly identifier)
   - A dedicated PostgreSQL schema for data isolation
   - Configuration settings
   
   Note: Timestamps use :inst (java.time.Instant) internally."
  [:map {:title "Tenant"}
   [:id :uuid]
   [:slug [:re {:error/message "Invalid slug (must be lowercase alphanumeric with hyphens, 2-100 chars)"} 
           slug-regex]]
   [:name [:string {:min 1 :max 255}]]
   [:schema-name [:string {:min 1 :max 63}]] ; PostgreSQL schema name limit
   [:status [:enum :active :suspended :deleted]]
   [:settings {:optional true} [:maybe :map]] ; JSON configuration
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]
   [:deleted-at {:optional true} [:maybe inst?]]])

(def TenantSettings
  "Schema for tenant configuration settings."
  [:map {:title "Tenant Settings"}
   [:features
    {:optional true}
    [:map
     [:mfa-enabled {:optional true} :boolean]
     [:api-enabled {:optional true} :boolean]
     [:webhooks-enabled {:optional true} :boolean]]]
   [:limits
    {:optional true}
    [:map
     [:max-users {:optional true} :int]
     [:max-storage-mb {:optional true} :int]
     [:max-api-calls-per-day {:optional true} :int]]]
   [:branding
    {:optional true}
    [:map
     [:logo-url {:optional true} :string]
     [:primary-color {:optional true} :string]
     [:custom-domain {:optional true} :string]]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateTenantRequest
  "Schema for create tenant API requests."
  [:map {:title "Create Tenant Request"}
   [:slug [:re {:error/message "Invalid slug"} slug-regex]]
   [:name [:string {:min 1 :max 255}]]
   [:settings {:optional true} TenantSettings]])

(def UpdateTenantRequest
  "Schema for update tenant API requests."
  [:map {:title "Update Tenant Request"}
   [:name {:optional true} [:string {:min 1 :max 255}]]
   [:status {:optional true} [:enum :active :suspended :deleted]]
   [:settings {:optional true} TenantSettings]])

;; =============================================================================
;; API Response Schemas
;; =============================================================================

(def TenantResponse
  "Schema for tenant responses (camelCase for API compatibility)."
  [:map {:title "Tenant Response"}
   [:id :string] ; UUID as string
   [:slug :string]
   [:name :string]
   [:schemaName :string]
   [:status :string] ; Enum as string
   [:settings {:optional true} :map]
   [:createdAt :string] ; Instant as ISO string
   [:updatedAt {:optional true} :string]]) ; Instant as ISO string

;; =============================================================================
;; Tenant-Specific Transformation Functions
;; =============================================================================

(defn tenant-specific-camel->kebab
  "Transforms tenant-specific camelCase API keys to kebab-case internal keys."
  [value]
  (-> value
      case-conversion/camel-case->kebab-case-map
      (cond->
       (:schema-name value) (assoc :schema-name (:schemaName value))
       (:schemaName value) (dissoc :schemaName))))

(defn tenant-specific-kebab->camel
  "Transforms tenant-specific kebab-case internal keys to camelCase API keys.
   Removes null values for cleaner API responses."
  [value]
  (-> value
      case-conversion/kebab-case->camel-case-map
      (cond->
       (:id value) (assoc :id (type-conversion/uuid->string (:id value)))
       (:created-at value) (assoc :createdAt (type-conversion/instant->string (:created-at value)))
       (:updated-at value) (assoc :updatedAt (type-conversion/instant->string (:updated-at value)))
       (:deleted-at value) (assoc :deletedAt (type-conversion/instant->string (:deleted-at value)))
       (:schema-name value) (assoc :schemaName (:schema-name value))
       (:status value) (assoc :status (type-conversion/keyword->string (:status value))))
      ;; Remove null values for cleaner API responses
      (#(into {} (remove (fn [[_ v]] (nil? v)) %)))))

;; =============================================================================
;; Schema Transformers
;; =============================================================================

(def tenant-request-transformer
  "Transforms external API data to internal domain format."
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :tenant-request
    :transformers
    {:map {:compile (fn [_schema _options] tenant-specific-camel->kebab)}
     :enum {:compile (fn [_schema _options] type-conversion/string->enum)}
     :boolean {:compile (fn [_schema _options] type-conversion/string->boolean)}}}))

(def tenant-response-transformer
  "Transforms internal domain data to external API format."
  (mt/transformer
   {:name :tenant-response
    :transformers
    {:map {:compile (fn [_schema _options] tenant-specific-kebab->camel)}
     :uuid {:compile (fn [_schema _options] type-conversion/uuid->string)}
     :inst {:compile (fn [_schema _options] type-conversion/instant->string)}
     :enum {:compile (fn [_schema _options] type-conversion/keyword->string)}}}))

(defn validate-tenant
  "Validates a tenant entity against the Tenant schema."
  [tenant-data]
  (m/validate Tenant tenant-data))

(defn explain-tenant
  "Provides detailed validation errors for tenant data."
  [tenant-data]
  (m/explain Tenant tenant-data))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all tenant module schemas for easy access."
  {:domain-entities
   {:tenant Tenant
    :tenant-settings TenantSettings}
   :api-requests
   {:create-tenant CreateTenantRequest
    :update-tenant UpdateTenantRequest}
   :api-responses
   {:tenant TenantResponse}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))
