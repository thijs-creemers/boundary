(ns blog.post.shell.http
  "HTTP handlers for blog posts.
   
   This is the IMPERATIVE SHELL that handles HTTP I/O.
   Responsibilities:
   - Parse HTTP requests
   - Call service layer
   - Render responses (HTML or JSON)
   - Handle errors"
  (:require [blog.post.ports :as ports]
            [blog.post.core.ui :as ui]
            [blog.shared.ui.layout :as layout]
            [ring.util.response :as response]
            [clojure.string :as str])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- str->uuid
  "Parse string to UUID, returns nil on failure."
  [s]
  (when s
    (try (UUID/fromString s)
         (catch Exception _ nil))))

(defn- parse-form-params
  "Parse form parameters into a post input map."
  [params]
  {:title (get params "title")
   :content (get params "content")
   :excerpt (let [e (get params "excerpt")] 
              (when-not (str/blank? e) e))
   :published (= "true" (get params "published"))})

(defn- render-page
  "Render a full HTML page response."
  [title content opts]
  (-> (layout/render (layout/page title content opts))
      (response/response)
      (response/content-type "text/html")))

(defn- not-found-page
  "Render a 404 page."
  [opts]
  (-> (render-page "Not Found" 
                   [:div 
                    [:h1 "Not Found"]
                    [:p "The page you're looking for doesn't exist."]
                    [:a {:href "/"} "Go home"]]
                   opts)
      (response/status 404)))

;; =============================================================================
;; Public Handlers
;; =============================================================================

(defn home-handler
  "GET / - Home page with published posts."
  [post-service blog-config]
  (fn [request]
    (let [result (ports/list-published-posts post-service {:limit 10})
          posts (get-in result [:ok :posts] [])
          opts {:active :home
                :blog-name (:name blog-config)
                :flash (:flash request)}]
      (render-page (:name blog-config)
                   (ui/home-page-content posts)
                   opts))))

(defn post-handler
  "GET /posts/:slug - Single post view."
  [post-service blog-config]
  (fn [request]
    (let [slug (get-in request [:path-params :slug])
          result (ports/get-post-by-slug post-service slug)
          opts {:blog-name (:name blog-config)
                :user (:session request)
                :flash (:flash request)}]
      (if-let [post (:ok result)]
        (if (or (:published post) 
                (= (:author-id post) (get-in request [:session :user-id])))
          (render-page (:title post)
                       (ui/post-page-content post {:show-edit-link true
                                                    :user (:session request)})
                       (assoc opts :description (:excerpt post)))
          (not-found-page opts))
        (not-found-page opts)))))

;; =============================================================================
;; Dashboard Handlers (Protected)
;; =============================================================================

(defn dashboard-handler
  "GET /dashboard - Author dashboard."
  [post-service blog-config]
  (fn [request]
    (let [user-id (get-in request [:session :user-id])
          ;; For demo, use a fixed user-id if not logged in
          user-id (or user-id (str->uuid "00000000-0000-0000-0000-000000000001"))
          result (ports/list-author-posts post-service user-id {:limit 50})
          posts (get-in result [:ok :posts] [])
          opts {:active :dashboard
                :blog-name (:name blog-config)
                :user {:id user-id :name "Demo User"}
                :flash (:flash request)}]
      (render-page "Dashboard"
                   (ui/dashboard-page-content posts)
                   opts))))

(defn new-post-handler
  "GET /dashboard/posts/new - New post form."
  [_post-service blog-config]
  (fn [request]
    (let [opts {:active :dashboard
                :blog-name (:name blog-config)
                :user {:name "Demo User"}
                :flash (:flash request)}]
      (render-page "New Post"
                   (ui/new-post-page-content)
                   opts))))

(defn create-post-handler
  "POST /dashboard/posts - Create a new post."
  [post-service blog-config]
  (fn [request]
    (let [user-id (or (get-in request [:session :user-id])
                      (str->uuid "00000000-0000-0000-0000-000000000001"))
          params (:form-params request)
          input (parse-form-params params)
          result (ports/create-post post-service user-id input)]
      (if-let [post (:ok result)]
        (-> (response/redirect (str "/posts/" (:slug post)))
            (assoc :flash {:type :success :message "Post created!"}))
        ;; Show form with errors
        (let [opts {:active :dashboard
                    :blog-name (:name blog-config)
                    :user {:name "Demo User"}
                    :flash {:type :error :message "Please fix the errors below."}}]
          (render-page "New Post"
                       (ui/new-post-page-content {:errors ["Invalid post data"]})
                       opts))))))

(defn edit-post-handler
  "GET /dashboard/posts/:id/edit - Edit post form."
  [post-service blog-config]
  (fn [request]
    (let [post-id (str->uuid (get-in request [:path-params :id]))
          result (when post-id (ports/get-post post-service post-id))
          opts {:active :dashboard
                :blog-name (:name blog-config)
                :user {:name "Demo User"}
                :flash (:flash request)}]
      (if-let [post (:ok result)]
        (render-page "Edit Post"
                     (ui/edit-post-page-content post)
                     opts)
        (not-found-page opts)))))

(defn update-post-handler
  "POST /dashboard/posts/:id - Update a post."
  [post-service blog-config]
  (fn [request]
    (let [post-id (str->uuid (get-in request [:path-params :id]))
          params (:form-params request)
          input (parse-form-params params)
          result (when post-id (ports/update-post post-service post-id input))]
      (if-let [post (:ok result)]
        (-> (response/redirect (str "/posts/" (:slug post)))
            (assoc :flash {:type :success :message "Post updated!"}))
        (-> (response/redirect "/dashboard")
            (assoc :flash {:type :error :message "Failed to update post."}))))))

(defn delete-post-handler
  "POST /dashboard/posts/:id/delete - Delete a post."
  [post-service _blog-config]
  (fn [request]
    (let [post-id (str->uuid (get-in request [:path-params :id]))]
      (when post-id
        (ports/delete-post post-service post-id))
      (-> (response/redirect "/dashboard")
          (assoc :flash {:type :success :message "Post deleted."})))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Create all post-related routes.
   
   Args:
     post-service: IPostService implementation
     blog-config: Blog configuration map
     
   Returns:
     Vector of Reitit route definitions."
  [post-service blog-config]
  [;; Public routes
   ["/" {:get {:handler (home-handler post-service blog-config)}}]
   ["/posts/:slug" {:get {:handler (post-handler post-service blog-config)}}]
   
   ;; Dashboard routes
   ["/dashboard" {:get {:handler (dashboard-handler post-service blog-config)}}]
   ["/dashboard/posts" {:post {:handler (create-post-handler post-service blog-config)}}]
   ["/dashboard/posts/new" {:get {:handler (new-post-handler post-service blog-config)}}]
   ["/dashboard/posts/:id/edit" {:get {:handler (edit-post-handler post-service blog-config)}}]
   ["/dashboard/posts/:id/update" {:post {:handler (update-post-handler post-service blog-config)}}]
   ["/dashboard/posts/:id/delete" {:post {:handler (delete-post-handler post-service blog-config)}}]])
