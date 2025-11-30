(ns boundary.inventory.core.ui
  "Pure UI generation for inventory module - Hiccup templates.")

(defn item-list-page
  "Generate item listing page."
  [items opts]
  [:div.page
   [:h1 "Items"]
   [:div.items
    (for [item items]
      [:div.item {:key (:id item)}
       [:p (str (:id item))]])]])
