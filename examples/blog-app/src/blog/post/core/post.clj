(ns blog.post.core.post
  "Pure business logic for blog posts.
   
   This is the FUNCTIONAL CORE in the FC/IS pattern.
   All functions here are:
   - Pure (no side effects)
   - Deterministic (same input = same output)
   - Easy to test (no mocking required)
   
   Key responsibilities:
   - Creating new post entities
   - Updating existing posts
   - Publishing/unpublishing logic
   - Slug generation"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Slug Generation
;; =============================================================================

(defn generate-slug
  "Generate a URL-friendly slug from a title.
   
   Converts to lowercase, replaces spaces with hyphens,
   removes special characters, and collapses multiple hyphens.
   
   Examples:
     (generate-slug \"Hello World!\") => \"hello-world\"
     (generate-slug \"  Multiple   Spaces  \") => \"multiple-spaces\"
     (generate-slug \"Special @#$ Characters!\") => \"special-characters\""
  [title]
  (-> title
      str/lower-case
      str/trim
      (str/replace #"[^a-z0-9\s-]" "")  ; Remove special chars
      (str/replace #"\s+" "-")           ; Spaces to hyphens
      (str/replace #"-+" "-")            ; Collapse multiple hyphens
      (str/replace #"^-|-$" "")))        ; Trim leading/trailing hyphens

(defn ensure-unique-slug
  "Ensure a slug is unique by appending a suffix if needed.
   
   Args:
     base-slug: The generated slug
     existing-slugs: Set of slugs that already exist
     
   Returns:
     A unique slug, possibly with a numeric suffix."
  [base-slug existing-slugs]
  (if-not (contains? existing-slugs base-slug)
    base-slug
    (loop [n 2]
      (let [candidate (str base-slug "-" n)]
        (if (contains? existing-slugs candidate)
          (recur (inc n))
          candidate)))))

;; =============================================================================
;; Post Creation
;; =============================================================================

(defn create-post
  "Create a new post entity from input data.
   
   Args:
     input: Map with :title, :content, optional :excerpt, :published
     author-id: UUID of the author (optional)
     now: Current timestamp
     
   Returns:
     Complete post entity map ready for persistence."
  [input author-id now]
  (let [title (:title input)
        published? (boolean (:published input))]
    {:id (random-uuid)
     :author-id author-id
     :title title
     :slug (generate-slug title)
     :content (:content input)
     :excerpt (or (:excerpt input)
                  (let [content (:content input)]
                    (when (> (count content) 200)
                      (str (subs content 0 197) "..."))))
     :published published?
     :published-at (when published? now)
     :created-at now
     :updated-at now}))

;; =============================================================================
;; Post Updates
;; =============================================================================

(defn update-post
  "Apply updates to an existing post.
   
   Args:
     post: Existing post entity
     updates: Map with optional :title, :content, :excerpt, :published
     now: Current timestamp
     
   Returns:
     Updated post entity."
  [post updates now]
  (let [title-changed? (and (:title updates) 
                            (not= (:title updates) (:title post)))
        new-title (or (:title updates) (:title post))
        was-published? (:published post)
        will-publish? (:published updates)
        
        ;; Determine published-at
        published-at (cond
                       ;; Explicitly publishing for the first time
                       (and will-publish? (not was-published?))
                       now
                       
                       ;; Unpublishing
                       (and (false? will-publish?) was-published?)
                       nil
                       
                       ;; Keep existing
                       :else
                       (:published-at post))]
    
    (cond-> post
      ;; Update title and regenerate slug if title changed
      title-changed?
      (assoc :title new-title
             :slug (generate-slug new-title))
      
      ;; Update content if provided
      (:content updates)
      (assoc :content (:content updates))
      
      ;; Update excerpt if provided (including nil to clear)
      (contains? updates :excerpt)
      (assoc :excerpt (:excerpt updates))
      
      ;; Update published status if provided
      (contains? updates :published)
      (assoc :published (boolean (:published updates))
             :published-at published-at)
      
      ;; Always update timestamp
      true
      (assoc :updated-at now))))

;; =============================================================================
;; Publishing Logic
;; =============================================================================

(defn publish-post
  "Mark a post as published.
   
   Args:
     post: Post entity
     now: Current timestamp
     
   Returns:
     {:ok post} if successful
     {:error :already-published} if post was already published."
  [post now]
  (if (:published post)
    {:error :already-published}
    {:ok (assoc post
                :published true
                :published-at now
                :updated-at now)}))

(defn unpublish-post
  "Mark a post as unpublished (draft).
   
   Args:
     post: Post entity
     now: Current timestamp
     
   Returns:
     {:ok post} with updated status."
  [post now]
  {:ok (assoc post
              :published false
              :published-at nil
              :updated-at now)})

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn draft?
  "Check if a post is a draft (not published)."
  [post]
  (not (:published post)))

(defn published?
  "Check if a post is published."
  [post]
  (boolean (:published post)))

(defn author?
  "Check if a user is the author of a post."
  [post user-id]
  (= (:author-id post) user-id))

;; =============================================================================
;; Content Helpers
;; =============================================================================

(defn generate-excerpt
  "Generate an excerpt from content if not provided.
   
   Args:
     content: Full post content
     max-length: Maximum excerpt length (default 200)
     
   Returns:
     Truncated content with ellipsis if needed."
  ([content]
   (generate-excerpt content 200))
  ([content max-length]
   (if (<= (count content) max-length)
     content
     (let [truncated (subs content 0 max-length)
           ;; Try to break at a word boundary
           last-space (str/last-index-of truncated " ")]
       (if (and last-space (> last-space (- max-length 50)))
         (str (subs truncated 0 last-space) "...")
         (str truncated "..."))))))

(defn word-count
  "Count words in content."
  [content]
  (when content
    (count (str/split (str/trim content) #"\s+"))))

(defn reading-time
  "Estimate reading time in minutes.
   Assumes average reading speed of 200 words per minute."
  [content]
  (when content
    (max 1 (int (Math/ceil (/ (word-count content) 200.0))))))
