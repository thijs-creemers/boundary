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
     :find-tenant-by-id
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [tenant-id (:tenant-id params)
             query {:select [:*]
                    :from [:tenants]
                    :where [:and
                            [:= :id (type-conversion/uuid->string tenant-id)]
                            [:is :deleted_at nil]]}
             result (db/execute-one! ctx query)]
         (db->tenant-entity ctx result)))
     ctx))

  (find-tenant-by-slug [_this slug]
    (persistence-interceptors/execute-persistence-operation
     :find-tenant-by-slug
     {:slug slug}
     (fn [{:keys [params]}]
       (let [slug (:slug params)
             query {:select [:*]
                    :from [:tenants]
                    :where [:and
                            [:= :slug slug]
                            [:is :deleted_at nil]]}
             result (db/execute-one! ctx query)]
         (db->tenant-entity ctx result)))
     ctx))

  (find-all-tenants [_this {:keys [limit offset include-deleted?] :or {limit 50 offset 0 include-deleted? false}}]
    (persistence-interceptors/execute-persistence-operation
     :find-all-tenants
     {:limit limit :offset offset :include-deleted? include-deleted?}
     (fn [{:keys [params]}]
       (let [{:keys [limit offset include-deleted?]} params
             query (cond-> {:select [:*]
                            :from [:tenants]
                            :order-by [[:created_at :desc]]
                            :limit limit
                            :offset offset}
                     (not include-deleted?) (assoc :where [:is :deleted_at nil]))
             records (db/execute-query! ctx query)]
         (mapv #(db->tenant-entity ctx %) records)))
     ctx))

  (create-tenant [_this tenant-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-tenant
     {:tenant-id (:id tenant-entity) :slug (:slug tenant-entity)}
     (fn [_]
       (let [db-record (tenant-entity->db ctx tenant-entity)
             query {:insert-into :tenants
                    :values [db-record]}
             _ (db/execute-update! ctx query)]
         tenant-entity))
     ctx))

  (update-tenant [_this tenant-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-tenant
     {:tenant-id (:id tenant-entity)}
     (fn [_]
       (let [db-record (tenant-entity->db ctx tenant-entity)
             updates (select-keys db-record [:name :status :settings :updated_at :deleted_at])
             query {:update :tenants
                    :set updates
                    :where [:= :id (:id db-record)]}
             _ (db/execute-update! ctx query)]
         tenant-entity))
     ctx))

  (delete-tenant [_this tenant-id]
    (persistence-interceptors/execute-persistence-operation
     :delete-tenant
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [tenant-id (:tenant-id params)
             query {:delete-from :tenants
                    :where [:= :id (type-conversion/uuid->string tenant-id)]}
             _ (db/execute-update! ctx query)]
         nil))
     ctx))

  (tenant-slug-exists? [_this slug]
    (persistence-interceptors/execute-persistence-operation
     :tenant-slug-exists?
     {:slug slug}
     (fn [{:keys [params]}]
       (let [slug (:slug params)
             query {:select [1]
                    :from [:tenants]
                    :where [:= :slug slug]}
             result (db/execute-one! ctx query)]
         (boolean result)))
     ctx))

  (create-tenant-schema [_this schema-name]
    (persistence-interceptors/execute-persistence-operation
     :create-tenant-schema
     {:schema-name schema-name}
     (fn [{:keys [params]}]
       (let [schema-name (:schema-name params)]
         (db/execute-ddl! ctx (str "CREATE SCHEMA IF NOT EXISTS " schema-name))
         nil))
     ctx))

  (drop-tenant-schema [_this schema-name]
    (persistence-interceptors/execute-persistence-operation
     :drop-tenant-schema
     {:schema-name schema-name}
     (fn [{:keys [params]}]
       (let [schema-name (:schema-name params)]
         (db/execute-ddl! ctx (str "DROP SCHEMA IF EXISTS " schema-name " CASCADE"))
         nil))
     ctx)))

(defn create-tenant-repository
  [ctx logger error-reporter]
  (->TenantRepository ctx logger error-reporter))
