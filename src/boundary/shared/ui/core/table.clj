(ns boundary.shared.ui.core.table
  "Shared table UI helpers (sorting, paging) for Hiccup-based web UIs."
  (:require [boundary.shared.web.table :as web-table]))

(defn sortable-th
  "Reusable sortable table header cell.

   opts:
   - :label         string label to display
   - :field         keyword used as :sort in TableQuery
   - :current-sort  current :sort from TableQuery
   - :current-dir   current :dir from TableQuery
   - :base-url      base path (e.g. \"/web/users\")
   - :page          current page (integer)
   - :page-size     current page-size (integer)
   - :hx-target     HTMX target selector (string)
   - :hx-push-url?  bool, default true
   - :extra-params  map of additional query params (keyword/string keys)

   Behaviour:
   - Clicking toggles :dir between :asc and :desc for the same :field.
   - When changing sort field or direction, page is reset to 1.
   "
  [{:keys [label field current-sort current-dir base-url page page-size
           hx-target hx-push-url? extra-params]}]
  (let [active?  (= current-sort field)
        next-dir (if (and active? (= current-dir :asc)) :desc :asc)
        icon     (cond
                   (not active?) ""
                   (= current-dir :asc) " ↑"
                   :else " ↓")
        page*    1
        base-q   (web-table/table-query->params
                  {:sort field :dir next-dir :page page* :page-size page-size})
        extra-q  (into {}
                       (for [[k v] extra-params]
                         [(name k) (str v)]))
        qs-map   (merge base-q extra-q)
        url      (str base-url "?" (web-table/encode-query-params qs-map))]
    [:th
     {:hx-get     url
      :hx-target  hx-target
      :hx-push-url (when hx-push-url? "true")
      :class      (str "sortable-header"
                       (when active? " sortable-header--active"))
      :role       "button"
      :tabindex   "0"}
     (str label icon)]))

(defn pagination
  "Render pagination controls for a table.

   opts:
   - :table-query   normalized TableQuery map
   - :total-count   total number of items
   - :base-url      base URL for hx-get links (e.g. \"/web/users/table\")
   - :hx-target     HTMX target selector
   - :extra-params  map of additional query params (filters, etc.)

   Returns nil when a single page is sufficient."
  [{:keys [table-query total-count base-url hx-target extra-params]}]
  (let [{:keys [page page-size]} table-query
        total-count  (long (or total-count 0))
        page-size    (long (max 1 (or page-size 20)))
        page         (long (max 1 (or page 1)))
        total-pages  (max 1 (long (Math/ceil (/ (double total-count)
                                                (double page-size)))))
        page         (min page total-pages)
        from         (if (pos? total-count)
                       (inc (* (dec page) page-size))
                       0)
        to           (if (pos? total-count)
                       (min total-count (+ (* (dec page) page-size) page-size))
                       0)
        show-pages?  (> total-pages 1)
        extra-q      (into {}
                           (for [[k v] extra-params]
                             [(name k) (str v)]))
        mk-url       (fn [page*]
                       (let [tq   (assoc table-query :page page*)
                             base (web-table/table-query->params tq)
                             qs   (merge base extra-q)]
                         (str base-url "?" (web-table/encode-query-params qs))))
        prev-page    (max 1 (dec page))
        next-page    (min total-pages (inc page))]
    (when show-pages?
      [:div.table-pagination
       [:div.page-info
        (str "Showing " from "–" to " of " total-count)]
       [:nav.pagination-nav
        [:button {:type       "button"
                  :hx-get     (mk-url prev-page)
                  :hx-target  hx-target
                  :hx-push-url "true"
                  :disabled   (<= page 1)}
         "Previous"]
        [:span.page-status
         (str "Page " page " of " total-pages)]
        [:button {:type       "button"
                  :hx-get     (mk-url next-page)
                  :hx-target  hx-target
                  :hx-push-url "true"
                  :disabled   (>= page total-pages)}
         "Next"]]])))
