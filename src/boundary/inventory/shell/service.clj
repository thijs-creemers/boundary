(ns boundary.inventory.shell.service
  "Service layer for inventory module."
  (:require [boundary.inventory.ports :as ports]
            [boundary.inventory.core.item :as core]))

(defrecord ItemService [repository]
  ports/IItemService
  (create-item [this data]
    (let [prepared (core/prepare-new-item data (java.time.Instant/now))]
      (.create repository prepared)))
  (get-item [this id]
    (.find-by-id repository id))
  (list-items [this opts]
    (.find-all repository opts))
  (update-item-data [this id data]
    (.update-item repository (assoc data :id id)))
  (delete-item [this id]
    (.delete repository id)))

(defn create-service [repository]
  (->ItemService repository))
