(ns blog.post.ports
  "Port definitions for the post module.
   
   Ports define the interfaces (protocols) that the shell layer implements.
   This follows the Functional Core / Imperative Shell pattern where:
   - Core contains pure business logic
   - Shell implements these ports with actual I/O operations
   - Core never depends on shell, only on these port definitions")

;; =============================================================================
;; Repository Port
;; =============================================================================

(defprotocol IPostRepository
  "Repository interface for post persistence.
   
   Implementations handle actual database operations.
   All methods are side-effectful (I/O operations)."
  
  (find-post-by-id [this post-id]
    "Find a post by its UUID.
     Returns the post map or nil if not found.")
  
  (find-post-by-slug [this slug]
    "Find a post by its URL slug.
     Returns the post map or nil if not found.")
  
  (list-posts [this options]
    "List posts with filtering and pagination.
     
     Options:
       :filters - Map with :published, :author-id, :search
       :limit   - Max posts to return (default 10)
       :offset  - Number of posts to skip (default 0)
       :order-by - Sort field :created-at, :published-at, :title
     
     Returns a map with :posts and :total.")
  
  (save-post! [this post]
    "Save a post (insert or update based on existence).
     Returns the saved post.")
  
  (delete-post! [this post-id]
    "Delete a post by ID.
     Returns true if deleted, false if not found."))

;; =============================================================================
;; Service Port
;; =============================================================================

(defprotocol IPostService
  "Service interface for post operations.
   
   Orchestrates business logic and repository calls.
   Used by HTTP handlers and other consumers."
  
  (get-post [this post-id]
    "Get a single post by ID.
     Returns {:ok post} or {:error :not-found}.")
  
  (get-post-by-slug [this slug]
    "Get a single post by slug.
     Returns {:ok post} or {:error :not-found}.")
  
  (list-published-posts [this options]
    "List all published posts for public viewing.
     Returns {:ok {:posts [...] :total n}}.")
  
  (list-author-posts [this author-id options]
    "List all posts by a specific author (including drafts).
     Returns {:ok {:posts [...] :total n}}.")
  
  (create-post [this author-id input]
    "Create a new post.
     Returns {:ok post} or {:error :validation errors}.")
  
  (update-post [this post-id input]
    "Update an existing post.
     Returns {:ok post} or {:error :not-found/:validation}.")
  
  (publish-post [this post-id]
    "Publish a draft post.
     Returns {:ok post} or {:error :not-found/:already-published}.")
  
  (unpublish-post [this post-id]
    "Unpublish a post (convert back to draft).
     Returns {:ok post} or {:error :not-found}.")
  
  (delete-post [this post-id]
    "Delete a post.
     Returns {:ok true} or {:error :not-found}."))
