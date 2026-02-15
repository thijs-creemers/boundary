# Tenant Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Multi-tenancy with schema-per-tenant isolation on PostgreSQL. Provides tenant CRUD, schema provisioning, tenant resolution, and cross-module integration (jobs, cache).

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.tenant.core.tenant` | Pure functions: slug validation, schema name generation, prepare-tenant, decisions |
| `boundary.tenant.ports` | Protocols: ITenantRepository, ITenantService, ITenantSchemaProvider |
| `boundary.tenant.schema` | Malli schemas: Tenant, TenantInput, TenantUpdate, TenantSettings |
| `boundary.tenant.shell.service` | Service layer with observability interceptors |
| `boundary.tenant.shell.persistence` | Database CRUD with case conversion |
| `boundary.tenant.shell.provisioning` | PostgreSQL schema provisioning/deprovisioning |
| `boundary.tenant.shell.http` | REST API handlers (CRUD + provision/suspend/activate) |
| `boundary.tenant.shell.module-wiring` | Integrant lifecycle (ig/init-key, ig/halt-key!) |

## Slug and Schema Naming

```clojure
;; Slug rules: lowercase alphanumeric + hyphens, 2-100 chars
;; Regex: ^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$
(tenant/valid-slug? "acme-corp")     ;=> true
(tenant/valid-slug? "ACME")          ;=> false (uppercase)
(tenant/valid-slug? "acme_corp")     ;=> false (underscore)

;; Slug → PostgreSQL schema name (hyphens become underscores)
(tenant/slug->schema-name "acme-corp")  ;=> "tenant_acme_corp"
```

## Tenant Lifecycle States

```
:active       → Created, not yet provisioned
:provisioned  → Schema created and ready
:suspended    → Access disabled (data preserved)
:deleted      → Soft delete (schema can be deprovisioned)
```

## Schema Provisioning

```clojure
;; Provision: creates PostgreSQL schema and copies table structure
(provisioning/provision-tenant! db-ctx tenant)
;=> {:success? true :schema-name "tenant_acme_corp" :table-count 5}

;; Execute queries in tenant schema context
(provisioning/with-tenant-schema db-ctx "tenant_acme_corp"
  (fn [tx]
    ;; SET search_path TO tenant_acme_corp, public
    (jdbc/execute! tx ["SELECT * FROM users"])))
```

## HTTP API Routes

```
GET    /tenants              # List tenants
POST   /tenants              # Create tenant
GET    /tenants/:id          # Get tenant
PUT    /tenants/:id          # Update tenant
DELETE /tenants/:id          # Soft delete
POST   /tenants/:id/suspend  # Suspend tenant
POST   /tenants/:id/activate # Activate tenant
POST   /tenants/:id/provision # Provision schema
```

## Gotchas

1. **PostgreSQL only** for production - provisioning throws on other databases
2. **H2 (tests)** skips provisioning with warning - can't test schema isolation in H2
3. **Soft deletes** - `:deleted-at` timestamp set, schema NOT auto-dropped. Call `deprovision-tenant!` separately
4. **Settings are JSONB** - nested map stored as JSON in PostgreSQL, parsed via Cheshire
5. **Case conversion** at persistence boundary: kebab → snake (entity→db), snake → kebab (db→entity)
6. **UUID and Instant** stored as strings in DB, converted via `type-conversion` utilities

## Cross-Module Integration

Other modules use tenant context:
- **Jobs**: `boundary.jobs.shell.tenant-context/enqueue-tenant-job!` - jobs execute in tenant schema
- **Cache**: `boundary.cache.shell.tenant-cache/create-tenant-cache` - keys prefixed with tenant ID

## Testing

```bash
clojure -M:test:db/h2 :tenant
clojure -M:test:db/h2 --focus boundary.tenant.core.tenant-test       # Unit
clojure -M:test:db/h2 --focus boundary.tenant.shell.service-test     # Service
```
