# Boundary Framework: Killer Features Roadmap

**Status**: Planning Phase  
**Created**: 2026-01-05  
**Owner**: Boundary Framework Team  
**Purpose**: Prioritize high-impact features that differentiate Boundary and accelerate enterprise adoption

---

## Executive Summary

After completing Phase 4.5 (Full-Text Search), Boundary Framework has achieved production readiness with:
- ✅ Solid architectural foundation (FC/IS pattern)
- ✅ Enterprise security (MFA, JWT, RBAC)
- ✅ Background jobs, caching, search
- ✅ API versioning & pagination
- ✅ 765 tests passing, 100% coverage

**Next Goal**: Add killer features that make Boundary irresistible to enterprise teams and differentiate from Django, Rails, Spring Boot.

---

## Priority Order

| # | Feature | Impact | Uniqueness | Enterprise | Implementation | Total |
|---|---------|--------|------------|------------|----------------|-------|
| **1** | Visual Admin Dashboard | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥 | 🔥🔥🔥🔥 | **18/20** |
| **2** | Real-Time Collaboration | 🔥🔥🔥🔥 | 🔥🔥🔥🔥 | 🔥🔥🔥 | 🔥🔥🔥 | **14/20** |
| **3** | Multi-Tenancy Framework | 🔥🔥🔥🔥🔥 | 🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥 | **15/20** |
| 4 | File Upload & Storage | 🔥🔥🔥 | 🔥 | 🔥🔥 | 🔥🔥🔥🔥 | **10/20** |
| 5 | GraphQL Support | 🔥🔥🔥 | 🔥🔥 | 🔥🔥 | 🔥🔥🔥 | **10/20** |
| 6 | Automated API Testing | 🔥🔥🔥 | 🔥🔥🔥 | 🔥🔥 | 🔥🔥 | **10/20** |

**Recommendation**: Start with **Visual Admin Dashboard** for maximum impact and market differentiation.

---

## Feature #1: Visual Admin Dashboard ⭐ TOP PRIORITY

### Why This Is THE Killer Feature

**Market Reality:**
- **Django's killer feature**: Django Admin (beloved, industry-standard)
- **Rails's killer feature**: ActiveAdmin/Administrate (20K+ stars)
- **Clojure's current state**: Nothing comparable exists

**Value Proposition:**
- 80% reduction in admin panel development time
- Every SaaS company needs admin panels
- Non-technical stakeholders can interact with system
- Immediate "wow factor" for new users

### Goals

1. **Auto-generate CRUD UI from database schema**
   - Browse, search, filter any entity
   - Inline editing with validation
   - Relationship navigation
   - Custom actions framework

2. **Enterprise Features**
   - Role-based access control (RBAC)
   - Audit log viewer
   - Bulk operations
   - Dashboard widgets

3. **Developer Experience**
   - Zero-config for basic CRUD
   - Customizable widgets
   - Theme/branding support
   - Mobile-responsive

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Layer (Shell)                       │
│  • Route handling (/admin/*)                                │
│  • Authentication/authorization                             │
│  Files: boundary/admin/shell/http.clj                      │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                  Service Layer (Shell)                      │
│  • CRUD orchestration                                       │
│  • Permission checking                                      │
│  • Action execution                                         │
│  Files: boundary/admin/shell/service.clj                   │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Schema introspection                                     │
│  • UI generation (Hiccup)                                   │
│  • Permission logic                                         │
│  Files: boundary/admin/core/schema_introspection.clj       │
│         boundary/admin/core/ui.clj                         │
│         boundary/admin/core/permissions.clj                │
└─────────────────────────────────────────────────────────────┘
```

### Deliverables

**Module Structure:**
```
src/boundary/admin/
├── core/
│   ├── schema_introspection.clj  # Read PostgreSQL schema (400 lines)
│   ├── ui.clj                    # Hiccup components (800 lines)
│   ├── permissions.clj           # RBAC logic (300 lines)
│   └── actions.clj               # Custom action framework (200 lines)
├── shell/
│   ├── service.clj               # CRUD orchestration (500 lines)
│   ├── http.clj                  # Routes (600 lines)
│   └── adapters/
│       └── postgres.clj          # Schema reader (200 lines)
├── ports.clj                     # IAdminService, ISchemaProvider (150 lines)
└── schema.clj                    # Malli schemas (150 lines)

test/boundary/admin/
├── core/
│   ├── schema_introspection_test.clj
│   ├── ui_test.clj
│   └── permissions_test.clj
└── shell/
    ├── service_test.clj
    └── http_test.clj
```

**Total Code**: ~3,000 lines production + ~1,000 lines tests

### Milestones

**Week 1: Foundation** (Jan 6-12, 2026)
- [ ] Create `boundary/admin/` module structure
- [ ] Database schema introspection (PostgreSQL)
- [ ] Core UI components (table, form, filter) in Hiccup
- [ ] Basic CRUD operations (list, view, create, update, delete)
- [ ] Auto-generated navigation menu
- [ ] Integration with existing user authentication

**Deliverables**:
- Working admin UI at `/admin`
- Can browse/edit User entity
- Unit tests for schema introspection
- Basic styling (Pico CSS)

**Week 2: Rich Features** (Jan 13-19, 2026)
- [ ] Inline editing with HTMX
- [ ] Malli validation integration
- [ ] Relationship handling (foreign keys, dropdowns)
- [ ] Search integration (leverage Phase 4.5 full-text search)
- [ ] Custom actions framework
- [ ] Bulk operations (select multiple, delete, export)

**Deliverables**:
- Inline editing working
- Can navigate relationships (e.g., User -> Items)
- Search bar on list views
- Export to CSV functionality
- Integration tests

**Week 3: Enterprise Features** (Jan 20-26, 2026)
- [ ] Role-based permissions (RBAC)
  - Define which roles can access which entities
  - Row-level permissions (edit own, edit all)
- [ ] Audit log viewer
  - Who changed what, when
  - Integration with existing audit log
- [ ] Dashboard widgets (stats, charts, recent activity)
- [ ] File upload widget (images, documents)
- [ ] Custom field renderers (markdown, JSON, dates, colors)

**Deliverables**:
- RBAC working (admin, manager, viewer roles)
- Audit log viewer at `/admin/audit`
- Dashboard at `/admin` (default page)
- File upload for user avatars
- Contract tests for permissions

**Week 4: Polish & Launch** (Jan 27 - Feb 2, 2026)
- [ ] Theme customization (colors, logo, branding)
- [ ] Mobile-responsive design
- [ ] Performance optimization (pagination, lazy loading)
- [ ] Comprehensive documentation
  - Getting started guide
  - Configuration reference
  - Customization guide
- [ ] Video tutorial: "Admin Panel in 10 Minutes"
- [ ] Blog post: "Why Clojure Finally Has Django Admin"
- [ ] Example app: E-commerce admin

**Deliverables**:
- Polished, production-ready admin UI
- Complete documentation (20KB+)
- 15-minute video tutorial
- Example application
- Blog post + marketing materials
- Performance benchmarks

### Configuration Example

```clojure
;; In resources/conf/dev/config.edn
{:boundary/admin
 {:enabled true
  :base-path "/admin"
  :title "My SaaS Admin"
  :logo "/assets/logo.png"
  
  ;; Entities to show in admin
  :entities [{:name :user
              :table "users"
              :display-name "Users"
              :icon "👤"
              :list-fields [:id :email :name :created-at]
              :search-fields [:email :name]
              :form-fields [:email :name :role]
              :actions [:approve :suspend :reset-password]}
             
             {:name :inventory/item
              :table "items"
              :display-name "Inventory Items"
              :icon "📦"
              :list-fields [:sku :name :quantity :price]
              :search-fields [:sku :name :description]
              :relationships [{:type :belongs-to
                               :entity :user
                               :field :owner-id}]}]
  
  ;; Role-based permissions
  :permissions {:admin [:all]  ; Full access
                :manager [:user :read :update
                          :inventory/item :all]
                :viewer [:user :read
                         :inventory/item :read]}
  
  ;; Custom actions
  :custom-actions {:approve {:handler 'my-app.admin.actions/approve-user
                             :label "Approve User"
                             :confirm? true
                             :bulk? true}
                   :export {:handler 'my-app.admin.actions/export-csv
                            :label "Export to CSV"
                            :bulk? true}}}}
```

### Usage Example

```clojure
;; That's it! Admin UI auto-generated at /admin

;; Navigate to http://localhost:3000/admin
;; See auto-generated UI for User and Item entities

;; Add custom action
(ns my-app.admin.actions
  (:require [boundary.admin.ports :as admin-ports]))

(defn approve-user
  "Custom action to approve a user."
  [admin-service user-ids]
  (doseq [user-id user-ids]
    (admin-ports/update-entity admin-service :user user-id {:approved true}))
  {:success true
   :message (str "Approved " (count user-ids) " users")})

;; Customize field renderer
(defn render-status-badge
  "Custom renderer for status field."
  [value]
  [:span {:class (str "badge badge-" (name value))}
   (str/upper-case (name value))])

;; Register custom renderer
(admin/register-renderer :status render-status-badge)
```

### Success Metrics

**Week 1**:
- [ ] Admin UI accessible at `/admin`
- [ ] Can browse/edit at least one entity
- [ ] 20+ unit tests passing

**Week 2**:
- [ ] Inline editing working
- [ ] Relationship navigation working
- [ ] Search integration complete
- [ ] 50+ tests passing

**Week 3**:
- [ ] RBAC enforced across all endpoints
- [ ] Audit log viewer functional
- [ ] Dashboard widgets rendering
- [ ] 80+ tests passing

**Week 4**:
- [ ] Production-ready (zero lint errors, 100% tests)
- [ ] Complete documentation published
- [ ] Video tutorial recorded
- [ ] Example app deployed
- [ ] Blog post drafted
- [ ] Performance: List 1000 items < 200ms

### Synergies with Existing Features

| Existing Feature | Integration Point |
|-----------------|-------------------|
| **Phase 4.5 (Search)** | Search bar on every list view |
| **Phase 4.4 (Pagination)** | Paginated tables with RFC 5988 links |
| **Phase 4.3 (MFA)** | Secure admin access with MFA |
| **Phase 4.1 (Jobs)** | Job monitoring dashboard widget |
| **Phase 3 (Scaffolder)** | Auto-register new entities in admin |
| **Phase 2 (Observability)** | Admin actions logged/traced |

### Technical Stack

- **Backend**: Boundary platform, PostgreSQL schema introspection
- **Frontend**: HTMX + Hiccup (no build step!)
- **Styling**: Pico CSS + custom admin theme
- **Components**: Hiccup DSL (pure functions)
- **Architecture**: FC/IS pattern throughout

### Risk Mitigation

**Risk: Too ambitious for 4 weeks**
- Mitigation: Start with minimal viable admin (Week 1), iterate
- Fallback: Defer dashboard widgets to Week 5

**Risk: Performance with large tables**
- Mitigation: Pagination from day 1, lazy loading
- Fallback: Virtual scrolling for 10K+ rows

**Risk: Limited widget library**
- Mitigation: Focus on 10 essential widgets (text, number, date, select, etc.)
- Fallback: Allow custom renderers for complex fields

**Risk: RBAC complexity**
- Mitigation: Start with simple role-based, defer row-level to Week 5
- Fallback: Admin-only mode if RBAC too complex

### Marketing Strategy

**Demo Video Script** (15 minutes):
1. **Introduction** (2 min): What is auto-admin? Why it matters?
2. **From Zero to Admin** (3 min): Fresh app → enable admin → see UI
3. **Customization** (4 min): Add custom actions, permissions, widgets
4. **Real-World Example** (4 min): E-commerce admin (products, orders, customers)
5. **Comparison** (2 min): Boundary vs Django Admin vs manual admin

**Blog Post Outline**: "Why Clojure Finally Has Django Admin"
- Problem: Building admin panels is tedious
- Solution: Auto-generated admin UI from database schema
- Demo: 10-minute walkthrough
- Architecture: How FC/IS makes this possible
- Comparison: Boundary vs Django vs Rails
- Call to Action: Try Boundary today

**Conference Talk Pitch**: "Auto-Generated UIs in Functional Programming"
- Abstract: How FC/IS enables auto-admin without magic
- Target: Clojure/conj, EuroClojure, Strange Loop
- Demo: Live coding an admin panel

---

## Feature #2: Real-Time Collaboration

### Why This Matters

**Modern UX Expectation**: Users expect live updates (notifications, presence, dashboards)

**Technical Showcase**: Demonstrates Clojure's concurrency strengths (core.async, agents, atoms)

**Enterprise Use Cases**:
- Live dashboards (admin sees changes instantly)
- Collaborative editing (multiple users editing same resource)
- Notification system (push updates)
- Live monitoring (job status, system health)

**Market Gap**:
- Phoenix has Channels (killer feature)
- Rails has ActionCable
- Clojure has libraries, no framework integration

### Goals

1. **WebSocket Infrastructure**
   - Bidirectional communication
   - Connection pooling
   - Automatic reconnection
   - Heartbeat/keepalive

2. **Server-Sent Events (SSE)**
   - Unidirectional (simpler)
   - HTTP/2 friendly
   - Automatic reconnection

3. **Channel Abstraction**
   - Pub/sub pattern
   - Presence tracking
   - Broadcast to channel
   - Private channels (user-specific)

4. **Integration Points**
   - Admin dashboard (live updates)
   - Background jobs (live status)
   - Search results (streaming)
   - Notifications

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 WebSocket/SSE Handlers (Shell)              │
│  • Connection upgrade                                       │
│  • Message serialization                                    │
│  Files: boundary/realtime/shell/websocket.clj              │
│         boundary/realtime/shell/sse.clj                    │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                    Channel Manager (Shell)                  │
│  • Connection registry                                      │
│  • Message routing                                          │
│  • Presence tracking                                        │
│  Files: boundary/realtime/shell/channels.clj               │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Message routing logic                                    │
│  • Presence calculation                                     │
│  • Channel authorization                                    │
│  Files: boundary/realtime/core/routing.clj                 │
│         boundary/realtime/core/presence.clj                │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Adapters (Distributed)                    │
│  • Redis pub/sub (multi-server)                            │
│  • In-memory (single server)                               │
│  Files: boundary/realtime/shell/adapters/redis.clj         │
└─────────────────────────────────────────────────────────────┘
```

### Deliverables

**Module Structure:**
```
src/boundary/realtime/
├── core/
│   ├── routing.clj       # Message routing logic (300 lines)
│   ├── presence.clj      # Presence tracking (200 lines)
│   └── auth.clj          # Channel authorization (150 lines)
├── shell/
│   ├── websocket.clj     # WebSocket handler (400 lines)
│   ├── sse.clj           # SSE handler (250 lines)
│   ├── channels.clj      # Channel manager (500 lines)
│   └── adapters/
│       ├── redis.clj     # Redis pub/sub (300 lines)
│       └── in_memory.clj # In-memory (200 lines)
├── ports.clj             # IWebSocketHandler, IChannelManager (200 lines)
└── schema.clj            # Message schemas (100 lines)
```

**Client Library (JavaScript)**:
```javascript
// resources/public/js/boundary-realtime.js (500 lines)
const rt = BoundaryRealtime.connect('/realtime');

rt.channel('admin:dashboard')
  .on('user:created', (data) => { /* update UI */ })
  .join();
```

**Total Code**: ~2,600 lines

### Milestones

**Week 1: Infrastructure** (Feb 3-9, 2026)
- [ ] Create `boundary/realtime/` module
- [ ] WebSocket handler (connection upgrade, send/receive)
- [ ] SSE handler (streaming responses)
- [ ] In-memory channel manager
- [ ] Basic routing logic

**Week 2: Channels & Presence** (Feb 10-16, 2026)
- [ ] Channel abstraction (join, leave, broadcast)
- [ ] Presence tracking (who's online)
- [ ] Private channels (user-specific)
- [ ] Redis adapter (distributed channels)
- [ ] Message persistence (optional)

**Week 3: Integration** (Feb 17-23, 2026)
- [ ] Admin dashboard live updates
- [ ] Job status streaming (Phase 4.1 integration)
- [ ] Search result streaming (Phase 4.5 integration)
- [ ] Notification system
- [ ] Client JavaScript library

**Week 4: Scale & Polish** (Feb 24 - Mar 2, 2026)
- [ ] Connection limit management
- [ ] Heartbeat/keepalive
- [ ] Automatic reconnection
- [ ] Performance testing (10K concurrent connections)
- [ ] Documentation + examples

### Success Metrics

- [ ] 1000+ concurrent WebSocket connections
- [ ] < 50ms message latency
- [ ] Automatic reconnection working
- [ ] Admin dashboard updates instantly
- [ ] Complete documentation

---

## Feature #3: Multi-Tenancy Framework

### Why This Matters

**SaaS Reality**: Most modern apps are multi-tenant

**Complexity**: Data isolation is hard, easy to get wrong (security nightmare)

**Enterprise Requirement**: Many enterprises won't adopt without multi-tenancy

**Market Gap**:
- Rails: acts_as_tenant (gem, not built-in)
- Django: django-tenant-schemas (not built-in)
- Phoenix: Manual schema switching
- Clojure: Nothing

### Goals

1. **Tenant Identification**
   - Subdomain (acme.myapp.com)
   - Header (X-Tenant-ID)
   - Path (/tenants/acme/...)
   - Token (JWT tenant claim)

2. **Data Isolation Strategies**
   - Schema-based (separate PostgreSQL schemas)
   - Row-level (tenant_id column + RLS)
   - Database-based (separate DB per tenant)

3. **Tenant Management**
   - Provisioning (create schema, seed data)
   - Deprovisioning (archive, delete)
   - Tenant configuration (feature flags, limits)
   - Cross-tenant operations (platform admin)

4. **Integration**
   - Tenant-aware caching
   - Tenant-aware background jobs
   - Tenant-aware search
   - Tenant-aware audit logging

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  Tenant Middleware (Shell)                  │
│  • Resolve tenant from request                              │
│  • Inject into request context                              │
│  Files: boundary/tenancy/shell/middleware.clj              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Database Context (Shell)                  │
│  • Switch PostgreSQL schema                                 │
│  • Apply RLS policies                                       │
│  Files: boundary/tenancy/shell/db_context.clj              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Tenant identification logic                              │
│  • Access control rules                                     │
│  Files: boundary/tenancy/core/resolver.clj                 │
│         boundary/tenancy/core/access.clj                   │
└─────────────────────────────────────────────────────────────┘
```

### Deliverables

**Module Structure:**
```
src/boundary/tenancy/
├── core/
│   ├── resolver.clj      # Tenant identification (300 lines)
│   ├── access.clj        # Access control (200 lines)
│   └── provisioning.clj  # Provisioning logic (250 lines)
├── shell/
│   ├── middleware.clj    # Tenant middleware (300 lines)
│   ├── db_context.clj    # DB switching (400 lines)
│   ├── service.clj       # Tenant management (500 lines)
│   └── provisioner.clj   # Provisioning executor (400 lines)
├── ports.clj             # ITenantResolver, ITenantManager (200 lines)
└── schema.clj            # Tenant schemas (150 lines)
```

**Migrations:**
```sql
-- migrations/010_add_tenant_support.sql
CREATE SCHEMA tenant_template;
-- ... create tables in template schema

-- migrations/011_enable_rls.sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_isolation ON users
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

**Total Code**: ~2,700 lines

### Milestones

**Week 1: Foundation** (Mar 3-9, 2026)
- [ ] Create `boundary/tenancy/` module
- [ ] Tenant resolver (subdomain, header, path)
- [ ] Middleware for tenant injection
- [ ] Database context switching (PostgreSQL schemas)
- [ ] Basic tenant CRUD

**Week 2: Data Isolation** (Mar 10-16, 2026)
- [ ] Schema-based isolation (create tenant schemas)
- [ ] Row-level security (RLS policies)
- [ ] Tenant-aware queries (automatic filtering)
- [ ] Cross-tenant queries (platform admin)
- [ ] Tenant configuration

**Week 3: Provisioning** (Mar 17-23, 2026)
- [ ] Tenant provisioning service
- [ ] Schema creation from template
- [ ] Seed data injection
- [ ] Deprovisioning (archive vs delete)
- [ ] Tenant migration tools

**Week 4: Integration** (Mar 24-30, 2026)
- [ ] Tenant-aware caching (namespace by tenant)
- [ ] Tenant-aware background jobs
- [ ] Tenant-aware search
- [ ] Tenant-aware audit logging
- [ ] Documentation + migration guide

### Success Metrics

- [ ] Data isolation verified (tenant A can't see tenant B data)
- [ ] Provisioning < 10 seconds
- [ ] No performance degradation with 100+ tenants
- [ ] Zero cross-tenant data leaks
- [ ] Complete documentation

---

## Feature #4: File Upload & Storage

### Goals

- Secure file uploads with validation
- Storage adapters (local, S3, GCS)
- Image processing (resize, thumbnails)
- Signed URLs for secure access
- CDN integration

### Deliverables

**Module Structure:**
```
src/boundary/storage/
├── core/
│   ├── validation.clj    # File validation (200 lines)
│   └── processing.clj    # Image processing (300 lines)
├── shell/
│   ├── service.clj       # Storage orchestration (400 lines)
│   ├── http.clj          # Upload endpoints (300 lines)
│   └── adapters/
│       ├── local.clj     # Local filesystem (200 lines)
│       ├── s3.clj        # AWS S3 (300 lines)
│       └── gcs.clj       # Google Cloud Storage (300 lines)
├── ports.clj             # IFileStorage, IImageProcessor (150 lines)
└── schema.clj            # File schemas (100 lines)
```

**Total Code**: ~2,250 lines

### Milestones

**Week 1: Foundation** (Mar 31 - Apr 6, 2026)
- [ ] Create `boundary/storage/` module
- [ ] File validation (type, size, virus scan)
- [ ] Local filesystem adapter
- [ ] S3 adapter
- [ ] Upload endpoints

**Week 2: Processing & Polish** (Apr 7-13, 2026)
- [ ] Image processing (resize, crop, thumbnails)
- [ ] Signed URLs (temporary access)
- [ ] GCS adapter
- [ ] Admin widget integration
- [ ] Documentation

### Success Metrics

- [ ] Upload, process, serve from S3
- [ ] Image resize < 500ms
- [ ] Signed URLs expire correctly
- [ ] Admin widget functional

---

## Feature #5: GraphQL Support (Optional)

### Goals

- Auto-generate GraphQL schema from entities
- DataLoader for N+1 prevention
- Subscriptions via real-time module
- GraphiQL interface

### Deliverables

**Module Structure:**
```
src/boundary/graphql/
├── core/
│   ├── schema_gen.clj    # Schema generation (400 lines)
│   └── resolvers.clj     # Resolver logic (300 lines)
├── shell/
│   ├── handler.clj       # GraphQL endpoint (300 lines)
│   └── dataloader.clj    # DataLoader (200 lines)
├── ports.clj             # IGraphQLHandler (100 lines)
└── schema.clj            # GraphQL schemas (100 lines)
```

**Total Code**: ~1,400 lines

### Milestones

**Week 1: Schema Generation** (Apr 14-20, 2026)
- [ ] Generate GraphQL schema from entities
- [ ] Basic resolvers
- [ ] GraphiQL UI

**Week 2: Optimization** (Apr 21-27, 2026)
- [ ] DataLoader implementation
- [ ] Subscriptions via realtime
- [ ] Documentation

### Success Metrics

- [ ] GraphQL parity with REST endpoints
- [ ] N+1 queries eliminated
- [ ] Subscriptions working

---

## Feature #6: Automated API Testing (Optional)

### Goals

- Property-based testing from schemas
- Fuzzing for edge cases
- Contract testing
- Load testing scenarios

### Deliverables

**Module Structure:**
```
src/boundary/testing/
├── api/
│   ├── generators.clj    # Test data generation (400 lines)
│   ├── fuzzing.clj       # Fuzzing strategies (300 lines)
│   └── load.clj          # Load testing (200 lines)
└── cli.clj               # CLI commands (200 lines)
```

**Total Code**: ~1,100 lines

### Milestones

**Week 1: Generators** (Apr 28 - May 4, 2026)
- [ ] Generate test cases from Malli schemas
- [ ] Property-based testing integration
- [ ] Fuzzing strategies

**Week 2: Integration** (May 5-11, 2026)
- [ ] Contract testing framework
- [ ] Load testing scenarios
- [ ] CI integration
- [ ] Documentation

### Success Metrics

- [ ] 50% test reduction via generation
- [ ] Find edge case bugs via fuzzing
- [ ] Load tests in CI

---

## Cross-Cutting Requirements

### Documentation

**For Each Feature:**
- [ ] Architecture overview
- [ ] Getting started guide
- [ ] Configuration reference
- [ ] API documentation
- [ ] Migration guide (if applicable)
- [ ] Troubleshooting guide
- [ ] Example applications

**Estimated**: 15-20KB per feature

### Observability

**For Each Feature:**
- [ ] Structured logging (all major operations)
- [ ] Metrics emission (latency, throughput, errors)
- [ ] Error reporting (exceptions captured)
- [ ] Distributed tracing (correlation IDs)

**Use Existing Ports:**
- `boundary.logging.ports/ILogger`
- `boundary.metrics.ports/IMetricsEmitter`
- `boundary.error-reporting.ports/IErrorReporter`

### Security

**For Each Feature:**
- [ ] RBAC integration (role-based access)
- [ ] Rate limiting (prevent abuse)
- [ ] CSRF protection (web forms)
- [ ] Input validation (Malli schemas)
- [ ] SQL injection prevention (parameterized queries)
- [ ] Secrets validation (no hardcoded secrets)

**Security Checklist:**
- [ ] OWASP Top 10 compliance
- [ ] Penetration testing
- [ ] Security headers (CSP, HSTS, etc.)
- [ ] Dependency scanning

### Developer Experience

**Scaffolder Updates:**
- [ ] `boundary scaffold admin-entity` - Register entity in admin
- [ ] `boundary scaffold realtime-channel` - Create new channel
- [ ] `boundary scaffold tenant-aware` - Make module tenant-aware
- [ ] `boundary scaffold storage-widget` - Add file upload to form

**IDE Support:**
- [ ] Update `.clj-kondo` configuration
- [ ] Add code snippets
- [ ] Update REPL helpers

---

## Implementation Timeline

### Overview (20 weeks total = 5 months)

| Phase | Feature | Duration | Start | End |
|-------|---------|----------|-------|-----|
| 5.1 | Visual Admin Dashboard | 4 weeks | Jan 6 | Feb 2 |
| 5.2 | Real-Time Collaboration | 4 weeks | Feb 3 | Mar 2 |
| 5.3 | Multi-Tenancy Framework | 4 weeks | Mar 3 | Mar 30 |
| 5.4 | File Upload & Storage | 2 weeks | Mar 31 | Apr 13 |
| 5.5 | GraphQL Support (Optional) | 2 weeks | Apr 14 | Apr 27 |
| 5.6 | API Testing (Optional) | 2 weeks | Apr 28 | May 11 |
| 5.7 | Polish & Marketing | 2 weeks | May 12 | May 25 |

**Total**: 20 weeks (5 months) to complete all killer features

### Team Requirements

**Option 1: Small Team (Recommended)**
- 1 Senior Clojure Engineer (full-time)
- 0.5 Technical Writer (documentation)
- 0.25 DevRel (marketing, examples)

**Option 2: Solo Developer**
- Extend timeline to 8-10 months
- Focus on features 1-4 (defer GraphQL/API Testing)
- Prioritize documentation as you go

---

## Success Metrics

### Phase 5.1 (Admin Dashboard)
- [ ] Admin accessible in <10 min from fresh install
- [ ] 80% dev time reduction for admin panels
- [ ] 3,000+ lines of production code
- [ ] 1,000+ lines of tests
- [ ] Complete documentation (20KB+)
- [ ] Video tutorial (15 min)
- [ ] Example app deployed

### Phase 5.2 (Real-Time)
- [ ] 1000+ concurrent connections
- [ ] < 50ms message latency
- [ ] Admin dashboard live updates working
- [ ] 2,600+ lines of production code

### Phase 5.3 (Multi-Tenancy)
- [ ] Zero cross-tenant data leaks
- [ ] Provisioning < 10 seconds
- [ ] 100+ tenants with no performance degradation
- [ ] 2,700+ lines of production code

### Phase 5.4 (File Upload)
- [ ] Upload, process, serve from S3
- [ ] Image resize < 500ms
- [ ] 2,250+ lines of production code

### Overall Phase 5 Completion
- [ ] 500+ GitHub stars
- [ ] 10+ production deployments
- [ ] 200+ Discord/community members
- [ ] 3+ conference talks accepted
- [ ] Competitive with Django/Rails feature set

---

## Risk Mitigation

### Technical Risks

**Risk: Features too ambitious (scope creep)**
- **Mitigation**: Strict weekly milestones, defer non-essential features
- **Fallback**: Ship MVPs, iterate based on feedback

**Risk: Performance issues at scale**
- **Mitigation**: Benchmarks per feature, load testing before GA
- **Fallback**: Redis/caching opt-in, document scaling strategies

**Risk: Integration complexity**
- **Mitigation**: Clear interfaces (ports), modular design
- **Fallback**: Features work independently, integration optional

### Market Risks

**Risk: Low adoption (no one uses it)**
- **Mitigation**: Build in public, gather feedback early, iterate
- **Fallback**: Internal tool, consultant productivity framework

**Risk: Competition releases similar features**
- **Mitigation**: Ship fast, market aggressively, focus on DX
- **Fallback**: Double down on Clojure-specific advantages (REPL, FC/IS)

### Resource Risks

**Risk: Limited development time**
- **Mitigation**: Focus on top 3 features (Admin, Real-Time, Multi-Tenancy)
- **Fallback**: Defer GraphQL/API Testing, prioritize core differentiators

**Risk: Documentation debt**
- **Mitigation**: Write docs during development, not after
- **Fallback**: Community-contributed docs, video tutorials instead of text

---

## Dependencies

### Infrastructure

**Required:**
- PostgreSQL 12+ (schema support, RLS, full-text search)
- Redis 6+ (distributed caching, channels, rate limiting)

**Optional:**
- AWS S3 / Google Cloud Storage (file storage)
- Datadog / Sentry (observability providers)

### External Libraries

**New Dependencies:**
- WebSocket support: `http-kit` or `aleph`
- Image processing: `mikera/imagez` or Java AWT
- S3 client: `com.amazonaws/aws-java-sdk-s3`
- GraphQL (optional): `com.walmartlabs/lacinia`

**Estimated**: 5-10 new dependencies across all features

---

## Marketing Strategy

### Content Plan

**Blog Posts** (1 per feature):
1. "Why Clojure Finally Has Django Admin" (Admin Dashboard)
2. "Real-Time Features Without JavaScript" (Real-Time)
3. "Multi-Tenancy Done Right" (Multi-Tenancy)
4. "From Zero to Production in 10 Minutes" (Overall DX)

**Video Tutorials** (15 min each):
1. Admin Dashboard Walkthrough
2. Building a Real-Time Dashboard
3. SaaS Multi-Tenancy Setup
4. Complete E-Commerce Example

**Conference Talks**:
1. Clojure/conj: "Auto-Generated UIs in Functional Programming"
2. EuroClojure: "Building SaaS with Clojure"
3. Strange Loop: "Functional Core, Imperative Shell at Scale"

### Community Building

**Channels:**
- Discord server: Boundary Framework Community
- Twitter/X: @BoundaryFramework
- Reddit: r/Clojure (weekly updates)
- Hacker News: Launch announcements

**Engagement:**
- Weekly office hours (live Q&A)
- Monthly livestream (feature development)
- Contributor recognition (Hall of Fame)
- Swag store (t-shirts, stickers)

### Launch Strategy

**Soft Launch** (After Admin Dashboard):
- Blog post + HN announcement
- Demo video on YouTube
- Discord invite
- Early adopter program (10 companies)

**1.0 Launch** (After Top 3 Features):
- Major announcement (HN, Reddit, Twitter)
- Press release
- Conference talks
- Partnership announcements

**Success Metrics:**
- 500+ GitHub stars in 3 months
- 10+ production deployments in 6 months
- 200+ community members in 6 months

---

## Competitive Positioning

### After Killer Features

| Capability | Boundary | Django | Rails | Spring Boot | Phoenix |
|------------|----------|--------|-------|-------------|---------|
| **FC/IS Architecture** | ✅ Enforced | ❌ | ❌ | ❌ | ❌ |
| **Module-Centric** | ✅ | ❌ | ❌ | ❌ | ✅ |
| **Auto-Admin UI** | ✅ **NEW** | ✅ | ~⚠️ (gems) | ❌ | ❌ |
| **Real-Time** | ✅ **NEW** | ~⚠️ (channels) | ~⚠️ (cable) | ~⚠️ | ✅ |
| **Multi-Tenancy** | ✅ **NEW** | ~⚠️ (3rd party) | ~⚠️ (gems) | ❌ | ~⚠️ |
| **Background Jobs** | ✅ | ✅ | ✅ | ❌ | ✅ |
| **Full-Text Search** | ✅ | ~⚠️ | ~⚠️ | ❌ | ✅ |
| **MFA** | ✅ | ~⚠️ | ~⚠️ | ❌ | ~⚠️ |
| **Scaffolding** | ✅ | ✅ | ✅ | ❌ | ✅ |

**Legend:**
- ✅ Built-in, production-ready
- ~⚠️ Requires 3rd-party library or manual setup
- ❌ Not available or requires significant development

### Value Proposition

> **Boundary is the first production-ready Clojure framework with auto-generated admin UI, real-time collaboration, and multi-tenancy, enforcing Functional Core / Imperative Shell architecture for highly testable, modular systems that scale from prototype to production with confidence.**

### Taglines

- "Build admin panels in 10 minutes, not 10 days."
- "Real-time by default, multi-tenant ready."
- "Clojure's answer to Django, Rails, and Phoenix."
- "Production-ready functional programming."

---

## Next Steps

### Immediate (This Week)

1. **Review & Approve**: Confirm Visual Admin Dashboard as Phase 5.1
2. **Create Design Doc**: Detailed technical design for admin module
3. **Set Up Tasks**: Break Week 1 into 20-25 small tasks
4. **Reserve Resources**: AWS for demo deployment, domain names

### Week 1 (Jan 6-12, 2026)

1. **Start Development**: Create `boundary/admin/` module
2. **Schema Introspection**: PostgreSQL schema reading
3. **Basic UI Components**: Table, form, navigation
4. **Integration**: Wire into existing system

### Marketing Prep

1. **Reserve Domains**:
   - `boundary-admin-demo.com`
   - `boundary-framework.org`

2. **Social Media**:
   - Create @BoundaryFramework Twitter
   - Set up Discord server
   - Create YouTube channel

3. **Content Pipeline**:
   - Draft blog post outline
   - Script video tutorial
   - Prepare conference talk abstracts

---

## Conclusion

**Visual Admin Dashboard is the clear priority** for Phase 5.1:
- Maximum market impact (Django Admin killer)
- Immediate visual wow factor
- Synergizes with all existing features
- Unique in Clojure ecosystem
- 4 weeks to production-ready

**Following with Real-Time and Multi-Tenancy** positions Boundary as:
- Complete enterprise framework
- Competitive with Django/Rails/Phoenix
- Clojure's best-in-class solution for SaaS

**Timeline**: 5 months to feature-complete, market-leading framework

**Go/No-Go Decision Point**: After Admin Dashboard (Week 4)
- If adoption strong → Proceed with Real-Time
- If adoption weak → Pivot to consulting/internal tool
- If DX poor → Iterate on admin, defer other features

---

**Ready to start Phase 5.1: Visual Admin Dashboard!** 🚀

**Next Action**: Create `docs/PHASE5_1_ADMIN_DASHBOARD_DESIGN.md` with detailed technical design.
