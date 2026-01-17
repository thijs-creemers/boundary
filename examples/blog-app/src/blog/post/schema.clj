(ns blog.post.schema
  "Malli schemas for blog posts.
   
   Defines the data shapes for posts including:
   - Post entity (complete post data)
   - CreatePostRequest (input for creating posts)
   - UpdatePostRequest (input for updating posts)
   - PostSummary (abbreviated post for listings)"
  (:require [malli.core :as m]))

;; =============================================================================
;; Base Schemas
;; =============================================================================

(def NonEmptyString
  "Non-empty string schema."
  [:string {:min 1}])

(def Slug
  "URL-friendly slug: lowercase letters, numbers, and hyphens."
  [:re #"^[a-z0-9]+(?:-[a-z0-9]+)*$"])

;; =============================================================================
;; Post Schemas
;; =============================================================================

(def Post
  "Complete blog post entity."
  [:map
   [:id :uuid]
   [:author-id {:optional true} [:maybe :uuid]]
   [:title NonEmptyString]
   [:slug Slug]
   [:content NonEmptyString]
   [:excerpt {:optional true} [:maybe :string]]
   [:published :boolean]
   [:published-at {:optional true} [:maybe inst?]]
   [:created-at inst?]
   [:updated-at inst?]])

(def PostSummary
  "Abbreviated post for list views."
  [:map
   [:id :uuid]
   [:title NonEmptyString]
   [:slug Slug]
   [:excerpt {:optional true} [:maybe :string]]
   [:published :boolean]
   [:published-at {:optional true} [:maybe inst?]]
   [:created-at inst?]])

(def CreatePostRequest
  "Input schema for creating a new post."
  [:map
   [:title [:string {:min 1 :max 200}]]
   [:content [:string {:min 1}]]
   [:excerpt {:optional true} [:string {:max 500}]]
   [:published {:optional true} :boolean]])

(def UpdatePostRequest
  "Input schema for updating an existing post."
  [:map
   [:title {:optional true} [:string {:min 1 :max 200}]]
   [:content {:optional true} [:string {:min 1}]]
   [:excerpt {:optional true} [:maybe [:string {:max 500}]]]
   [:published {:optional true} :boolean]])

;; =============================================================================
;; Query Schemas
;; =============================================================================

(def PostFilters
  "Filters for querying posts."
  [:map
   [:published {:optional true} :boolean]
   [:author-id {:optional true} :uuid]
   [:search {:optional true} :string]])

(def PostListOptions
  "Options for listing posts."
  [:map
   [:filters {:optional true} PostFilters]
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0}]]
   [:order-by {:optional true} [:enum :created-at :published-at :title]]])

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn valid-post?
  "Check if data is a valid Post."
  [data]
  (m/validate Post data))

(defn valid-create-request?
  "Check if data is a valid CreatePostRequest."
  [data]
  (m/validate CreatePostRequest data))

(defn valid-update-request?
  "Check if data is a valid UpdatePostRequest."
  [data]
  (m/validate UpdatePostRequest data))
