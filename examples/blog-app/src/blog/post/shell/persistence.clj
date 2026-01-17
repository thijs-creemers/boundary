(ns blog.post.shell.persistence
  "SQLite persistence adapter for posts.
   
   This is the IMPERATIVE SHELL in the FC/IS pattern.
   Contains all database I/O operations.
   
   Key responsibilities:
   - Database queries and mutations
   - Converting between database format and domain entities
   - SQL query building"
  (:require [blog.post.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Data Conversion
;; =============================================================================

(defn- str->uuid
  "Parse a string to UUID, or return nil."
  [s]
  (when s
    (try (UUID/fromString s)
         (catch Exception _ nil))))

(defn- parse-instant
  "Parse an ISO-8601 string to Instant."
  [s]
  (when s
    (try (Instant/parse s)
         (catch Exception _ nil))))

(defn- instant->string
  "Convert Instant to ISO-8601 string."
  [inst]
  (when inst
    (str inst)))

(defn- db->post
  "Convert database row to post entity."
  [row]
  (when row
    {:id (str->uuid (:posts/id row))
     :author-id (str->uuid (:posts/author_id row))
     :title (:posts/title row)
     :slug (:posts/slug row)
     :content (:posts/content row)
     :excerpt (:posts/excerpt row)
     :published (= 1 (:posts/published row))
     :published-at (parse-instant (:posts/published_at row))
     :created-at (parse-instant (:posts/created_at row))
     :updated-at (parse-instant (:posts/updated_at row))}))

(defn- post->db
  "Convert post entity to database row."
  [post]
  {:id (str (:id post))
   :author_id (when (:author-id post) (str (:author-id post)))
   :title (:title post)
   :slug (:slug post)
   :content (:content post)
   :excerpt (:excerpt post)
   :published (if (:published post) 1 0)
   :published_at (instant->string (:published-at post))
   :created_at (instant->string (:created-at post))
   :updated_at (instant->string (:updated-at post))})

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn- build-where-clause
  "Build WHERE clause from filters."
  [filters]
  (let [conditions (cond-> []
                     (some? (:published filters))
                     (conj (if (:published filters)
                             "published = 1"
                             "published = 0"))
                     
                     (:author-id filters)
                     (conj "author_id = ?")
                     
                     (:search filters)
                     (conj "(title LIKE ? OR content LIKE ?)"))]
    (when (seq conditions)
      (str " WHERE " (str/join " AND " conditions)))))

(defn- build-params
  "Build query parameters from filters."
  [filters]
  (cond-> []
    (:author-id filters)
    (conj (str (:author-id filters)))
    
    (:search filters)
    (into [(str "%" (:search filters) "%")
           (str "%" (:search filters) "%")])))

(defn- order-by-clause
  "Build ORDER BY clause."
  [order-by]
  (case order-by
    :title " ORDER BY title ASC"
    :published-at " ORDER BY published_at DESC NULLS LAST, created_at DESC"
    ;; Default: created-at DESC
    " ORDER BY created_at DESC"))

;; =============================================================================
;; Repository Implementation
;; =============================================================================

(defrecord SQLitePostRepository [datasource]
  ports/IPostRepository
  
  (find-post-by-id [_this post-id]
    (let [query "SELECT * FROM posts WHERE id = ?"
          result (jdbc/execute-one! datasource
                                    [query (str post-id)]
                                    {:builder-fn rs/as-unqualified-kebab-maps})]
      (when result
        (db->post {:posts/id (:id result)
                   :posts/author_id (:author-id result)
                   :posts/title (:title result)
                   :posts/slug (:slug result)
                   :posts/content (:content result)
                   :posts/excerpt (:excerpt result)
                   :posts/published (:published result)
                   :posts/published_at (:published-at result)
                   :posts/created_at (:created-at result)
                   :posts/updated_at (:updated-at result)}))))
  
  (find-post-by-slug [_this slug]
    (let [query "SELECT * FROM posts WHERE slug = ?"
          result (jdbc/execute-one! datasource
                                    [query slug]
                                    {:builder-fn rs/as-unqualified-kebab-maps})]
      (when result
        (db->post {:posts/id (:id result)
                   :posts/author_id (:author-id result)
                   :posts/title (:title result)
                   :posts/slug (:slug result)
                   :posts/content (:content result)
                   :posts/excerpt (:excerpt result)
                   :posts/published (:published result)
                   :posts/published_at (:published-at result)
                   :posts/created_at (:created-at result)
                   :posts/updated_at (:updated-at result)}))))
  
  (list-posts [_this options]
    (let [{:keys [filters limit offset order-by]
           :or {limit 10 offset 0 order-by :created-at}} options
          
          where-clause (build-where-clause filters)
          params (build-params filters)
          order-clause (order-by-clause order-by)
          
          ;; Count query
          count-query (str "SELECT COUNT(*) as cnt FROM posts" where-clause)
          count-result (jdbc/execute-one! datasource
                                          (into [count-query] params))
          total (or (:cnt count-result) (:posts/cnt count-result) 0)
          
          ;; Data query
          data-query (str "SELECT * FROM posts"
                          where-clause
                          order-clause
                          " LIMIT ? OFFSET ?")
          rows (jdbc/execute! datasource
                              (into [data-query] (concat params [limit offset]))
                              {:builder-fn rs/as-unqualified-kebab-maps})]
      
      {:posts (mapv #(db->post {:posts/id (:id %)
                                :posts/author_id (:author-id %)
                                :posts/title (:title %)
                                :posts/slug (:slug %)
                                :posts/content (:content %)
                                :posts/excerpt (:excerpt %)
                                :posts/published (:published %)
                                :posts/published_at (:published-at %)
                                :posts/created_at (:created-at %)
                                :posts/updated_at (:updated-at %)})
                    rows)
       :total total}))
  
  (save-post! [this post]
    (let [db-post (post->db post)
          existing (ports/find-post-by-id this (:id post))]
      (if existing
        ;; Update
        (do
          (jdbc/execute! datasource
                         ["UPDATE posts SET 
                           author_id = ?, title = ?, slug = ?, content = ?,
                           excerpt = ?, published = ?, published_at = ?, updated_at = ?
                           WHERE id = ?"
                          (:author_id db-post)
                          (:title db-post)
                          (:slug db-post)
                          (:content db-post)
                          (:excerpt db-post)
                          (:published db-post)
                          (:published_at db-post)
                          (:updated_at db-post)
                          (:id db-post)])
          post)
        ;; Insert
        (do
          (jdbc/execute! datasource
                         ["INSERT INTO posts (id, author_id, title, slug, content, excerpt, published, published_at, created_at, updated_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                          (:id db-post)
                          (:author_id db-post)
                          (:title db-post)
                          (:slug db-post)
                          (:content db-post)
                          (:excerpt db-post)
                          (:published db-post)
                          (:published_at db-post)
                          (:created_at db-post)
                          (:updated_at db-post)])
          post))))
  
  (delete-post! [_this post-id]
    (let [result (jdbc/execute-one! datasource
                                    ["DELETE FROM posts WHERE id = ?" (str post-id)])]
      (> (or (:next.jdbc/update-count result) 0) 0))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-post-repository
  "Create a new SQLite post repository.
   
   Args:
     datasource: JDBC datasource or connection spec
     
   Returns:
     IPostRepository implementation."
  [datasource]
  (->SQLitePostRepository datasource))
