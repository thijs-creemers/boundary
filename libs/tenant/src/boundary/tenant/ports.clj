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

(defprotocol ITenantSchemaProvider
  "Protocol for executing code within a tenant's database schema context.
   
   This protocol abstracts tenant schema switching operations, allowing
   different implementations for different database types (PostgreSQL vs others)."
  
  (with-tenant-schema [this db-ctx schema-name f]
    "Execute function f within the specified tenant schema context.
     
     For PostgreSQL: Sets search_path to tenant schema for the duration of f
     For other databases: May use row-level filtering or other mechanisms
     
     Args:
       db-ctx: Database context map with :datasource
       schema-name: Tenant schema name (e.g., 'tenant_acme_corp')
       f: Function to execute, receives transaction context as argument
       
     Returns:
       Result of executing f
       
     Throws:
       ex-info with :type :unsupported-database if database doesn't support schemas
       ex-info with :type :schema-not-found if schema doesn't exist"))
