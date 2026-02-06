(ns boundary.tenant.ports
  (:import (java.util UUID)))

(defprotocol ITenantRepository
  (find-tenant-by-id [this tenant-id])

  (find-tenant-by-slug [this slug])

  (find-all-tenants [this options])

  (create-tenant [this tenant-entity])

  (update-tenant [this tenant-entity])

  (delete-tenant [this tenant-id])

  (tenant-slug-exists? [this slug])

  (create-tenant-schema [this schema-name])

  (drop-tenant-schema [this schema-name]))

(defprotocol ITenantService
  (get-tenant [this tenant-id])

  (get-tenant-by-slug [this slug])

  (list-tenants [this options])

  (create-new-tenant [this tenant-input])

  (update-existing-tenant [this tenant-id update-data])

  (delete-existing-tenant [this tenant-id])

  (suspend-tenant [this tenant-id])

  (activate-tenant [this tenant-id]))
