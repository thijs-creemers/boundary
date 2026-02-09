(ns boundary.tenant.core.tenant
  (:require
   [clojure.string :as str]))

(defn valid-slug?
  [slug]
  (boolean
   (and
    (string? slug)
    (re-matches #"^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$" slug))))

(defn slug->schema-name
  [slug]
  (str "tenant_" (str/replace slug "-" "_")))

(defn prepare-tenant
  [{:keys [slug name settings]} tenant-id now]
  (let [schema-name (slug->schema-name slug)]
    {:id tenant-id
     :slug slug
     :name name
     :schema-name schema-name
     :status :active
     :settings settings
     :created-at now
     :updated-at now
     :deleted-at nil}))

(defn create-tenant-decision
  [slug existing-slugs]
  (cond
    (not (valid-slug? slug))
    {:valid? false
     :error "Invalid tenant slug (must be lowercase alphanumeric with hyphens, 2-100 chars)"}

    (contains? existing-slugs slug)
    {:valid? false
     :error "Tenant slug already exists"}

    :else
    {:valid? true
     :schema-name (slug->schema-name slug)}))

(defn update-tenant-decision
  [existing-tenant update-data]
  (cond
    (nil? existing-tenant)
    {:valid? false
     :error "Tenant not found"}

    (and (:status update-data)
         (not (#{:active :suspended :deleted} (:status update-data))))
    {:valid? false
     :error "Invalid status"}

    :else
    {:valid? true
     :changes update-data}))

(defn prepare-tenant-update
  [existing-tenant update-data now]
  (-> existing-tenant
      (merge (select-keys update-data [:name :status :settings]))
      (assoc :updated-at now)))

(defn tenant-deleted?
  [tenant]
  (or (= :deleted (:status tenant))
      (some? (:deleted-at tenant))))

(defn tenant-active?
  [tenant]
  (and (= :active (:status tenant))
       (nil? (:deleted-at tenant))))

(defn tenant-suspended?
  [tenant]
  (= :suspended (:status tenant)))

(defn can-delete-tenant?
  [tenant]
  (not (tenant-deleted? tenant)))

(defn prepare-tenant-deletion
  [tenant now]
  (assoc tenant
         :status :deleted
         :deleted-at now
         :updated-at now))
