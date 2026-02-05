# Multi-Tenancy Database Architecture Patterns - Research Notes

**Date**: 2026-02-05  
**Purpose**: Research multi-tenancy patterns for Boundary Framework design decision  
**Target**: ADR-004 Multi-tenancy Architecture Design

---

## Executive Summary

After extensive research across PostgreSQL documentation, Rails/Django ecosystems, and modern SaaS architectures (2025-2026), three primary multi-tenancy database patterns emerge:

1. **Shared Database, Shared Schema** (row-level isolation via `tenant_id`)
2. **Shared Database, Separate Schemas** (schema-per-tenant) ⭐ **RECOMMENDED**
3. **Database-per-Tenant** (complete database isolation)

**Recommendation for Boundary**: **Schema-per-tenant** provides the optimal balance of isolation, scalability, and operational simplicity for a Clojure/PostgreSQL framework targeting mid-to-large SaaS applications.

---

## Pattern 1: Shared Database, Shared Schema (Row-Level Isolation)

### Architecture

```
┌─────────────────────────────────────┐
│         Single Database             │
│                                     │
│  ┌───────────────────────────┐     │
│  │   Shared Schema (public)  │     │
│  │                           │     │
│  │  users                    │     │
│  │  ├─ id                    │     │
│  │  ├─ tenant_id   ← Filter  │     │
│  │  ├─ name                  │     │
│  │  └─ email                 │     │
│  │                           │     │
│  │  orders                   │     │
│  │  ├─ id                    │     │
│  │  ├─ tenant_id   ← Filter  │     │
│  │  ├─ amount                │     │
│  │  └─ ...                   │     │
│  └───────────────────────────┘     │
└─────────────────────────────────────┘
```

Every query MUST include: `WHERE tenant_id = ?`

### Pros

- ✅ **Simplest to implement**: Single database, standard SQL queries
- ✅ **Most cost-effective**: One database instance serves all tenants
- ✅ **Easy maintenance**: Single backup, single schema migration
- ✅ **Efficient resource usage**: Shared connection pool, shared indexes
- ✅ **Cross-tenant analytics**: Easy to aggregate data across tenants
- ✅ **No connection overhead**: Single database connection

### Cons

- ❌ **High data leak risk**: Forget `WHERE tenant_id = ?` → data breach
- ❌ **No database-level enforcement**: All isolation logic in application code
- ❌ **Query complexity**: Every query requires tenant filtering
- ❌ **Performance degradation**: Large shared tables affect all tenants
- ❌ **Noisy neighbor problem**: One tenant's heavy queries impact all others
- ❌ **Limited customization**: Cannot have per-tenant schema differences
- ❌ **Compliance challenges**: Hard to prove tenant isolation for regulations

### PostgreSQL Row-Level Security (RLS) Enhancement

PostgreSQL's RLS can enforce `tenant_id` filtering at the database level:

```sql
-- Enable RLS on table
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- Create policy to enforce tenant isolation
CREATE POLICY tenant_isolation ON users
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

**RLS Pros**:
- ✅ Database-enforced isolation (not just app-level)
- ✅ Reduces risk of forgotten WHERE clauses
- ✅ Works with any query, even raw SQL

**RLS Cons**:
- ❌ Performance overhead (policy evaluation on every row)
- ❌ Query planner complexity (can't optimize across tenants)
- ❌ Debugging difficulty (policies can hide data unexpectedly)
- ❌ Connection session management (setting `app.current_tenant` per request)

### Use Cases

**Best for**:
- Small SaaS applications (<100 tenants)
- Low data volume per tenant (<10GB total)
- Homogeneous tenant requirements
- Cost-sensitive deployments
- Proof-of-concept or MVP phase

**Not suitable for**:
- High-security/compliance requirements (HIPAA, SOC2)
- Large enterprise tenants with custom needs
- Tenants with massive data volumes (>1GB each)
- Applications with strict SLA requirements per tenant

### Real-World Examples

- **Early-stage SaaS products** (pre-scaling phase)
- **Internal tools** with departmental tenancy
- **Low-risk applications** (non-sensitive data)

---

## Pattern 2: Shared Database, Separate Schemas (Schema-per-Tenant) ⭐

### Architecture

```
┌──────────────────────────────────────────────────────┐
│              Single Database                         │
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
│  │ Schema: public   │  ← Shared/global tables       │
│  │  tenants         │     (tenant registry)         │
│  │  users (login)   │     (user accounts)           │
│  └──────────────────┘                               │
└──────────────────────────────────────────────────────┘
```

**Tenant Identification**: Subdomain, JWT claim, or header
**Schema Switching**: `SET search_path TO tenant_a, public;`

### Pros

- ✅ **Strong logical isolation**: Database-enforced schema boundaries
- ✅ **No tenant_id in queries**: Clean SQL without WHERE clauses
- ✅ **Per-tenant customization**: Each tenant can have custom tables/columns
- ✅ **Better performance**: Smaller tables per schema (faster queries)
- ✅ **Independent backups**: Can backup/restore individual tenants
- ✅ **Easier migrations**: Can migrate tenants incrementally
- ✅ **Clear data ownership**: One schema = one tenant
- ✅ **Audit trail**: Database logs show schema-level access
- ✅ **Connection pooling**: Shared connection pool across schemas
- ✅ **Compliance-friendly**: Easier to demonstrate tenant isolation

### Cons

- ❌ **More complex setup**: Schema creation/management overhead
- ❌ **Migration complexity**: Must run migrations per tenant schema
- ❌ **Schema count limits**: PostgreSQL handles ~1000 schemas well (not 100k+)
- ❌ **Cross-tenant queries**: Harder to aggregate data across tenants
- ❌ **Connection management**: Must set `search_path` per request
- ❌ **Metadata bloat**: More schemas = larger system catalogs
- ❌ **Backup size**: All schemas backed up together (large files)

### PostgreSQL Implementation

**Schema Creation**:
```sql
-- Create tenant schema
CREATE SCHEMA tenant_abc123;

-- Grant usage to application role
GRANT USAGE ON SCHEMA tenant_abc123 TO app_user;
GRANT ALL ON ALL TABLES IN SCHEMA tenant_abc123 TO app_user;
```

**Schema Switching (per request)**:
```sql
-- Set search path for current session
SET search_path TO tenant_abc123, public;

-- Now all queries default to tenant_abc123 schema
SELECT * FROM users;  -- Uses tenant_abc123.users
```

**Shared Tables** (in `public` schema):
- Tenant registry (`tenants` table)
- User authentication (`users` table with `tenant_id`)
- Global configuration

### Migration Strategy

**Per-Tenant Migrations**:
```clojure
(defn run-migration-for-all-tenants [migration-fn]
  (doseq [tenant (get-all-tenants)]
    (with-tenant-schema tenant
      (migration-fn))))
```

**Migration Approaches**:
1. **Sequential**: Migrate tenants one-by-one (safer, slower)
2. **Parallel**: Migrate in batches (faster, riskier)
3. **Rolling**: Migrate during low-traffic windows
4. **Canary**: Test on 1-2 tenants first, then roll out

### Use Cases

**Best for**:
- Mid-to-large SaaS applications (100-10,000 tenants)
- Enterprise customers requiring data isolation
- Compliance-heavy industries (HIPAA, SOC2, GDPR)
- Applications with varying tenant sizes (small to large)
- Per-tenant customization requirements
- Applications requiring tenant-specific indexes/optimizations

**Not suitable for**:
- Massive scale (100k+ tenants) - consider database-per-tenant
- Serverless architectures (connection pool issues)
- Frequent cross-tenant analytics

### Real-World Examples

- **Rails Apartment Gem**: 10+ years of production use, powers thousands of SaaS apps
- **Shopify**: Used schema-per-tenant for years (migrated to sharded approach later)
- **Neon**: Database-per-user pattern (similar isolation)
- **GitLab**: Schema-per-organization for self-hosted deployments

### Rails Apartment Gem (Reference Implementation)

**Installation**:
```ruby
# Gemfile
gem 'apartment'

# config/initializers/apartment.rb
Apartment.configure do |config|
  config.excluded_models = %w{ User Tenant }  # Shared tables
  config.use_schemas = true
  config.tenant_names = -> { Tenant.pluck(:schema_name) }
end
```

**Usage**:
```ruby
# Switch tenant
Apartment::Tenant.switch!('tenant_abc123')

# All queries now use tenant_abc123 schema
User.all  # SELECT * FROM tenant_abc123.users

# Create new tenant
Apartment::Tenant.create('tenant_xyz789')
```

**Key Learnings from Rails Ecosystem**:
- Exclude authentication/global tables from tenant schemas
- Use middleware to automatically switch schemas per request
- Cache tenant list to avoid database lookups
- Handle missing tenant gracefully (404 vs 500)
- Log schema switches for debugging

---

## Pattern 3: Database-per-Tenant

### Architecture

```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  DB: tenant_a   │  │  DB: tenant_b   │  │  DB: tenant_c   │
│                 │  │                 │  │                 │
│  users          │  │  users          │  │  users          │
│  orders         │  │  orders         │  │  orders         │
│  products       │  │  products       │  │  products       │
└─────────────────┘  └─────────────────┘  └─────────────────┘

┌──────────────────────────────┐
│  Master DB: control_plane    │
│                              │
│  tenants (registry)          │
│  users (authentication)      │
└──────────────────────────────┘
```

### Pros

- ✅ **Maximum isolation**: Physical database separation
- ✅ **Complete independence**: Each tenant has dedicated resources
- ✅ **Easy tenant removal**: Drop database = tenant deleted
- ✅ **Per-tenant scaling**: Can allocate different resources per tenant
- ✅ **Compliance excellence**: Strongest isolation story
- ✅ **Custom configurations**: Each tenant can have different PostgreSQL settings
- ✅ **Backup flexibility**: Per-tenant backup schedules
- ✅ **No noisy neighbor**: Tenant queries don't affect others

### Cons

- ❌ **High operational complexity**: Managing 1000s of databases
- ❌ **Connection pool overhead**: Each database needs connections
- ❌ **Cost**: More database instances = higher infrastructure costs
- ❌ **Migration complexity**: Must run migrations on ALL databases
- ❌ **Cross-tenant queries**: Nearly impossible without federation
- ❌ **Monitoring overhead**: Need to monitor every database
- ❌ **Backup complexity**: Many databases to backup/restore
- ❌ **Disaster recovery**: Harder to coordinate multi-database recovery

### Use Cases

**Best for**:
- Very large tenants (>10GB data each)
- Extreme compliance requirements (financial, healthcare)
- Tenants requiring dedicated hardware
- White-label SaaS with tenant branding
- Tenants with specific SLA requirements
- Applications with <100 very large tenants

**Not suitable for**:
- Large number of small tenants (1000s of small businesses)
- Cost-sensitive applications
- Frequent cross-tenant analytics
- Resource-constrained environments

### Real-World Examples

- **Enterprise SaaS**: Dedicated instances for Fortune 500 customers
- **White-label platforms**: Each reseller gets own database
- **Financial services**: Strict regulatory isolation

---

## Comparison Matrix

| Aspect | Shared Schema | Schema-per-Tenant ⭐ | Database-per-Tenant |
|--------|---------------|---------------------|---------------------|
| **Isolation** | Low (app-level) | Medium (schema-level) | High (physical) |
| **Data Leak Risk** | High | Low | Very Low |
| **Setup Complexity** | Low | Medium | High |
| **Operational Cost** | Low | Medium | High |
| **Scalability** | 10,000+ tenants | 1,000-10,000 tenants | <1,000 tenants |
| **Query Performance** | Degrades with scale | Good | Excellent |
| **Noisy Neighbor** | High risk | Medium risk | No risk |
| **Custom Schema** | No | Yes | Yes |
| **Migrations** | Easy (one schema) | Medium (iterate schemas) | Hard (many DBs) |
| **Backups** | Easy (one DB) | Medium (one DB, many schemas) | Hard (many DBs) |
| **Compliance** | Difficult | Good | Excellent |
| **Connection Pool** | Shared | Shared | Per-database |
| **Cross-Tenant Analytics** | Easy | Medium | Hard |
| **Monitoring** | Easy | Medium | Complex |

---

## Tenant Identification Strategies

### 1. Subdomain-Based

```
tenant-a.myapp.com → tenant_a schema
tenant-b.myapp.com → tenant_b schema
```

**Pros**:
- Clear tenant identity in URL
- Works well with SSL certificates (wildcard)
- User-friendly (branded URLs)

**Cons**:
- Requires DNS management
- SSL certificate complexity
- Can't easily support multiple tenants in one browser session

### 2. Path-Based

```
myapp.com/tenant-a/... → tenant_a schema
myapp.com/tenant-b/... → tenant_b schema
```

**Pros**:
- Single domain (easier SSL)
- Easier to test locally

**Cons**:
- URL structure less clean
- Tenant slug in every route

### 3. JWT Claim-Based

```json
{
  "user_id": "user-123",
  "tenant_id": "tenant-abc",
  "roles": ["admin"]
}
```

**Pros**:
- Works with any URL structure
- Clean API design
- Supports multi-tenant users

**Cons**:
- Requires JWT validation on every request
- Client must send JWT correctly
- Harder to debug (tenant not visible in URL)

### 4. HTTP Header-Based

```
X-Tenant-ID: tenant-abc
```

**Pros**:
- Flexible
- Works with API gateways

**Cons**:
- Easy to forget/misconfigure
- Not user-visible
- Requires middleware

**Recommendation for Boundary**: **Subdomain + JWT claim fallback**
- Primary: Subdomain for web UI (`tenant.myapp.com`)
- Fallback: JWT claim for API access (mobile apps, integrations)

---

## Django & Rails Multi-Tenancy Patterns

### Rails Apartment Gem Insights

**Key Features**:
- Automatic schema switching via middleware
- Excluded models (shared across tenants)
- Tenant creation/deletion
- Migration management across tenants

**Best Practices from Rails Community**:
1. Always exclude authentication tables from tenant schemas
2. Use background jobs for tenant creation (slow operation)
3. Cache tenant list (don't query on every request)
4. Handle 404 for non-existent tenants gracefully
5. Use connection pooling wisely (don't exhaust connections)

### Django django-tenants Package

**Approach**: Schema-per-tenant with middleware-based switching

**Key Features**:
- Automatic `search_path` management
- Tenant-aware migrations
- Shared vs tenant-specific apps
- Subdomain routing

**Lessons Learned**:
- Schema creation is slow (3-5 seconds for complex apps)
- Test on production-like data volumes
- Monitor schema count (metadata bloat)
- Use database connection pooling carefully

---

## Clojure/JVM Considerations

### JDBC Connection Management

```clojure
;; Schema-per-tenant with next.jdbc
(defn with-tenant-schema [datasource tenant-id f]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx [(str "SET search_path TO " tenant-id ", public")])
    (f tx)))

(with-tenant-schema datasource "tenant_abc"
  (fn [tx]
    (jdbc/execute! tx ["SELECT * FROM users"])))
```

### Connection Pooling

**Challenge**: Schema switching requires setting session variable
**Solution**: Use connection pooling wisely, don't cache connections too aggressively

```clojure
;; HikariCP configuration
{:maximum-pool-size 20  ; Per-application (not per-tenant)
 :minimum-idle 5
 :connection-timeout 30000}
```

### Migration Strategy with Migratus

```clojure
(defn migrate-all-tenants []
  (doseq [tenant (get-all-tenants)]
    (let [config {:store :database
                  :migration-dir "migrations/"
                  :init-in-transaction? true
                  :migration-table-name "schema_migrations"
                  :db (assoc db-spec :schema (:schema_name tenant))}]
      (migratus/migrate config))))
```

---

## Security Considerations

### Preventing Data Leaks

**Schema-per-Tenant Safeguards**:
1. **Middleware enforcement**: Set schema on EVERY request
2. **Connection validation**: Verify schema is set before queries
3. **Audit logging**: Log all schema switches
4. **Test coverage**: Integration tests for tenant isolation
5. **Code review**: Flag raw SQL without schema context

**Example Safeguard (Clojure)**:
```clojure
(defn ensure-tenant-schema-set [conn]
  (let [current-schema (-> (jdbc/execute-one! conn ["SHOW search_path"])
                           :search_path)]
    (when (= "public" current-schema)
      (throw (ex-info "Tenant schema not set - potential data leak!"
                      {:type :security-error})))))
```

### Compliance & Regulations

**GDPR Compliance**:
- ✅ Per-tenant schemas simplify data export (dump schema)
- ✅ Right to be forgotten: Drop schema = tenant deleted
- ✅ Data residency: Can place tenant schemas on specific servers

**HIPAA Compliance**:
- ✅ Strong isolation story for auditors
- ✅ Audit logs per tenant
- ✅ Encryption at rest (per-schema encryption possible)

**SOC2 Compliance**:
- ✅ Logical separation of customer data
- ✅ Access controls at schema level
- ✅ Audit trail of schema access

---

## Performance Benchmarks (from research)

### Query Performance

| Pattern | 100 rows | 10k rows | 1M rows | Notes |
|---------|----------|----------|---------|-------|
| Shared Schema | 2ms | 50ms | 1200ms | Query planner uses global statistics |
| Schema-per-Tenant | 2ms | 45ms | 800ms | Per-schema statistics, smaller tables |
| DB-per-Tenant | 2ms | 40ms | 750ms | Dedicated resources |

*(Source: Debugg.ai Postgres Multitenancy 2025)*

### Migration Time

| Pattern | 10 tenants | 100 tenants | 1000 tenants |
|---------|------------|-------------|--------------|
| Shared Schema | 5s | 5s | 5s |
| Schema-per-Tenant | 30s | 5min | 50min |
| DB-per-Tenant | 1min | 10min | 100min+ |

### Connection Pool Usage

- **Shared Schema**: 1 pool, N connections
- **Schema-per-Tenant**: 1 pool, N connections (shared across tenants)
- **DB-per-Tenant**: T pools × N connections (T = number of tenants)

---

## Recommended Pattern for Boundary Framework

### Primary Recommendation: Schema-per-Tenant ⭐

**Rationale**:
1. **Balanced trade-offs**: Strong isolation without operational complexity
2. **Compliance-friendly**: Easier to prove tenant isolation
3. **Clojure-friendly**: Works well with JDBC/next.jdbc
4. **Proven pattern**: Rails Apartment gem shows 10+ years of success
5. **Scalability**: Handles 100-10,000 tenants efficiently
6. **Cost-effective**: Single database, shared connection pool

**Architecture for Boundary**:
```
┌────────────────────────────────────────────────────┐
│              PostgreSQL Database                    │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐               │
│  │ Public       │  │ Tenant       │  ...          │
│  │ Schema       │  │ Schemas      │               │
│  │              │  │              │               │
│  │ tenants      │  │ tenant_abc   │               │
│  │ users*       │  │ users        │               │
│  │              │  │ orders       │               │
│  └──────────────┘  └──────────────┘               │
└────────────────────────────────────────────────────┘
         ↑
         │
    Boundary
  Framework App
     (Clojure)
```

*users in public schema: authentication only (username, password, tenant_id)

### Implementation Approach

**Phase 1: Foundation**
- Tenant registry in `public` schema
- Schema creation/deletion functions
- Middleware for automatic schema switching
- Connection pool management

**Phase 2: Migration Support**
- Per-tenant migration runner
- Rollback support
- Migration status tracking

**Phase 3: Operations**
- Tenant backup/restore
- Monitoring per tenant
- Performance metrics per schema

**Phase 4: Advanced Features**
- Tenant provisioning API
- Self-service tenant creation
- Tenant lifecycle management

---

## References

### Primary Sources

1. **Postgres Multitenancy in 2025: RLS vs Schemas vs Separate DBs**  
   Source: Debugg.ai (2025-09-07)  
   URL: https://debugg.ai/resources/postgres-multitenancy-rls-vs-schemas-vs-separate-dbs-performance-isolation-migration-playbook-2025

2. **Multi-Tenancy Database Patterns: Schema vs Database vs Row-Level Comparison**  
   Source: dasroot.net (2026-01-20)  
   URL: https://dasroot.net/posts/2026/01/multi-tenancy-database-patterns-schema-database-row-level/

3. **PostgreSQL row-level security patterns for multi-tenant apps**  
   Source: AppMaster (2025-03-03)  
   URL: https://appmaster.io/blog/postgresql-row-level-security-multitenant-patterns

4. **Multi-tenancy and Database-per-User Design in Postgres**  
   Source: Neon (2024-08-29)  
   URL: https://neon.tech/blog/multi-tenancy-and-database-per-user-design-in-postgres

### Rails & Django Patterns

5. **Apartment Gem Documentation** (Rails schema-per-tenant pattern)  
   - LinkedIn post: https://www.linkedin.com/posts/etika-ahuja_simple-rails-example-schema-per-tenant-activity-7379828209992990720-6zuY
   - Medium articles on Rails multi-tenancy implementation

6. **Django django-tenants Package**  
   - Schema-per-tenant pattern for Django
   - Middleware-based tenant switching

### Additional References

7. **Multi-Tenant RAG Applications With PostgreSQL**  
   Source: TigerData (2024-10-11)  
   URL: https://www.tigerdata.com/blog/building-multi-tenant-rag-applications-with-postgresql-choosing-the-right-approach

8. **Shipping multi-tenant SaaS using Postgres Row-Level Security**  
   Source: Nile (2022-07-26)  
   URL: https://www.thenile.dev/blog/multi-tenant-rls

9. **Multi-Tenant Databases with Postgres Row-Level Security**  
   Source: Midnyte City (2024-12-18)  
   URL: https://www.midnytecity.com.au/blogs/multi-tenant-databases-with-postgres-row-level-security

---

## Next Steps for Boundary

1. **Create ADR-004**: Document schema-per-tenant decision
2. **Design tenant registry**: Tables in `public` schema
3. **Design middleware**: Automatic schema switching per request
4. **Design migration strategy**: Per-tenant migration runner
5. **Prototype implementation**: Proof of concept with 2-3 tenants
6. **Performance testing**: Benchmark schema switching overhead
7. **Documentation**: Guide for users implementing multi-tenancy

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-05  
**Status**: Complete - Ready for ADR-004
