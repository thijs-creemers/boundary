 Boundary Framework Developer Guide

A comprehensive guide for developing with Boundary, a module-centric Clojure framework implementing the Functional Core / Imperative Shell (FC/IS) architectural paradigm.


*** Important: do not stage, commit or push without permission ***

*** Important: Please use the clojure MCP server for creating correctly balanced clojure code.

## Table of Contents

- [Project Overview](#project-overview)
- [Quick Start](#quick-start)
- [Architecture Summary](#architecture-summary)
- [Development Workflow](#development-workflow)
- [Module Structure](#module-structure)
- [Key Technologies](#key-technologies)
- [Testing Strategy](#testing-strategy)
- [Configuration Management](#configuration-management)
- [Common Development Tasks](#common-development-tasks)
- [References](#references)

## Project Overview

Boundary is a **module-centric software framework** built on Clojure that implements the "Functional Core / Imperative Shell" architectural paradigm. It provides a foundation for creating highly composable, testable, and maintainable systems where each domain module owns its complete functionality stack.

### Key Characteristics

- **Module-Centric Architecture**: Each domain module (`user`, `billing`, `workflow`) contains its complete functionality from pure business logic to external interfaces
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
- Multi-tenancy runtime support (framework provides preparation patterns only)
- Mobile/desktop client support (focuses on server-side interfaces)
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
│  │   (Ring)    │ │(tools.cli)  │ │   (ClojureScript)   │ │
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

## Module Structure

Boundary follows a **module-centric architecture** where each domain module owns its complete functionality stack:

### Updated Module Template (Clean Architecture)

```
src/boundary/
├── {module-name}/           # DOMAIN MODULE (e.g., user, billing)
│   ├── schema.clj          # Domain entities and validation (Malli schemas)
│   ├── ports.clj           # Repository interfaces and contracts
│   ├── core/               # Pure business logic (DEPRECATED - moved to shell/service.clj)
│   │   ├── {entity}.clj    # Core domain functions
│   │   └── {business-logic}.clj
│   ├── shell/              # Application layer
│   │   └── service.clj     # Database-agnostic business services
│   ├── infrastructure/     # Infrastructure implementations
│   │   └── database.clj    # Database-specific repository implementations
│   ├── http.clj            # HTTP handlers & routes
│   └── cli.clj             # CLI commands & parsing
```

### Example: User Module (Updated Architecture)

```
src/boundary/user/
├── schema.clj             # User, UserSession schemas (Malli)
├── ports.clj              # IUserRepository, IUserSessionRepository interfaces
├── shell/
│   └── service.clj        # Database-agnostic business services
├── infrastructure/
│   └── database.clj       # Database repository implementations
├── http.clj               # POST /users, GET /users/{id} handlers
└── cli.clj                # user create, user list commands
```

**Key improvements:**
- Clean separation between business logic (service) and infrastructure (database)
- Database-agnostic services that use dependency injection
- Easy to test business logic with mocked repositories
- Clear layered architecture following clean architecture principles

### Core Function Example

```clojure
;; Pure business logic in user core
(ns boundary.user.core.user
  (:require [boundary.user.ports :as ports]))

(defn calculate-membership-benefits
  "Pure function: calculates membership benefits based on user data.
   No side effects, deterministic, easily testable."
  [user-data membership-rules current-date]
  (let [membership-years (calculate-years-active user-data current-date)
        tier (determine-tier membership-years (:spending-total user-data))
        discount-rate (get-discount-for-tier tier membership-rules)]
    {:membership-tier tier
     :discount-rate discount-rate
     :years-active membership-years
     :benefits (calculate-tier-benefits tier)}))
```

### Database-Agnostic Service Example

```clojure
;; Database-agnostic business service using dependency injection
(ns boundary.user.shell.service
  (:require [boundary.user.ports :as ports]
            [boundary.user.schema :as user-schema]
            [boundary.shared.utils.password :as password]
            [malli.core :as m]
            [clojure.tools.logging :as log]))

(defrecord UserService [user-repository session-repository]
  ;; Business service that uses repository interfaces, not implementations
  
  (create-user [_ user-data]
    "Create user with business validation and password hashing."
    (log/info "Creating user" {:email (:email user-data)})
    
    ;; Validate input using domain schema
    (when-not (m/validate user-schema/CreateUserRequest user-data)
      (throw (ex-info "Invalid user data" {:type :validation-error
                                           :errors (m/explain user-schema/CreateUserRequest user-data)})))
    
    ;; Business logic: hash password, validate email uniqueness
    (when (.find-user-by-email user-repository (:email user-data) (:tenant-id user-data))
      (throw (ex-info "User already exists" {:type :user-exists
                                             :email (:email user-data)})))
    
    (let [processed-user (-> user-data
                            (assoc :password-hash (password/hash (:password user-data)))
                            (dissoc :password))]
      ;; Delegate to infrastructure
      (.create-user user-repository processed-user)))
  
  (authenticate [_ email password]
    "Authenticate user and create session."
    (log/info "Authenticating user" {:email email})
    
    (if-let [user (.find-user-by-email user-repository email (:tenant-id user))]
      (if (password/verify password (:password-hash user))
        (let [session-data {:user-id (:id user)
                           :tenant-id (:tenant-id user)
                           :expires-at (-> (java.time.Instant/now)
                                          (.plusSeconds 3600))}]  ; 1 hour
          (.create-session session-repository session-data))
        (throw (ex-info "Invalid credentials" {:type :auth-failed})))
      (throw (ex-info "User not found" {:type :user-not-found})))))

;; Factory function for service creation
(defn create-user-service
  "Create a user service with injected repositories."
  [user-repository session-repository]
  (->UserService user-repository session-repository))
```

### Infrastructure Implementation Example

```clojure
;; Infrastructure adapter implementation in user module
(ns boundary.user.infrastructure.database
  (:require [boundary.user.ports :as ports]
            [boundary.shell.adapters.database.core :as db]
            [boundary.shared.utils.type-conversion :as type-conversion]
            [clojure.tools.logging :as log]))

(defrecord DatabaseUserRepository [ctx]  ; Uses generic database context
  ports/IUserRepository
  
  (find-user-by-email [_ email tenant-id]
    (log/debug "Finding user by email" {:email email :tenant-id tenant-id})
    (let [query {:select [:*]
                 :from [:users]
                 :where [:and
                         [:= :email email]
                         [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                         [:is :deleted_at nil]]}
          result (db/execute-one! ctx query)]
      (when result
        ;; Transform from database format to domain entity
        (-> result
            (update :id type-conversion/string->uuid)
            (update :tenant-id type-conversion/string->uuid)
            (update :role type-conversion/string->keyword)))))
  
  (create-user [_ user-entity]
    (log/info "Creating user" {:email (:email user-entity)})
    (let [now (java.time.Instant/now)
          user-with-metadata (-> user-entity
                                (assoc :id (java.util.UUID/randomUUID))
                                (assoc :created-at now)
                                (assoc :updated-at nil)
                                (assoc :deleted-at nil))
          ;; Transform to database format
          db-user (-> user-with-metadata
                     (update :id type-conversion/uuid->string)
                     (update :tenant-id type-conversion/uuid->string)
                     (update :role type-conversion/keyword->string))
          query {:insert-into :users
                 :values [db-user]}]
      (db/execute-update! ctx query)
      user-with-metadata)))

;; Factory function for repository creation
(defn create-user-repository
  "Create a database user repository instance."
  [ctx]
  (->DatabaseUserRepository ctx))
```

**Key differences from old approach:**
- Lives in `boundary.user.infrastructure.database` (user module owns its infrastructure)
- Uses generic database utilities from `boundary.shell.adapters.database.core`
- Handles user-specific entity transformations
- Clear separation between generic database layer and user-specific logic

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

# Run tests for specific module
clojure -M:test:db/h2 --focus :unit.user

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

# Specific test categories
clojure -M:test:db/h2 --focus :unit
clojure -M:test:db/h2 --focus :integration
clojure -M:test:db/h2 --focus :contract

# Specific modules
clojure -M:test:db/h2 --focus :user
clojure -M:test:db/h2 --focus :billing

# Watch mode with focus
clojure -M:test:db/h2 --watch --focus :unit

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

- [Product Requirements Document](docs/boundary.prd.adoc) - Comprehensive project requirements and vision
- [PRD Improvement Summary](docs/PRD-IMPROVEMENT-SUMMARY.adoc) - Recent improvements and development roadmap
- [Architecture Overview](docs/architecture/overview.adoc) - High-level architectural decisions and principles
- [Component Architecture](docs/architecture/components.adoc) - Detailed component organization and interactions
- [Data Flow Architecture](docs/architecture/data-flow.adoc) - Request processing and data transformation patterns
- [Ports and Adapters Guide](docs/architecture/ports-and-adapters.adoc) - Hexagonal architecture implementation
- [Layer Separation Guidelines](docs/architecture/layer-separation.adoc) - FC/IS boundary rules and practices

### Development Documentation

- [Implementation Guide](docs/implementation/) - Concrete implementation examples and patterns
- [API Documentation](docs/api/) - REST API specifications and examples
- [Architecture Diagrams](docs/diagrams/) - Visual system architecture references

### Infrastructure and Migration

- [User Module Architecture](docs/user-module-architecture.md) - Clean architecture implementation guide
- [Migration Guide](docs/migration-guide.md) - Step-by-step guide to new infrastructure
- [Infrastructure Examples](examples/user_infrastructure_example.clj) - Working code examples
- [Refactoring Summary](INFRASTRUCTURE-REFACTOR-SUMMARY.md) - Complete overview of infrastructure changes

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

Last updated: Based on PRD-IMPROVEMENT-SUMMARY.adoc and architecture documentation as of the current codebase state.
