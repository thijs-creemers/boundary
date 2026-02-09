# Migration Guide: Single-Tenant to Multi-Tenant

**Status**: Production-Ready Migration Path  
**Version**: 1.0  
**Last Updated**: 2026-02-09  
**Prerequisites**: Boundary Framework with tenant module, PostgreSQL 12+

---

## Overview

This guide walks you through migrating an existing single-tenant Boundary application to multi-tenant architecture with schema-per-tenant isolation.

**Migration Strategy**: Zero-downtime migration with gradual rollout

**Estimated Time**: 2-4 hours (depending on data volume)

**Rollback**: Possible at any step before Step 6

---

## Before You Begin

### Prerequisites Checklist

- [ ] Running PostgreSQL 12+ (multi-tenancy requires PostgreSQL)
- [ ] Current database backed up
- [ ] Boundary tenant module installed (`boundary-tenant`)
- [ ] Application running migrations 001-009 (pre-tenant migrations)
- [ ] Read ADR-004 Multi-Tenancy Architecture
- [ ] Staging environment available for testing

### Understanding the Migration

**Current State (Single-Tenant)**:
```
public schema
├── users (email, password_hash, name, role, etc.)
├── user_sessions
├── items
└── ... (all application tables)
```

**Target State (Multi-Tenant)**:
```
public schema
├── tenants (tenant registry)
├── auth_users (email, password_hash, mfa_enabled, etc.)
└── (shared tables)

tenant_default schema
├── users (id, tenant_id, name, role, etc.)
├── items
└── ... (tenant-specific tables)

tenant_acme_corp schema
├── users
├── items
└── ... (isolated per tenant)
```

---

## Migration Steps

### Step 0: Backup Everything

**Critical**: Create comprehensive backup before proceeding.

```bash
# PostgreSQL backup
pg_dump -h localhost -U postgres -d boundary_app > backup_$(date +%Y%m%d_%H%M%S).sql

# Verify backup can be restored (test in separate database)
createdb boundary_test
psql -d boundary_test < backup_20260209_120000.sql
dropdb boundary_test
```

**Backup Verification Checklist**:
- [ ] Backup file exists and has size > 0
- [ ] Backup can be restored successfully
- [ ] Row counts match production (`SELECT COUNT(*) FROM users;`)

---

### Step 1: Run Tenant Module Migrations

Run migrations **010** and **011** to create tenant infrastructure.

```bash
cd /path/to/boundary-app

# Run migration 010: Create tenants table
clojure -M:migrate up

# Verify tenants table exists
psql -d boundary_app -c "\d tenants"
# Expected: Table with columns: id, slug, name, schema_name, status, settings, etc.

# Run migration 011: Split authentication
clojure -M:migrate up

# Verify auth_users table exists
psql -d boundary_app -c "\d auth_users"
# Expected: Table with columns: id, email, password_hash, mfa_enabled, etc.

# Verify users table structure changed
psql -d boundary_app -c "\d users"
# Expected: No password_hash, email columns (moved to auth_users)
```

**Post-Migration Verification**:
```sql
-- Count should match original users count
SELECT COUNT(*) FROM auth_users;
SELECT COUNT(*) FROM users;

-- Verify authentication data migrated
SELECT id, email, mfa_enabled, active FROM auth_users LIMIT 5;

-- Verify profile data preserved
SELECT id, name, role, tenant_id FROM users LIMIT 5;
-- Note: tenant_id will be NULL at this point

-- Check for orphaned records (should be 0)
SELECT COUNT(*) FROM users WHERE id NOT IN (SELECT id FROM auth_users);
SELECT COUNT(*) FROM auth_users WHERE id NOT IN (SELECT id FROM users);
```

---

### Step 2: Create Default Tenant

Create a "default" tenant to represent your existing single-tenant data.

```clojure
(ns my-app.migrations.create-default-tenant
  (:require [boundary.tenant.ports :as tenant-ports]
            [integrant.core :as ig]))

(defn create-default-tenant! [system]
  (let [tenant-service (:boundary/tenant-service system)
        tenant-input {:slug "default"
                      :name "Default Organization"
                      :settings {:created-by "migration"
                                 :original-deployment true}}]
    
    ;; Create tenant record
    (def default-tenant (tenant-ports/create-new-tenant tenant-service tenant-input))
    
    (println "Created default tenant:")
    (println "  ID:" (:id default-tenant))
    (println "  Slug:" (:slug default-tenant))
    (println "  Schema:" (:schema-name default-tenant))
    
    default-tenant))
```

**Run the migration**:
```clojure
;; In REPL or migration script
(require '[integrant.repl :as ig-repl])
(ig-repl/go)

(def default-tenant (create-default-tenant! integrant.repl.state/system))
;; => {:id #uuid "...", :slug "default", :schema-name "tenant_default", ...}
```

**Verify**:
```sql
-- Should see 1 tenant
SELECT * FROM tenants;
```

---

### Step 3: Provision Default Tenant Schema

Create the `tenant_default` schema and copy table structures from `public`.

```clojure
(require '[boundary.tenant.shell.provisioning :as provisioning]
         '[boundary.platform.core.db-factory :as db-factory])

(defn provision-default-tenant! [system default-tenant]
  (let [db-ctx (get system :boundary/db-context)]
    
    (println "Provisioning tenant schema:" (:schema-name default-tenant))
    
    ;; This creates tenant_default schema and copies table structures
    (provisioning/provision-tenant! db-ctx default-tenant)
    
    (println "Provisioning complete!")
    (println "Schema:" (:schema-name default-tenant))
    (println "Status:" (provisioning/tenant-provisioned? db-ctx default-tenant))))

;; Run provisioning
(provision-default-tenant! integrant.repl.state/system default-tenant)
```

**Verify**:
```sql
-- Check schema was created
SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'tenant_default';

-- Check tables were copied
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'tenant_default' 
ORDER BY table_name;

-- Verify structure matches (but tables are empty)
SELECT COUNT(*) FROM tenant_default.users;  -- Should be 0
SELECT COUNT(*) FROM public.users;  -- Should be > 0
```

---

### Step 4: Migrate Data to Tenant Schema

Copy existing data from `public` schema to `tenant_default` schema.

**Important**: This step requires careful handling of foreign keys and relationships.

```sql
-- Begin transaction for data migration
BEGIN;

-- Step 4a: Update users.tenant_id in public schema
UPDATE users SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');

-- Verify all users have tenant_id
SELECT COUNT(*) FROM users WHERE tenant_id IS NULL;  -- Should be 0

-- Step 4b: Copy users to tenant_default schema
INSERT INTO tenant_default.users (
    id, tenant_id, name, role, avatar_url,
    login_count, last_login, date_format, time_format,
    created_at, updated_at, deleted_at
)
SELECT 
    id, tenant_id, name, role, avatar_url,
    login_count, last_login, date_format, time_format,
    created_at, updated_at, deleted_at
FROM public.users;

-- Verify row counts match
SELECT COUNT(*) FROM public.users;  -- e.g., 100
SELECT COUNT(*) FROM tenant_default.users;  -- Should match: 100

-- Step 4c: Copy other application tables (repeat for each table)
-- Example: items table
INSERT INTO tenant_default.items (id, name, description, created_at, updated_at)
SELECT id, name, description, created_at, updated_at
FROM public.items;

-- Verify
SELECT COUNT(*) FROM public.items;
SELECT COUNT(*) FROM tenant_default.items;  -- Should match

-- Step 4d: If everything looks good, commit
COMMIT;

-- If something went wrong, rollback
-- ROLLBACK;
```

**Automated Migration Script** (for multiple tables):

```clojure
(defn migrate-table-to-tenant-schema! 
  [db-ctx tenant-schema table-name]
  (let [public-count (jdbc/execute-one! 
                       db-ctx 
                       [(str "SELECT COUNT(*) as count FROM public." table-name)])
        
        ;; Copy data
        _ (jdbc/execute! 
            db-ctx 
            [(str "INSERT INTO " tenant-schema "." table-name 
                  " SELECT * FROM public." table-name)])
        
        tenant-count (jdbc/execute-one! 
                       db-ctx 
                       [(str "SELECT COUNT(*) as count FROM " tenant-schema "." table-name)])]
    
    (println (format "Migrated %s: %d rows (public) -> %d rows (%s)" 
                     table-name 
                     (:count public-count) 
                     (:count tenant-count)
                     tenant-schema))
    
    ;; Verify counts match
    (assert (= (:count public-count) (:count tenant-count))
            (format "Row count mismatch for %s" table-name))))

;; Migrate all application tables
(doseq [table ["users" "items" "orders" "products"]]  ; Add your tables
  (migrate-table-to-tenant-schema! db-ctx "tenant_default" table))
```

---

### Step 5: Update Application Code

Update your application to use tenant context.

**Before (Single-Tenant)**:
```clojure
(defn get-users [db-ctx]
  (jdbc/execute! db-ctx ["SELECT * FROM users"]))

(defn create-item [db-ctx item-data]
  (jdbc/execute! db-ctx 
                 ["INSERT INTO items (name, description) VALUES (?, ?)"
                  (:name item-data) (:description item-data)]))
```

**After (Multi-Tenant)**:
```clojure
(require '[boundary.tenant.shell.provisioning :as provisioning])

(defn get-users [db-ctx tenant-slug]
  (provisioning/with-tenant-schema db-ctx (str "tenant_" tenant-slug)
    (fn [ctx]
      (jdbc/execute! ctx ["SELECT * FROM users"]))))

(defn create-item [db-ctx tenant-slug item-data]
  (provisioning/with-tenant-schema db-ctx (str "tenant_" tenant-slug)
    (fn [ctx]
      (jdbc/execute! ctx 
                     ["INSERT INTO items (name, description) VALUES (?, ?)"
                      (:name item-data) (:description item-data)]))))
```

**HTTP Handler Updates**:
```clojure
;; Before: No tenant context
(defn list-items-handler [request]
  (let [items (get-items db-ctx)]
    {:status 200 :body {:items items}}))

;; After: Extract tenant from request
(defn list-items-handler [request]
  (let [tenant (:tenant request)  ; Added by tenant middleware
        tenant-slug (:slug tenant)
        items (get-items db-ctx tenant-slug)]
    {:status 200 :body {:items items}}))
```

**Add Tenant Middleware**:
```clojure
(require '[boundary.platform.shell.interfaces.http.tenant-middleware :as tenant-mw])

(def app
  (-> routes
      ;; Add tenant resolution (extracts from subdomain/header/JWT)
      (tenant-mw/wrap-tenant-resolution tenant-service)
      
      ;; Add schema switching (sets search_path)
      (tenant-mw/wrap-tenant-schema db-context)
      
      ;; Other middleware
      (wrap-defaults site-defaults)))
```

---

### Step 6: Test with Default Tenant

**Critical**: Thoroughly test before enabling multi-tenancy for real.

```clojure
;; Test authentication (public.auth_users)
(def auth-result 
  (jdbc/execute-one! db-ctx 
                     ["SELECT * FROM auth_users WHERE email = ?" "admin@example.com"]))

;; Test profile query (tenant_default.users)
(provisioning/with-tenant-schema db-ctx "tenant_default"
  (fn [ctx]
    (jdbc/execute-one! ctx 
                       ["SELECT * FROM users WHERE id = ?" (:id auth-result)])))

;; Test application queries
(provisioning/with-tenant-schema db-ctx "tenant_default"
  (fn [ctx]
    (jdbc/execute! ctx ["SELECT COUNT(*) FROM items"])))
```

**Integration Test Checklist**:
- [ ] User login works (queries `auth_users`)
- [ ] User profile loads (queries `tenant_default.users`)
- [ ] Application data accessible (queries `tenant_default.<tables>`)
- [ ] Data isolation verified (no queries to `public` tables)
- [ ] HTTP endpoints work with tenant middleware
- [ ] Background jobs work (if using jobs module)
- [ ] Cache works (if using cache module)

---

### Step 7: Clean Up Public Schema (Optional)

After verifying everything works in `tenant_default`, optionally drop old tables from `public` schema.

**WARNING**: Only do this after thorough testing and backup verification!

```sql
-- Verify tenant schema has all data
SELECT COUNT(*) FROM public.users;  -- e.g., 100
SELECT COUNT(*) FROM tenant_default.users;  -- Should match: 100

-- Drop old tables from public (IRREVERSIBLE!)
-- CAUTION: Only after extensive testing!
DROP TABLE public.users;  -- Profile data now in tenant_default.users
DROP TABLE public.items;  -- Application data now in tenant_default.items
-- ... (repeat for all tenant-specific tables)

-- Keep in public schema:
-- - tenants (tenant registry)
-- - auth_users (authentication)
-- - migrations metadata
-- - any truly shared/global tables
```

---

### Step 8: Enable Multi-Tenancy

Now you can add additional tenants!

```clojure
;; Create second tenant
(def acme-tenant
  (tenant-ports/create-new-tenant tenant-service
                                  {:slug "acme-corp"
                                   :name "ACME Corporation"
                                   :settings {:plan "enterprise"}}))

;; Provision schema
(provisioning/provision-tenant! db-ctx acme-tenant)

;; Create first user in new tenant
(provisioning/with-tenant-schema db-ctx "tenant_acme_corp"
  (fn [ctx]
    (jdbc/execute! ctx
                   ["INSERT INTO users (id, tenant_id, name, role) VALUES (?, ?, ?, ?)"
                    (random-uuid) (:id acme-tenant) "ACME Admin" "admin"])))
```

**Test Isolation**:
```sql
-- Verify data isolation
SET search_path TO tenant_default, public;
SELECT COUNT(*) FROM users;  -- Shows default tenant users only

SET search_path TO tenant_acme_corp, public;
SELECT COUNT(*) FROM users;  -- Shows ACME Corp users only (should be 1)
```

---

## Rollback Procedures

### Rollback from Step 1-3 (Before Data Migration)

If you haven't copied data to tenant schemas yet, rollback is straightforward:

```sql
-- Drop tenant schemas
DROP SCHEMA IF EXISTS tenant_default CASCADE;

-- Drop tenant-specific tables
DROP TABLE IF EXISTS tenants CASCADE;

-- Restore original users table from backup
-- (restore from pg_dump backup created in Step 0)
```

### Rollback from Step 4-6 (After Data Migration)

If data is already in tenant schemas but you need to rollback:

```sql
BEGIN;

-- Step 1: Copy data back from tenant_default to public
TRUNCATE public.users;
INSERT INTO public.users SELECT * FROM tenant_default.users;

-- Verify
SELECT COUNT(*) FROM public.users;
SELECT COUNT(*) FROM tenant_default.users;

-- Step 2: Drop tenant schemas
DROP SCHEMA IF EXISTS tenant_default CASCADE;

-- Step 3: Drop tenant tables
DROP TABLE IF EXISTS tenants CASCADE;
DROP TABLE IF EXISTS auth_users CASCADE;

COMMIT;
```

### Rollback from Step 7+ (After Public Cleanup)

**Restore from backup**:
```bash
# Restore entire database from backup
psql -d boundary_app < backup_20260209_120000.sql
```

---

## Troubleshooting

### Issue: Migration 011 fails with "table users does not exist"

**Cause**: Migration 001 already removed the old users table structure.

**Solution**: Manually create users table before running migration 011, or restore from backup.

### Issue: Row counts don't match after data migration

**Cause**: Foreign key constraints or ON DELETE CASCADE rules triggered deletions.

**Solution**: 
1. Disable foreign key checks temporarily: `SET session_replication_role = 'replica';`
2. Copy data
3. Re-enable: `SET session_replication_role = 'origin';`

### Issue: Application queries fail with "relation does not exist"

**Cause**: `search_path` not set correctly, queries looking in wrong schema.

**Solution**: Verify middleware is setting search_path:
```sql
-- Check current search_path
SHOW search_path;
-- Should be: tenant_<slug>, public
```

### Issue: Authentication fails after migration

**Cause**: Email lookup querying wrong table (public.users instead of public.auth_users).

**Solution**: Update authentication code to query `auth_users`:
```clojure
;; Before
(jdbc/execute-one! db-ctx ["SELECT * FROM users WHERE email = ?" email])

;; After
(jdbc/execute-one! db-ctx ["SELECT * FROM auth_users WHERE email = ?" email])
```

---

## Performance Considerations

### Schema Switching Overhead

**Benchmark**: `SET search_path` takes < 1ms in PostgreSQL.

**Optimization**: Use connection-level search_path (already done by tenant middleware).

### Data Volume

**Small datasets** (< 1GB): Migration takes minutes.

**Large datasets** (> 100GB): Consider:
- Partitioning data migration into batches
- Running migration during maintenance window
- Using `pg_dump` with `--schema` for faster schema-only setup

### Indexes

After copying data, rebuild indexes:
```sql
REINDEX SCHEMA tenant_default;
```

---

## Post-Migration Checklist

- [ ] All data migrated to tenant schemas
- [ ] Row counts verified (source == destination)
- [ ] Authentication working (public.auth_users)
- [ ] Profile queries working (tenant_<slug>.users)
- [ ] Application queries working (tenant_<slug>.*)
- [ ] Tenant middleware configured
- [ ] HTTP endpoints tested with tenant context
- [ ] Background jobs tested (if applicable)
- [ ] Cache tested (if applicable)
- [ ] Backup of new multi-tenant structure created
- [ ] Monitoring configured for per-tenant metrics
- [ ] Documentation updated

---

## Next Steps

After successful migration:

1. **Add More Tenants**: Create additional tenants for real customers
2. **Enable Tenant Signup**: Allow self-service tenant creation
3. **Configure Tenant Resolution**: Set up subdomain or API-based tenant resolution
4. **Add Tenant Metrics**: Monitor performance per tenant
5. **Implement Tenant Limits**: Add usage limits and quotas per tenant
6. **Test Tenant Isolation**: Verify cross-tenant data leakage prevention

---

## References

- **ADR-004**: Multi-Tenancy Architecture Decision Record
- **Tenant Module README**: `libs/tenant/README.md`
- **Migration 010**: `migrations/010_create_tenants_table.sql`
- **Migration 011**: `migrations/011_split_user_authentication.sql`

---

## Support

**Questions or Issues?**
- GitHub Issues: Report migration problems
- GitHub Discussions: Ask migration questions
- Documentation: See ADR-004 for architecture details

---

**Last Updated**: 2026-02-09  
**Version**: 1.0  
**Status**: Production-Ready
