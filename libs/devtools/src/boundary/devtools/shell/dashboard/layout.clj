(ns boundary.devtools.shell.dashboard.layout
  "Dashboard shell: HTML wrapper, sidebar, top bar."
  (:require [boundary.ui-style :as ui-style]
            [hiccup2.core :as h]))

(def ^:private nav-items
  "Dashboard navigation items with unicode icons."
  [{:path "/dashboard"         :icon "⬡"  :label "Overview"}
   {:path "/dashboard/routes"  :icon "⇄"  :label "Routes"}
   {:path "/dashboard/requests" :icon "⇅"  :label "Requests"}
   {:path "/dashboard/schemas" :icon "⬡"  :label "Schemas"}
   {:path "/dashboard/database" :icon "⬤"  :label "Database"}
   {:path "/dashboard/errors"  :icon "⚠"  :label "Errors"}])

(defn sidebar
  "Renders the collapsible dashboard sidebar nav.
   opts — {:active-path string :system-status keyword}"
  [{:keys [active-path system-status]}]
  [:aside.sidebar
   {:x-data "{collapsed: false}"
    :x-bind:class "collapsed ? 'sidebar-collapsed' : ''"}
   [:div.sidebar-header
    [:div.sidebar-logo
     [:span.sidebar-logo-icon "⬡"]
     [:span.sidebar-logo-text {:x-show "!collapsed"} "Boundary Dev"]]
    [:button.sidebar-collapse-btn
     {:x-on:click "collapsed = !collapsed"
      :title "Toggle sidebar"}
     [:span {:x-show "collapsed"} "→"]
     [:span {:x-show "!collapsed"} "←"]]]
   [:nav.sidebar-nav
    (for [{:keys [path icon label]} nav-items]
      [:a.sidebar-nav-item
       {:href  path
        :class (when (= active-path path) "active")}
       [:span.nav-icon icon]
       [:span.nav-label {:x-show "!collapsed"} label]])]
   [:div.sidebar-footer
    (when system-status
      [:div.sidebar-status
       {:class (str "sidebar-status-" (name system-status))}
       [:span.nav-icon (case system-status :healthy "●" :error "●" "●")]
       [:span.nav-label {:x-show "!collapsed"}
        (str "System: " (name system-status))]])]])

(defn topbar
  "Renders the dashboard top bar with status info and app links.
   opts — {:title :component-count :error-count :http-port}"
  [{:keys [title component-count error-count http-port]}]
  [:header.topbar
   [:div.topbar-left
    [:h1.topbar-title (or title "Dev Dashboard")]]
   [:div.topbar-right
    (when component-count
      [:div.topbar-stat
       [:span.topbar-stat-label "Components: "]
       [:span.topbar-stat-value component-count]])
    (when error-count
      [:div.topbar-stat {:class (when (pos? error-count) "topbar-stat-error")}
       [:span.topbar-stat-label "Errors: "]
       [:span.topbar-stat-value error-count]])
    (when http-port
      [:div.topbar-links
       [:a.topbar-link {:href (str "http://localhost:" http-port) :target "_blank"}
        "App ↗"]
       [:a.topbar-link {:href (str "http://localhost:" http-port "/admin") :target "_blank"}
        "Admin ↗"]])]])

(defn dashboard-page
  "Renders a full HTML dashboard page document.
   opts — {:title :active-path :system-status :component-count :error-count :http-port}
   body — child hiccup elements placed in .dashboard-content"
  [opts & body]
  (let [{:keys [title active-path system-status
                component-count error-count http-port]} opts
        js-files  (ui-style/js-bundle :base)
        page-title (or title "Boundary Dev Dashboard")]
    (str
     "<!DOCTYPE html>"
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title page-title]
        [:link {:rel "stylesheet" :href "/assets/dashboard.css"}]
        (for [js-src js-files]
          [:script {:src js-src :defer true}])]
       [:body
        [:div.dashboard-layout
         (sidebar {:active-path   active-path
                   :system-status system-status})
         [:div.dashboard-main
          (topbar {:title           title
                   :component-count component-count
                   :error-count     error-count
                   :http-port       http-port})
          (into [:div.dashboard-content] body)]]]]))))
