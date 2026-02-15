# Changelog

All notable changes to the Boundary Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha] - 2026-02-14

### 🎉 Initial Release

The first production-ready release of the Boundary Framework - a batteries-included web framework for Clojure that brings Django's productivity and Rails' conventions with functional programming rigor.

### Architecture

#### Functional Core / Imperative Shell (FC/IS)
- **Pure business logic** in `core/` namespaces (no side effects)
- **I/O and side effects** in `shell/` namespaces
- **Protocol definitions** in `ports.clj` for dependency injection
- **Consistent module structure** across all libraries

#### Library Organization (Monorepo)
- **13 independently publishable libraries** via Clojars
- **Modular design** - use only what you need
- **Zero lock-in** - each library is a standard deps.edn dependency

### Core Libraries

#### `boundary-core` (0.1.0)
Foundation library with essential utilities:
- **Validation**: Malli-based schema validation with human-readable error messages
- **Interceptors**: Declarative cross-cutting concerns
- **Utilities**: Case conversion (kebab-case ↔ snake_case), type conversion, PII redaction
- **Feature flags**: Runtime feature toggles

#### `boundary-observability` (0.1.0)
Multi-provider observability infrastructure:
- **Logging**: Structured logging with Datadog and stdout adapters
- **Metrics**: Counter, gauge, histogram, summary (Datadog StatsD protocol)
- **Error reporting**: Sentry integration with PII redaction
- **Audit logging**: Security and compliance event tracking
- **Interceptor pattern**: Automatic breadcrumbs, logging, metrics (50% code reduction)

#### `boundary-platform` (0.1.0)
HTTP and database infrastructure:
- **HTTP server**: Jetty-based with Integrant lifecycle
- **Routing**: Reitit with normalized route format
- **Database**: HikariCP connection pooling, next.jdbc integration
- **Migrations**: Flyway-based schema migrations
- **CLI**: Command-line interface utilities
- **HTTP interceptors**: Auth, rate limiting, audit (declarative)

#### `boundary-user` (0.1.0)
Authentication and authorization:
- **JWT authentication**: Secure token-based auth
- **Password security**: bcrypt hashing with configurable rounds
- **Multi-Factor Authentication (MFA)**: TOTP-based 2FA (production-ready)
- **Role-based access control (RBAC)**: Fine-grained permissions
- **User management**: CRUD operations with soft delete
- **Account security**: Login attempt tracking, account lockout (5 failures = 15min lockout)

#### `boundary-admin` (0.1.0)
Auto-generated CRUD admin interface (Django Admin for Clojure):
- **Schema introspection**: Auto-detect entity config from database schema
- **Zero-config CRUD**: Create, read, update, delete with no boilerplate
- **Search and filtering**: Full-text search across configurable fields
- **Pagination**: Offset-based with page size control
- **Sorting**: Multi-column sorting (client-side)
- **Field widgets**: Auto-inferred form inputs (text, email, checkbox, select, textarea, date, datetime)
- **Field grouping**: Organize forms with collapsible sections
- **Soft delete support**: Respect `deleted_at` columns
- **Permissions**: Role-based access (admin-only by default, Week 2+ entity-level permissions)
- **HTMX-powered**: Server-side rendering with progressive enhancement
- **Cyberpunk Professionalism UI**: Indigo (#4f46e5) + Lime (#65a30d), Geist fonts, dark mode

#### `boundary-storage` (0.1.0)
File storage abstraction:
- **Local storage**: File system-based storage
- **S3 storage**: Amazon S3 integration (not included in 1.0.0)
- **Validation**: File size, content type, extension validation
- **Security**: Filename sanitization, path traversal prevention
- **Signed URLs**: Temporary access links

#### `boundary-scaffolder` (0.1.0)
Production-ready module generator:
- **Complete module generation**: 9 source files (core, shell, ports, schema, wiring)
- **Test generation**: 3 test files (unit, integration, contract)
- **Migration generation**: 1 Flyway migration file
- **FC/IS architecture**: Zero linting errors, follows all conventions
- **Entity support**: Multi-field entities with types (string, integer, decimal, boolean, text, date, datetime, uuid, json)
- **Field constraints**: Required, unique, indexed

#### `boundary-cache` (0.1.0)
Distributed caching:
- **Redis adapter**: Production-ready caching
- **In-memory adapter**: Development and testing
- **TTL support**: Automatic expiration
- **Tenant scoping**: Multi-tenant cache isolation
- **Atomic operations**: Thread-safe cache access

#### `boundary-jobs` (0.1.0)
Background job processing:
- **In-memory queue**: Development and testing (Redis adapter planned)
- **Job lifecycle**: Enqueue, dequeue, retry, dead letter queue
- **Tenant context**: Multi-tenant job isolation
- **Priority queues**: High, normal, low priority
- **Scheduled jobs**: Future execution with `run-at` timestamp
- **Worker pool**: Parallel job processing with configurable concurrency
- **Retry logic**: Exponential backoff (1s, 2s, 4s, 8s, 16s)

#### `boundary-realtime` (0.1.0)
WebSocket-based real-time communication:
- **JWT authentication**: Secure WebSocket connections via boundary/user
- **Point-to-point messaging**: Send to specific user across all devices
- **Broadcast messaging**: Send to all connections
- **Role-based routing**: Send to users with specific role
- **Topic-based pub/sub**: Subscribe to arbitrary topics
- **Connection registry**: Track active WebSocket connections
- **Production-ready**: Phoenix Channels for Clojure

#### `boundary-tenant` (0.1.0)
Multi-tenancy infrastructure:
- **Tenant management**: CRUD operations for tenant entities
- **PostgreSQL schema isolation**: Per-tenant database schemas
- **Tenant context**: Thread-local tenant resolution
- **Job integration**: Tenant-scoped background jobs
- **Cache integration**: Tenant-scoped caching
- **Lifecycle**: Create, provision, suspend, activate, delete

#### `boundary-email` (0.1.0)
Email infrastructure:
- **SMTP adapter**: Production-ready email sending
- **Email preparation**: Validation, header formatting, recipient normalization
- **Async support**: Non-blocking email delivery
- **Attachment support**: File attachments via multipart/mixed

#### `boundary-external` (0.1.0) - **In Development**
External service adapters:
- **Skeleton implementation**: Not production-ready
- **Week 2+ roadmap**: HTTP client, API adapters, webhooks

### Features

#### Auto-CRUD Admin Interface
- **Django Admin for Clojure**: Auto-generated CRUD UIs from database schema
- **Zero boilerplate**: No manual form definitions required
- **Schema introspection**: Automatically detects entity structure, primary keys, soft delete
- **Customizable**: Override auto-detected config with manual settings
- **Field ordering**: Control form field display order via `:field-order`
- **Field grouping**: Organize forms into collapsible sections via `:field-groups`
- **Widget inference**: Smart form inputs based on field names and types
- **Relationship detection (Week 2+)**: Foreign key relationships, belongs-to, has-many

#### Multi-Factor Authentication (MFA)
- **TOTP-based**: RFC 6238 compliant Time-based One-Time Passwords
- **QR code generation**: Easy mobile app pairing
- **Backup codes**: 10 single-use recovery codes per user
- **Grace period**: 7-day enrollment window after setup
- **Login flow**: Email/password + TOTP code
- **API endpoints**: `/api/auth/mfa/setup`, `/api/auth/mfa/enable`, `/api/auth/mfa/verify`
- **Status**: ✅ Production Ready

#### HTTP Interceptors
- **Declarative pattern**: Auth, rate limiting, audit as route metadata
- **Three phases**: `:enter` (request), `:leave` (response), `:error` (exception)
- **Built-in interceptors**: Request logging, metrics, error reporting, correlation IDs
- **Composable**: Stack multiple interceptors per route
- **Example**:
  ```clojure
  {:path "/api/admin"
   :methods {:post {:handler 'handlers/create-resource
                    :interceptors ['auth/require-admin 'audit/log-action]
                    :summary "Create admin resource"}}}
  ```

#### Observability Interceptor Pattern
- **Multi-layer**: Service layer + persistence layer
- **Automatic**: Logging, metrics, error reporting, breadcrumbs
- **50% code reduction**: Eliminates boilerplate in 31/31 methods (user module)
- **Consistent error handling**: Standardized across all operations
- **Example**:
  ```clojure
  (defn create-user [this user-data]
    (service-interceptors/execute-service-operation
     :create-user
     {:user-data user-data}
     (fn [{:keys [params]}]
       ;; Business logic here - observability automatic
       (let [user (user-core/prepare-user (:user-data params))]
         (.create-user repository user)))))
  ```

#### API Pagination
- **Offset-based**: `limit` and `offset` parameters
- **RFC 5988 Link headers**: `first`, `prev`, `next`, `last` relations
- **Cursor-based (Week 2+)**: Planned for large datasets

#### Configuration Management
- **Aero-based**: Environment-specific profiles (`dev`, `test`, `prod`)
- **`#include` support**: Modular config files per module
- **Environment variables**: Override via `BND_ENV`
- **Example**: Admin entity configs in `resources/conf/{env}/admin/{module}.edn`

#### Database Support
- **Development**: SQLite (zero-config)
- **Testing**: H2 in-memory (via `:test` alias)
- **Production**: PostgreSQL (with schema isolation for multi-tenancy)
- **Migrations**: Flyway-based with `clojure -M:migrate up`

### Documentation

#### Comprehensive Documentation Site
- **Hugo-powered**: Static site generator with AsciiDoc support
- **Content**:
  - **Architecture Decision Records (ADRs)**: 8 documents
  - **Architecture guides**: 18 documents (FC/IS, ports/adapters, module structure)
  - **User guides**: 23 documents (authentication, admin, storage, MFA)
  - **API reference**: Complete API documentation
  - **Examples**: 5 code examples
  - **Getting started**: 6 onboarding guides
- **Deployed**: GitHub Pages at `https://thijs-creemers.github.io/boundary/`
- **Local dev**: `hugo server` in `docs-site/` directory

#### Developer Resources
- **AGENTS.md**: Complete developer guide (commands, patterns, conventions, troubleshooting)
- **Interactive Cheat Sheet**: `docs/cheatsheet.html` with client-side search, copy-to-clipboard
- **README.md**: Elevator pitches for developers (148 words) and management (94 words)
- **Scaffolder README**: Complete module generation workflow

#### Key Documentation Files
- **Architecture guides**: FC/IS patterns, design decisions
- **MFA Setup Guide**: Multi-factor authentication integration
- **API Pagination**: Offset and cursor pagination
- **Observability Integration**: Custom adapters, configuration
- **HTTP Interceptors**: Technical specification (ADR-010)
- **PRD**: Product vision and requirements

### Naming Conventions

#### ✅ ALWAYS Use kebab-case Internally
- **All Clojure code**: `:password-hash`, `:created-at`
- **Database (at boundary only)**: `password_hash`, `created_at`
- **API (at boundary only)**: `passwordHash`, `createdAt`
- **Conversion utilities**: `snake-case->kebab-case-map`, `kebab-case->snake-case-map`

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`. This convention prevents such mismatches.

### Testing

#### Comprehensive Test Suite
- **Test types**:
  - **Unit tests**: Pure functions, no mocks (`:unit` metadata)
  - **Integration tests**: Service with mocked deps (`:integration` metadata)
  - **Contract tests**: Adapters with real DB (`:contract` metadata)
- **Test commands**:
  ```bash
  clojure -M:test:db/h2                    # All tests
  clojure -M:test:db/h2 :core              # Core library
  clojure -M:test:db/h2 --focus-meta :unit # Unit tests only
  clojure -M:test:db/h2 --watch :core      # Watch mode
  ```
- **Coverage**: ~90-95% docstring coverage, comprehensive test coverage

#### Validation Snapshot Testing
- **Graph generation**: Visualize validation rules
- **Coverage reports**: Per-module validation coverage
- **Commands**:
  ```bash
  clojure -M:repl-clj <<'EOF'
  (require '[boundary.shared.tools.validation.repl :as v])
  (spit "build/validation-user.dot" (v/rules->dot {:modules #{:user}}))
  (System/exit 0)
  EOF
  dot -Tpng build/validation-user.dot -o docs/diagrams/validation-user.png
  ```

### Design System

#### Cyberpunk Professionalism
- **Primary color**: Indigo #4f46e5 (5.2:1 contrast on white ✅ WCAG AA)
- **Accent color**: Lime #65a30d (4.6:1 contrast ✅ WCAG AA)
- **Typography**: Geist font family (SIL Open Font License, loaded via jsDelivr CDN)
- **Dark mode**: Gray-12 #030712 base with neon glows
- **Design tokens**: Open Props CSS (`resources/public/css/tokens-openprops.css`)
- **Status colors**: All WCAG AA compliant

#### UI Technologies
- **Hiccup**: Server-side HTML generation (no build step)
- **HTMX**: Progressive enhancement for dynamic interactions
- **Pico CSS**: Base framework
- **Lucide Icons**: Icon system (50+ icons)

### Publishing Infrastructure

#### GitHub Actions Workflow
- **File**: `.github/workflows/publish.yml` (304 lines)
- **Triggers**: Manual dispatch + git tag `v*`
- **Libraries published**: 12 libraries in dependency order
- **Version strategy**: Lockstep versioning (all libraries at 1.0.0)
- **Status**: ✅ Ready (blocked on GitHub Secrets configuration)

#### Clojars Publishing
- **Organization**: `io.github.thijs-creemers`
- **Credentials**: Username `thijs-creemers` (password via GitHub Secrets)
- **Libraries**:
  - `boundary-core` → `io.github.thijs-creemers/boundary-core`
  - `boundary-observability` → `io.github.thijs-creemers/boundary-observability`
  - `boundary-platform` → `io.github.thijs-creemers/boundary-platform`
  - `boundary-user` → `io.github.thijs-creemers/boundary-user`
  - `boundary-admin` → `io.github.thijs-creemers/boundary-admin`
  - `boundary-storage` → `io.github.thijs-creemers/boundary-storage`
  - `boundary-scaffolder` → `io.github.thijs-creemers/boundary-scaffolder`
  - `boundary-cache` → `io.github.thijs-creemers/boundary-cache`
  - `boundary-jobs` → `io.github.thijs-creemers/boundary-jobs`
  - `boundary-tenant` → `io.github.thijs-creemers/boundary-tenant`
  - `boundary-email` → `io.github.thijs-creemers/boundary-email`
  - `boundary-external` → `io.github.thijs-creemers/boundary-external` (skeleton, not production-ready)

### Quick Start

#### Try Boundary (Recommended)
Use the [boundary-starter](https://github.com/thijs-creemers/boundary-starter) template:
```bash
git clone https://github.com/thijs-creemers/boundary-starter
cd boundary-starter
export JWT_SECRET="change-me-dev-secret-min-32-chars"
export BND_ENV="development"
clojure -M:repl-clj
```

In REPL:
```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)  ;; Visit http://localhost:3000
```

**What you get**:
- ✅ SQLite database (zero-config)
- ✅ HTTP server on port 3000
- ✅ Complete Integrant system
- ✅ REPL-driven development
- ✅ Production-ready Dockerfile

#### Using Individual Libraries
```clojure
;; deps.edn
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-platform {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-user {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-admin {:mvn/version "1.0.0"}}}
```

### Deployment

#### Standalone JAR
```bash
clojure -T:build clean && clojure -T:build uber
java -jar target/boundary-*.jar server
```

#### Docker
Use provided `Dockerfile` in boundary-starter template.

#### Environment Variables
```bash
export JWT_SECRET="production-secret-min-32-chars"
export BND_ENV="production"
export DB_PASSWORD="secure_password"
export DATABASE_URL="jdbc:postgresql://localhost:5432/boundary"
```

### Known Issues and Limitations

#### Week 1 Limitations (To be addressed in Week 2+)
- **Admin permissions**: Entity-level and field-level permissions not yet implemented (admin-only)
- **Admin relationships**: Foreign key relationships not auto-detected
- **Composite primary keys**: Not fully supported in admin interface
- **Denylist mode**: Entity discovery only supports allowlist mode
- **Cursor-based pagination**: Not yet implemented (offset-based only)
- **Redis job queue**: In-memory only (Redis adapter planned)
- **External library**: Skeleton implementation, not production-ready

#### Pre-existing LSP Errors (Not Critical)
- **tenant/provisioning.clj**: Unresolved symbol `tx` (15 occurrences)
- **user/user_property_test.clj**: Unresolved test function symbols (17 occurrences)
- **platform/core_test.clj**: Unresolved symbol `tx-ctx` (5 occurrences)

These are false positives from clj-kondo's static analysis and do not affect runtime behavior.

#### Linting Warnings (Non-Critical)
- **Redundant `let` expressions**: 3 warnings in test files (cosmetic issue)

### Migration Guide

#### Not Applicable (First Release)
This is the initial 1.0.0 release. No migration from previous versions.

### Dependencies

#### Key Libraries
- **Clojure**: 1.12.0
- **Integrant**: 0.13.2 (lifecycle management)
- **Aero**: 1.1.6 (configuration)
- **Malli**: 0.16.4 (validation)
- **Reitit**: 0.7.2 (routing)
- **next.jdbc**: 1.3.955 (database)
- **HikariCP**: 6.2.1 (connection pooling)
- **Flyway**: 11.1.0 (migrations)
- **buddy**: 2.0.0 (authentication, JWT)
- **bcrypt**: 0.4.1 (password hashing)

#### Database Drivers
- **H2**: 2.3.232 (testing)
- **PostgreSQL**: 42.7.4 (production)
- **SQLite**: 3.47.2.0 (development)

### Contributors

- **Thijs Creemers** ([@thijs-creemers](https://github.com/thijs-creemers)) - Creator and maintainer

### License

Copyright 2024-2025 Thijs Creemers. All rights reserved.

### Acknowledgments

#### Inspirations
- **Django** (Python): Admin interface, conventions over configuration
- **Ruby on Rails**: Rapid development, batteries-included philosophy
- **Spring Boot** (Java): Production-ready infrastructure
- **Luminus** (Clojure): Web development patterns (not compared, superseded by Boundary)
- **Kit** (Clojure): Module system (not compared, superseded by Boundary)

#### Design Patterns
- **Functional Core / Imperative Shell**: Gary Bernhardt's "Boundaries" talk
- **Ports and Adapters**: Alistair Cockburn's Hexagonal Architecture
- **Problem Details (RFC 7807)**: HTTP API error responses

### Roadmap

#### Week 2+ Features (Post-1.0.0)
- **Admin enhancements**:
  - Entity-level permissions (custom per-entity access rules)
  - Field-level permissions (hide/show fields based on user)
  - Record-level permissions (row-level security)
  - Permission groups (reusable permission sets)
  - Relationship detection (foreign keys, belongs-to, has-many)
  - Composite primary key support
  - Denylist entity discovery mode
- **Pagination**:
  - Cursor-based pagination (for large datasets)
- **Job processing**:
  - Redis queue adapter (distributed job processing)
- **External library**:
  - HTTP client adapter
  - API client framework
  - Webhook handling
- **Database support**:
  - MySQL adapter
  - SQLite adapter improvements
- **Validation**:
  - Validation graph visualization improvements
  - Cross-field validation
- **Testing**:
  - Property-based testing examples
  - Integration test helpers

---

## Version History

- **[1.0.0]** - 2026-02-14: Initial production release

[1.0.0]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.0
