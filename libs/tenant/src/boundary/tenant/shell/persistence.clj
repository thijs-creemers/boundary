(ns boundary.tenant.shell.persistence
  (:require [boundary.core.utils.type-conversion :as type-conversion]
            [boundary.platform.shell.adapters.database.common.core :as db]
            [boundary.platform.shell.persistence-interceptors :as persistence-interceptors]
            [boundary.tenant.ports :as ports]
            [cheshire.core]
            [clojure.set]))

(defn- tenant-entity->db
  [ctx tenant-entity]
  (let [_adapter (:adapter ctx)]
    (-> tenant-entity
        (update :id type-conversion/uuid->string)
        (update :status type-conversion/keyword->string)
        (update :created-at type-conversion/instant->string)
        (update :updated-at type-conversion/instant->string)
        (update :deleted-at type-conversion/instant->string)
        (update :settings #(when % (cheshire.core/generate-string %)))
        (clojure.set/rename-keys {:schema-name :schema_name
                                  :created-at :created_at
                                  :updated-at :updated_at
                                  :deleted-at :deleted_at}))))

(defn- db->tenant-entity
  [ctx db-record]
  (when db-record
    (let [_adapter (:adapter ctx)]
      (-> db-record
          (clojure.set/rename-keys {:schema_name :schema-name
                                    :created_at :created-at
                                    :updated_at :updated_at
                                    :deleted_at :deleted-at})
          (update :id type-conversion/string->uuid)
          (update :status type-conversion/string->keyword)
          (update :created-at type-conversion/string->instant)
          (update :updated-at type-conversion/string->instant)
          (update :deleted-at type-conversion/string->instant)
          (update :settings #(when % (cheshire.core/parse-string % true)))))))

(defrecord TenantRepository [ctx logger error-reporter]
  ports/ITenantRepository

  (find-tenant-by-id [_this tenant-id]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "find-tenant-by-id"
     {:tenant-id tenant-id}
     (fn []
       (when-let [record (db/execute-one! ctx
                                          ["SELECT * FROM tenants WHERE id = ? AND deleted_at IS NULL"
                                           (str tenant-id)])]
         (db->tenant-entity ctx record)))))

  (find-tenant-by-slug [_this slug]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "find-tenant-by-slug"
     {:slug slug}
     (fn []
       (when-let [record (db/execute-one! ctx
                                          ["SELECT * FROM tenants WHERE slug = ? AND deleted_at IS NULL"
                                           slug])]
         (db->tenant-entity ctx record)))))

  (find-all-tenants [_this {:keys [limit offset include-deleted?] :or {limit 50 offset 0 include-deleted? false}}]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "find-all-tenants"
     {:limit limit :offset offset :include-deleted? include-deleted?}
      (fn []
        (let [query (if include-deleted?
                      "SELECT * FROM tenants ORDER BY created_at DESC LIMIT ? OFFSET ?"
                      "SELECT * FROM tenants WHERE deleted_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?")
              records (db/execute-query! ctx [query limit offset])]
          (mapv #(db->tenant-entity ctx %) records)))))

  (create-tenant [_this tenant-entity]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "create-tenant"
     {:tenant-id (:id tenant-entity) :slug (:slug tenant-entity)}
     (fn []
       (let [db-record (tenant-entity->db ctx tenant-entity)]
         (db/execute-one! ctx
                         ["INSERT INTO tenants (id, slug, name, schema_name, status, settings, created_at, updated_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                          (:id db-record)
                          (:slug db-record)
                          (:name db-record)
                          (:schema_name db-record)
                          (:status db-record)
                          (:settings db-record)
                          (:created_at db-record)
                          (:updated_at db-record)])
         tenant-entity))))

  (update-tenant [_this tenant-entity]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "update-tenant"
     {:tenant-id (:id tenant-entity)}
     (fn []
       (let [db-record (tenant-entity->db ctx tenant-entity)]
         (db/execute-one! ctx
                         ["UPDATE tenants 
                           SET name = ?, status = ?, settings = ?, updated_at = ?, deleted_at = ?
                           WHERE id = ?"
                          (:name db-record)
                          (:status db-record)
                          (:settings db-record)
                          (:updated_at db-record)
                          (:deleted_at db-record)
                          (:id db-record)])
         tenant-entity))))

  (delete-tenant [_this tenant-id]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "delete-tenant"
     {:tenant-id tenant-id}
     (fn []
       (db/execute-one! ctx
                       ["DELETE FROM tenants WHERE id = ?"
                        (str tenant-id)])
       nil)))

  (tenant-slug-exists? [_this slug]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "tenant-slug-exists?"
     {:slug slug}
     (fn []
       (boolean (db/execute-one! ctx
                                ["SELECT 1 FROM tenants WHERE slug = ?" slug])))))

  (create-tenant-schema [_this schema-name]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "create-tenant-schema"
     {:schema-name schema-name}
      (fn []
        (db/execute-ddl! ctx (str "CREATE SCHEMA IF NOT EXISTS " schema-name))
        nil)))

  (drop-tenant-schema [_this schema-name]
    (persistence-interceptors/execute-persistence-operation
     logger error-reporter
     "drop-tenant-schema"
     {:schema-name schema-name}
      (fn []
        (db/execute-ddl! ctx (str "DROP SCHEMA IF EXISTS " schema-name " CASCADE"))
        nil))))

(defn create-tenant-repository
  [ctx logger error-reporter]
  (->TenantRepository ctx logger error-reporter))
