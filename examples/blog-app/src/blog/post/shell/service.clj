(ns blog.post.shell.service
  "Post service implementation.
   
   This is the IMPERATIVE SHELL orchestration layer.
   Coordinates between:
   - Pure business logic (core)
   - Repository (persistence)
   - Validation (schema)
   
   Key responsibilities:
   - Validating input data
   - Calling core functions for business logic
   - Persisting changes via repository
   - Handling errors and returning results"
  (:require [blog.post.ports :as ports]
            [blog.post.core.post :as post-core]
            [blog.post.schema :as schema]
            [malli.core :as m])
  (:import [java.time Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- now []
  (Instant/now))

(defn- validate-input
  "Validate input against schema.
   Returns {:ok input} or {:error :validation :details ...}."
  [schema-def input]
  (if (m/validate schema-def input)
    {:ok input}
    {:error :validation
     :details (m/explain schema-def input)}))

;; =============================================================================
;; Service Implementation
;; =============================================================================

(defrecord PostService [repository]
  ports/IPostService
  
  (get-post [_this post-id]
    (if-let [post (ports/find-post-by-id repository post-id)]
      {:ok post}
      {:error :not-found}))
  
  (get-post-by-slug [_this slug]
    (if-let [post (ports/find-post-by-slug repository slug)]
      {:ok post}
      {:error :not-found}))
  
  (list-published-posts [_this options]
    (let [opts (assoc-in options [:filters :published] true)
          result (ports/list-posts repository opts)]
      {:ok result}))
  
  (list-author-posts [_this author-id options]
    (let [opts (assoc-in options [:filters :author-id] author-id)
          result (ports/list-posts repository opts)]
      {:ok result}))
  
  (create-post [_this author-id input]
    (let [validation (validate-input schema/CreatePostRequest input)]
      (if (:error validation)
        validation
        (let [post (post-core/create-post input author-id (now))
              saved (ports/save-post! repository post)]
          {:ok saved}))))
  
  (update-post [_this post-id input]
    (let [validation (validate-input schema/UpdatePostRequest input)]
      (if (:error validation)
        validation
        (if-let [existing (ports/find-post-by-id repository post-id)]
          (let [updated (post-core/update-post existing input (now))
                saved (ports/save-post! repository updated)]
            {:ok saved})
          {:error :not-found}))))
  
  (publish-post [_this post-id]
    (if-let [post (ports/find-post-by-id repository post-id)]
      (let [result (post-core/publish-post post (now))]
        (if (:ok result)
          (let [saved (ports/save-post! repository (:ok result))]
            {:ok saved})
          result))
      {:error :not-found}))
  
  (unpublish-post [_this post-id]
    (if-let [post (ports/find-post-by-id repository post-id)]
      (let [result (post-core/unpublish-post post (now))
            saved (ports/save-post! repository (:ok result))]
        {:ok saved})
      {:error :not-found}))
  
  (delete-post [_this post-id]
    (if (ports/find-post-by-id repository post-id)
      (do
        (ports/delete-post! repository post-id)
        {:ok true})
      {:error :not-found})))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-post-service
  "Create a new post service.
   
   Args:
     repository: IPostRepository implementation
     
   Returns:
     IPostService implementation."
  [repository]
  (->PostService repository))
