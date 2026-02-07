# ADR-004: Multi-Tenancy Architecture

**Status:** Proposed  
**Date:** 2026-02-05  
**Deciders:** Boundary Core Team  
**Context:** Multi-Tenancy Design (Phase 6)

---

## Context and Problem Statement

The Boundary Framework currently supports single-tenant deployments where each application instance serves a single organization. As the framework targets SaaS applications and enterprise platforms, multi-tenancy becomes essential for:

1. **Cost Efficiency**: Serve multiple customers from single infrastructure
2. **Operational Simplicity**: Manage one deployment instead of many
3. **Resource Optimization**: Share database connections, caching, and compute resources
4. **Faster Time-to-Market**: Onboard new tenants without provisioning infrastructure
5. **Competitive Positioning**: Enable SaaS business models

**Current Limitations**:
- No tenant isolation mechanisms
- Single database schema shared by all data
- No tenant-scoped caching
- No tenant-aware background jobs
- No tenant-specific configurations

**Goal**: Design a multi-tenancy architecture that provides strong isolation, scales to thousands of tenants, and integrates seamlessly with Boundary's existing modules (user, admin, jobs, cache, realtime).

---

## Decision Drivers

### Technical Requirements

1. **Data Isolation**: Prevent accidental data leaks between tenants
2. **Performance**: No significant overhead from multi-tenancy (< 10ms per request)
3. **Scalability**: Support 100-10,000 tenants on single database
4. **Flexibility**: Allow per-tenant customization (schema extensions, configurations)
5. **Migration Safety**: Run migrations across all tenants without downtime risk
6. **Query Simplicity**: Developers should not add `WHERE tenant_id = ?` to every query

### Operational Requirements

1. **Easy Tenant Provisioning**: Create new tenant in < 30 seconds
2. **Tenant Lifecycle**: Support suspend, resume, delete operations
3. **Backup/Restore**: Per-tenant and full-database backup strategies
4. **Monitoring**: Track performance and resource usage per tenant
5. **Compliance**: Support data residency and regulatory requirements (GDPR, HIPAA)

### Developer Experience

1. **Transparent to Business Logic**: Core logic should be tenant-agnostic
2. **Clear Extension Points**: Know where to add tenant-specific behavior
3. **Easy Testing**: Test with multiple tenants locally
4. **Debugging**: Trace requests through tenant context

---

## Considered Options

### Option 1: Shared Database, Shared Schema (Row-Level Isolation)

**Architecture:**
```
┌─────────────────────────────────────┐
│         Single Database             │
│  ┌───────────────────────────┐     │
│  │   Shared Schema (public)  │     │
│  │  users (tenant_id column) │     │
│  │  orders (tenant_id column)│     │
│  │  products (tenant_id)     │     │
│  └───────────────────────────┘     │
└─────────────────────────────────────┘
```

Every table has `tenant_id` column. Every query must include `WHERE tenant_id = ?`.

**Pros:**
- ✅ Simplest to implement
- ✅ Most cost-effective (single database)
- ✅ Easy cross-tenant analytics
- ✅ Single schema migration

**Cons:**
- ❌ **Critical**: High data leak risk (forget `WHERE tenant_id = ?` → breach)
- ❌ No database-level enforcement
- ❌ Query complexity (every query needs filtering)
- ❌ Performance degradation at scale (large shared tables)
- ❌ Difficult to prove compliance (all data in same tables)
- ❌ No per-tenant schema customization

**Row-Level Security (RLS) Variant:**
PostgreSQL RLS can enforce tenant filtering:
```sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

RLS adds database enforcement but:
- ❌ Performance overhead (policy evaluation on every row)
- ❌ Query planner complexity
- ❌ Debugging difficulty (policies hide data)

**Verdict:** ❌ **Rejected** - Too risky for production SaaS. Data leak risk outweighs simplicity benefits.

---

### Option 2: Schema-per-Tenant ⭐ RECOMMENDED

**Architecture:**
```
┌──────────────────────────────────────────────────────┐
│              PostgreSQL Database                     │
│                                                      │
│  ┌──────────────────┐  ┌──────────────────┐         │
│  │ Schema: tenant_a │  │ Schema: tenant_b │  ...    │
│  │                  │  │                  │         │
│  │  users           │  │  users           │         │
│  │  orders          │  │  orders          │         │
│  │  products        │  │  products        │         │
│  └──────────────────┘  └──────────────────┘         │
│                                                      │
│  ┌──────────────────┐                               │
│  │ Schema: public   │  ← Shared tables              │
│  │  tenants         │     (tenant registry)         │
│  │  auth_users      │     (authentication)          │
│  └──────────────────┘                               │
└──────────────────────────────────────────────────────┘
```

Each tenant gets a separate PostgreSQL schema (namespace). Middleware sets `search_path` per request.

**Pros:**
- ✅ **Strong logical isolation**: Schema boundaries enforced by database
- ✅ **Clean queries**: No `tenant_id` filtering needed
- ✅ **Per-tenant customization**: Each schema can have custom tables/columns
- ✅ **Better performance**: Smaller tables per schema (faster queries)
- ✅ **Independent backups**: Can backup/restore individual tenants
- ✅ **Safer migrations**: Migrate tenants incrementally (canary rollout)
- ✅ **Compliance-friendly**: Easy to demonstrate tenant isolation
- ✅ **Clear ownership**: One schema = one tenant
- ✅ **Audit trail**: Database logs show schema-level access

**Cons:**
- ❌ Schema creation overhead (2-5 seconds for complex schemas)
- ❌ Migration complexity (must run per tenant)
- ❌ Schema count limits (PostgreSQL handles ~1000 schemas well, not 100k)
- ❌ Cross-tenant queries harder (must specify schema explicitly)
- ❌ Must set `search_path` per request (connection management)

**Verdict:** ✅ **SELECTED** - Best balance of isolation, scalability, and operational simplicity.

---

### Option 3: Database-per-Tenant

**Architecture:**
```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  DB: tenant_a   │  │  DB: tenant_b   │  │  DB: tenant_c   │
│  users          │  │  users          │  │  users          │
│  orders         │  │  orders         │  │  orders         │
└─────────────────┘  └─────────────────┘  └─────────────────┘

┌──────────────────────────────┐
│  Master DB: control_plane    │
│  tenants (registry)          │
│  auth_users                  │
└──────────────────────────────┘
```

**Pros:**
- ✅ Maximum isolation (physical separation)
- ✅ Easy tenant removal (drop database)
- ✅ Per-tenant resource allocation
- ✅ Compliance excellence

**Cons:**
- ❌ High operational complexity (managing 1000s of databases)
- ❌ Connection pool overhead (pools per database)
- ❌ Expensive (more database instances)
- ❌ Migration complexity (run on ALL databases)
- ❌ Cross-tenant queries nearly impossible

**Verdict:** ❌ **Rejected** - Too complex for target scale (100-10,000 tenants). Better suited for < 100 very large tenants.

---

## Decision Outcome

**Chosen Option:** Schema-per-Tenant (Option 2)

### Rationale

1. **Proven Pattern**: Rails Apartment gem and Django django-tenants have 10+ years production use
2. **Scales Well**: Handles 100-10,000 tenants efficiently (Boundary's target)
3. **Strong Isolation**: Schema boundaries prevent accidental data leaks
4. **Clojure-Friendly**: Works well with JDBC/next.jdbc connection management
5. **Cost-Effective**: Single database with shared connection pool
6. **Compliance**: Easier to prove tenant isolation than shared schema

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ HTTP Handler │→ │  Middleware  │→ │   Service    │  │
│  │              │  │ (set schema) │  │   Layer      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│              PostgreSQL Database                         │
│                                                          │
│  ┌──────────────┐  Public Schema (Shared)               │
│  │ tenants      │  - Tenant registry                    │
│  │ auth_users   │  - Authentication                     │
│  │ admin_config │  - Global configuration               │
│  └──────────────┘                                       │
│                                                          │
│  ┌──────────────┐  Tenant Schemas (Isolated)            │
│  │ tenant_abc   │  - users (tenant-specific data)       │
│  │ tenant_xyz   │  - orders, products, etc.             │
│  │ ...          │                                       │
│  └──────────────┘                                       │
└─────────────────────────────────────────────────────────┘
```

---

## Detailed Design

### 1. Tenant Identification Strategy

**Primary: Subdomain-based** (Web UI)
```
https://acme-corp.myapp.com → tenant_acme_corp schema
https://widgets-inc.myapp.com → tenant_widgets_inc schema
```

**Fallback: JWT Claim** (API access)
```json
{
  "user_id": "user-123",
  "tenant_id": "tenant-abc",
  "exp": "<unix_timestamp_expiry_generated_at_runtime>"
}
```

**Middleware Flow:**
```clojure
(defn tenant-middleware
  "Extract tenant from request and set database schema."
  [handler tenant-registry]
  (fn [request]
    (let [tenant-id (or
                      (extract-subdomain request)    ; From subdomain
                      (get-jwt-tenant request)       ; From JWT
                      (get-header-tenant request))   ; From header
          tenant (find-tenant tenant-registry tenant-id)]
      
      (if tenant
        (with-tenant-schema (:schema-name tenant)
          (handler (assoc request :tenant tenant)))
        {:status 404
         :body {:error "Tenant not found"}}))))
```

### 2. Database Design

#### Public Schema Tables

**tenants** - Tenant registry
```sql
CREATE TABLE public.tenants (
  id UUID PRIMARY KEY,
  slug VARCHAR(100) UNIQUE NOT NULL,         -- acme-corp
  schema_name VARCHAR(100) UNIQUE NOT NULL,  -- tenant_acme_corp
  name VARCHAR(255) NOT NULL,                -- Acme Corporation
  status VARCHAR(20) NOT NULL,               -- active, suspended, deleted
  plan VARCHAR(50),                          -- free, pro, enterprise
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_tenants_slug ON public.tenants(slug);
CREATE INDEX idx_tenants_status ON public.tenants(status);
```

**auth_users** - Authentication (shared across tenants)
```sql
CREATE TABLE public.auth_users (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES public.tenants(id),
  email VARCHAR(255) NOT NULL,
  password_hash TEXT NOT NULL,
  mfa_secret TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  
  UNIQUE(tenant_id, email)  -- Email unique per tenant
);

CREATE INDEX idx_auth_users_tenant ON public.auth_users(tenant_id);
CREATE INDEX idx_auth_users_email ON public.auth_users(email);
```

#### Tenant Schema Tables

Each tenant schema contains:
```sql
-- All business logic tables (no tenant_id column needed!)
CREATE TABLE users (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  role VARCHAR(50) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id),
  total DECIMAL(10, 2),
  created_at TIMESTAMPTZ NOT NULL
);

-- etc.
```

### 3. Schema Switching Implementation

**next.jdbc Middleware:**
```clojure
(ns boundary.platform.shell.db.tenant
  (:require [next.jdbc :as jdbc]))

(defn with-tenant-schema
  "Execute function f with database search_path set to tenant schema."
  [datasource tenant-schema-name f]
  (jdbc/with-transaction [tx datasource]
    ;; Set search_path for this transaction
    (jdbc/execute! tx [(str "SET search_path TO " 
                           tenant-schema-name ", public")])
    
    ;; Execute function in tenant context
    (f tx)))

;; Usage in service layer
(defn list-users-service [tenant-context db-ctx limit offset]
  (with-tenant-schema (:datasource db-ctx)
                      (:schema-name tenant-context)
    (fn [tx]
      ;; Query automatically uses tenant schema
      (jdbc/execute! tx ["SELECT * FROM users LIMIT ? OFFSET ?" 
                        limit offset]))))
```

**Integrant Component:**
```clojure
(defmethod ig/init-key :boundary/tenant-context
  [_ {:keys [db-context]}]
  {:datasource (:datasource db-context)
   :tenant-registry (atom {})})  ; Cached tenant lookup

(defmethod ig/halt-key! :boundary/tenant-context
  [_ context]
  ;; Cleanup if needed
  nil)
```

### 4. Tenant Provisioning

**Create Tenant Flow:**
```clojure
(ns boundary.platform.core.tenant
  (:require [clojure.string :as str]))

(defn create-tenant-decision
  "Pure function: decide if tenant can be created."
  [tenant-slug existing-tenants]
  (cond
    (some #(= (:slug %) tenant-slug) existing-tenants)
    {:valid? false
     :error "Tenant slug already exists"}
    
    (not (valid-slug? tenant-slug))
    {:valid? false
     :error "Invalid tenant slug (must be lowercase alphanumeric)"}
    
    :else
    {:valid? true
     :schema-name (str "tenant_" (str/replace tenant-slug "-" "_"))}))

(defn valid-slug? [slug]
  (re-matches #"^[a-z0-9][a-z0-9\-]{1,98}[a-z0-9]$" slug))
```

**Service Layer:**
```clojure
(ns boundary.platform.shell.service.tenant
  (:require [boundary.platform.core.tenant :as tenant-core]
            [next.jdbc :as jdbc]))

(defn create-tenant!
  "Create new tenant with schema."
  [this tenant-input]
  (let [decision (tenant-core/create-tenant-decision
                   (:slug tenant-input)
                   (list-all-tenants this))]
    
    (if-not (:valid? decision)
      {:success? false
       :error (:error decision)}
      
      (jdbc/with-transaction [tx (:datasource this)]
        ;; 1. Insert tenant record
        (let [tenant-id (java.util.UUID/randomUUID)
              schema-name (:schema-name decision)]
          
          (jdbc/execute! tx
            ["INSERT INTO public.tenants 
              (id, slug, schema_name, name, status, created_at, updated_at)
              VALUES (?, ?, ?, ?, 'active', NOW(), NOW())"
             tenant-id
             (:slug tenant-input)
             schema-name
             (:name tenant-input)])
          
          ;; 2. Create schema
          ;; SECURITY: schema-name is validated via create-tenant-decision before use
          ;; to prevent SQL injection (see valid-slug? function above)
          (jdbc/execute! tx [(str "CREATE SCHEMA " schema-name)])
          
          ;; 3. Run migrations for tenant schema
          (run-tenant-migrations! tx schema-name)
          
          {:success? true
           :tenant-id tenant-id
           :schema-name schema-name})))))
```

### 5. Migration Strategy

**Per-Tenant Migration Runner:**
```clojure
(ns boundary.platform.shell.db.migrations
  (:require [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(defn migrate-all-tenants!
  "Run migrations on all tenant schemas."
  [db-ctx migration-config]
  (let [tenants (get-all-active-tenants db-ctx)]
    
    (doseq [tenant tenants]
      (try
        (println "Migrating tenant:" (:slug tenant))
        
        ;; Run migratus with tenant schema
        ;; SECURITY: Validate schema name before SQL concatenation to prevent injection
        (when-not (re-matches #"^tenant_[a-z0-9_]{1,100}$" (:schema-name tenant))
          (throw (ex-info "Invalid schema name" {:schema-name (:schema-name tenant)})))
        
        (migratus/migrate
          (assoc migration-config
                 :modify-sql-fn
                 (fn [sql]
                   ;; schema-name already validated above
                   (str "SET search_path TO " (:schema-name tenant) ", public;\n"
                        sql))))
        
        (println "✓ Migrated:" (:slug tenant))
        
        (catch Exception e
          (println "✗ Failed:" (:slug tenant) (.getMessage e))
          ;; Continue with other tenants
          nil)))))

;; Rollback support
(defn rollback-tenant-migration!
  [db-ctx tenant-id]
  (let [tenant (get-tenant db-ctx tenant-id)]
    ;; SECURITY: Validate schema name before SQL concatenation to prevent injection
    (when-not (re-matches #"^tenant_[a-z0-9_]{1,100}$" (:schema-name tenant))
      (throw (ex-info "Invalid schema name" {:schema-name (:schema-name tenant)})))
    
    (migratus/rollback
      {:store :database
       :db (:datasource db-ctx)
       :modify-sql-fn
       (fn [sql]
         ;; schema-name already validated above
         (str "SET search_path TO " (:schema-name tenant) ", public;\n"
              sql))})))
```

**Migration Strategies:**
1. **Sequential**: Migrate tenants one-by-one (safer, slower)
2. **Parallel**: Migrate in batches (faster, riskier)
3. **Canary**: Test on 1-2 tenants first, then roll out
4. **Blue-Green**: Create new schema version, switch tenants over

---

## Module Integration

### User Module Integration

**Authentication**: Shared table in `public` schema
```clojure
;; Login: Query public.auth_users (no tenant context needed)
(defn authenticate-user [email password]
  (jdbc/execute-one! db
    ["SELECT * FROM public.auth_users WHERE email = ?" email]))

;; After login: Load user profile from tenant schema
(defn get-user-profile [tenant-context user-id]
  (with-tenant-schema (:datasource tenant-context)
                      (:schema-name tenant-context)
    (fn [tx]
      (jdbc/execute-one! tx
        ["SELECT * FROM users WHERE id = ?" user-id]))))
```

**Authorization**: Tenant-scoped roles
```clojure
;; Roles stored in tenant schema
CREATE TABLE roles (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id),
  role_name VARCHAR(50) NOT NULL
);
```

### Jobs Module Integration

**Tenant-Scoped Job Queues:**
```clojure
(ns boundary.jobs.shell.multi-tenant
  (:require [boundary.jobs.ports :as jobs]))

(defn enqueue-tenant-job!
  "Enqueue job with tenant context."
  [job-queue tenant-id job-type args]
  (let [job-input {:job-type job-type
                   :args args
                   :metadata {:tenant-id tenant-id}  ; Store tenant
                   :priority :normal}
        job (jobs/create-job job-input)]
    
    (jobs/enqueue-job! job-queue :default job)))

;; Process job in tenant context
(defn process-tenant-job!
  [job tenant-registry db-ctx]
  (let [tenant-id (get-in job [:metadata :tenant-id])
        tenant (find-tenant tenant-registry tenant-id)]
    
    (with-tenant-schema (:datasource db-ctx)
                        (:schema-name tenant)
      (fn [tx]
        ;; Job handler executes in tenant schema
        (handle-job job tx)))))
```

### Cache Module Integration

**Tenant-Scoped Cache Keys:**
```clojure
(ns boundary.cache.shell.multi-tenant
  (:require [boundary.cache.ports :as cache]))

(defn tenant-cache-key
  "Prefix cache key with tenant ID."
  [tenant-id key]
  (str "tenant:" tenant-id ":" (name key)))

;; Usage
(cache/set-value! cache
                  (tenant-cache-key tenant-id :user-123)
                  user-data
                  3600)

;; Alternative: Use namespaced cache
(def tenant-cache (cache/with-namespace cache (str "tenant:" tenant-id)))
(cache/set-value! tenant-cache :user-123 user-data 3600)
```

### Admin Module Integration

**Tenant Management UI:**
```clojure
;; Admin panel endpoints
GET  /admin/tenants              ; List all tenants
POST /admin/tenants              ; Create tenant
GET  /admin/tenants/:id          ; View tenant details
PUT  /admin/tenants/:id          ; Update tenant
POST /admin/tenants/:id/suspend  ; Suspend tenant
POST /admin/tenants/:id/activate ; Activate tenant
DELETE /admin/tenants/:id        ; Delete tenant (soft delete)
```

### Realtime Module Integration

**Tenant-Scoped WebSocket Channels:**
```clojure
(ns boundary.realtime.shell.multi-tenant
  (:require [boundary.realtime.ports :as realtime]))

(defn tenant-channel-name
  "Scope channel to tenant."
  [tenant-id channel]
  (str "tenant:" tenant-id ":" channel))

;; Subscribe to tenant-scoped channel
(realtime/subscribe! ws-connection
                     (tenant-channel-name tenant-id "notifications"))

;; Broadcast to all users in tenant
(realtime/broadcast! realtime-service
                     (tenant-channel-name tenant-id "notifications")
                     {:type "user-created"
                      :data user-data})
```

---

## Security Considerations

### Preventing Data Leaks

**1. Middleware Enforcement:**
```clojure
(defn ensure-tenant-context
  "Ensure tenant context is set before handler execution."
  [handler]
  (fn [request]
    (if-not (:tenant request)
      {:status 500
       :body {:error "SECURITY: Tenant context missing"}}
      (handler request))))
```

**2. Schema Validation:**
```clojure
(defn validate-schema-set
  "Verify search_path is set before queries."
  [connection]
  (let [result (jdbc/execute-one! connection ["SHOW search_path"])
        path (:search_path result)]
    (when (= "public" path)
      (throw (ex-info "SECURITY: Tenant schema not set"
                      {:type :security-error
                       :path path})))))
```

**3. Audit Logging:**
```clojure
(defn log-tenant-access
  "Log all tenant schema access."
  [tenant-id user-id action]
  (log/info {:event "tenant-access"
             :tenant-id tenant-id
             :user-id user-id
             :action action
             :timestamp (java.time.Instant/now)}))
```

### Compliance Support

**GDPR - Right to be Forgotten:**
```clojure
(defn delete-tenant-data!
  "Delete all tenant data (GDPR compliance)."
  [db-ctx tenant-id]
  (let [tenant (get-tenant db-ctx tenant-id)]
    (jdbc/with-transaction [tx (:datasource db-ctx)]
      ;; 1. Drop tenant schema (deletes all data)
      (jdbc/execute! tx [(str "DROP SCHEMA " (:schema-name tenant) " CASCADE")])
      
      ;; 2. Soft delete tenant record
      (jdbc/execute! tx
        ["UPDATE public.tenants SET status = 'deleted', deleted_at = NOW()
          WHERE id = ?" tenant-id])
      
      ;; 3. Delete auth records
      (jdbc/execute! tx
        ["DELETE FROM public.auth_users WHERE tenant_id = ?" tenant-id]))))
```

**Data Export (GDPR):**
```clojure
(defn export-tenant-data
  "Export all tenant data for GDPR compliance."
  [db-ctx tenant-id output-path]
  (let [tenant (get-tenant db-ctx tenant-id)]
    ;; Use pg_dump to export tenant schema
    (sh/sh "pg_dump"
           "--schema" (:schema-name tenant)
           "--format=custom"
           "--file" output-path
           (db-connection-string db-ctx))))
```

---

## Performance Considerations

### Connection Pooling

**Shared Pool Across Tenants:**
```clojure
;; Single connection pool serves all tenants
(def datasource
  (jdbc/get-datasource
    {:dbtype "postgresql"
     :dbname "boundary_production"
     :host "localhost"
     :port 5432
     :maximum-pool-size 50      ; Shared across all tenants
     :minimum-idle 10}))

;; search_path is set per transaction, not per connection
```

### Query Performance

**Schema Statistics:**
```sql
-- Ensure PostgreSQL has statistics per schema
ANALYZE tenant_abc.users;
ANALYZE tenant_abc.orders;

-- Autovacuum per schema
ALTER TABLE tenant_abc.users SET (autovacuum_enabled = true);
```

**Index Strategy:**
```sql
-- Each tenant schema has its own indexes
CREATE INDEX idx_users_email ON tenant_abc.users(email);

-- Smaller tables = faster index scans
-- tenant_abc.users: 1,000 rows  → 5ms query
-- vs shared table: 1,000,000 rows → 50ms query
```

### Caching Strategy

**Tenant Lookup Cache:**
```clojure
(defn cached-find-tenant
  "Cache tenant lookups (1 hour TTL)."
  [cache tenant-slug]
  (if-let [cached (cache/get-value cache (str "tenant:" tenant-slug))]
    cached
    (let [tenant (db-find-tenant tenant-slug)]
      (cache/set-value! cache (str "tenant:" tenant-slug) tenant 3600)
      tenant)))
```

---

## Operational Considerations

### Monitoring

**Per-Tenant Metrics:**
```clojure
(defn track-tenant-request
  "Track metrics per tenant."
  [tenant-id duration-ms status]
  (metrics/increment! "requests.total" {:tenant tenant-id})
  (metrics/histogram! "requests.duration" duration-ms {:tenant tenant-id})
  (metrics/increment! (str "requests.status." status) {:tenant tenant-id}))
```

**Schema Size Monitoring:**
```sql
-- Monitor schema disk usage
SELECT
  schemaname,
  pg_size_pretty(sum(pg_total_relation_size(schemaname||'.'||tablename))::bigint)
FROM pg_tables
WHERE schemaname LIKE 'tenant_%'
GROUP BY schemaname
ORDER BY sum(pg_total_relation_size(schemaname||'.'||tablename)) DESC;
```

### Backup Strategy

**Full Database Backup:**
```bash
# Backup all tenants (includes public schema + all tenant schemas)
pg_dump -Fc boundary_production > backup_all_tenants.dump
```

**Per-Tenant Backup:**
```bash
# Backup single tenant schema
pg_dump -Fc --schema=tenant_acme_corp boundary_production > tenant_acme_corp.dump
```

**Restore Tenant:**
```bash
# Restore tenant to new database
pg_restore -d boundary_production --schema=tenant_acme_corp tenant_acme_corp.dump
```

### Tenant Lifecycle

**Suspend Tenant:**
```clojure
(defn suspend-tenant!
  "Suspend tenant (block access, keep data)."
  [db-ctx tenant-id]
  (jdbc/execute! (:datasource db-ctx)
    ["UPDATE public.tenants SET status = 'suspended', updated_at = NOW()
      WHERE id = ?" tenant-id]))
```

**Activate Tenant:**
```clojure
(defn activate-tenant!
  "Reactivate suspended tenant."
  [db-ctx tenant-id]
  (jdbc/execute! (:datasource db-ctx)
    ["UPDATE public.tenants SET status = 'active', updated_at = NOW()
      WHERE id = ?" tenant-id]))
```

---

## Implementation Phases

### Phase 1: Foundation (Weeks 1-2) - ✅ COMPLETE

**Deliverables:**
- ✅ Tenant registry in `public` schema
- ✅ Schema creation/deletion functions
- ✅ Basic middleware for schema switching
- ✅ Connection pool management
- ✅ Unit tests for tenant core logic

**Success Criteria:**
- ✅ Can create/delete tenant schemas programmatically
- ✅ Middleware correctly sets `search_path`
- ✅ All tests pass (16/16 passing)

**Implementation Details:**
- Tenant resolution middleware with subdomain/JWT/header support
- PostgreSQL schema switching via `SET search_path`
- In-memory caching with 1-hour TTL
- Located: `libs/platform/src/boundary/platform/shell/interfaces/http/tenant_middleware.clj`
- Tests: `libs/platform/test/boundary/platform/shell/interfaces/http/tenant_middleware_test.clj`

### Phase 2: Core Integration (Weeks 3-4)

**Deliverables:**
- Integrate with user module (authentication remains in public)
- Update platform module for tenant context
- Modify database adapters for schema awareness
- Add tenant to observability (logging with tenant_id)

**Success Criteria:**
- Can authenticate user and load profile from tenant schema
- All service operations execute in correct tenant context
- Logs include tenant information

### Phase 3: Migration Support (Weeks 5-6)

**Deliverables:**
- Per-tenant migration runner
- Rollback support
- Migration status tracking per tenant
- Parallel migration execution (optional)

**Success Criteria:**
- Can run migration on all tenants
- Can rollback failed migrations
- Migration status visible per tenant

### Phase 4: Module Integration (Weeks 7-8)

**Deliverables:**
- Jobs module: Per-tenant job queues
- Cache module: Tenant-scoped cache keys
- Realtime module: Tenant-specific channels (if Phase 5 merged)

**Success Criteria:**
- Background jobs execute in correct tenant context
- Cache operations scoped to tenant
- WebSocket messages delivered to correct tenant

### Phase 5: Operations & Tooling (Weeks 9-10)

**Deliverables:**
- Tenant provisioning API
- Tenant backup/restore tools
- Monitoring dashboards per tenant
- Tenant lifecycle management (suspend, delete)

**Success Criteria:**
- Can provision tenant via API in < 30s
- Can backup/restore individual tenants
- Metrics visible per tenant

### Phase 6: Advanced Features (Weeks 11-12)

**Deliverables:**
- Self-service tenant creation
- Tenant customization options
- Performance optimization
- Documentation and examples

**Success Criteria:**
- Users can create tenants without admin intervention
- Performance overhead < 10ms per request
- Complete documentation available

**Total Effort:** 12 weeks (3 months)

---

## Migration Path for Existing Applications

### From Single-Tenant to Multi-Tenant

**Step 1: Create Shared Tables**
```sql
-- Move authentication to public schema
CREATE TABLE public.tenants (...);
CREATE TABLE public.auth_users (...);
```

**Step 2: Create First Tenant Schema**
```sql
CREATE SCHEMA tenant_default;
-- Move existing tables to tenant_default
```

**Step 3: Update Application Code**
```clojure
;; Add tenant middleware
(def app
  (-> routes
      (wrap-tenant-context tenant-registry)
      (wrap-defaults site-defaults)))
```

**Step 4: Test with Single Tenant**
```clojure
;; Verify all operations work in tenant_default schema
```

**Step 5: Enable Multi-Tenant**
```clojure
;; Add second tenant for testing
(create-tenant! "acme-corp" "Acme Corporation")
```

---

## Alternatives Considered and Rejected

### Separate Connection Pools per Tenant

**Idea:** Create dedicated connection pool for each tenant.

**Rejected because:**
- Doesn't scale (1000 tenants = 1000 pools × 10 connections = 10,000 connections)
- PostgreSQL max connections typically 100-300
- Shared pool with `search_path` switching is sufficient

### Thread-Local Tenant Context

**Idea:** Store tenant context in thread-local storage.

**Rejected because:**
- Violates Clojure functional principles
- Difficult to debug (implicit state)
- Doesn't work with async operations
- Explicit tenant context is clearer

### Tenant-Specific Databases with Connection Routing

**Idea:** Route connections to different databases based on tenant.

**Rejected because:**
- Too complex for target scale
- Same cons as database-per-tenant option
- Better suited for very large tenants (not target use case)

---

## Consequences

### Positive

1. **Strong Isolation**: Schema boundaries prevent accidental data leaks
2. **Clean Code**: No `tenant_id` filtering in business logic
3. **Scalability**: Supports 100-10,000 tenants efficiently
4. **Compliance**: Easy to demonstrate tenant isolation
5. **Flexibility**: Per-tenant schema customization possible
6. **Performance**: Smaller tables per schema = faster queries
7. **Proven Pattern**: 10+ years production use in Rails/Django ecosystems

### Negative

1. **Migration Complexity**: Must run migrations on all tenant schemas
2. **Schema Limits**: PostgreSQL handles ~1000 schemas (not 100k+)
3. **Cross-Tenant Queries**: Require explicit schema qualification
4. **Initial Setup**: 6-12 weeks implementation effort
5. **Testing Complexity**: Must test with multiple tenants
6. **Connection Management**: Must ensure `search_path` is always set

### Risks

1. **Forgotten search_path**: Query executes in wrong schema
   - **Mitigation**: Middleware validation, audit logging
2. **Migration failure**: One tenant fails, others succeed
   - **Mitigation**: Canary rollout, per-tenant rollback
3. **Schema count growth**: Hitting PostgreSQL limits
   - **Mitigation**: Monitor schema count, archive inactive tenants

---

## References

### Research

- [Multi-Tenancy Patterns Research](../research/multi-tenancy-patterns.md) - Comprehensive analysis of all patterns

### External Resources

- **Rails Apartment Gem**: https://github.com/influitive/apartment
- **Django django-tenants**: https://github.com/django-tenants/django-tenants
- **PostgreSQL Schemas**: https://www.postgresql.org/docs/current/ddl-schemas.html
- **Neon Multi-Tenancy**: https://neon.tech/blog/multi-tenancy-and-database-per-user-design-in-postgres

### Related ADRs

- **ADR-001**: Library Split (module architecture)
- **ADR-002**: Boundary New Command (scaffolding)
- Future ADR: Tenant-Specific Configurations
- Future ADR: Cross-Tenant Analytics

---

## Open Questions

1. **Tenant Slug Validation**: Allow underscores or hyphens only?
   - **Recommendation**: Hyphens only (URL-friendly)

2. **Schema Naming Convention**: Prefix length?
   - **Recommendation**: `tenant_` prefix (7 chars), slug max 93 chars

3. **Cross-Tenant Queries**: Support via unified view or explicit schema qualification?
   - **Decision Deferred**: Start with explicit qualification, add views if needed

4. **Tenant Deletion**: Hard delete or soft delete?
   - **Recommendation**: Soft delete (set status='deleted'), hard delete after 90 days

5. **Schema Creation Time**: Acceptable delay for new tenant?
   - **Target**: < 30 seconds, async provisioning acceptable

---

## Approval

**Status:** Proposed  
**Implementation Owner:** TBD  
**Expected Completion:** 12 weeks from approval  
**Review Date:** 2026-02-12

---

---

## Appendix A: Detailed Implementation Roadmap

This appendix provides a week-by-week breakdown of the multi-tenancy implementation, including specific deliverables, acceptance criteria, and testing strategies.

### Phase 1: Foundation (Weeks 1-2)

#### Week 1: Database Schema & Core Logic

**Tasks:**
1. Create migration for `public.tenants` table
2. Create migration for `public.auth_users` table
3. Implement `boundary.platform.core.tenant` namespace (pure functions)
4. Implement tenant validation logic
5. Write comprehensive unit tests

**Deliverables:**
```clojure
;; boundary.platform.core.tenant
- create-tenant-decision
- valid-slug?
- schema-name-from-slug
- can-delete-tenant?

;; boundary.platform.schema
- Tenant (Malli schema)
- TenantInput (validation schema)
```

**Acceptance Criteria:**
- [ ] All core functions are pure (no side effects)
- [ ] 100% unit test coverage
- [ ] Slug validation prevents SQL injection
- [ ] Schema names follow convention: `tenant_[slug]`

**Testing:**
```clojure
(deftest test-tenant-slug-validation
  (is (valid-slug? "acme-corp"))
  (is (not (valid-slug? "ACME-CORP")))      ; uppercase
  (is (not (valid-slug? "acme corp")))       ; spaces
  (is (not (valid-slug? "acme_corp")))       ; underscores
  (is (not (valid-slug? "-acme")))           ; starts with hyphen
  (is (not (valid-slug? "a"))))              ; too short
```

#### Week 2: Persistence & Schema Management

**Tasks:**
1. Implement schema creation/deletion functions
2. Create tenant repository (database adapter)
3. Implement tenant service layer
4. Write integration tests with H2
5. Test with PostgreSQL

**Deliverables:**
```clojure
;; boundary.platform.shell.db.schema
- create-schema!
- drop-schema!
- schema-exists?
- list-all-schemas

;; boundary.platform.shell.persistence.tenant
- create-tenant-record!
- find-tenant-by-slug
- find-tenant-by-id
- list-all-tenants

;; boundary.platform.shell.service.tenant
- create-tenant!
- delete-tenant!
- suspend-tenant!
- activate-tenant!
```

**Acceptance Criteria:**
- [ ] Can create tenant schema in PostgreSQL
- [ ] Can delete tenant schema (CASCADE)
- [ ] All operations logged
- [ ] Integration tests pass with real database

**Testing:**
```clojure
(deftest test-tenant-creation-integration
  (let [tenant-input {:slug "test-tenant"
                      :name "Test Tenant"}]
    (is (= {:success? true} (create-tenant! service tenant-input)))
    
    ;; Verify schema exists
    (is (schema-exists? db "tenant_test_tenant"))
    
    ;; Cleanup
    (delete-tenant! service tenant-id)))
```

---

### Phase 2: Core Integration (Weeks 3-4)

#### Week 3: Middleware & Request Context

**Tasks:**
1. Implement tenant middleware
2. Create tenant context extraction (subdomain, JWT, header)
3. Integrate with HTTP router
4. Add tenant to request logging
5. Write middleware tests

**Deliverables:**
```clojure
;; boundary.platform.shell.http.middleware.tenant
- wrap-tenant-context
- extract-subdomain
- extract-jwt-tenant
- extract-header-tenant
- ensure-tenant-context

;; Integrant components
:boundary/tenant-registry
:boundary/tenant-middleware
```

**Acceptance Criteria:**
- [ ] Middleware extracts tenant from subdomain
- [ ] Falls back to JWT claim if no subdomain
- [ ] Returns 404 if tenant not found
- [ ] Attaches tenant to request map
- [ ] All logs include tenant_id

**Testing:**
```clojure
(deftest test-subdomain-extraction
  (let [request {:server-name "acme-corp.myapp.com"}
        handler (wrap-tenant-context identity tenant-registry)]
    (is (= "acme-corp" (-> (handler request) :tenant :slug)))))
```

#### Week 4: Database Context & Schema Switching

**Tasks:**
1. Implement `with-tenant-schema` macro/function
2. Update all repository functions to accept tenant context
3. Modify user module for multi-tenancy
4. Add schema validation guards
5. Write integration tests across modules

**Deliverables:**
```clojure
;; boundary.platform.shell.db.tenant
- with-tenant-schema
- set-search-path!
- get-current-schema
- ensure-schema-set

;; Updated repositories
- All repository functions accept tenant-context parameter
```

**Acceptance Criteria:**
- [ ] `search_path` set correctly per request
- [ ] User queries execute in tenant schema
- [ ] Auth queries use public schema
- [ ] Schema not set = exception thrown
- [ ] Integration tests pass with multiple tenants

**Testing:**
```clojure
(deftest test-user-operations-multi-tenant
  (with-test-tenants [tenant-a tenant-b]
    ;; Create user in tenant A
    (with-tenant-context tenant-a
      (create-user! {:name "Alice"}))
    
    ;; Verify user exists in tenant A only
    (is (= 1 (count-users tenant-a)))
    (is (= 0 (count-users tenant-b)))))
```

---

### Phase 3: Migration Support (Weeks 5-6)

#### Week 5: Migration Runner

**Tasks:**
1. Implement per-tenant migration runner
2. Add migration status tracking
3. Create migration CLI commands
4. Implement rollback support
5. Write migration tests

**Deliverables:**
```clojure
;; boundary.platform.shell.db.migrations
- migrate-all-tenants!
- migrate-tenant!
- rollback-tenant!
- migration-status
- pending-migrations

;; CLI commands
clojure -M:migrate tenants up    ; Migrate all tenants
clojure -M:migrate tenant <slug> up
clojure -M:migrate tenant <slug> rollback
clojure -M:migrate tenants status
```

**Acceptance Criteria:**
- [ ] Migrations run sequentially across tenants
- [ ] Failed migration doesn't block others
- [ ] Migration status tracked per tenant
- [ ] Rollback works per tenant
- [ ] Dry-run mode available

**Testing:**
```clojure
(deftest test-tenant-migrations
  (with-test-tenants [tenant-a tenant-b]
    ;; Run migration on all tenants
    (migrate-all-tenants! migration-config)
    
    ;; Verify migration applied to both
    (is (table-exists? tenant-a "new_table"))
    (is (table-exists? tenant-b "new_table"))
    
    ;; Rollback tenant A only
    (rollback-tenant! tenant-a)
    (is (not (table-exists? tenant-a "new_table")))
    (is (table-exists? tenant-b "new_table"))))
```

#### Week 6: Migration Strategies & Safety

**Tasks:**
1. Implement canary migration (test tenant first)
2. Add parallel migration option
3. Create migration monitoring
4. Add migration hooks (pre/post)
5. Document migration best practices

**Deliverables:**
```clojure
;; Migration strategies
- migrate-canary! (test on 1 tenant first)
- migrate-parallel! (batch processing)
- migrate-with-monitoring!

;; Hooks
- before-tenant-migration
- after-tenant-migration
- on-migration-error
```

**Acceptance Criteria:**
- [ ] Canary migration stops on first failure
- [ ] Parallel migrations configurable (batch size)
- [ ] Migration duration tracked per tenant
- [ ] Hooks allow custom logic
- [ ] Documentation complete

---

### Phase 4: Module Integration (Weeks 7-8)

#### Week 7: Jobs & Cache Modules

**Tasks:**
1. Add tenant context to job metadata
2. Implement tenant-scoped job processing
3. Add tenant prefix to cache keys
4. Create namespaced cache helpers
5. Write integration tests

**Deliverables:**
```clojure
;; boundary.jobs.shell.multi-tenant
- enqueue-tenant-job!
- process-tenant-job!
- tenant-job-stats

;; boundary.cache.shell.multi-tenant
- tenant-cache-key
- with-tenant-cache
- clear-tenant-cache!
```

**Acceptance Criteria:**
- [ ] Jobs execute in correct tenant schema
- [ ] Cache keys scoped to tenant
- [ ] Job failures don't affect other tenants
- [ ] Cache operations isolated per tenant
- [ ] Integration tests pass

**Testing:**
```clojure
(deftest test-tenant-job-execution
  (with-test-tenants [tenant-a tenant-b]
    ;; Enqueue job for tenant A
    (enqueue-tenant-job! job-queue (:id tenant-a) :process-data {})
    
    ;; Process job
    (process-next-tenant-job! job-queue tenant-registry)
    
    ;; Verify job executed in correct tenant
    (is (job-completed-in-schema? "tenant_a"))))
```

#### Week 8: Realtime Module (if Phase 5 merged)

**Tasks:**
1. Add tenant-scoped WebSocket channels
2. Implement tenant channel routing
3. Add tenant to connection metadata
4. Test cross-tenant message isolation
5. Write integration tests

**Deliverables:**
```clojure
;; boundary.realtime.shell.multi-tenant
- tenant-channel-name
- subscribe-tenant-channel!
- broadcast-to-tenant!
- disconnect-tenant-connections!
```

**Acceptance Criteria:**
- [ ] Messages scoped to tenant channels
- [ ] Connection includes tenant ID
- [ ] No cross-tenant message leaks
- [ ] Broadcast reaches correct tenant only
- [ ] Integration tests pass

---

### Phase 5: Operations & Tooling (Weeks 9-10)

#### Week 9: Tenant API & Admin UI

**Tasks:**
1. Create tenant management HTTP endpoints
2. Add tenant CRUD to admin interface
3. Implement tenant provisioning API
4. Add tenant status management (suspend/activate)
5. Write API tests

**Deliverables:**
```clojure
;; HTTP endpoints
POST   /api/admin/tenants           ; Create tenant
GET    /api/admin/tenants           ; List tenants
GET    /api/admin/tenants/:id       ; Get tenant
PUT    /api/admin/tenants/:id       ; Update tenant
DELETE /api/admin/tenants/:id       ; Delete tenant
POST   /api/admin/tenants/:id/suspend
POST   /api/admin/tenants/:id/activate

;; Admin UI pages
/admin/tenants                      ; Tenant list
/admin/tenants/:id                  ; Tenant details
/admin/tenants/:id/users            ; Tenant users
```

**Acceptance Criteria:**
- [ ] Can create tenant via API (< 30s)
- [ ] Tenant list shows all tenants
- [ ] Suspend blocks access immediately
- [ ] Delete soft-deletes tenant
- [ ] API tests cover all endpoints

#### Week 10: Monitoring & Observability

**Tasks:**
1. Add per-tenant metrics
2. Create tenant dashboard
3. Implement tenant alerts
4. Add schema size monitoring
5. Document monitoring setup

**Deliverables:**
```clojure
;; Metrics
- tenant.requests.total (by tenant_id)
- tenant.requests.duration (by tenant_id)
- tenant.schema.size (by tenant_id)
- tenant.users.active (by tenant_id)

;; Dashboards
- Tenant overview (all tenants)
- Tenant detail (single tenant)
- Schema size growth
```

**Acceptance Criteria:**
- [ ] Metrics collected per tenant
- [ ] Dashboard shows tenant health
- [ ] Alerts trigger on anomalies
- [ ] Schema size tracked
- [ ] Documentation complete

---

### Phase 6: Advanced Features (Weeks 11-12)

#### Week 11: Backup & Restore

**Tasks:**
1. Implement tenant backup function
2. Create tenant restore function
3. Add automated backup scheduling
4. Test backup/restore flow
5. Document backup procedures

**Deliverables:**
```clojure
;; boundary.platform.shell.db.backup
- backup-tenant!
- restore-tenant!
- schedule-tenant-backups!
- list-tenant-backups

;; CLI commands
clojure -M:backup tenant <slug>
clojure -M:restore tenant <slug> <backup-file>
```

**Acceptance Criteria:**
- [ ] Backup creates valid dump file
- [ ] Restore creates identical schema
- [ ] Automated backups run daily
- [ ] Backup retention configurable
- [ ] Documentation complete

#### Week 12: Performance & Polish

**Tasks:**
1. Performance testing (1000 tenants)
2. Optimize tenant lookup (caching)
3. Add connection pool monitoring
4. Write performance documentation
5. Create example applications

**Deliverables:**
- Performance test suite
- Optimization guide
- Example multi-tenant app
- Complete documentation
- Migration guide

**Acceptance Criteria:**
- [ ] Performance overhead < 10ms/request
- [ ] Supports 1000+ tenants
- [ ] Example app demonstrates features
- [ ] All documentation complete
- [ ] Migration guide tested

---

## Appendix B: Testing Strategy

### Unit Tests (Phase 1-2)

**Focus:** Pure functions, no database
```clojure
;; Test coverage targets
boundary.platform.core.tenant     ; 100% coverage
boundary.platform.schema          ; 100% coverage
```

### Integration Tests (Phase 2-4)

**Focus:** Database operations, single tenant
```clojure
;; Use H2 in-memory for speed
(deftest test-with-database
  (with-test-db [db]
    (create-tenant! db {:slug "test"})))
```

### Contract Tests (Phase 3-4)

**Focus:** Multi-tenant isolation
```clojure
;; Ensure tenants are isolated
(deftest test-tenant-isolation
  (with-test-tenants [tenant-a tenant-b]
    (create-user! tenant-a {:name "Alice"})
    (is (= 0 (count-users tenant-b)))))
```

### Performance Tests (Phase 6)

**Focus:** Scalability, latency
```clojure
;; Test with 1000 tenants
(deftest test-scalability
  (with-test-tenants (create-n-tenants 1000)
    (let [start (System/currentTimeMillis)
          result (with-tenant-context (rand-nth tenants)
                   (list-users))]
      (is (< (- (System/currentTimeMillis) start) 10)))))
```

---

## Appendix C: Risk Mitigation

### Risk: Data Leak Between Tenants

**Mitigation:**
1. Middleware validation (ensure schema set)
2. Audit logging (all tenant access logged)
3. Integration tests (cross-tenant isolation)
4. Code review checklist (flag raw SQL)
5. Pre-deployment testing (multi-tenant test suite)

### Risk: Migration Failure

**Mitigation:**
1. Canary migration (test on 1 tenant first)
2. Per-tenant rollback (independent rollback)
3. Migration monitoring (track progress)
4. Dry-run mode (test without applying)
5. Database backups (before migrations)

### Risk: Performance Degradation

**Mitigation:**
1. Performance benchmarks (< 10ms overhead)
2. Connection pool monitoring (prevent exhaustion)
3. Schema statistics (ANALYZE per schema)
4. Tenant lookup caching (1 hour TTL)
5. Load testing (1000+ tenants)

---

**Document Version:** 1.0  
**Last Updated:** 2026-02-05  
**Total Lines:** 1,400+
**Appendices:** A (Roadmap), B (Testing), C (Risk Mitigation)
