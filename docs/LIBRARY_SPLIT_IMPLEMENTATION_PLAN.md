# Boundary Framework: Library Split Implementation Plan

**Version:** 1.0  
**Date:** 2026-01-18  
**Status:** Planning  
**Owner:** Boundary Core Team

---

## Executive Summary

This document outlines the detailed implementation plan for splitting the Boundary monolithic framework into 8 modular, independently publishable Clojars libraries. This transformation will enable developers to use Boundary as a foundation for their projects with flexible dependency management while maintaining the cohesive framework experience.

### Goals

1. **Modularity**: Enable users to depend only on the parts of Boundary they need
2. **Open Source Distribution**: Publish all libraries to Clojars for public use
3. **Backward Compatibility**: Provide clear migration path for existing users
4. **Maintainability**: Improve long-term maintainability through clear separation of concerns

### Target Architecture

8 independently publishable libraries in a monorepo:
- `boundary/core` - Foundation utilities and validation
- `boundary/observability` - Logging, metrics, error reporting
- `boundary/platform` - Database, HTTP, pagination, search
- `boundary/user` - Authentication, MFA, sessions
- `boundary/admin` - Auto-generated CRUD admin interface
- `boundary/storage` - File storage (local, S3)
- `boundary/external` - External service adapters (email, payments)
- `boundary/scaffolder` - Code generation tooling

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Target Library Structure](#2-target-library-structure)
3. [Dependency Graph](#3-dependency-graph)
4. [Repository Structure](#4-repository-structure)
5. [Implementation Phases](#5-implementation-phases)
6. [Technical Changes](#6-technical-changes)
7. [Migration Guide](#7-migration-guide)
8. [Testing Strategy](#8-testing-strategy)
9. [Release Process](#9-release-process)
10. [Timeline & Resources](#10-timeline--resources)
11. [Risks & Mitigation](#11-risks--mitigation)

---

## 1. Current State Analysis

### Existing Module Structure

```
src/boundary/
├── admin/              # Auto-CRUD admin interface
├── config.clj          # Aero-based configuration
├── error_reporting/    # Error tracking (Sentry)
├── inventory/          # Example domain module → moving to examples/
├── jobs/               # Background jobs → future enhancement
├── logging/            # Structured logging
├── main.clj            # Entry point
├── metrics/            # Metrics collection
├── platform/           # Core infrastructure
├── scaffolder/         # Code generator
├── shared/             # Shared utilities → becoming core
├── storage/            # File storage
└── user/               # User management & auth
```

### Dependency Analysis

**Current Coupling Issues:**
1. `platform/shell/system/wiring.clj` hard-codes module requires
2. `boundary.shared` namespace will need renaming to `boundary.core`
3. Shared UI components currently in `shared/ui` → moving to `admin`
4. External service adapters mixed in platform → need separation

**Dependency Flow (Current):**
```
admin, user, inventory → platform → logging, metrics, error_reporting → shared
```

---

## 2. Target Library Structure

### 2.1 boundary/core

**Purpose:** Foundation library providing validation, utilities, and interceptor framework.

**Contents:**
- Malli-based validation framework with behavior-driven testing
- Case conversion utilities (kebab-case ↔ snake_case ↔ camelCase)
- Type conversion utilities (UUID, Instant, BigDecimal)
- PII redaction utilities
- Interceptor pipeline framework
- Feature flags configuration

**Namespace Mapping:**
```
boundary.shared.core.validation.*     → boundary.core.validation.*
boundary.shared.core.utils.*          → boundary.core.utils.*
boundary.shared.core.interceptor      → boundary.core.interceptor
boundary.shared.core.config.feature_flags → boundary.core.config.feature_flags
```

**Dependencies:**
```clojure
{:deps {org.clojure/clojure {:mvn/version "1.12.4"}
        metosin/malli {:mvn/version "0.20.0"}}}
```

**Size:** ~8,000 LOC

---

### 2.2 boundary/observability

**Purpose:** Unified observability stack with pluggable adapters.

**Contents:**
- Logging with adapters: no-op, stdout, slf4j, datadog
- Metrics with adapters: no-op, datadog
- Error reporting with adapters: no-op, sentry
- Protocol-based adapter pattern

**Namespace Mapping:**
```
boundary.logging.*           → boundary.observability.logging.*
boundary.metrics.*           → boundary.observability.metrics.*
boundary.error_reporting.*   → boundary.observability.errors.*
```

**Dependencies:**
```clojure
{:deps {boundary/core {:mvn/version "0.1.0"}
        org.clojure/tools.logging {:mvn/version "1.3.1"}
        ch.qos.logback/logback-classic {:mvn/version "1.5.23"}
        io.sentry/sentry-clj {:mvn/version "8.29.238"}}}
```

**Size:** ~3,500 LOC

---

### 2.3 boundary/platform

**Purpose:** Core infrastructure for web applications.

**Contents:**
- Multi-database support (SQLite, PostgreSQL, MySQL, H2)
- Database query builder and validation
- HTTP routing (Reitit-based)
- API versioning
- Offset and cursor-based pagination
- Full-text search with ranking
- Database migrations (Migratus)
- Service and persistence interceptors
- System lifecycle management (Integrant)
- Dynamic module registration system

**Namespace Structure:**
```
boundary.platform.database/
  core/              # Query building, validation
  ports.clj          # Database protocols
  adapters/          # Multi-DB implementations
boundary.platform.http/
  core/              # Problem details
  ports.clj          # HTTP protocols
  interceptors.clj   # HTTP middleware
  versioning.clj     # API versioning
  adapters/reitit.clj
boundary.platform.pagination/
boundary.platform.search/
boundary.platform.system/
  wiring.clj         # Integrant lifecycle
  modules.clj        # Module discovery
boundary.platform.interceptors/
  service.clj        # Service layer observability
  persistence.clj    # DB operation observability
```

**Dependencies:**
```clojure
{:deps {boundary/observability {:mvn/version "0.1.0"}
        com.github.seancorfield/next.jdbc {:mvn/version "1.3.1086"}
        com.github.seancorfield/honeysql {:mvn/version "2.7.1364"}
        com.zaxxer/HikariCP {:mvn/version "7.0.2"}
        migratus/migratus {:mvn/version "1.6.4"}
        ring/ring-core {:mvn/version "1.15.3"}
        ring/ring-jetty-adapter {:mvn/version "1.15.3"}
        metosin/reitit-ring {:mvn/version "0.9.2"}
        metosin/reitit-malli {:mvn/version "0.9.2"}
        integrant/integrant {:mvn/version "1.0.1"}
        integrant/repl {:mvn/version "0.5.0"}
        aero/aero {:mvn/version "1.1.6"}}}

;; Database drivers are OPTIONAL - users include what they need:
;; org.xerial/sqlite-jdbc
;; org.postgresql/postgresql
;; com.mysql/mysql-connector-j
;; com.h2database/h2
```

**Size:** ~15,000 LOC

---

### 2.4 boundary/user

**Purpose:** Complete user management and authentication system.

**Contents:**
- User CRUD operations
- Password hashing (bcrypt)
- JWT-based authentication
- Session management
- Multi-factor authentication (TOTP)
- Audit logging
- Account lockout/rate limiting
- Web UI components for user management

**Dependencies:**
```clojure
{:deps {boundary/platform {:mvn/version "0.1.0"}
        buddy/buddy-hashers {:mvn/version "2.0.167"}
        buddy/buddy-sign {:mvn/version "3.6.1-359"}
        one-time/one-time {:mvn/version "0.8.0"}
        hiccup/hiccup {:mvn/version "2.0.0"}}}
```

**Size:** ~12,000 LOC

---

### 2.5 boundary/admin

**Purpose:** Auto-generated CRUD admin interface with shared UI components.

**Contents:**
- Database schema introspection
- Dynamic CRUD operations
- Filtering, sorting, pagination UI
- Permission system
- Shared UI components (from `boundary.shared.ui`):
  - Layout system
  - Hiccup component library
  - Icon system (50+ Lucide icons)
  - Table rendering
  - Form validation display

**Namespace Structure:**
```
boundary.admin/
  core/
    permissions.clj
    schema_introspection.clj
    ui.clj
  shell/
    service.clj
    http.clj
    schema_repository.clj
    module_wiring.clj

boundary.ui/               # Shared UI components
  core/
    layout.clj
    components.clj
    icons.clj
    table.clj
    validation.clj
```

**Dependencies:**
```clojure
{:deps {boundary/platform {:mvn/version "0.1.0"}
        boundary/user {:mvn/version "0.1.0"}  ; For admin authentication
        hiccup/hiccup {:mvn/version "2.0.0"}}}
```

**Size:** ~8,000 LOC

---

### 2.6 boundary/storage

**Purpose:** File storage abstraction with multiple backends.

**Contents:**
- Storage protocol
- Local filesystem adapter
- S3 adapter
- Image processing utilities
- Upload validation

**Dependencies:**
```clojure
{:deps {boundary/platform {:mvn/version "0.1.0"}
        software.amazon.awssdk/s3 {:mvn/version "2.39.5"}
        software.amazon.awssdk/s3-transfer-manager {:mvn/version "2.39.5"}}}
```

**Size:** ~2,000 LOC

---

### 2.7 boundary/external

**Purpose:** Adapters for external services.

**Contents:**
- SMTP email adapter
- Stripe payment adapter
- Generic notification adapter

**Dependencies:**
```clojure
{:deps {boundary/platform {:mvn/version "0.1.0"}}}
```

**Size:** ~1,500 LOC

---

### 2.8 boundary/scaffolder

**Purpose:** Code generation tool for creating new modules.

**Contents:**
- Module template generator
- Entity scaffolding
- Migration generation
- Test scaffolding
- CLI interface

**Dependencies:**
```clojure
{:deps {boundary/core {:mvn/version "0.1.0"}
        org.clojure/tools.cli {:mvn/version "1.3.250"}}}
```

**Size:** ~3,000 LOC

---

## 3. Dependency Graph

```
┌─────────────────────────────────────────────────────────────────┐
│                      APPLICATION LAYER                          │
│                (User's project using Boundary)                  │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴────────────────┐
              │                                │
              ▼                                ▼
┌──────────────────────┐          ┌──────────────────────┐
│   boundary/admin     │          │  boundary/scaffolder │
│   + shared UI        │          │  (dev tool only)     │
└──────────┬───────────┘          └──────────┬───────────┘
           │                                 │
           ▼                                 │
┌──────────────────────┐                     │
│   boundary/user      │                     │
└──────────┬───────────┘                     │
           │                                 │
           ├────────────┬────────────┐       │
           ▼            ▼            ▼       │
┌─────────────┐  ┌──────────┐  ┌─────────┐   │
│boundary/    │  │boundary/ │  │boundary/│   │
│storage      │  │external  │  │admin    │   │
└──────┬──────┘  └────┬─────┘  └────┬────┘   │
       │              │             │        │
       └──────────────┴─────────────┘        │
                      │                      │
                      ▼                      │
          ┌────────────────────┐             │
          │ boundary/platform  │             │
          └─────────┬──────────┘             │
                    │                        │
                    ▼                        │
          ┌────────────────────────┐         │
          │ boundary/observability │         │
          └─────────┬──────────────┘         │
                    │                        │
                    ▼                        ▼
                ┌──────────────────────────────┐
                │       boundary/core          │
                └──────────────────────────────┘
```

---

## 4. Repository Structure

### 4.1 Monorepo Layout

```
boundary/                                    # Root repository
├── .github/
│   └── workflows/
│       ├── ci.yml                          # CI for all libs
│       ├── release.yml                     # Coordinated release
│       └── docs.yml                        # Documentation generation
│
├── libs/                                   # All libraries
│   ├── core/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── CHANGELOG.md
│   │   ├── src/boundary/core/
│   │   └── test/boundary/core/
│   │
│   ├── observability/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── src/boundary/observability/
│   │   └── test/boundary/observability/
│   │
│   ├── platform/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── src/boundary/platform/
│   │   ├── test/boundary/platform/
│   │   └── resources/
│   │       └── migrations/
│   │
│   ├── user/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── src/boundary/user/
│   │   ├── test/boundary/user/
│   │   └── resources/
│   │       └── migrations/
│   │
│   ├── admin/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── src/
│   │   │   ├── boundary/admin/
│   │   │   └── boundary/ui/              # Shared UI components
│   │   ├── test/
│   │   └── resources/
│   │       ├── public/css/
│   │       └── public/js/
│   │
│   ├── storage/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── src/boundary/storage/
│   │   └── test/boundary/storage/
│   │
│   ├── external/
│   │   ├── deps.edn
│   │   ├── build.clj
│   │   ├── README.md
│   │   ├── src/boundary/external/
│   │   └── test/boundary/external/
│   │
│   └── scaffolder/
│       ├── deps.edn
│       ├── build.clj
│       ├── README.md
│       ├── src/boundary/scaffolder/
│       └── test/boundary/scaffolder/
│
├── examples/                               # Example applications
│   ├── inventory/                         # Domain module example
│   │   ├── README.md
│   │   ├── deps.edn
│   │   ├── src/boundary/inventory/
│   │   └── test/boundary/inventory/
│   │
│   ├── starter-app/                       # Minimal working app
│   │   ├── README.md
│   │   ├── deps.edn
│   │   ├── src/myapp/
│   │   └── resources/conf/
│   │
│   └── full-app/                          # All features enabled
│       ├── README.md
│       ├── deps.edn
│       ├── src/myapp/
│       └── resources/conf/
│
├── docs/                                   # Documentation
│   ├── guides/
│   │   ├── getting-started.md
│   │   ├── migration-guide.md
│   │   ├── module-development.md
│   │   └── database-setup.md
│   ├── adr/                               # Architecture Decision Records
│   │   └── ADR-001-library-split.md
│   ├── api/                               # API documentation
│   └── LIBRARY_SPLIT_IMPLEMENTATION_PLAN.md
│
├── tools/                                  # Build and development tools
│   ├── build.clj                          # Coordinated build script
│   └── release.clj                        # Release automation
│
├── deps.edn                               # Root deps for development
├── README.md                              # Main project README
├── LICENSE                                # Project license
└── CHANGELOG.md                           # Changelog for all libs
```

### 4.2 Individual Library Structure

Each library follows this pattern:

```
libs/{library-name}/
├── deps.edn              # Library dependencies
├── build.clj             # Build configuration
├── README.md             # Library-specific README
├── CHANGELOG.md          # Library changelog
├── src/
│   └── boundary/
│       └── {library-name}/
│           ├── core/     # Pure business logic
│           ├── shell/    # I/O, adapters
│           ├── ports.clj # Protocol definitions
│           └── schema.clj# Malli schemas
├── test/
│   └── boundary/
│       └── {library-name}/
│           ├── core/     # Unit tests
│           └── shell/    # Integration tests
└── resources/            # Library-specific resources
```

---

## 5. Implementation Phases

### Phase 0: Preparation (2 days)

**Objective:** Set up monorepo infrastructure without changing code.

**Tasks:**
1. Create monorepo directory structure
   ```bash
   mkdir -p libs/{core,observability,platform,user,admin,storage,external,scaffolder}
   mkdir -p examples/{inventory,starter-app,full-app}
   mkdir -p tools
   ```

2. Create root `deps.edn` with development aliases
   ```clojure
   {:paths []
    :deps {}
    :aliases
    {:dev {:extra-paths ["libs/core/src" "libs/observability/src" ...]
           :extra-deps {...}}
     :test {:extra-paths ["libs/core/test" "libs/observability/test" ...]}
     :build:all {:exec-fn build/release-all}
     :build:core {:exec-fn build/release-core}
     ...}}
   ```

3. Set up CI/CD pipeline
   - GitHub Actions workflow for running all tests
   - Path-based test filtering (only test changed libs)
   - Release automation workflow

4. Create build tooling
   - `tools/build.clj` - Coordinated build/release
   - `tools/release.clj` - Clojars publishing

5. Initialize each library with skeleton structure

**Deliverables:**
- [ ] Monorepo structure created
- [ ] Root `deps.edn` configured
- [ ] CI/CD pipeline configured
- [ ] Build scripts created
- [ ] Documentation skeleton created

**Success Criteria:**
- Can run `clojure -A:dev -M:repl-clj` from root
- CI pipeline runs successfully (even with empty tests)

---

### Phase 1: Extract boundary/core (3 days)

**Objective:** Create the foundation library with zero internal dependencies.

**Tasks:**

1. **Namespace Migration**
   ```bash
   # Move files
   mkdir -p libs/core/src/boundary/core
   mv src/boundary/shared/core/validation libs/core/src/boundary/core/
   mv src/boundary/shared/core/utils libs/core/src/boundary/core/
   mv src/boundary/shared/core/interceptor.clj libs/core/src/boundary/core/
   mv src/boundary/shared/core/interceptor_context.clj libs/core/src/boundary/core/
   mv src/boundary/shared/core/config libs/core/src/boundary/core/
   ```

2. **Update Namespace Declarations**
   - Search and replace: `boundary.shared.core` → `boundary.core`
   - Update all `(:require ...)` statements

3. **Create `libs/core/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {org.clojure/clojure {:mvn/version "1.12.4"}
           metosin/malli {:mvn/version "0.20.0"}}
    :aliases
    {:test {:extra-paths ["test"]
            :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}}}}}
   ```

4. **Migrate Tests**
   ```bash
   mv test/boundary/shared/core libs/core/test/boundary/core
   # Update test namespaces
   ```

5. **Create Library README**
   - Installation instructions
   - Core concepts (validation, interceptors, utils)
   - API examples
   - Migration notes

6. **Verify Independence**
   ```bash
   cd libs/core
   clojure -M:test
   ```

**Deliverables:**
- [ ] All files moved and namespaces updated
- [ ] `deps.edn` created and tested
- [ ] All core tests passing
- [ ] README.md with examples
- [ ] No dependencies on other boundary namespaces

**Success Criteria:**
- `clojure -M:test` passes in `libs/core/` directory
- No `boundary.shared`, `boundary.platform`, etc. in requires
- Linting passes: `clojure -M:clj-kondo --lint libs/core/src`

---

### Phase 2: Extract boundary/observability (3 days)

**Objective:** Separate observability stack (logging, metrics, errors).

**Tasks:**

1. **Namespace Migration**
   ```bash
   mkdir -p libs/observability/src/boundary/observability
   mv src/boundary/logging libs/observability/src/boundary/observability/
   mv src/boundary/metrics libs/observability/src/boundary/observability/
   mv src/boundary/error_reporting libs/observability/src/boundary/observability/errors
   ```

2. **Update Namespace Declarations**
   - `boundary.logging` → `boundary.observability.logging`
   - `boundary.metrics` → `boundary.observability.metrics`
   - `boundary.error_reporting` → `boundary.observability.errors`

3. **Update Dependencies**
   - Change `boundary.shared` requires to `boundary.core`
   - Add `boundary/core` as dependency

4. **Create `libs/observability/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/core {:local/root "../core"}  ; For dev, mvn/version for release
           org.clojure/tools.logging {:mvn/version "1.3.1"}
           ch.qos.logback/logback-classic {:mvn/version "1.5.23"}
           io.sentry/sentry-clj {:mvn/version "8.29.238"}}
    :aliases {:test {...}}}
   ```

5. **Migrate Tests**

6. **Update All References in Codebase**
   - Find all `(:require [boundary.logging` and update
   - Update `platform/shell/system/wiring.clj`

**Deliverables:**
- [ ] All files moved and namespaces updated
- [ ] Dependencies on `boundary/core` working
- [ ] All observability tests passing
- [ ] README.md with adapter documentation
- [ ] All referencing code updated

**Success Criteria:**
- Tests pass: `cd libs/observability && clojure -M:test`
- No old `boundary.logging` references in codebase

---

### Phase 3: Extract boundary/platform (5 days)

**Objective:** Core platform infrastructure with dynamic module system.

**Tasks:**

1. **Implement Dynamic Module Registration** (Critical!)
   
   Create `src/boundary/platform/system/modules.clj`:
   ```clojure
   (ns boundary.platform.system.modules
     "Dynamic module registration system.")
   
   (defonce ^:private registered-modules (atom {}))
   
   (defn register-module!
     "Register a module's wiring namespace.
      Called by each module on namespace load."
     [module-key wiring-ns-sym]
     (swap! registered-modules assoc module-key wiring-ns-sym))
   
   (defn load-registered-modules!
     "Load all registered module wirings."
     []
     (doseq [[module-key ns-sym] @registered-modules]
       (try
         (require ns-sym)
         (catch Exception e
           (log/warn "Failed to load module" {:module module-key :error e})))))
   ```

2. **Update `system/wiring.clj`**
   - Remove hard-coded module requires
   - Add call to `load-registered-modules!` at init

3. **Move Platform Files**
   ```bash
   mkdir -p libs/platform/src/boundary/platform
   mv src/boundary/platform/* libs/platform/src/boundary/platform/
   ```

4. **Update Dependencies**
   - Change `boundary.shared` → `boundary.core`
   - Change `boundary.logging` → `boundary.observability.logging`
   - Change `boundary.metrics` → `boundary.observability.metrics`
   - Change `boundary.error_reporting` → `boundary.observability.errors`

5. **Create `libs/platform/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/observability {:local/root "../observability"}
           com.github.seancorfield/next.jdbc {:mvn/version "1.3.1086"}
           com.github.seancorfield/honeysql {:mvn/version "2.7.1364"}
           com.zaxxer/HikariCP {:mvn/version "7.0.2"}
           migratus/migratus {:mvn/version "1.6.4"}
           ring/ring-core {:mvn/version "1.15.3"}
           ring/ring-jetty-adapter {:mvn/version "1.15.3"}
           metosin/reitit-ring {:mvn/version "0.9.2"}
           metosin/reitit-malli {:mvn/version "0.9.2"}
           integrant/integrant {:mvn/version "1.0.1"}
           integrant/repl {:mvn/version "0.5.0"}
           aero/aero {:mvn/version "1.1.6"}}
    :aliases {:test {:extra-deps {com.h2database/h2 {:mvn/version "2.4.240"}}}}}
   ```

6. **Extract Database Migrations**
   - Move migration files to `resources/migrations/`
   - Update migration configuration

7. **Migrate Tests**
   - Integration tests with H2 in-memory database

**Deliverables:**
- [ ] Dynamic module registration implemented
- [ ] All platform files moved
- [ ] Dependencies updated throughout
- [ ] Platform tests passing
- [ ] README with architecture docs

**Success Criteria:**
- Platform tests pass independently
- Can initialize Integrant system without domain modules
- Module registration system tested

---

### Phase 4: Extract boundary/user (3 days)

**Objective:** User management module with self-registration.

**Tasks:**

1. **Add Module Self-Registration**
   
   Update `src/boundary/user/shell/module_wiring.clj`:
   ```clojure
   (ns boundary.user.shell.module-wiring
     (:require [boundary.platform.system.modules :as modules]
               [boundary.user.shell.persistence :as persistence]
               [boundary.user.shell.service :as service]
               ...))
   
   ;; Self-registration on namespace load
   (modules/register-module! :user 'boundary.user.shell.module-wiring)
   
   ;; ... existing defmethod forms ...
   ```

2. **Move User Files**
   ```bash
   mkdir -p libs/user/src/boundary/user
   mv src/boundary/user/* libs/user/src/boundary/user/
   ```

3. **Update Dependencies**
   - Add `boundary/platform` dependency
   - Update namespace requires

4. **Create `libs/user/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/platform {:local/root "../platform"}
           buddy/buddy-hashers {:mvn/version "2.0.167"}
           buddy/buddy-sign {:mvn/version "3.6.1-359"}
           one-time/one-time {:mvn/version "0.8.0"}
           hiccup/hiccup {:mvn/version "2.0.0"}}
    :aliases {:test {:extra-deps {com.h2database/h2 {:mvn/version "2.4.240"}}}}}
   ```

5. **Move Migrations**
   ```bash
   mv resources/migrations/*_create_users.sql libs/user/resources/migrations/
   mv resources/migrations/*_create_sessions.sql libs/user/resources/migrations/
   ```

6. **Migrate Tests**

7. **Update Configuration Loading**
   - Ensure user config is properly loaded

**Deliverables:**
- [ ] Module self-registration working
- [ ] All user files moved
- [ ] User tests passing
- [ ] Migrations moved
- [ ] README with auth documentation

**Success Criteria:**
- User tests pass: `cd libs/user && clojure -M:test`
- Module loads via registration system
- MFA functionality works

---

### Phase 5: Extract boundary/admin (3 days)

**Objective:** Admin interface with shared UI components.

**Tasks:**

1. **Move Admin Files**
   ```bash
   mkdir -p libs/admin/src/boundary/admin
   mkdir -p libs/admin/src/boundary/ui
   mv src/boundary/admin/* libs/admin/src/boundary/admin/
   mv src/boundary/shared/ui/* libs/admin/src/boundary/ui/
   ```

2. **Update UI Namespace**
   - `boundary.shared.ui.core` → `boundary.ui.core`
   - Update all references to UI components

3. **Add Module Self-Registration**
   ```clojure
   (modules/register-module! :admin 'boundary.admin.shell.module-wiring)
   ```

4. **Move Static Assets**
   ```bash
   mkdir -p libs/admin/resources/public
   mv resources/public/css libs/admin/resources/public/
   mv resources/public/js libs/admin/resources/public/
   ```

5. **Create `libs/admin/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/platform {:local/root "../platform"}
           boundary/user {:local/root "../user"}  ; For admin auth
           hiccup/hiccup {:mvn/version "2.0.0"}}
    :aliases {:test {...}}}
   ```

6. **Update All UI Component References**
   - Search for `boundary.shared.ui` across entire codebase
   - Replace with `boundary.ui`

7. **Migrate Tests**

**Deliverables:**
- [ ] Admin and UI files moved
- [ ] UI namespace updated everywhere
- [ ] Static assets moved
- [ ] Admin tests passing
- [ ] README with UI component docs

**Success Criteria:**
- Admin tests pass
- UI components render correctly
- Admin interface loads in browser

---

### Phase 6: Extract boundary/storage (2 days)

**Objective:** File storage library.

**Tasks:**

1. **Move Storage Files**
   ```bash
   mkdir -p libs/storage/src/boundary/storage
   mv src/boundary/storage/* libs/storage/src/boundary/storage/
   ```

2. **Create `libs/storage/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/platform {:local/root "../platform"}
           software.amazon.awssdk/s3 {:mvn/version "2.39.5"}
           software.amazon.awssdk/s3-transfer-manager {:mvn/version "2.39.5"}}
    :aliases {:test {...}}}
   ```

3. **Migrate Tests**

**Deliverables:**
- [ ] Storage files moved
- [ ] Tests passing
- [ ] README with adapter docs

---

### Phase 7: Extract boundary/external (2 days)

**Objective:** External service adapters library.

**Tasks:**

1. **Move External Service Files**
   ```bash
   mkdir -p libs/external/src/boundary/external
   mv src/boundary/platform/shell/adapters/external/* libs/external/src/boundary/external/
   ```

2. **Create `libs/external/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/platform {:local/root "../platform"}}
    :aliases {:test {...}}}
   ```

3. **Migrate Tests**

**Deliverables:**
- [ ] External service adapters moved
- [ ] Tests passing
- [ ] README with adapter examples

---

### Phase 8: Extract boundary/scaffolder (2 days)

**Objective:** Code generation tool.

**Tasks:**

1. **Move Scaffolder Files**
   ```bash
   mkdir -p libs/scaffolder/src/boundary/scaffolder
   mv src/boundary/scaffolder/* libs/scaffolder/src/boundary/scaffolder/
   ```

2. **Create Standalone CLI**
   - Ensure scaffolder can run independently
   - Update template generation to reference new namespaces

3. **Create `libs/scaffolder/deps.edn`**
   ```clojure
   {:paths ["src" "resources"]
    :deps {boundary/core {:local/root "../core"}
           org.clojure/tools.cli {:mvn/version "1.3.250"}}
    :aliases {:test {...}}}
   ```

4. **Update Templates**
   - Generated modules should use new namespace structure
   - Use `boundary.platform.system.modules/register-module!`

**Deliverables:**
- [ ] Scaffolder moved
- [ ] Templates updated
- [ ] CLI works independently
- [ ] Tests passing

---

### Phase 9: Create Examples (3 days)

**Objective:** Example applications and documentation.

**Tasks:**

1. **Move Inventory to Examples**
   ```bash
   mkdir -p examples/inventory
   mv src/boundary/inventory examples/inventory/src/boundary/
   ```

2. **Create Starter App**
   - Minimal app with user auth only
   - Clear deps.edn showing library usage
   - README with getting started

3. **Create Full App**
   - All features enabled
   - Example of extending Boundary
   - Production-ready configuration

4. **Write Migration Guide**
   - Namespace rename table
   - deps.edn examples (before/after)
   - Module registration examples
   - Common issues and solutions

5. **Update Documentation**
   - Main README with new library structure
   - Individual library READMEs
   - Architecture diagrams
   - API documentation

**Deliverables:**
- [ ] Inventory moved to examples
- [ ] Starter app created and tested
- [ ] Full app created and tested
- [ ] Migration guide complete
- [ ] All documentation updated

---

### Phase 10: Publishing Setup (1 day)

**Objective:** Prepare for Clojars release.

**Tasks:**

1. **Register Clojars Group**
   - Claim `boundary` group on Clojars
   - Or use `io.github.boundary` as fallback

2. **Configure Build Scripts**
   ```clojure
   ;; tools/build.clj
   (defn release-all [_]
     (doseq [lib ["core" "observability" "platform" 
                  "user" "admin" "storage" "external" "scaffolder"]]
       (release-lib lib)))
   ```

3. **Create Release Checklist**
   - Version bumping procedure
   - Changelog generation
   - Git tagging convention
   - Clojars deployment

4. **Set Up GitHub Actions Release Workflow**
   - Triggered on version tags
   - Runs full test suite
   - Publishes to Clojars
   - Creates GitHub release

5. **Test Publishing Process**
   - Publish snapshot versions first
   - Verify installation from Clojars

**Deliverables:**
- [ ] Clojars group registered
- [ ] Build scripts working
- [ ] Release workflow automated
- [ ] Test publish successful

---

### Phase 11: Release v0.1.0 (1 day)

**Objective:** Public release to Clojars.

**Tasks:**

1. **Final Testing**
   - Run full test suite
   - Test starter and full apps
   - Verify all documentation

2. **Version All Libraries to 0.1.0**

3. **Update All `deps.edn` Files**
   - Change `:local/root` to `:mvn/version` references

4. **Create Release Notes**

5. **Tag Release**
   ```bash
   git tag -a v0.1.0 -m "Initial library release"
   git push origin v0.1.0
   ```

6. **Publish to Clojars**
   - Automated via GitHub Actions
   - Verify all 8 artifacts published

7. **Announce Release**
   - Update main README
   - Publish blog post
   - Announce on Clojureverse, Reddit, Slack

**Deliverables:**
- [ ] All tests passing
- [ ] Version 0.1.0 tagged
- [ ] All libraries published to Clojars
- [ ] Release notes published
- [ ] Announcement made

---

## 6. Technical Changes

### 6.1 Dynamic Module Registration

**Problem:** Current implementation hard-codes module requires in `wiring.clj`.

**Solution:** Module self-registration pattern.

**Implementation:**

```clojure
;; In boundary.platform.system.modules
(ns boundary.platform.system.modules
  "Dynamic module registration for Integrant components.")

(defonce ^:private registered-modules (atom {}))

(defn register-module!
  "Register a module's Integrant wiring namespace.
   
   Args:
     module-key: Keyword identifier for the module (e.g., :user)
     wiring-ns-sym: Quoted symbol of the wiring namespace
   
   Example:
     (register-module! :user 'boundary.user.shell.module-wiring)"
  [module-key wiring-ns-sym]
  (swap! registered-modules assoc module-key wiring-ns-sym)
  (log/debug "Registered module" {:module module-key :wiring-ns wiring-ns-sym}))

(defn unregister-module!
  "Unregister a module (primarily for testing)."
  [module-key]
  (swap! registered-modules dissoc module-key))

(defn registered-modules
  "Return map of all registered modules."
  []
  @registered-modules)

(defn load-registered-modules!
  "Load all registered module wiring namespaces.
   Called by system/wiring.clj during Integrant initialization."
  []
  (doseq [[module-key ns-sym] @registered-modules]
    (try
      (require ns-sym)
      (log/info "Loaded module" {:module module-key})
      (catch Exception e
        (log/error e "Failed to load module" {:module module-key})
        (throw (ex-info "Module loading failed"
                        {:module module-key :wiring-ns ns-sym}
                        e))))))
```

**Module Usage:**

```clojure
;; In boundary.user.shell.module-wiring
(ns boundary.user.shell.module-wiring
  (:require [boundary.platform.system.modules :as modules]
            [integrant.core :as ig]
            ...))

;; Self-registration on namespace load
(modules/register-module! :user 'boundary.user.shell.module-wiring)

;; Existing Integrant methods
(defmethod ig/init-key :boundary/user-repository [_ config] ...)
(defmethod ig/halt-key! :boundary/user-repository [_ repo] ...)
```

**System Wiring:**

```clojure
;; In boundary.platform.system.wiring
(ns boundary.platform.system.wiring
  (:require [boundary.platform.system.modules :as modules]
            ...))

;; Load registered modules BEFORE Integrant init
(modules/load-registered-modules!)

;; ... rest of wiring code ...
```

### 6.2 Namespace Renames

**Migration Table:**

| Old Namespace | New Namespace | Breaking Change? |
|---------------|---------------|------------------|
| `boundary.shared.core.*` | `boundary.core.*` | Yes |
| `boundary.shared.ui.*` | `boundary.ui.*` | Yes |
| `boundary.logging.*` | `boundary.observability.logging.*` | Yes |
| `boundary.metrics.*` | `boundary.observability.metrics.*` | Yes |
| `boundary.error_reporting.*` | `boundary.observability.errors.*` | Yes |
| `boundary.platform.*` | `boundary.platform.*` | No |
| `boundary.user.*` | `boundary.user.*` | No |
| `boundary.admin.*` | `boundary.admin.*` | No |

**Automated Migration Script:**

```bash
#!/bin/bash
# migrate-namespaces.sh

# Core namespace renames
find . -name "*.clj" -type f -exec sed -i '' 's/boundary\.shared\.core/boundary.core/g' {} +
find . -name "*.clj" -type f -exec sed -i '' 's/boundary\.shared\.ui/boundary.ui/g' {} +

# Observability namespace renames
find . -name "*.clj" -type f -exec sed -i '' 's/boundary\.logging/boundary.observability.logging/g' {} +
find . -name "*.clj" -type f -exec sed -i '' 's/boundary\.metrics/boundary.observability.metrics/g' {} +
find . -name "*.clj" -type f -exec sed -i '' 's/boundary\.error-reporting/boundary.observability.errors/g' {} +

echo "Namespace migration complete. Please review changes and run tests."
```

### 6.3 Dependency Updates

**Before (Monolith):**
```clojure
;; All internal requires work directly
(ns myapp.core
  (:require [boundary.shared.core.validation :as validation]
            [boundary.platform.database.core :as db]))
```

**After (Libraries):**
```clojure
;; deps.edn
{:deps {boundary/platform {:mvn/version "0.1.0"}}}

;; Namespace updates
(ns myapp.core
  (:require [boundary.core.validation :as validation]
            [boundary.platform.database.core :as db]))
```

### 6.4 Configuration Changes

**Module Registration in Application Code:**

```clojure
;; In your app's main.clj or config.clj
(ns myapp.main
  (:require 
    ;; Explicitly require modules you want to use
    ;; This triggers their self-registration
    [boundary.user.shell.module-wiring]
    [boundary.admin.shell.module-wiring]
    [myapp.modules.orders.shell.module-wiring]
    
    ;; Then require system wiring
    [boundary.platform.system.wiring :as wiring]
    [boundary.config :as config]
    [integrant.core :as ig]))

(defn -main [& args]
  (let [config (config/load-config)
        ig-config (config/ig-config config)
        system (ig/init ig-config)]
    ;; System running with all registered modules
    system))
```

---

## 7. Migration Guide

### 7.1 For Existing Boundary Users

**Step 1: Update deps.edn**

```clojure
;; BEFORE (Git dependency)
{:deps {boundary/boundary {:git/url "https://github.com/boundary/boundary"
                           :git/sha "abc123"}}}

;; AFTER (Choose your approach)

;; Option A: Full framework (all features)
{:deps {boundary/admin {:mvn/version "0.1.0"}
        ;; Plus your database driver
        org.postgresql/postgresql {:mvn/version "42.7.8"}}}

;; Option B: Platform + select modules
{:deps {boundary/platform {:mvn/version "0.1.0"}
        boundary/user {:mvn/version "0.1.0"}
        org.postgresql/postgresql {:mvn/version "42.7.8"}}}

;; Option C: Core utilities only
{:deps {boundary/core {:mvn/version "0.1.0"}}}
```

**Step 2: Update Namespace Requires**

Run the migration script or manually update:

```clojure
;; BEFORE
(ns myapp.core
  (:require [boundary.shared.core.validation :as validation]
            [boundary.shared.core.utils.case-conversion :as case]
            [boundary.logging.core :as log]
            [boundary.metrics.core :as metrics]))

;; AFTER
(ns myapp.core
  (:require [boundary.core.validation :as validation]
            [boundary.core.utils.case-conversion :as case]
            [boundary.observability.logging.core :as log]
            [boundary.observability.metrics.core :as metrics]))
```

**Step 3: Update Module Registration**

```clojure
;; BEFORE
;; Module wiring was automatically loaded

;; AFTER
;; Explicitly require module wirings in your app entry point
(ns myapp.main
  (:require [boundary.user.shell.module-wiring]
            [boundary.admin.shell.module-wiring]
            ;; Your custom modules
            [myapp.orders.shell.module-wiring]
            
            [boundary.config :as config]
            [integrant.core :as ig]))
```

**Step 4: Update Database Drivers**

```clojure
;; Database drivers are now optional - add what you need
{:deps {boundary/platform {:mvn/version "0.1.0"}
        ;; Choose your database driver(s)
        org.postgresql/postgresql {:mvn/version "42.7.8"}
        ;; org.xerial/sqlite-jdbc {:mvn/version "3.51.0.0"}
        ;; com.mysql/mysql-connector-j {:mvn/version "9.5.0"}
        }}
```

**Step 5: Update UI Component Requires (if using admin/UI)**

```clojure
;; BEFORE
(ns myapp.views
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.icons :as icons]))

;; AFTER
(ns myapp.views
  (:require [boundary.ui.core.components :as ui]
            [boundary.ui.core.icons :as icons]))
```

**Step 6: Test Your Application**

```bash
# Run tests
clojure -M:test

# Verify no old namespace references
grep -r "boundary.shared" src/
grep -r "boundary.logging" src/  # Should be boundary.observability.logging
```

### 7.2 For New Boundary Users

**Quick Start:**

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps {boundary/admin {:mvn/version "0.1.0"}
        org.postgresql/postgresql {:mvn/version "42.7.8"}}
 :aliases {:dev {:extra-paths ["dev"]}}}
```

```clojure
;; src/myapp/main.clj
(ns myapp.main
  (:require [boundary.user.shell.module-wiring]
            [boundary.admin.shell.module-wiring]
            [boundary.config :as config]
            [integrant.core :as ig]))

(defn -main [& args]
  (let [config (config/load-config)
        ig-config (config/ig-config config)]
    (ig/init ig-config)))
```

See `examples/starter-app` for complete working example.

### 7.3 Common Migration Issues

**Issue 1: "Namespace not found" errors**

```
CompilerException: java.io.FileNotFoundException: 
Could not locate boundary/shared/core/validation.clj
```

**Solution:** Update namespace requires to new names.

---

**Issue 2: Module not loading**

```
WARNING: No implementation of method: :init-key 
for class: clojure.lang.Keyword
```

**Solution:** Explicitly require module wiring namespace:
```clojure
(require '[boundary.user.shell.module-wiring])
```

---

**Issue 3: Circular dependency**

**Solution:** Libraries are designed to avoid circular deps. If you encounter this, check your custom code dependencies.

---

## 8. Testing Strategy

### 8.1 Test Organization

**Unit Tests:** Pure functions, no I/O
```
libs/{lib}/test/boundary/{lib}/core/
```

**Integration Tests:** With mocked dependencies
```
libs/{lib}/test/boundary/{lib}/shell/
```

**Contract Tests:** With real dependencies (H2 database)
```
libs/{lib}/test/boundary/{lib}/contract/
```

### 8.2 CI Pipeline

```yaml
# .github/workflows/ci.yml
name: CI

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        lib: [core, observability, platform, user, admin, storage, external, scaffolder]
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Clojure
        uses: DeLaGuardo/setup-clojure@master
        with:
          cli: 1.11.1.1347
      
      - name: Test ${{ matrix.lib }}
        run: |
          cd libs/${{ matrix.lib }}
          clojure -M:test
      
      - name: Lint ${{ matrix.lib }}
        run: |
          cd libs/${{ matrix.lib }}
          clojure -M:clj-kondo --lint src test
```

### 8.3 Integration Testing

**Cross-Library Integration Test:**

```clojure
;; test/integration/full_stack_test.clj
(ns integration.full-stack-test
  "Test that all libraries work together"
  (:require [clojure.test :refer :all]
            [boundary.user.shell.module-wiring]
            [boundary.admin.shell.module-wiring]
            [boundary.config :as config]
            [integrant.core :as ig]))

(deftest full-stack-integration
  (testing "Can initialize complete system with all modules"
    (let [config (config/load-config {:profile :test})
          ig-config (config/ig-config config)
          system (ig/init ig-config)]
      (is (some? system))
      (is (contains? system :boundary/user-service))
      (is (contains? system :boundary/admin-service))
      (ig/halt! system))))
```

---

## 9. Release Process

### 9.1 Version Strategy

**Synchronized Versioning:** All libraries share the same version number.

- Initial release: `0.1.0`
- Pre-1.0: Breaking changes allowed with minor version bumps
- Post-1.0: Semantic versioning guarantees

### 9.2 Release Checklist

- [ ] All tests passing (unit, integration, contract)
- [ ] Linting passes for all libraries
- [ ] CHANGELOG.md updated for each library
- [ ] Version numbers updated in all `deps.edn` files
- [ ] Documentation reviewed and updated
- [ ] Migration guide complete
- [ ] Example apps tested
- [ ] Git tag created: `v0.1.0`
- [ ] GitHub release created with notes
- [ ] All artifacts published to Clojars
- [ ] Announcement prepared

### 9.3 Clojars Publishing

**Manual Process (Initial):**

```bash
# For each library
cd libs/core
clojure -T:build jar
clojure -T:build deploy

cd ../observability
clojure -T:build jar
clojure -T:build deploy

# ... repeat for all libraries
```

**Automated Process (GitHub Actions):**

```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Clojure
        uses: DeLaGuardo/setup-clojure@master
      
      - name: Publish All Libraries
        env:
          CLOJARS_USERNAME: ${{ secrets.CLOJARS_USERNAME }}
          CLOJARS_PASSWORD: ${{ secrets.CLOJARS_PASSWORD }}
        run: |
          clojure -T:build release-all
```

---

## 10. Timeline & Resources

### 10.1 Timeline Summary

| Phase | Duration | Start | End |
|-------|----------|-------|-----|
| 0. Preparation | 2 days | Day 1 | Day 2 |
| 1. Core | 3 days | Day 3 | Day 5 |
| 2. Observability | 3 days | Day 6 | Day 8 |
| 3. Platform | 5 days | Day 9 | Day 13 |
| 4. User | 3 days | Day 14 | Day 16 |
| 5. Admin | 3 days | Day 17 | Day 19 |
| 6. Storage | 2 days | Day 20 | Day 21 |
| 7. External | 2 days | Day 22 | Day 23 |
| 8. Scaffolder | 2 days | Day 24 | Day 25 |
| 9. Examples | 3 days | Day 26 | Day 28 |
| 10. Publishing | 1 day | Day 29 | Day 29 |
| 11. Release | 1 day | Day 30 | Day 30 |
| **Total** | **30 days** | | **6 weeks** |

### 10.2 Resource Requirements

**Personnel:**
- 1-2 developers (full-time for 6 weeks)
- 1 technical writer (part-time for documentation)
- 1 reviewer (code review throughout)

**Infrastructure:**
- GitHub repository access
- Clojars account with `boundary` group ownership
- CI/CD credits (GitHub Actions)

### 10.3 Dependencies & Blockers

**Critical Path:**
1. Core must be completed before observability
2. Observability must be completed before platform
3. Platform must be completed before domain modules
4. Dynamic module registration (Phase 3) blocks Phase 4-5

**Potential Blockers:**
- Clojars `boundary` group name unavailability → Use `io.github.boundary`
- Circular dependency discovery → Requires architectural refactor
- Test failures post-split → May extend timeline

---

## 11. Risks & Mitigation

### 11.1 Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Circular dependencies discovered | Medium | High | Careful dependency analysis completed; strict layering enforced |
| Test coverage gaps | Low | Medium | Maintain >80% coverage throughout; run full suite after each phase |
| Breaking changes missed | Medium | High | Comprehensive migration testing; maintain backwards compatibility checklist |
| Performance regression | Low | Medium | Benchmark before/after; optimize if needed |
| Module registration bugs | Medium | High | Extensive testing of dynamic loading; fallback to explicit requires |

### 11.2 Project Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Timeline overrun | Medium | Medium | Build in 20% buffer; prioritize phases |
| Clojars group unavailable | Low | Low | Fallback to `io.github.boundary` |
| Breaking existing users | High | High | Comprehensive migration guide; deprecation warnings; support channel |
| Documentation incomplete | Medium | Medium | Parallel doc work; review at each phase |
| Adoption resistance | Low | Medium | Clear value proposition; easy migration path |

### 11.3 Mitigation Strategies

**For Breaking Changes:**
1. Provide automated migration script
2. Support both old and new namespaces temporarily (deprecation period)
3. Comprehensive migration guide with examples
4. Active support in community channels

**For Test Coverage:**
1. Maintain existing test suite throughout
2. Add integration tests for cross-library interaction
3. Run full test suite after each phase
4. Gate releases on test passing

**For Documentation:**
1. Update docs as you go, not at the end
2. Include examples in each library README
3. Create video walkthrough of migration
4. Maintain FAQ for common issues

---

## 12. Success Criteria

### 12.1 Technical Success

- [ ] All 8 libraries published to Clojars
- [ ] All tests passing (unit, integration, contract)
- [ ] No circular dependencies
- [ ] Linting passes for all libraries
- [ ] Example apps run successfully
- [ ] Performance within 5% of baseline

### 12.2 User Success

- [ ] Migration guide complete and tested
- [ ] At least one existing user successfully migrated
- [ ] Starter app works out of the box
- [ ] Documentation comprehensive and accurate
- [ ] Community support channels established

### 12.3 Project Success

- [ ] Completed within 6 weeks
- [ ] All phases delivered
- [ ] No major architectural changes needed post-release
- [ ] Positive community feedback
- [ ] At least 10 new projects using Boundary within 3 months

---

## Appendices

### Appendix A: File Movement Checklist

Track file movements across phases:

```markdown
## Phase 1: Core

- [ ] src/boundary/shared/core/validation/* → libs/core/src/boundary/core/validation/
- [ ] src/boundary/shared/core/utils/* → libs/core/src/boundary/core/utils/
- [ ] src/boundary/shared/core/interceptor.clj → libs/core/src/boundary/core/
- [ ] src/boundary/shared/core/interceptor_context.clj → libs/core/src/boundary/core/
- [ ] src/boundary/shared/core/config/* → libs/core/src/boundary/core/config/
- [ ] test/boundary/shared/core/* → libs/core/test/boundary/core/

## Phase 2: Observability

- [ ] src/boundary/logging/* → libs/observability/src/boundary/observability/logging/
- [ ] src/boundary/metrics/* → libs/observability/src/boundary/observability/metrics/
- [ ] src/boundary/error_reporting/* → libs/observability/src/boundary/observability/errors/
- [ ] test/boundary/logging/* → libs/observability/test/boundary/observability/logging/
- [ ] test/boundary/metrics/* → libs/observability/test/boundary/observability/metrics/
- [ ] test/boundary/error_reporting/* → libs/observability/test/boundary/observability/errors/

...
```

### Appendix B: Namespace Mapping Reference

Complete mapping for automated migration:

```clojure
{;; Core
 "boundary.shared.core.validation"           "boundary.core.validation"
 "boundary.shared.core.utils.case-conversion" "boundary.core.utils.case-conversion"
 "boundary.shared.core.utils.type-conversion" "boundary.core.utils.type-conversion"
 "boundary.shared.core.utils.pii-redaction"  "boundary.core.utils.pii-redaction"
 "boundary.shared.core.interceptor"          "boundary.core.interceptor"
 "boundary.shared.core.interceptor-context"  "boundary.core.interceptor-context"
 "boundary.shared.core.config.feature-flags" "boundary.core.config.feature-flags"
 
 ;; UI
 "boundary.shared.ui.core"                   "boundary.ui.core"
 
 ;; Observability
 "boundary.logging"                          "boundary.observability.logging"
 "boundary.metrics"                          "boundary.observability.metrics"
 "boundary.error-reporting"                  "boundary.observability.errors"}
```

### Appendix C: Contact & Support

**Project Lead:** [Name]  
**Email:** [Email]  
**Slack:** #boundary-dev  
**GitHub:** https://github.com/boundary/boundary  

**For Questions:**
- Technical: Open GitHub issue
- Migration help: Ask in #boundary-users Slack
- Security: security@boundary.dev

---

**Document Version:** 1.0  
**Last Updated:** 2026-01-18  
**Next Review:** After Phase 3 completion
