# Boundary Framework Developer Guide

A comprehensive reference for developing with Boundary, a module-centric Clojure framework implementing the Functional Core / Imperative Shell (FC/IS) architectural paradigm.

**Looking for practical tutorials and how-to guides?** See the [User Guide](docs/user-guide/DECISIONS.md) for development decisions and step-by-step guidance.

This **Developer Guide** provides comprehensive architectural documentation, advanced development workflows, and complete technical reference.

> **⚠️ Important Development Notes**
> - Do not stage, commit or push without my explicit permission  
> - Follow parinfer conventions for proper Clojure formatting

# Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.

## Table of Contents

- [Project Overview](#project-overview)
- [Quick Start](#quick-start)
- [Architecture Summary](#architecture-summary)
- [Development Workflow](#development-workflow)
- [Observability Integration](#observability-integration)
- [Module Structure](#module-structure)
- [Key Technologies](#key-technologies)
- [Testing Strategy](#testing-strategy)
- [Configuration Management](#configuration-management)
- [Common Development Tasks](#common-development-tasks)
- [References](#references)

## Project Overview

Boundary is a **module-centric software framework** built on Clojure that implements the "Functional Core / Imperative Shell" architectural paradigm. It provides a foundation for creating highly composable, testable, and maintainable systems where each domain module owns its complete functionality stack.

### Key Characteristics

- **Module-Centric Architecture**: Each domain module (`user`, `billing`, `workflow`, `error_reporting`, `logging`, `metrics`) contains its complete functionality from pure business logic to external interfaces
- **Functional Core / Imperative Shell**: Strict separation between pure business logic (core) and side-effectful operations (shell)
- **Multi-Interface Support**: Consistent behavior across REST API, CLI, and Web interfaces
- **Ports and Adapters**: Hexagonal architecture enabling dependency inversion and easy testing
- **Strategic Framework Vision**: Designed to evolve into reusable development toolchains and domain-specific frameworks

### Primary User Types

- **Domain Developers**: Implement business logic in functional core
- **Platform Engineers**: Maintain shell layer and infrastructure adapters  
- **API Integrators**: Consume REST endpoints for system integration
- **Operators/SREs**: Manage deployment and operational tasks via CLI

### Goals and Non-Goals

**Goals:**
- Architectural clarity with enforced FC/IS separation
- Excellent developer experience with comprehensive tooling
- Multi-interface consistency across REST, CLI, and Web
- Domain-agnostic patterns supporting extensible business domains
- Production-ready observability and operational tooling

**Non-Goals:**
- Mobile/desktop client support (focuses on server-side interfaces, maybe later)
- Specific domain logic (provides patterns, not implementations)
- Built-in authentication providers (supports auth patterns)

## Quick Start

### Prerequisites

**For macOS (using Homebrew):**
```zsh
# Install required dependencies
brew install openjdk clojure/tools/clojure
```

**For Linux (Ubuntu/Debian):**
```zsh
# Install JDK
sudo apt-get update
sudo apt-get install -y default-jdk rlwrap

# Install Clojure CLI
curl -L -O https://download.clojure.org/install/linux-install-1.12.3.1577.sh
chmod +x linux-install-*.sh && sudo ./linux-install-*.sh
```

### Getting Started

```zsh
# Clone the repository
git clone <repository-url> boundary
cd boundary

# Verify your shell (commands in this guide assume zsh)
echo $SHELL

# Run tests to verify setup
clojure -M:test

# Start a development REPL
clojure -M:repl-clj

# In the REPL, load and start the system
user=> (require '[integrant.repl :as ig-repl])
user=> (require '[boundary.config :as config])
user=> (ig-repl/go)  ; Start the system
```

### Verify Installation

```zsh
# Check if tests pass
clojure -M:test:db/h2

# Lint the codebase
clojure -M:clj-kondo --config .clj-kondo/config.edn --lint src

# Check for outdated dependencies
clojure -M:outdated
```

## Architecture Summary

Boundary implements a **clean architecture** pattern with proper separation of concerns. Each domain module follows a layered structure organized around the Functional Core / Imperative Shell paradigm, with clear dependency inversion through ports and adapters.

### Core Architectural Principles

#### Functional Core (Pure Business Logic)
- **Pure functions only**: No side effects, deterministic behavior
- **Domain-focused**: Contains only business rules, calculations, and decision logic
- **Data in, data out**: Immutable data structures as inputs and outputs
- **Port-dependent**: Depends only on abstractions (ports), never concrete implementations
- **Highly testable**: Unit tests require no mocks or external dependencies

#### Imperative Shell (Infrastructure & I/O)
- **Side effect boundary**: All I/O, networking, persistence, and system interactions
- **Adapter implementation**: Concrete implementations of ports used by the core
- **Validation gateway**: Input validation and coercion before calling core functions
- **Error translation**: Convert core data responses to appropriate interface formats
- **Infrastructure management**: Configuration, logging, monitoring, and operational concerns

### Dependency Flow Rules

| Direction | Status | Rule |
|-----------|--------|------|
| **Shell → Core** | ✅ Allowed | Shell calls core functions with validated data |
| **Core → Ports** | ✅ Allowed | Core depends only on abstract interfaces |
| **Shell → Adapters** | ✅ Allowed | Shell provides concrete implementations |
| **Core → Shell** | ❌ Forbidden | Core never depends on shell or infrastructure |
| **Core → Adapters** | ❌ Forbidden | Core never depends on concrete implementations |

### System Architecture

```
┌──────────────────────────────────────────────────────────┐
│                 Presentation Layer                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐ │
│  │  REST API   │ │     CLI     │ │    Web Frontend     │ │
│  │   (Ring)    │ │(tools.cli)  │ │  (HTMX + Hiccup)    │ │
│  └─────────────┘ └─────────────┘ └─────────────────────┘ │
└──────────────────────────────────────────────────────────┘
           │                   │                   │
           ▼                   ▼                   ▼
┌──────────────────────────────────────────────────────────┐
│                Imperative Shell                          │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐ │
│  │ Validation  │ │   Logging   │ │    Configuration    │ │
│  │  (Malli)    │ │(TeleMere)   │ │     (Aero)          │ │
│  └─────────────┘ └─────────────┘ └─────────────────────┘ │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐ │
│  │   Adapters  │ │    Ports    │ │   Error Handling    │ │
│  │(SQL, Email) │ │ (Protocols) │ │  (Problem Details)  │ │
│  └─────────────┘ └─────────────┘ └─────────────────────┘ │
└──────────────────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                  Functional Core                         │
│  ┌─────────────────────────────────────────────────────┐ │
│  │              Pure Functions                         │ │
│  │         Business Rules & Logic                      │ │
│  │        Domain Calculations                          │ │
│  │         Decision Tables                             │ │
│  └─────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

1. **ADR-001: Functional Core / Imperative Shell Pattern** - Strict FC/IS separation for enhanced testability and maintainability
2. **ADR-002: Ports and Adapters (Hexagonal Architecture)** - Use Clojure protocols as ports for dependency inversion
3. **ADR-003: Multi-Interface Consistency** - Identical behavior across REST API, CLI, and Web interfaces
4. **ADR-004: Schema-First Validation with Malli** - Runtime validation with compile-time schema definitions
5. **ADR-005: Validation Developer Experience Foundations** - Enhanced validation infrastructure for better developer experience
6. **ADR-006: Web UI Architecture - HTMX + Hiccup** - Server-side rendering with progressive enhancement for web interface

## Development Workflow

### Starting the Development Environment

```zsh
# Start REPL with development profile
clojure -M:repl-clj

# In REPL - load development utilities
user=> (require '[integrant.repl :as ig-repl])
user=> (require '[boundary.config :as config])

# Start the system
user=> (ig-repl/go)

# After making changes, reload
user=> (ig-repl/reset)
```

### Development Lifecycle with Integrant

The project uses Integrant for system lifecycle management:

```clojure
;; Development workflow in REPL
;; 1. Start system
(ig-repl/go)

;; 2. Make code changes in editor

;; 3. Reload changed code
(ig-repl/reset)

;; 4. System automatically restarts with new changes

;; 5. Stop system when done
(ig-repl/halt)
```

### Working with Modules

When adding new functionality:

1. **Choose the appropriate module** (user, billing, workflow, or create new)
2. **Start with core functions** - implement pure business logic
3. **Define ports** if external capabilities are needed
4. **Implement adapters** for concrete implementations
5. **Add shell services** to orchestrate core and adapters
6. **Create interfaces** (HTTP, CLI) that use shell services

### Code Reloading

```zsh
# Watch mode for automatic test running
clojure -M:test:db/h2 --watch

# Manual reset in REPL when things get stuck
user=> (ig-repl/halt)
user=> (ig-repl/go)
```

## Observability Integration

The Boundary framework includes built-in observability infrastructure with logging, metrics, and error reporting capabilities following the Functional Core/Imperative Shell pattern.

### Multi-Layer Interceptor Pattern Achievement ✅

**Major Architecture Milestone Completed**: Boundary has successfully implemented a **multi-layer interceptor pattern** that eliminates observability boilerplate while preserving business logic integrity:

**Phase 4 & 5 Results:**
- ✅ **Service Layer**: 10/10 methods converted (64% average code reduction)
- ✅ **Persistence Layer**: 21/21 methods converted (48% average code reduction)  
- ✅ **Total Impact**: 31/31 methods using interceptor pattern
- ✅ **Boilerplate Elimination**: 200+ manual observability calls removed
- ✅ **Business Logic Preservation**: 100% functional core logic maintained

**Key Technical Implementations:**
- `execute-service-operation` interceptor for business services
- `execute-persistence-operation` interceptor for data access
- Automatic breadcrumb, error reporting, and logging injection
- Clean separation between business logic and observability concerns

This represents a **foundational framework achievement** demonstrating clean architecture principles and uniform observability behavior across the entire user module.

### Quick Integration

Feature modules can easily integrate observability by accepting the protocols as dependencies:

```clojure
(ns my-feature.service
  (:require [boundary.logging.ports :as logging]
            [boundary.metrics.ports :as metrics]
            [boundary.error-reporting.ports :as error-reporting]))

(defrecord MyFeatureService [logger metric-collector error-reporter]
  ;; Feature protocols...
  
  IMyFeatureService
  (process-request [this request]
    (logging/info logger "Processing request" {:request-id (:id request)})
    (metrics/increment metric-collector "requests.processed" {:feature "my-feature"})
    
    (try
      ;; Business logic here
      (let [result (do-processing request)]
        (logging/info logger "Request processed successfully")
        result)
      (catch Exception e
        (error-reporting/capture-exception error-reporter e 
                                         {:context "process-request" 
                                          :request-id (:id request)})
        (throw (ex-info "Processing failed" {:request-id (:id request)} e))))))
```

### System Configuration

Configure observability providers in your `config.edn`:

```clojure
{:logging {:provider :no-op  ; or :datadog
           :level :info}
 :metrics {:provider :no-op  ; or :datadog  
           :namespace "boundary"}
 :error-reporting {:provider :no-op  ; or :sentry
                   :dsn "your-sentry-dsn"}}
```

### Available Providers

- **Logging**: No-op (development), Datadog
- **Metrics**: No-op (development), Datadog  
- **Error Reporting**: No-op (development), Sentry

See [docs/OBSERVABILITY_INTEGRATION.md](docs/OBSERVABILITY_INTEGRATION.md) for complete integration guide including custom adapters and advanced configuration.

## Module Structure

Boundary follows a **module-centric architecture** where each domain module owns its complete functionality stack:

### Updated Module Template (Clean Architecture)

```
src/boundary/user/
├── core/
│   ├── session.clj
│   └── user.clj
├── ports.clj
├── schema.clj
└── shell/
    ├── cli.clj
    ├── http.clj
    ├── persistence.clj
    └── service.clj
```

### Example: User Module (Updated Architecture)

```
src/boundary/user/
├── core/
│   ├── session.clj
│   └── user.clj
├── ports.clj
├── schema.clj
└── shell/
    ├── cli.clj
    ├── http.clj
    ├── persistence.clj
    └── service.clj
```

**Key improvements:**
- Clean separation between business logic (service) and infrastructure (database)
- Database-agnostic services that use dependency injection
- Easy to test business logic with mocked repositories
- Clear layered architecture following clean architecture principles

### User Core Example

```clojure
;; Pure business logic in user core
(ns boundary.user.core.user)

(defn calculate-user-membership-tier
  "Pure function: Calculate membership tier based on user data."
  [user current-date]
  (let [join-date    (:created-at user)
        days-member  (/ (.between ChronoUnit/DAYS join-date current-date) 1)
        years-member (/ days-member 365.25)]
    (cond
      (>= years-member 5) :platinum
      (>= years-member 3) :gold
      (>= years-member 1) :silver
      :else :bronze)))


### User Service Example

```clojure
;; Database-agnostic business service using dependency injection
(ns boundary.user.shell.service
  (:require [boundary.user.ports :as ports]
            [boundary.user.core.user :as user-core]))

(defrecord UserService [user-repository session-repository]
  ports/IUserService
  (create-user [this user-data]
    (let [validation-result (user-core/validate-user-creation-request user-data)]
      (when-not (:valid? validation-result)
        (throw (ex-info "Invalid user data" {:type :validation-error :errors (:errors validation-result)}))))
    (let [existing-user (.find-user-by-email user-repository (:email user-data))
          uniqueness-result (user-core/check-duplicate-user-decision user-data existing-user)]
      (when (= :reject (:decision uniqueness-result))
        (throw (ex-info "User already exists" {:type :user-exists :email (:email user-data)}))))
    (let [current-time (current-timestamp)
          user-id (generate-user-id)
          prepared-user (user-core/prepare-user-for-creation user-data current-time user-id)]
      (.create-user user-repository prepared-user))))

(defn make-user-service
  [user-repository session-repository]
  (->UserService user-repository session-repository))
```

### User Persistence Example

```clojure
(ns boundary.user.shell.persistence
  (:require [boundary.user.ports :as ports]
            [boundary.shell.adapters.database.common.core :as db]))

(defrecord DatabaseUserRepository [ctx]
  ports/IUserRepository
  (find-user-by-id [_ user-id]
    (let [query {:select [:*] :from [:users] :where [:and [:= :id (type-conversion/uuid->string user-id)] [:is :deleted_at nil]]} result (db/execute-one! ctx query)]
      (db->user-entity ctx result)))

  (create-user [_ user-entity]
    (let [now (java.time.Instant/now)
          user-with-metadata (-> user-entity
                                (assoc :id (UUID/randomUUID))
                                (assoc :created-at now)
                                (assoc :updated-at nil)
                                (assoc :deleted-at nil))
          db-user (user-entity->db ctx user-with-metadata)
          query {:insert-into :users :values [db-user]}]
      (db/execute-update! ctx query)
      user-with-metadata)))
```
```


## Key Technologies

### Core Framework Dependencies

| Library | Purpose | Usage in Boundary |
|---------|---------|-----------------|
| **Clojure 1.12.1** | Core language | Foundation for all modules |
| **Integrant** | System lifecycle | Component management and dependency injection |
| **Aero** | Configuration | Environment-based config with profile overlays |

### Data Management

| Library | Purpose | Usage in Boundary |
|---------|---------|-----------------|
| **next.jdbc** | Database connectivity | PostgreSQL connections and queries |
| **HoneySQL** | SQL generation | Type-safe SQL query building |
| **HikariCP** | Connection pooling | Database connection management |
| **Malli** | Schema validation | Request validation and data coercion |

### Web and Interface

| Library | Purpose | Usage in Boundary |
|---------|---------|-----------------|
| **Ring** | HTTP abstraction | Web server foundation |
| **Reitit** | Routing | HTTP request routing |
| **Cheshire** | JSON processing | Request/response serialization |

### Development and Operations

| Library | Purpose | Usage in Boundary |
|---------|---------|-----------------|
| **TeleMere** | Structured logging | Application logging and telemetry |
| **tools.logging** | Logging abstraction | Legacy logging support |
| **Kaocha** | Test runner | Test execution and reporting |
| **clj-kondo** | Static analysis | Code linting and quality checks |

### Key Technology Decisions

- **Integrant over Component**: Better support for configuration-driven systems
- **Malli over Spec**: More mature ecosystem and better error messages
- **next.jdbc over java.jdbc**: Modern, performant database access
- **Aero over environ**: More sophisticated configuration management

## Testing Strategy

Boundary's testing strategy aligns with the Functional Core / Imperative Shell architecture:

> **⚠️ Database Requirement**: Tests require H2 database driver. Use `clojure -M:test:db/h2` instead of `clojure -M:test` to include the necessary database dependencies for testing.

### Core Layer Testing (Pure Functions)

```zsh
# Run all tests
clojure -M:test:db/h2

# Run tests for specific module by metadata tag
# (see tests.edn :kaocha.plugin.filter/focus-meta)
clojure -M:test:db/h2 --focus-meta :unit.user

# Run tests in watch mode
clojure -M:test:db/h2 --watch
```

**Core testing characteristics:**
- No mocks required - pure functions with predictable inputs/outputs
- Fast execution - no I/O dependencies
- Property-based testing for complex business rules

```clojure
;; Example core test
(deftest calculate-membership-benefits-test
  (testing "gold tier calculation"
    (let [user-data {:joined-at #inst "2020-01-01"
                    :spending-total 7500}
          result (user-core/calculate-membership-benefits 
                   user-data membership-rules #inst "2024-01-01")]
      (is (= :gold (:membership-tier result)))
      (is (= 0.15 (:discount-rate result))))))
```

### Shell Layer Testing (Integration)

**Shell testing focuses on:**
- Service orchestration correctness
- Adapter boundary behavior  
- Error handling and logging
- Effects execution

```clojure
;; Example service integration test
(deftest register-user-integration-test
  (testing "user registration with mocked dependencies"
    (let [system {:user-repository (mock/user-repository)
                 :notification-service (mock/notification-service)}]
      (let [result (user-service/register-user system valid-user-data)]
        (is (= :success (:status result)))
        (is (mock/verify-called :create-user))))))
```

### Adapter Layer Testing (Contract Tests)

**Adapter tests verify:**
- Correct implementation of port protocols
- Integration with external systems
- Error handling and resilience

```zsh
# Run integration tests (requires test database)
clojure -M:test:db/h2 --focus :integration

# Run with test database container
docker-compose -f docker/test-compose.yml up -d
clojure -M:test:db/h2 --focus :integration
docker-compose -f docker/test-compose.yml down
```

### Test Organization

```
test/
├── unit/                   # Pure core function tests
│   ├── user_core_test.clj
│   └── billing_core_test.clj
├── integration/            # Shell service tests
│   ├── user_service_test.clj
│   └── billing_service_test.clj
├── contract/              # Adapter implementation tests
│   ├── postgresql_adapter_test.clj
│   └── smtp_adapter_test.clj
└── system/                # End-to-end system tests
    └── api_integration_test.clj
```

### Testing Commands Reference

```zsh
# All tests
clojure -M:test:db/h2

# Specific test categories (metadata-based)
clojure -M:test:db/h2 --focus-meta :unit
clojure -M:test:db/h2 --focus-meta :integration
clojure -M:test:db/h2 --focus-meta :contract

# Specific modules (metadata-based)
clojure -M:test:db/h2 --focus-meta :user
clojure -M:test:db/h2 --focus-meta :billing

# Watch mode with focus on unit tests
clojure -M:test:db/h2 --watch --focus-meta :unit

# Generate test coverage (if configured)
clojure -M:test:coverage
```

## Configuration Management

Boundary uses **Aero** for sophisticated configuration management with module-centric organization and environment-based profiles.

### Configuration Structure

```
resources/
└── conf/
    └── dev/
        └── config.edn       # Development configuration
```

*Note: The project currently has a simplified configuration structure. The architectural documentation describes a more comprehensive config approach that may be implemented as the project evolves.*

### Environment Setup

```zsh
# Development environment variables (zsh)
export BND_ENV=development
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=boundary_dev
export DB_USERNAME=boundary_dev
export DB_PASSWORD=dev_password

# Feature flags
export BND_FEATURE_BILLING=true
export BND_FEATURE_WORKFLOW=false

# External services
export SMTP_HOST=localhost
export SMTP_PORT=1025  # MailHog for development
```

### Configuration Loading

```clojure
;; Load configuration in REPL
user=> (require '[boundary.config :as config])
user=> (def cfg (config/load-config))  ; Loads from resources/conf/dev/config.edn
user=> (get-in cfg [:active :boundary/settings :name])  ; "boundary-dev"
user=> (get-in cfg [:boundary/sqlite :db])  ; "dev-database.db"
```

### Environment-Specific Configurations

**Current Development Configuration (`resources/conf/dev/config.edn`):**
```clojure
{:active
 {:boundary/settings
  {:name              "boundary-dev"
   :version           "0.1.0"
   :date-format       "yyyy-MM-dd"
   :date-time-format  "yyyy-MM-dd HH:mm:ss"
   :currency/iso-code "EUR"}}

 :boundary/sqlite
 {:db "dev-database.db"}

 :inactive  ; Available but not currently active
 {:boundary/postgresql
  {:host          #env "POSTGRES_HOST"
   :port          #env "POSTGRES_PORT"
   :dbname        #env "POSTGRES_DB"
   :user          #env "POSTGRES_USER"
   :password      #env "POSTGRES_PASSWORD"
   :auto-commit   true
   :max-pool-size 15}}}
```


### Configuration Best Practices

1. **No secrets in code** - Use environment variables for sensitive data
2. **Profile-based overrides** - Different values per environment
3. **Validation at startup** - Fail fast with invalid configuration
4. **Module ownership** - Each module defines its own config requirements

### Development Configuration Setup

```zsh
# The project currently uses SQLite for development (no additional setup required)
# For PostgreSQL setup, set environment variables:
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=boundary_dev
export POSTGRES_USER=boundary_dev
export POSTGRES_PASSWORD=dev_password

# Start development services (if using PostgreSQL)
# docker-compose up -d postgres  # If you have a docker-compose.yml

# Verify configuration in REPL
clojure -M:repl-clj
user=> (require '[boundary.config :as config])
user=> (def cfg (config/load-config))
user=> (keys (:active cfg))  ; See active configuration sections
user=> (:boundary/sqlite cfg)   ; Check SQLite config
```

## Web UI Development

### Overview

Boundary provides a server-side rendered web interface using **HTMX + Hiccup** architecture. The web UI follows the same Functional Core / Imperative Shell pattern as the rest of the framework.

**Key Features:**
- Server-side rendering with Hiccup
- Progressive enhancement with HTMX
- Module-integrated architecture (UI code lives in domain modules)
- No build tooling or compilation required
- Vendored static assets (no CDN dependencies)

### Quick Start

```zsh
# Start the system in REPL
clojure -M:repl-clj
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)

# Navigate to http://localhost:3000/web/users
# Default web UI is enabled by feature flag
```

### Web UI Architecture

**Route Organization:**
- `/web/*` - Web UI pages and HTMX fragments
- `/api/*` - REST API endpoints (unchanged)
- `/css/*` - Stylesheets (Pico CSS, app.css, module CSS)
- `/js/*` - JavaScript libraries (HTMX)
- `/modules/*` - Module-specific assets

**File Organization (User Module Example):**
```
src/boundary/user/
├── core/
│   └── ui.clj              # Pure UI generation functions (Hiccup)
├── shell/
│   ├── web_handlers.clj    # Web request handlers (Ring)
│   └── http.clj            # Route composition
```

### HTMX Integration Patterns

**1. Form Targeting**
```clojure
;; Forms target their container for in-place updates
[:div#create-user-form {:hx-target "#create-user-form"}
 [:form {:hx-post "/web/users"}
  ;; form fields...]]
```

**2. Event-Based Refresh**
```clojure
;; Table listens for custom events from forms
[:div#users-table-container
 {:hx-get "/web/users/table"
  :hx-trigger "userCreated from:body, userUpdated from:body, userDeleted from:body"
  :hx-target "#users-table-container"}
 [:table ...]]
```

**3. Custom Event Triggers**
```clojure
;; Handler sets HX-Trigger header to emit custom event
(defn create-user-htmx-handler [user-service config]
  (fn [request]
    ;; ... validation and creation ...
    {:status 201
     :headers {"Content-Type" "text/html; charset=utf-8"
               "HX-Trigger" "userCreated"}  ; Triggers refresh
     :body (render-html success-message)}))
```

**4. Confirmation Dialogs**
```clojure
[:button {:hx-delete "/web/users/123"
          :hx-confirm "Deactivate this user?"
          :hx-target "#users-table-container"}
  "Deactivate"]
```

### Working with Web Handlers

**Page Handlers** (Full HTML):
```clojure
(defn users-page-handler [user-service config]
  (fn [request]
    (let [users (user-ports/list-users user-service options)]
      (html-response
        (user-ui/users-page (:users users))))))
```

**HTMX Fragment Handlers** (Partial HTML):
```clojure
(defn create-user-htmx-handler [user-service config]
  (fn [request]
    (let [[valid? errors data] (validate-request-data schema request)]
      (if valid?
        (html-response
          (user-ui/user-created-success (create-user data))
          201
          {"HX-Trigger" "userCreated"})
        (html-response
          (user-ui/create-user-form data errors)
          400)))))
```

### Validation and Error Display

**Field-Level Errors:**
```clojure
;; Convert Malli errors to field-keyed map
(require '[boundary.shared.ui.core.validation :as validation])

(defn validate-request [schema data]
  (let [transformed (m/decode schema data transformer)
        valid? (m/validate schema transformed)]
    (if valid?
      [true nil transformed]
      (let [explain (m/explain schema transformed)
            errors (validation/explain->field-errors explain)]
        [false errors transformed]))))

;; Use in forms
(defn create-user-form [data errors]
  [:div#create-user-form
   [:form {:hx-post "/web/users"}
    [:label "Name"]
    [:input {:name "name" :value (:name data)}]
    (when-let [errs (:name errors)]
      (for [err errs]
        [:span.error err]))]])
```

### Static Assets

**Vendored Assets:**
- HTMX v1.9.12 at `/js/htmx.min.js` (BSD 2-Clause)
- Pico CSS v2.0 at `/css/pico.min.css` (MIT)
- Module CSS at `/modules/user/css/user.css`

**Local Development:**
All assets are served from `resources/public/` - no CDN dependencies or build step required.

### Testing Web UI

**Handler Tests:**
```zsh
# Run web handler tests
clojure -M:test:db/h2 -n boundary.user.shell.web-handlers-test
```

**Test Pattern:**
```clojure
(deftest create-user-htmx-handler-test
  (testing "creates user successfully"
    (let [service (create-mock-service)
          handler (web-handlers/create-user-htmx-handler service config)
          response (handler {:form-params {"name" "Test"
                                           "email" "test@example.com"
                                           "password" "password123"
                                           "role" "user"
                                           "active" "true"}})
      (is (= 201 (:status response)))
      (is (= "userCreated" (get-in response [:headers "HX-Trigger"])))
      (is (str/includes? (:body response) "User Created Successfully")))))
```

### Feature Flags

**Enable/Disable Web UI:**
```clojure
;; resources/conf/dev/config.edn
{:active
 {:boundary/settings
  {:features
   {:user-web-ui {:enabled? true}}  ; Toggle web UI
   }}}
```

### Common Patterns

**1. List Page with Create Button:**
```clojure
(defn users-page [users opts]
  (layout/page-layout "Users"
    [:div.page-header
     [:h1 "Users"]
     [:a.button {:href "/web/users/new"} "Create User"]]
    [:div#users-table-container
     (users-table users)]))
```

**2. Form with Inline Validation:**
```clojure
(defn create-user-form [data errors]
  [:div#create-user-form
   [:h2 "Create New User"]
   [:form {:hx-post "/web/users"
           :hx-target "#create-user-form"}
    (ui/text-input "name" "Name"
                   {:value (:name data)
                    :required true
                    :errors (:name errors)})
    ;; more fields...
    (ui/submit-button "Create User")]])
```

**3. Success Message with Auto-Refresh:**
```clojure
(defn user-created-success [user]
  [:div.alert.alert-success
   [:h3 "User Created Successfully!"]
   [:p "Created user: " [:strong (:name user)]]
   [:a {:href "/web/users"} "View All Users"]])
```

### Development Workflow

1. **Edit UI functions** in `{module}/core/ui.clj` (pure Hiccup)
2. **Edit handlers** in `{module}/shell/web_handlers.clj` (Ring handlers)
3. **Reload in REPL**: `(ig-repl/reset)`
4. **Refresh browser** - no build step required
5. **Run tests**: `clojure -M:test:db/h2`

### Debugging Tips

**Check HTMX requests:**
```javascript
// In browser console
htmx.logAll();  // Enable HTMX debug logging
```

**Verify routes:**
```clojure
;; In REPL
(require '[boundary.user.shell.http :as http])
(http/user-routes)  ; Inspect route definitions
```

**Test validation:**
```clojure
(require '[boundary.user.schema :as schema])
(require '[malli.core :as m])
(m/explain schema/CreateUserRequest
  {:name "" :email "invalid" :password "123"})
```

### Best Practices

1. **Keep UI functions pure** - No side effects in `core/ui.clj`
2. **Use semantic HTML** - Leverage Pico CSS defaults
3. **Target containers** - Use `hx-target` to update specific divs
4. **Emit custom events** - Use `HX-Trigger` header for coordination
5. **Validate server-side** - Never trust client input
6. **Test handlers** - Write tests for all HTMX endpoints
7. **Soft delete** - Deactivate instead of permanent delete
8. **Hide deactivated** - Don't show soft-deleted items by default

### Further Reading

- [ADR-006: Web UI Architecture](docs/modules/ROOT/pages/adr/ADR-006-web-ui-architecture-htmx-hiccup.adoc)
- [HTMX Documentation](https://htmx.org/)
- [Hiccup Documentation](https://github.com/weavejester/hiccup)
- [Pico CSS](https://picocss.com/)

## Common Development Tasks

### Essential Development Commands

```zsh
# Start development environment
clojure -M:repl-clj                     # Start REPL
clojure -M:test:db/h2 --watch                 # Run tests in watch mode

# Code quality
clojure -M:clj-kondo --lint src test   # Lint codebase
clojure -M:outdated                     # Check for outdated dependencies

# System lifecycle (in REPL)
(ig-repl/go)                           # Start system
(ig-repl/reset)                        # Reload and restart
(ig-repl/halt)                         # Stop system
```

### Testing Commands

```zsh
# Run all tests
clojure -M:test:db/h2

# Test categories
clojure -M:test:db/h2 --focus :unit           # Pure core function tests
clojure -M:test:db/h2 --focus :integration    # Shell service tests  
clojure -M:test:db/h2 --focus :contract       # Adapter tests

# Module-specific tests
clojure -M:test:db/h2 --focus :user           # User module tests
clojure -M:test:db/h2 --focus :billing        # Billing module tests

# Watch mode
clojure -M:test:db/h2 --watch --focus :unit   # Watch unit tests only
```

### Database Operations

```zsh
# Start local PostgreSQL (via Docker)
docker-compose -f docker/dev-compose.yml up -d postgres

# Connect to database
psql -h localhost -U boundary_dev -d boundary_dev

# Run migrations (if available)
clojure -M:migrate up

# Reset test database
clojure -M:test:db:reset
```

### Code Generation and Scaffolding

```zsh
# Generate new module (if scaffolding exists)
clojure -M:gen:module billing          # Create billing module structure

# Add new entity to existing module
clojure -M:gen:entity user profile     # Add profile entity to user module
```

### Documentation

```zsh
# Generate API documentation
clojure -M:docs:api

# Build architecture diagrams (if PlantUML configured)
cd docs/architecture
for doc in *.adoc; do asciidoctor -D ../../build/docs "$doc"; done
cd -

# Validate documentation links
npx -y markdown-link-check -q warp.md
```

### Build and Deployment

```zsh
# Build application using build.clj
clojure -T:build clean                 # Clean build artifacts
clojure -T:build uber                  # Create standalone JAR

# The uberjar will be created in target/ directory
ls target/*.jar

# Build Docker image (if Dockerfile exists)
docker build -t boundary:latest .
```

### Development Utilities

```zsh
# Format code
clojure -M:format                      # Format all source files

# Dependency analysis
clojure -M:deps:tree                   # Show dependency tree
clojure -M:deps:outdated              # Check for updates

# REPL utilities
rlwrap clojure -M:repl-clj            # REPL with readline support

# Validation development utilities (REPL)
(require '[boundary.shared.tools.validation.repl :as v])
(v/stats)                              # Show validation statistics
(v/list-rules {:module :user})         # List validation rules
(spit "build/validation-user.dot" (v/rules->dot {:modules #{:user}}))  # Generate validation graph
```

### Troubleshooting

```zsh
# Clean and restart
rm -rf .cpcache target                # Clean build artifacts
clojure -M:repl-clj                   # Fresh REPL start

# Check system health (in REPL)
user=> (require '[boundary.system.health :as health])
user=> (health/check-all)             # Check all system dependencies

# Database connection test
user=> (require '[boundary.db :as db])
user=> (db/health-check)              # Test database connectivity
```

### Environment-Specific Tasks

```zsh
# Development
export BND_ENV=development
clojure -M:repl-clj

# Testing
export BND_ENV=test
clojure -M:test:db/h2

# Staging deployment
export BND_ENV=staging
clojure -M:build:deploy
```

## References

### Internal Documentation

- [Observability Integration Guide](docs/OBSERVABILITY_INTEGRATION.md) - FC/IS observability framework implementation
- [Development Decisions](docs/user-guide/DECISIONS.md) - Architectural and implementation decisions
- [Documentation Build Guide](docs/README-DOCS.md) - Documentation structure and build process
- [Architecture Diagrams](docs/diagrams/README.adoc) - System architecture visualizations

### Development Documentation

- [Implementation Guide](docs/implementation/) - Concrete implementation examples and patterns
- [API Documentation](docs/api/) - REST API specifications and examples
- [Architecture Diagrams](docs/diagrams/) - Visual system architecture references

### Infrastructure and Migration

- [User Module Architecture](docs/user-module-architecture.adoc)
- [Migration Guide](docs/migration-guide.adoc)
- [Infrastructure Examples](examples/user_infrastructure_example.clj) - Working code examples
- [Refactoring Summary](INFRASTRUCTURE-REFACTOR-SUMMARY.adoc)

### External References

#### Architectural Patterns
- [Functional Core, Imperative Shell](https://www.destroyallsoftware.com/screencasts/catalog/functional-core-imperative-shell) - Gary Bernhardt's original concept
- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/) - Ports and Adapters pattern by Alistair Cockburn
- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html) - Robert C. Martin's architectural principles

#### Clojure and Ecosystem
- [Clojure Documentation](https://clojure.org/guides/getting_started) - Official Clojure guides
- [Integrant Documentation](https://github.com/weavejester/integrant) - System lifecycle management
- [Malli Documentation](https://github.com/metosin/malli) - Schema validation library
- [Aero Configuration](https://github.com/juxt/aero) - Configuration management
- [next.jdbc Guide](https://github.com/seancorfield/next-jdbc) - Database connectivity
- be careful and concise in the placement of indents and put all parenthesis etc. right. We use parinfer and that gice a ot of trouble when we write sloppy code.
- use clojure-mcp for editing as much as possible.

#### Development Tools
- [Kaocha Test Runner](https://github.com/lambdaisland/kaocha) - Testing framework
- [clj-kondo Linter](https://github.com/clj-kondo/clj-kondo) - Static analysis tool
- [CIDER Development Environment](https://cider.mx/) - Emacs-based Clojure IDE

### Community and Support

- [Clojure Community](https://clojure.org/community/resources) - Forums, chat, and resources
- [Clojurians Slack](https://clojurians.net/) - Community chat and support
- [r/Clojure Reddit](https://www.reddit.com/r/Clojure/) - Community discussions

---

**Note**: This guide is based on the comprehensive documentation in the `docs/` directory. For the most up-to-date architectural decisions and implementation details, refer to the linked documentation files.

