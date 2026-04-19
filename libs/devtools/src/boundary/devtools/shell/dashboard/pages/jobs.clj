(ns boundary.devtools.shell.dashboard.pages.jobs
  "Dashboard page for Jobs & Queues monitoring."
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [hiccup2.core :as h]))

(defn- queue-table
  "Render a table of queues with their sizes and stats."
  [queues]
  (if (empty? queues)
    [:div.empty-state "No queues active."]
    (c/data-table
     {:columns      ["Queue" "Pending" "Processed" "Failed" "Avg Duration"]
      :col-template "1fr 100px 100px 100px 120px"
      :rows         (for [[queue-name {:keys [size processed failed avg-duration]}] (sort-by key queues)]
                      {:cells [[:span.text-mono (name queue-name)]
                               [:span (str (or size 0))]
                               [:span (str (or processed 0))]
                               [:span {:style (when (and failed (pos? failed))
                                                "color:var(--color-red,#f87171)")}
                                (str (or failed 0))]
                               [:span (if avg-duration (str avg-duration "ms") "—")]]})})))

(defn- failed-jobs-list
  "Render the list of failed jobs."
  [failed-jobs]
  (if (empty? failed-jobs)
    [:div.empty-state "No failed jobs."]
    [:div.error-list
     (for [{:keys [id job-type queue error retry-count]} failed-jobs]
       [:div.error-list-row
        [:span.error-code {:style "color: var(--color-red, #f87171); font-family: monospace; font-weight: bold;"}
         (name (or job-type :unknown))]
        [:span.error-message (or error "Unknown error")]
        (when retry-count
          (c/count-badge retry-count "yellow"))
        (when queue
          [:span.request-time (name queue)])
        [:button.filter-input
         {:hx-post   (str "/dashboard/fragments/retry-job?job-id=" id)
          :hx-target "#failed-jobs-container"
          :hx-swap   "innerHTML"
          :style     "cursor:pointer;padding:2px 8px;font-size:11px;width:auto"}
         "Retry"]])]))

(defn- jobs-content
  "Render the jobs page content (used for both full page and fragment)."
  [{:keys [job-stats failed-jobs]}]
  (let [{:keys [total-processed total-failed total-succeeded queues]} job-stats
        active (reduce + 0 (map (fn [[_ v]] (or (:size v) 0)) queues))]
    (list
     [:div.stat-row
      (c/stat-card {:label "Active/Pending" :value active
                    :value-class (when (pos? active) "stat-value-warning")})
      (c/stat-card {:label "Processed" :value (or total-processed 0)})
      (c/stat-card {:label "Succeeded" :value (or total-succeeded 0)
                    :value-class "green"})
      (c/stat-card {:label "Failed" :value (or total-failed 0)
                    :value-class (when (and total-failed (pos? total-failed)) "stat-value-error")})]
     [:div.two-col
      (c/card {:title "Queues"} (queue-table (or queues {})))
      (c/card {:title "Failed Jobs"
               :right [:span.live-indicator "● polling 5s"]}
              [:div#failed-jobs-container
               (failed-jobs-list (or failed-jobs []))])])))

(defn render
  "Render the Jobs & Queues full page."
  [opts]
  (if (or (:job-stats opts) (:job-queue opts))
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/jobs"
                  :title       "Jobs & Queues"})
     [:div {:hx-get     "/dashboard/fragments/jobs-content"
            :hx-trigger "every 5s"
            :hx-swap    "innerHTML"}
      (jobs-content opts)])
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/jobs"
                  :title       "Jobs & Queues"})
     [:div.empty-state
      "No job service configured. Add :boundary/jobs to your system config to enable job monitoring."])))

(defn render-fragment
  "Render the jobs content as an HTML fragment for HTMX polling."
  [opts]
  (str (h/html (jobs-content opts))))

(defn render-failed-jobs-fragment
  "Render only the failed jobs list as an HTML fragment for retry swap."
  [opts]
  (str (h/html (failed-jobs-list (or (:failed-jobs opts) [])))))
