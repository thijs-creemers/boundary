(ns boundary.devtools.shell.dashboard.pages.errors
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [hiccup2.core :as h]))

;; =============================================================================
;; Error log atom
;; =============================================================================

(defonce error-log* (atom []))

(defn record-error!
  "Swap new enriched-error into the atom, newest first, max 100 entries."
  [enriched-error]
  (swap! error-log* (fn [log]
                      (let [updated (vec (cons enriched-error log))]
                        (if (> (count updated) 100)
                          (subvec updated 0 100)
                          updated)))))

(defn clear-error-log!
  "Reset the error log atom."
  []
  (reset! error-log* []))

;; =============================================================================
;; Stats
;; =============================================================================

(defn error-stats
  "Count errors in last 24h by category (:validation, :persistence, :fcis)."
  []
  (let [now-ms      (System/currentTimeMillis)
        cutoff-ms   (- now-ms (* 24 60 60 1000))
        recent      (filter #(>= (get % :timestamp-ms 0) cutoff-ms) @error-log*)
        total       (count recent)
        validation  (count (filter #(= (:category %) :validation) recent))
        persistence (count (filter #(= (:category %) :persistence) recent))
        fcis        (count (filter #(= (:category %) :fcis) recent))]
    {:total       total
     :validation  validation
     :persistence persistence
     :fcis        fcis}))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- relative-time-ms
  "Convert epoch milliseconds to a human-readable relative time string."
  [ts-ms]
  (when ts-ms
    (let [diff-s (/ (- (System/currentTimeMillis) ts-ms) 1000)]
      (cond
        (< diff-s 60)   (str (int diff-s) "s ago")
        (< diff-s 3600) (str (int (/ diff-s 60)) "m ago")
        :else           (str (int (/ diff-s 3600)) "h ago")))))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn render-error-list
  "Render recent errors (max 20) as a list."
  []
  (let [entries (take 20 @error-log*)]
    (if (empty? entries)
      [:div.empty-state "No errors recorded yet. Errors will appear here as they occur."]
      [:div.error-list
       (for [{:keys [code message count timestamp-ms]} entries]
         [:div.error-list-row
          [:span.error-code {:style "color: var(--color-red, #f87171); font-family: monospace; font-weight: bold;"}
           (or code "BND-000")]
          [:span.error-message message]
          (when count
            (c/count-badge count "yellow"))
          (when timestamp-ms
            [:span.request-time (relative-time-ms timestamp-ms)])])])))

(defn render-fragment
  "Return the error list as an HTML string fragment for HTMX polling."
  []
  (str (h/html (render-error-list))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Error Dashboard full page with stats and HTMX polling."
  [opts]
  (let [{:keys [total validation persistence fcis]} (error-stats)]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/errors"
                  :title       "Error Dashboard"})
     [:div.stat-row
      (c/stat-card {:label "Total 24h"       :value total       :value-class (when (pos? total) "stat-value-error")})
      (c/stat-card {:label "Validation"      :value validation  :value-class (when (pos? validation) "stat-value-error")})
      (c/stat-card {:label "Persistence"     :value persistence :value-class (when (pos? persistence) "stat-value-error")})
      (c/stat-card {:label "FC/IS Violations" :value fcis       :value-class (when (pos? fcis) "stat-value-error")})]
     (c/card {:title "Recent Errors"
              :right [:span.live-indicator "● polling 2s"]}
             [:div#error-list-container
              {:hx-get     "/dashboard/fragments/error-list"
               :hx-trigger "every 2s"
               :hx-swap    "innerHTML"}
              (render-error-list)]))))
