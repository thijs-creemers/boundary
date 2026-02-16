# boundary/platform

[![Status](https://img.shields.io/badge/status-in%20development-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()

Core infrastructure for web applications: database, HTTP routing, pagination, search, and system lifecycle management.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {io.github.thijs-creemers/boundary-platform {:mvn/version "1.0.0-alpha"}
        ;; Choose your database driver
        org.postgresql/postgresql {:mvn/version "42.7.8"}}}
```

**Leiningen**:
```clojure
[io.github.thijs-creemers/boundary-platform "1.0.0-alpha"]
[org.postgresql/postgresql "42.7.8"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Multi-Database Support** | SQLite, PostgreSQL, MySQL, H2 with unified API |
| **HTTP Routing** | Reitit-based routing with interceptors |
| **Multi-Tenancy** | Tenant resolution and schema switching middleware |
| **API Versioning** | Built-in versioning support for REST APIs |
| **Pagination** | Offset and cursor-based pagination |
| **Search** | Full-text search with ranking and filtering |
| **Migrations** | Database migration management with Migratus |
| **System Lifecycle** | Integrant-based component lifecycle |
| **Module System** | Dynamic module registration and discovery |
| **Configuration** | Aero-based environment configuration |

## Requirements

- Clojure 1.12+
- boundary/core
- boundary/observability
- Database driver (PostgreSQL, SQLite, MySQL, or H2)

## Database Support

| Database | Driver | Status |
|----------|--------|--------|
| PostgreSQL | `org.postgresql/postgresql` | Production ready |
| SQLite | `org.xerial/sqlite-jdbc` | Development/testing |
| MySQL | `com.mysql/mysql-connector-j` | Production ready |
| H2 | `com.h2database/h2` | Testing only |

## Quick Start

### System Startup

```clojure
(ns myapp.main
  (:require [boundary.platform.system.wiring :as wiring]
            [boundary.config :as config]))

(defn -main [& args]
  (let [cfg (config/load-config "production")
        system (wiring/start! cfg)]
    (println "System started on port" (get-in system [:boundary/http :port]))
    system))
```

### HTTP Routes

```clojure
(ns myapp.routes
  (:require [boundary.platform.ports.http :as http]))

;; Define normalized routes
(def routes
  [{:path "/api/users"
    :methods {:get {:handler 'myapp.handlers/list-users
                    :summary "List all users"
                    :interceptors ['auth/require-auth]}
              :post {:handler 'myapp.handlers/create-user
                     :summary "Create user"}}}
   {:path "/api/users/:id"
    :methods {:get {:handler 'myapp.handlers/get-user}
              :put {:handler 'myapp.handlers/update-user}
              :delete {:handler 'myapp.handlers/delete-user}}}])
```

### Database Operations

```clojure
(ns myapp.persistence
  (:require [boundary.platform.shell.adapters.database.core :as db]))

;; Query with connection pool
(defn find-user-by-id [db-ctx user-id]
  (db/execute-one db-ctx
    ["SELECT * FROM users WHERE id = ?" user-id]))

;; Transaction support
(defn transfer-funds [db-ctx from-id to-id amount]
  (db/with-transaction [tx db-ctx]
    (db/execute! tx ["UPDATE accounts SET balance = balance - ? WHERE id = ?" amount from-id])
    (db/execute! tx ["UPDATE accounts SET balance = balance + ? WHERE id = ?" amount to-id])))
```

### Pagination

```clojure
(ns myapp.handlers
  (:require [boundary.platform.core.pagination :as pagination]))

;; Offset pagination
(defn list-users [request]
  (let [params (pagination/parse-offset-params request {:default-limit 20 :max-limit 100})
        result (user-service/list-users params)]
    (pagination/wrap-offset-response result params)))

;; Cursor pagination  
(defn list-events [request]
  (let [params (pagination/parse-cursor-params request)
        result (event-service/list-events params)]
    (pagination/wrap-cursor-response result params)))
```

### Search

```clojure
(ns myapp.search
  (:require [boundary.platform.core.search :as search]))

;; Full-text search with ranking
(defn search-products [db-ctx query]
  (search/full-text-search db-ctx
    {:table :products
     :fields [:name :description]
     :query query
     :limit 20}))
```

### Multi-Tenancy

#### Middleware Integration

The platform includes production-ready multi-tenant middleware that automatically resolves tenants and switches database schemas:

```clojure
(ns myapp.routes
  (:require [boundary.platform.shell.interfaces.http.tenant-middleware :as tenant-mw]))

;; Option 1: Combined middleware (recommended)
(def handler
  (-> routes-handler
      (tenant-mw/wrap-multi-tenant tenant-service db-context 
                                   {:require-tenant? true})))

;; Option 2: Separate middleware for finer control
(def handler
  (-> routes-handler
      (tenant-mw/wrap-tenant-resolution tenant-service {:require-tenant? false})
      (tenant-mw/wrap-tenant-schema db-context)))

;; Tenant resolution strategies (automatic):
;; 1. Subdomain: acme-corp.myapp.com → tenant with slug "acme-corp"
;; 2. JWT claims: {:identity {:tenant-slug "acme-corp"}}
;; 3. HTTP headers: X-Tenant-Slug or X-Tenant-Id

;; Access resolved tenant in handlers:
(defn my-handler [request]
  (let [tenant (:tenant request)]
    ;; Queries automatically use tenant schema
    {:status 200 :body {:tenant-name (:name tenant)}}))
```

#### Automatic HTTP Integration

When using the Boundary framework, tenant middleware is automatically integrated into the HTTP pipeline if `tenant-service` is available:

```clojure
;; In config.edn - tenant module is automatically wired
{:boundary/tenant-repository {...}
 :boundary/tenant-service {...}
 :boundary/http-handler
 {:tenant-service #ig/ref :boundary/tenant-service  ; Auto-injects middleware
  :db-context #ig/ref :boundary/db-context}}
```

The middleware is automatically added to the HTTP handler during system initialization, ensuring all requests pass through tenant resolution before reaching business logic.

#### Tenant Resolution Flow

```
1. HTTP Request arrives (e.g., https://acme-corp.myapp.com/api/users)
2. Tenant middleware extracts "acme-corp" from subdomain
3. Tenant service looks up tenant by slug
4. If PostgreSQL: Execute SET search_path TO tenant_acme_corp
5. Request continues with :tenant in request map
6. All database queries automatically use tenant schema
7. Response returned
```

#### Caching

Tenant lookups are cached for 1 hour (TTL: 3600000ms) to minimize database queries. Cache is stored in-memory and cleared when tenant is updated.

#### Error Handling

- **Tenant not found**: Returns 404 if `:require-tenant? true`, continues if `false`
- **No tenant identifier**: Returns 404 if `:require-tenant? true`, continues if `false`
- **Schema switch failure**: Logs error and continues (connection remains in previous schema)

#### PostgreSQL Schema Switching

For multi-tenant PostgreSQL setups, the middleware automatically switches schemas using `SET search_path`:

```sql
-- Before request: search_path = public
-- After middleware: search_path = tenant_acme_corp
-- All queries now use tenant_acme_corp schema
```

**Important**: Schema switching is per-connection (session-level). Each HTTP request gets an isolated database connection from the pool, ensuring tenant isolation.

#### Testing Middleware

See `libs/platform/test/boundary/platform/shell/interfaces/http/tenant_middleware_test.clj` for comprehensive test examples covering:
- Subdomain, JWT, and header resolution
- Priority handling (subdomain > JWT > headers)
- Caching behavior
- Schema switching
- Error scenarios

#### Tenant Management API

The platform automatically provides a REST API for tenant management when the tenant module is included:

**Endpoints** (under `/api/v1/tenants`):
```bash
# List all tenants (with filtering and pagination)
GET /api/v1/tenants?limit=20&offset=0&status=active

# Create new tenant
POST /api/v1/tenants
Content-Type: application/json
{"name": "Acme Corp", "slug": "acme-corp", "status": "active"}

# Get tenant by ID
GET /api/v1/tenants/:id

# Update tenant
PUT /api/v1/tenants/:id
Content-Type: application/json
{"name": "Acme Corporation", "status": "active"}

# Delete tenant (soft delete)
DELETE /api/v1/tenants/:id

# Suspend tenant (prevents access)
POST /api/v1/tenants/:id/suspend

# Activate tenant
POST /api/v1/tenants/:id/activate

# Provision tenant schema (PostgreSQL only)
POST /api/v1/tenants/:id/provision
```

All tenant endpoints return JSON responses and include proper error handling (400, 404, 500 status codes).
```

### Configuration

```clojure
;; resources/conf/prod/config.edn
{:boundary/db-context
 {:adapter :postgresql
  :host #env DB_HOST
  :port #env [DB_PORT :int 5432]
  :database #env DB_NAME
  :username #env DB_USER
  :password #env DB_PASSWORD
  :pool-size 10}

 :boundary/http
 {:port #env [PORT :int 3000]
  :host "0.0.0.0"}

 :boundary/modules
 [:boundary.user.shell.module-wiring
  :boundary.admin.shell.module-wiring]}
```

## Module Structure

```
src/boundary/platform/
├── core/
│   ├── pagination.clj       # Pagination utilities
│   ├── search.clj           # Search utilities
│   └── api-versioning.clj   # API version handling
├── ports/
│   ├── http.clj             # HTTP port protocol
│   └── database.clj         # Database port protocol
├── shell/
│   ├── adapters/
│   │   ├── database/        # Database adapters (SQLite, PG, MySQL)
│   │   └── http/            # HTTP server adapter
│   └── interceptors/        # HTTP interceptors
└── system/
    ├── wiring.clj           # Integrant system config
    └── modules.clj          # Module registration
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `boundary/observability` | 0.1.0 | Logging, metrics |
| `next.jdbc` | 1.3.1086 | Database access |
| `honeysql` | 2.7.1364 | SQL generation |
| `HikariCP` | 7.0.2 | Connection pooling |
| `migratus` | 1.6.4 | Database migrations |
| `reitit` | 0.9.2 | HTTP routing |
| `ring` | 1.15.3 | HTTP abstractions |
| `integrant` | 1.0.1 | System lifecycle |
| `aero` | 1.1.6 | Configuration |

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│       user, admin, storage, etc.        │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│           boundary/platform             │
│   (database, HTTP, config, modules)     │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│   boundary/observability + boundary/core│
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/platform
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test

# Run migrations
clojure -M:migrate migrate
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
