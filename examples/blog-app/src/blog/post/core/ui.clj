(ns blog.post.core.ui
  "Pure Hiccup templates for post views.
   
   This is in the FUNCTIONAL CORE because these functions are pure:
   - They take data and return Hiccup structures
   - No side effects, no I/O
   - Easy to test
   
   Templates include:
   - Post list (home page)
   - Post detail (single post view)
   - Post form (create/edit)
   - Post card (list item)"
  (:require [blog.post.core.post :as post-core]
            [clojure.string :as str]))

;; =============================================================================
;; Date Formatting
;; =============================================================================

(defn format-date
  "Format an Instant for display.
   
   Args:
     inst: java.time.Instant
     
   Returns:
     Formatted date string like 'January 17, 2026'"
  [inst]
  (when inst
    (let [formatter (java.time.format.DateTimeFormatter/ofPattern "MMMM d, yyyy")
          zoned (.atZone inst (java.time.ZoneId/of "UTC"))]
      (.format formatter zoned))))

(defn format-datetime
  "Format an Instant with time.
   
   Args:
     inst: java.time.Instant
     
   Returns:
     Formatted string like 'January 17, 2026 at 3:45 PM'"
  [inst]
  (when inst
    (let [formatter (java.time.format.DateTimeFormatter/ofPattern "MMMM d, yyyy 'at' h:mm a")
          zoned (.atZone inst (java.time.ZoneId/of "UTC"))]
      (.format formatter zoned))))

;; =============================================================================
;; Post Card (List Item)
;; =============================================================================

(defn post-card
  "Render a post card for list views.
   
   Args:
     post: Post entity
     opts: Optional map with :show-status (show draft/published badge)"
  [post & [opts]]
  (let [{:keys [show-status]} opts
        reading-mins (post-core/reading-time (:content post))]
    [:article.post-card
     [:header
      [:h2 [:a {:href (str "/posts/" (:slug post))} (:title post)]]
      [:p.post-meta
       (when-let [date (or (:published-at post) (:created-at post))]
         [:time {:datetime (str date)} (format-date date)])
       (when reading-mins
         [:span.reading-time (str " • " reading-mins " min read")])
       (when (and show-status (not (:published post)))
         [:span.badge.draft "Draft"])]]
     (when-let [excerpt (:excerpt post)]
       [:p.excerpt excerpt])
     [:footer
      [:a {:href (str "/posts/" (:slug post))} "Read more →"]]]))

;; =============================================================================
;; Post List
;; =============================================================================

(defn post-list
  "Render a list of posts.
   
   Args:
     posts: Sequence of post entities
     opts: Optional map with :empty-message, :show-status"
  [posts & [opts]]
  (let [{:keys [empty-message show-status]
         :or {empty-message "No posts yet."}} opts]
    (if (empty? posts)
      [:div.empty-state
       [:p empty-message]]
      [:div.post-list
       (for [post posts]
         ^{:key (:id post)}
         (post-card post {:show-status show-status}))])))

;; =============================================================================
;; Post Detail
;; =============================================================================

(defn post-detail
  "Render a full post view.
   
   Args:
     post: Post entity
     opts: Optional map with :show-edit-link, :user"
  [post & [opts]]
  (let [{:keys [show-edit-link user]} opts
        reading-mins (post-core/reading-time (:content post))
        is-author? (and user (post-core/author? post (:id user)))]
    [:article.post-detail
     [:header
      [:h1 (:title post)]
      [:p.post-meta
       (when-let [date (or (:published-at post) (:created-at post))]
         [:time {:datetime (str date)} (format-date date)])
       (when reading-mins
         [:span.reading-time (str " • " reading-mins " min read")])
       (when (and is-author? show-edit-link)
         [:a.edit-link {:href (str "/dashboard/posts/" (:id post) "/edit")} "Edit"])]]
     
     [:div.post-content
      ;; In a real app, you'd want to render Markdown here
      ;; For simplicity, we'll just preserve line breaks
      (for [paragraph (str/split (:content post) #"\n\n")]
        [:p paragraph])]
     
     (when-not (:published post)
       [:div.draft-notice
        [:p "This post is a draft and not visible to the public."]])]))

;; =============================================================================
;; Post Form
;; =============================================================================

(defn post-form
  "Render a post create/edit form.
   
   Args:
     opts: Map with:
       :post - Existing post for editing (nil for new)
       :errors - Validation error messages
       :action - Form action URL
       :method - Form method (default POST)"
  [opts]
  (let [{:keys [post errors action method]
         :or {method "POST"}} opts
        editing? (some? post)]
    [:form.post-form {:action action :method method}
     
     ;; Title
     [:div.form-group
      [:label {:for "title"} "Title"]
      [:input {:type "text"
               :id "title"
               :name "title"
               :value (or (:title post) "")
               :required true
               :maxlength 200
               :placeholder "Enter post title..."}]]
     
     ;; Content
     [:div.form-group
      [:label {:for "content"} "Content"]
      [:textarea {:id "content"
                  :name "content"
                  :rows 15
                  :required true
                  :placeholder "Write your post content..."}
       (or (:content post) "")]]
     
     ;; Excerpt (optional)
     [:div.form-group
      [:label {:for "excerpt"} "Excerpt (optional)"]
      [:textarea {:id "excerpt"
                  :name "excerpt"
                  :rows 3
                  :maxlength 500
                  :placeholder "Brief summary for post listings..."}
       (or (:excerpt post) "")]]
     
     ;; Published checkbox
     [:div.form-group
      [:label
       [:input {:type "checkbox"
                :name "published"
                :value "true"
                :checked (boolean (:published post))}]
       " Publish immediately"]]
     
     ;; Errors
     (when (seq errors)
       [:div.form-errors
        [:ul
         (for [error errors]
           [:li error])]])
     
     ;; Submit
     [:div.form-actions
      [:button.button {:type "submit"}
       (if editing? "Update Post" "Create Post")]
      [:a.button.secondary {:href "/dashboard"} "Cancel"]]]))

;; =============================================================================
;; Dashboard Components
;; =============================================================================

(defn dashboard-post-row
  "Render a post row for the dashboard table.
   
   Args:
     post: Post entity"
  [post]
  [:tr
   [:td [:a {:href (str "/posts/" (:slug post))} (:title post)]]
   [:td (if (:published post)
          [:span.badge.published "Published"]
          [:span.badge.draft "Draft"])]
   [:td (format-date (:created-at post))]
   [:td.actions
    [:a {:href (str "/dashboard/posts/" (:id post) "/edit")} "Edit"]
    " | "
    [:form {:method "POST" 
            :action (str "/dashboard/posts/" (:id post) "/delete")
            :style "display: inline;"
            :onsubmit "return confirm('Delete this post?');"}
     [:button.link-button {:type "submit"} "Delete"]]]])

(defn dashboard-posts-table
  "Render the dashboard posts table.
   
   Args:
     posts: Sequence of post entities"
  [posts]
  (if (empty? posts)
    [:div.empty-state
     [:p "You haven't written any posts yet."]
     [:a.button {:href "/dashboard/posts/new"} "Write your first post"]]
    [:div
     [:div.dashboard-header
      [:a.button {:href "/dashboard/posts/new"} "New Post"]]
     [:table.posts-table
      [:thead
       [:tr
        [:th "Title"]
        [:th "Status"]
        [:th "Created"]
        [:th "Actions"]]]
      [:tbody
       (for [post posts]
         ^{:key (:id post)}
         (dashboard-post-row post))]]]))

;; =============================================================================
;; Page Templates
;; =============================================================================

(defn home-page-content
  "Home page content showing published posts.
   
   Args:
     posts: Sequence of published posts
     pagination: Map with :current-page, :total-pages"
  [posts & [pagination]]
  [:div
   [:h1 "Latest Posts"]
   (post-list posts)
   (when pagination
     ;; Include pagination if needed
     nil)])

(defn post-page-content
  "Single post page content.
   
   Args:
     post: Post entity
     opts: Options passed to post-detail"
  [post & [opts]]
  (post-detail post opts))

(defn dashboard-page-content
  "Dashboard page content.
   
   Args:
     posts: Author's posts"
  [posts]
  [:div
   [:h1 "Dashboard"]
   [:p "Manage your blog posts."]
   (dashboard-posts-table posts)])

(defn new-post-page-content
  "New post form page.
   
   Args:
     opts: Form options"
  [& [opts]]
  [:div
   [:h1 "New Post"]
   (post-form (merge opts {:action "/dashboard/posts"}))])

(defn edit-post-page-content
  "Edit post form page.
   
   Args:
     post: Post to edit
     opts: Form options"
  [post & [opts]]
  [:div
   [:h1 "Edit Post"]
   (post-form (merge opts {:post post 
                           :action (str "/dashboard/posts/" (:id post) "/update")}))])
