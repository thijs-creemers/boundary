(ns boundary.inventory.shell.persistence
  "Persistence layer for inventory module."
  (:require [boundary.inventory.ports :as ports]
            [boundary.shell.adapters.database.common.core :as db]
            [honey.sql :as sql]))

(defrecord DatabaseItemRepository [db-ctx]
  ports/IItemRepository
  (create [this entity]
    (db/execute-one! db-ctx
      (sql/format {:insert-into :Items
                   :values [entity]
                   :returning [:*]})))
  (find-by-id [this id]
    (db/execute-one! db-ctx
      (sql/format {:select [:*]
                   :from [:Items]
                   :where [:= :id id]})))
  (find-all [this opts]
    (db/execute! db-ctx
      (sql/format {:select [:*]
                   :from [:Items]
                   :limit (:limit opts 20)})))
  (update-item [this entity]
    (db/execute-one! db-ctx
      (sql/format {:update :Items
                   :set (dissoc entity :id)
                   :where [:= :id (:id entity)]
                   :returning [:*]})))
  (delete [this id]
    (db/execute-one! db-ctx
      (sql/format {:delete-from :Items
                   :where [:= :id id]}))))

(defn create-repository [db-ctx]
  (->DatabaseItemRepository db-ctx))
