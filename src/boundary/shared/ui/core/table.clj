(ns boundary.shared.ui.core.table
  "Shared table UI helpers (sorting, paging) for Hiccup-based web UIs."
  (:require [boundary.shared.web.table :as web-table]
            [clojure.string :as str]))

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
