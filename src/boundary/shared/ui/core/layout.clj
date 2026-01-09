(ns boundary.shared.ui.core.layout
  "Layout components for consistent page structure.
   
   Contains page layout, navigation, and structural components that provide
   consistent look and feel across all domain modules."
  (:require [boundary.shared.ui.core.components :as components]
            [boundary.shared.ui.core.icons :as icons]))

(defn main-navigation
  "Main site navigation component.
   
   Args:
     opts: Optional map with navigation configuration
     
   Returns:
     Hiccup navigation structure"
  [& [opts]]
  (let [{:keys [user]} opts]
    [:nav
     [:a.logo {:href "/"} "Boundary App"]
     (when user
       [:div.nav-links
        [:a {:href "/web/users"} "Users"]
        [:a {:href "/web/audit"} "Audit Trail"]])
     (if user
       [:div.user-nav
        [:span "Welcome, " (:name user)]
        (icons/theme-toggle-button)
        [:form {:method "POST" :action "/web/logout" :style "display: inline;"}
         [:button {:type "submit" :class "link-button"} "Logout"]]]
       [:div.user-nav
        (icons/theme-toggle-button)
        [:a {:href "/web/login"} "Login"]])]))

(defn page-layout
  "Main page layout wrapper.
   
   Args:
     title: Page title string
     content: Main page content (Hiccup structure)
     opts: Optional map with :user, :flash, :css, :js, etc.
     
   Returns:
     Complete HTML page structure"
  [title content & [opts]]
  (let [{:keys [user flash css js]
         :or {css ["/css/pico.min.css" "/css/tokens.css" "/css/app.css"]
              js ["/js/theme.js" "/js/htmx.min.js"]}} opts]
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title title]
      (for [css-file css]
        [:link {:rel "stylesheet" :href css-file}])]
     [:body
      [:header.site-header
       (main-navigation {:user user})]
      (let [children (cond-> []
                       flash (conj [:div.flash-messages
                                    (for [[type message] flash]
                                      [:div {:class (str "alert alert-" (name type))} message])])
                       true  (conj content))]
        (into [:main.main-content] children))
      (for [js-file js]
        [:script {:src js-file}])]]))

(defn error-layout
  "Error page layout.
   
   Args:
     status: HTTP status code
     title: Error title
     message: Error message
     details: Optional error details
     
   Returns:
     Error page HTML structure"
  [status title message & [details]]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title (str status " - " title)]
    [:link {:rel "stylesheet" :href "/css/site.css"}]]
   [:body
    [:div.error-page
     [:h1 title]
     [:p.error-message message]
     (when details
       [:div.error-details
        [:h3 "Details:"]
        [:pre details]])
     [:a.button {:href "/"} "Go Home"]]]])

(defn home-page-content
  "Default home page content.
   
   Returns:
     Hiccup structure for home page"
  []
  [:div.home-content
   [:h1 "Welcome to Boundary Application"]
   [:p "This is a modular application built with Clojure."]
   [:div.navigation-links
    [:a.button {:href "/users"} "Manage Users"]]])

(defn render-error-page
  "Render a complete error page.
   
   Args:
     message: Error message
     status: HTTP status code (optional, defaults to 500)
     
   Returns:
     HTML string for error page"
  ([message]
   (render-error-page message 500))
  ([message status]
   (components/render-html
    (error-layout status "Error" message))))

(defn modal
  "Generic modal dialog component.
   
   Args:
     id: Modal ID for targeting
     title: Modal title
     content: Modal content (Hiccup structure)
     opts: Optional map with :closable, :size, etc.
     
   Returns:
     Hiccup modal structure"
  [id title content & [opts]]
  (let [{:keys [closable size]
         :or {closable true size "medium"}} opts]
    [:div.modal {:id id :class (str "modal-" size)}
     [:div.modal-backdrop]
     [:div.modal-content
      [:div.modal-header
       [:h3 title]
       (when closable
         [:button.modal-close {:onclick (str "closeModal('" id "')")} "×"])]
      [:div.modal-body content]]]))

(defn sidebar
  "Sidebar component for layouts that need side navigation.
   
   Args:
     content: Sidebar content (Hiccup structure)
     opts: Optional map with :position, :collapsed, etc.
     
   Returns:
     Hiccup sidebar structure"
  [content & [opts]]
  (let [{:keys [position collapsed]
         :or {position "left"}} opts]
    [:aside.sidebar {:class (str "sidebar-" position (when collapsed " collapsed"))}
     content]))

(defn breadcrumbs
  "Breadcrumb navigation component.
   
   Args:
     crumbs: Vector of [text url] pairs, last item is current page
     
   Returns:
     Hiccup breadcrumb structure"
  [crumbs]
  (when (seq crumbs)
    [:nav.breadcrumbs
     [:ol
      (for [[text url] crumbs]
        (if url
          [:li [:a {:href url} text]]
          [:li.current text]))]]))

(defn card
  "Card component for organizing content.
   
   Args:
     content: Card content (Hiccup structure)
     opts: Optional map with :title, :class, :actions, etc.
     
   Returns:
     Hiccup card structure"
  [content & [opts]]
  (let [{:keys [title class actions]} opts]
    [:div.card {:class class}
     (when title
       [:div.card-header
        [:h4 title]
        (when actions
          [:div.card-actions actions])])
     [:div.card-content content]]))