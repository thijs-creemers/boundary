(ns boundary.inventory.shell.service
  "Service layer for inventory module."
  (:require [boundary.inventory.ports :as ports]
            [boundary.inventory.core.Item :as core]))

(defrecord ItemService [repository]
  ports/IItemService
  (create-item [this data]
    (let [prepared (core/prepare-for-creation data)]
      (.create repository prepared)))
  (find-item [this id]
    (.find-by-id repository id))
  (list-items [this opts]
    (.list-items repository opts))
  (update-item [this id data]
    (.update repository (assoc data :id id)))
  (delete-item [this id]
    (.delete repository id)))

(defn create-service [repository]
  (->ItemService repository))
