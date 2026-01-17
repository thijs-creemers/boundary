(ns blog.shared.ui.layout
  "Blog layout components using Hiccup.
   
   Provides consistent page structure including:
   - HTML head with CSS/JS
   - Navigation header
   - Footer
   - Flash messages"
  (:require [hiccup2.core :as h]
            [hiccup.util :refer [raw-string]]))

;; =============================================================================
;; HTML Head
;; =============================================================================

(defn head
  "Generate HTML head with meta tags, CSS, and title.
   
   Args:
     title: Page title
     opts: Optional map with :description"
  [title & [opts]]
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:title title]
   (when (:description opts)
     [:meta {:name "description" :content (:description opts)}])
   ;; Pico CSS for minimal styling
   [:link {:rel "stylesheet" 
           :href "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css"}]
   ;; HTMX for dynamic interactions
   [:script {:src "https://unpkg.com/htmx.org@1.9.10"}]
   ;; Custom blog styles
   [:link {:rel "stylesheet" :href "/css/blog.css"}]])

;; =============================================================================
;; Navigation
;; =============================================================================

(defn nav-link
  "Generate a navigation link.
   
   Args:
     href: Link URL
     text: Link text
     active?: Whether this is the current page"
  [href text & [active?]]
  [:a {:href href 
       :class (when active? "active")} 
   text])

(defn navigation
  "Generate the site navigation header.
   
   Args:
     opts: Map with :user (logged-in user), :active (current page keyword)"
  [& [opts]]
  (let [{:keys [user active blog-name]} opts
        blog-name (or blog-name "My Blog")]
    [:nav.container
     [:ul
      [:li [:strong [:a {:href "/"} blog-name]]]]
     [:ul
      [:li (nav-link "/" "Home" (= active :home))]
      (if user
        (list
         [:li (nav-link "/dashboard" "Dashboard" (= active :dashboard))]
         [:li [:a {:href "/logout"} "Logout"]])
        (list
         [:li (nav-link "/login" "Login" (= active :login))]))]]))

;; =============================================================================
;; Footer
;; =============================================================================

(defn footer
  "Generate the site footer."
  [& [opts]]
  (let [{:keys [blog-name]} opts
        blog-name (or blog-name "My Blog")]
    [:footer.container
     [:small 
      "Built with "
      [:a {:href "https://github.com/thijs-creemers/boundary" 
           :target "_blank"} 
       "Boundary Framework"]
      " • "
      blog-name
      " © 2026"]]))

;; =============================================================================
;; Flash Messages
;; =============================================================================

(defn flash-message
  "Render a flash message if present.
   
   Args:
     flash: Map with :type (:success, :error, :info) and :message"
  [flash]
  (when flash
    (let [type-class (case (:type flash)
                       :success "flash-success"
                       :error "flash-error"
                       :info "flash-info"
                       "flash-info")]
      [:div {:class (str "flash " type-class)
             :role "alert"}
       (:message flash)])))

;; =============================================================================
;; Page Layout
;; =============================================================================

(defn page
  "Wrap content in a complete HTML page.
   
   Args:
     title: Page title
     content: Hiccup content for the page body
     opts: Optional map with:
       :user - Logged-in user
       :active - Current page keyword (:home, :dashboard, etc.)
       :flash - Flash message map
       :description - Meta description
       :blog-name - Blog name for header/footer"
  [title content & [opts]]
  (let [{:keys [flash]} opts]
    [:html {:lang "en"}
     (head title opts)
     [:body
      [:header
       (navigation opts)]
      [:main.container
       (flash-message flash)
       content]
      (footer opts)]]))

(defn render
  "Render a Hiccup structure to an HTML string.
   
   Args:
     hiccup: Hiccup data structure
     
   Returns:
     HTML string with doctype."
  [hiccup]
  (str "<!DOCTYPE html>\n" (h/html hiccup)))

;; =============================================================================
;; Common Components
;; =============================================================================

(defn loading-indicator
  "HTMX loading indicator."
  []
  [:div.htmx-indicator
   [:span "Loading..."]])

(defn empty-state
  "Empty state placeholder.
   
   Args:
     message: Message to display
     action: Optional map with :href and :text for call-to-action"
  [message & [action]]
  [:div.empty-state
   [:p message]
   (when action
     [:a.button {:href (:href action)} (:text action)])])

(defn pagination
  "Pagination controls.
   
   Args:
     current-page: Current page number (1-based)
     total-pages: Total number of pages
     base-url: Base URL for pagination links"
  [current-page total-pages base-url]
  (when (> total-pages 1)
    [:nav.pagination
     [:ul
      ;; Previous
      (when (> current-page 1)
        [:li [:a {:href (str base-url "?page=" (dec current-page))} "← Previous"]])
      
      ;; Page numbers
      (for [page (range 1 (inc total-pages))
            :when (or (<= page 3)
                      (>= page (- total-pages 2))
                      (<= (Math/abs (- page current-page)) 1))]
        [:li {:class (when (= page current-page) "active")}
         [:a {:href (str base-url "?page=" page)} page]])
      
      ;; Next
      (when (< current-page total-pages)
        [:li [:a {:href (str base-url "?page=" (inc current-page))} "Next →"]])]]))

(defn form-errors
  "Display form validation errors.
   
   Args:
     errors: Sequence of error messages"
  [errors]
  (when (seq errors)
    [:div.form-errors
     [:ul
      (for [error errors]
        [:li error])]]))
