(ns boundary.devtools.shell.dashboard.pages.requests
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.shell.dashboard.middleware :as middleware]
            [clojure.string :as str]
            [hiccup2.core :as h]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- relative-time
  "Convert java.time.Instant to a human-readable relative time string."
  [instant]
  (let [now-ms   (System/currentTimeMillis)
        then-ms  (.toEpochMilli instant)
        diff-s   (/ (- now-ms then-ms) 1000)]
    (cond
      (< diff-s 60)   (str (int diff-s) "s ago")
      (< diff-s 3600) (str (int (/ diff-s 60)) "m ago")
      :else           (str (int (/ diff-s 3600)) "h ago"))))

(defn- status-color
  "Return inline CSS style string based on HTTP status code."
  [status]
  (cond
    (< status 300) "color: var(--color-green-light, #4ade80)"
    (< status 400) "color: var(--color-blue, #60a5fa)"
    :else          "color: var(--color-red, #f87171)"))

(defn- duration-style
  "Return yellow style if ms > 100, empty string otherwise."
  [ms]
  (if (> ms 100)
    "color: var(--color-yellow, #facc15)"
    ""))

;; =============================================================================
;; Filtering
;; =============================================================================

(defn- status-matches?
  "Check if a numeric status code matches a filter like \"2xx\", \"4xx\", etc."
  [status filter-str]
  (when (and status (not (str/blank? filter-str)))
    (let [prefix (subs filter-str 0 1)]
      (= prefix (str (quot status 100))))))

(defn- filter-entries
  "Filter request log entries by path substring and status range."
  [entries {:keys [path-filter status-filter]}]
  (cond->> entries
    (not (str/blank? path-filter))
    (filter (fn [e] (str/includes? (str (:path e)) path-filter)))
    (not (str/blank? status-filter))
    (filter (fn [e] (status-matches? (:status e) status-filter)))))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn- request-rows
  "Build data-table rows from request log entries."
  [entries]
  (for [{:keys [status method path duration-ms timestamp]} entries]
    {:cells [[:span {:style (status-color status)} status]
             (c/method-badge method)
             [:span.route-path path]
             [:span {:style (duration-style duration-ms)} (str duration-ms "ms")]
             [:span.request-time (relative-time timestamp)]]}))

(defn render-request-list
  "Build a data-table from the current request log, newest first, limit 50."
  [& [{:keys [path-filter status-filter] :as filters}]]
  (let [entries (->> (middleware/request-log)
                     (filter-entries (or filters {}))
                     (take 50))]
    (if (empty? entries)
      [:div.empty-state "No requests captured yet. Make some HTTP requests to see them here."]
      (c/data-table
       {:columns      ["Status" "Method" "Path" "Duration" "Time"]
        :col-template "70px 90px 1fr 90px 100px"
        :rows         (request-rows entries)}))))

(defn render-fragment
  "Return the request list as an HTML string fragment for HTMX polling."
  [req]
  (let [params (get req :params {})
        filters {:path-filter   (or (get params "path") "")
                 :status-filter (or (get params "status") "")}]
    (str (h/html (render-request-list filters)))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Request Inspector full page with live HTMX polling."
  [opts]
  (layout/dashboard-page
   (merge opts {:active-path "/dashboard/requests"
                :title       "Request Inspector"})
   (c/filter-bar
    (c/filter-input {:name        "path"
                     :placeholder "Filter by path..."
                     :id          "path-search"
                     :hx-get      "/dashboard/fragments/request-list"
                     :hx-trigger  "keyup changed delay:300ms"
                     :hx-target   "#request-list-container"
                     :hx-swap     "innerHTML"
                     :hx-include  "[name='status']"})
    (c/filter-select {:name       "status"
                      :id         "status-filter"
                      :hx-get     "/dashboard/fragments/request-list"
                      :hx-trigger "change"
                      :hx-target  "#request-list-container"
                      :hx-swap    "innerHTML"
                      :hx-include "[name='path']"}
                     [{:value "" :label "All statuses"}
                      {:value "2xx" :label "2xx Success"}
                      {:value "3xx" :label "3xx Redirect"}
                      {:value "4xx" :label "4xx Client Error"}
                      {:value "5xx" :label "5xx Server Error"}])
    [:span.live-indicator "● Live — polling every 2s"])
   (c/card {:title "Requests"
            :right [:span.live-indicator "● polling 2s"]}
           [:div#request-list-container
            {:hx-get     "/dashboard/fragments/request-list"
             :hx-trigger "every 2s"
             :hx-swap    "innerHTML"
             :hx-include "[name='path'],[name='status']"}
            (render-request-list)])))

