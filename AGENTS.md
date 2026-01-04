# Boundary Framework - Comprehensive Agent Guide

**AI Agent Quick Reference**: This guide provides everything an AI coding agent needs to work effectively with the Boundary Framework - from quick commands to architectural patterns.

> **🛑 CRITICAL REMINDERS - READ THESE FIRST**
> 
> **GIT OPERATIONS - REQUIRE EXPLICIT PERMISSION:**
> - ❌ NEVER stage files with `git add` without asking first
> - ❌ NEVER commit with `git commit` without explicit user permission
> - ❌ NEVER push with `git push` without explicit user permission
> - ✅ ALWAYS show user what changes will be committed and ASK before committing
> - ✅ ALWAYS ask "Should I commit and push these changes?" and wait for confirmation
> 
> **CODE EDITING:**
> - Use clojure-mcp server for editing Clojure files (ensures balanced parentheses)
> - Always verify clojure-mcp is running before editing Clojure code
> - Follow parinfer conventions for proper formatting
> - All documentation must be kept up to date, accurate and in the English language

## Table of Contents

- [Project Overview](#project-overview)
- [Quick Start](#quick-start)
- [Quick Command Reference](#quick-command-reference)
- [Code Style Guidelines](#code-style-guidelines)
- [Common Pitfalls](#common-pitfalls)
- [Architecture Summary](#architecture-summary)
- [Key Technologies](#key-technologies)
- [Module Structure](#module-structure)
- [Configuration Management](#configuration-management)
- [Security Features](#security-features)
- [Secrets Management](#secrets-management)
- [Development Workflow](#development-workflow)
- [Module Scaffolding](#module-scaffolding)
- [Testing Strategy](#testing-strategy)
- [Database Operations](#database-operations)
- [Observability](#observability)
- [HTTP Interceptors](#http-interceptors)
- [Web UI Development](#web-ui-development)
- [Build and Deployment](#build-and-deployment)
- [Troubleshooting](#troubleshooting)

---

## Project Overview

Boundary is a **module-centric software framework** built on Clojure that implements the "Functional Core / Imperative Shell" architectural paradigm. Our PRD can be found [here](https://github.com/thijs-creemers/boundary-docs/tree/main/content/reference/boundary-prd.adoc).

### Key Characteristics

- **Module-Centric Architecture**: Each domain module (`user`, `billing`, `workflow`, `error_reporting`, `logging`, `metrics`) contains complete functionality from pure business logic to external interfaces
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
- Mobile/desktop client support (focuses on server-side interfaces)
- Specific domain logic (provides patterns, not implementations)
- Built-in authentication providers (supports auth patterns)

---

## Quick Start

### Prerequisites

**For macOS (using Homebrew):**
```bash
# Install required dependencies
brew install openjdk clojure/tools/clojure
```

**For Linux (Ubuntu/Debian):**
```bash
# Install JDK
sudo apt-get update
sudo apt-get install -y default-jdk rlwrap

# Install Clojure CLI
curl -L -O https://download.clojure.org/install/linux-install-1.12.3.1577.sh
chmod +x linux-install-*.sh && sudo ./linux-install-*.sh
```

### Getting Started

```bash
# Clone the repository
git clone <repository-url> boundary
cd boundary

# Run tests to verify setup
clojure -M:test:db/h2

# Start a development REPL
clojure -M:repl-clj

# In the REPL, load and start the system
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)  ; Start the system
```

### Verify Installation

```bash
# Check if tests pass
clojure -M:test:db/h2

# Lint the codebase
clojure -M:clj-kondo --lint src test

# Check for outdated dependencies (if alias exists)
clojure -M:outdated
```

### Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**
```bash
clj-nrepl-eval --discover-ports
```

**Evaluate code:**
```bash
clj-nrepl-eval -p <port> "<clojure-code>"

# With timeout (milliseconds)
clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"
```

**Note**: The REPL session persists between evaluations - namespaces and state are maintained. Always use `:reload` when requiring namespaces to pick up changes.

### Clojure LSP and clj-kondo

Use clj-kondo via Bash after making Clojure edits to verify syntax:
```bash
clojure -M:clj-kondo --lint src test
```

Use clojure-lsp CLI commands when available for operations like:
```bash
clojure-lsp format --dry      # Check formatting
clojure-lsp clean-ns          # Clean up namespaces
clojure-lsp diagnostics       # Deeper analysis
```

---

## Quick Command Reference

### Essential Commands

```bash
# Testing
clojure -M:test:db/h2                              # All tests (requires H2 driver)
clojure -M:test:db/h2 -n boundary.user.core.user-test  # Single namespace
clojure -M:test:db/h2 --focus-meta :unit           # Unit tests only
clojure -M:test:db/h2 --focus-meta :user           # User module tests
clojure -M:test:db/h2 --watch --focus-meta :unit   # Watch mode

# Code Quality
clojure -M:clj-kondo --lint src test               # Lint codebase
clojure -M:clj-kondo --config .clj-kondo/config.edn --lint src  # With config

# REPL Development
clojure -M:repl-clj                                # Start REPL
# In REPL:
(require '[integrant.repl :as ig-repl])
(ig-repl/go)                                       # Start system
(ig-repl/reset)                                    # Reload and restart
(ig-repl/halt)                                     # Stop system

# Build
clojure -T:build clean && clojure -T:build uber    # Build uberjar

# Clojure LSP & Tools
clojure-lsp format --dry                           # Check formatting
clojure-lsp clean-ns                               # Clean namespaces
clojure-lsp diagnostics                            # Deeper analysis

# nREPL Evaluation
clj-nrepl-eval --discover-ports                    # Find nREPL ports
clj-nrepl-eval -p <port> "<clojure-code>"         # Evaluate code
```

### Test Categories

```bash
# By Test Type
clojure -M:test:db/h2 --focus-meta :unit           # Pure core functions
clojure -M:test:db/h2 --focus-meta :integration    # Shell services
clojure -M:test:db/h2 --focus-meta :contract       # Adapter implementations

# By Module
clojure -M:test:db/h2 --focus-meta :user           # User module
clojure -M:test:db/h2 --focus-meta :billing        # Billing module
```

---

## Code Style Guidelines

### Architecture Principles

| Layer | Rules | Examples |
|-------|-------|----------|
| **Core** (`core/*`) | Pure functions only, no side effects | `user.clj`, `audit.clj` |
| **Shell** (`shell/*`) | All side effects, I/O, validation | `service.clj`, `persistence.clj`, `http.clj` |
| **Ports** (`ports.clj`) | Protocol definitions (abstractions) | `IUserService`, `IUserRepository` |
| **Schema** (`schema.clj`) | Malli schemas for validation | `CreateUserRequest`, `UserEntity` |

### Naming Conventions

```clojure
;; Functions and variables: kebab-case
(defn calculate-user-tier [user] ...)
(def max-login-attempts 5)

;; Predicates: end with ?
(defn active-user? [user] ...)

;; Collections: plural
(def users [...])
(def audit-logs [...])

;; Records: PascalCase
(defrecord UserService [user-repository session-repository] ...)
```

### Import Organization

```clojure
(ns boundary.user.shell.service
  ;; Clojure core and standard libraries first
  (:require [clojure.string :as str]
            [clojure.set :as set]
            ;; Third-party libraries alphabetically
            [malli.core :as m]
            [taoensso.timbre :as log]
            ;; Project namespaces alphabetically
            [boundary.user.core.user :as user-core]
            [boundary.user.ports :as ports]
            [boundary.shared.core.time :as time])
  ;; Java imports separate
  (:import [java.util UUID]
           [java.time Instant]))
```

### Formatting

- **Indentation**: 2 spaces (never tabs)
- **Parinfer**: Use parinfer-style formatting (careful parenthesis placement)
- **Line length**: Prefer 80-100 characters, max 120
- **Trailing parens**: Close on same line as last element

```clojure
;; Good
(defn create-user
  [user-data]
  (let [validated (validate-user user-data)
        prepared (prepare-user validated)]
    (save-user prepared)))

;; Bad - closing parens on separate lines
(defn create-user
  [user-data]
  (let [validated (validate-user user-data)
        prepared (prepare-user validated)
       ]
    (save-user prepared)
  )
)
```

### Documentation

```clojure
(defn calculate-membership-tier
  "Calculate user membership tier based on account age and activity.
   
   Args:
     user - User entity map with :created-at and :activity-score
     current-date - java.time.Instant for calculation reference
     
   Returns:
     Keyword - :bronze, :silver, :gold, or :platinum
     
   Pure: true"
  [user current-date]
  ...)
```

### Error Handling

```clojure
;; Core functions: return data (no exceptions for business logic)
(defn validate-user-data
  [user-data]
  {:valid? false
   :errors {:email ["Email is required"]}})

;; Shell functions: use ex-info with structured data
(when-not valid?
  (throw (ex-info "Validation failed"
                  {:type :validation-error
                   :errors errors
                   :data user-data})))
```

---

## Common Pitfalls

### 1. Key Naming: snake_case vs kebab-case

**The Problem**: Database layer returns `snake_case` keys while Clojure code uses `kebab-case`.

```clojure
;; ❌ WRONG - Looking for kebab-case in db result
(defn render-user [user]
  (:created-at user))  ; Returns nil if db returns :created_at

;; ✅ CORRECT - Check actual keys first
(keys user)  ; => (:id :email :created_at :password_hash)

;; ✅ CORRECT - Transform at persistence boundary
(defn db->user-entity [db-record]
  (-> db-record
      (clojure.set/rename-keys {:created_at :created-at
                                :password_hash :password-hash
                                :updated_at :updated-at})))
```

**Best Practice**: Always transform keys at the `db->entity` boundary in `shell/persistence.clj`.

**Recent Bug Example**: Audit table showing blank timestamps/IP addresses because UI code used `:created_at` (snake_case) instead of `:created-at` (kebab-case) that the entity transformation provided.

### 2. REPL Reloading with defrecord

**The Problem**: Reloading namespaces with `defrecord` doesn't update existing instances.

```clojure
;; Change UserService defrecord implementation
(defrecord UserService [repo]
  IUserService
  (create-user [this data]
    ;; NEW IMPLEMENTATION
    ...))

;; ❌ WRONG - System still has old implementation
(ig-repl/reset)  ; Doesn't recreate defrecord instances

;; ✅ CORRECT - Full system restart
(ig-repl/halt)
(ig-repl/go)

;; OR clear cache and restart REPL
rm -rf .cpcache
clojure -M:repl-clj
```

### 3. Parenthesis Balancing

**The Problem**: Manual editing of Clojure code often creates unbalanced parentheses.

```clojure
;; ❌ WRONG - Manual editing
(defn process-user [user]
  (let [validated (validate user)
        processed (process validated)]  ; Missing closing paren
    processed)

;; ✅ CORRECT - Use clojure-mcp
# Always use clojure-mcp server for editing Clojure files
```

**Best Practice**: Use `clojure-mcp` for all Clojure file edits. Verify it's running before editing.

### 4. Validation at Wrong Layer

```clojure
;; ❌ WRONG - Validation in core
(defn create-user-core [user-data]
  (when-not (m/validate UserSchema user-data)  ; Side effect!
    (throw (ex-info ...)))  ; Exceptions in pure code!
  ...)

;; ✅ CORRECT - Validation in shell
(defn create-user-service [this user-data]
  (let [[valid? errors data] (validate-request UserSchema user-data)]
    (if valid?
      (user-core/create-user data)  ; Core receives clean data
      (throw (ex-info "Validation failed" {:errors errors})))))
```

### 5. Direct Adapter Dependencies in Core

```clojure
;; ❌ WRONG - Core depends on concrete implementation
(ns boundary.user.core.user
  (:require [boundary.user.shell.persistence :as db]))  ; BAD!

(defn find-user [id]
  (db/find-by-id id))  ; Core calling shell directly!

;; ✅ CORRECT - Core depends on ports (protocols)
(ns boundary.user.core.user)

(defn find-user-decision [user-id existing-user]
  (if existing-user
    {:action :use-existing :user existing-user}
    {:action :not-found :user-id user-id}))

;; Shell orchestrates
(ns boundary.user.shell.service
  (:require [boundary.user.ports :as ports]
            [boundary.user.core.user :as core]))

(defn find-user [this user-id]
  (let [existing (.find-by-id user-repository user-id)
        decision (core/find-user-decision user-id existing)]
    (case (:action decision)
      :use-existing (:user decision)
      :not-found nil)))
```

---

## Architecture Summary

### Functional Core / Imperative Shell (FC/IS)

```
┌─────────────────────────────────────────────────────────┐
│                  Presentation Layer                     │
│   REST API (Ring)  │  CLI (tools.cli)  │  Web (HTMX)    │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│              IMPERATIVE SHELL (shell/*)                 │
│  • Side effects (I/O, network, persistence)             │
│  • Validation and coercion (Malli)                      │
│  • Error translation (domain → HTTP/CLI)                │
│  • Logging, metrics, error reporting                    │
│  • Adapter implementations                              │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼ (depends on)
┌─────────────────────────────────────────────────────────┐
│                 PORTS (ports.clj)                       │
│  • Protocol definitions (interfaces)                    │
│  • Abstract contracts                                   │
│  • No implementations                                   │
└─────────────────────────────────────────────────────────┘
                           ▲ (implements)
                           │
┌─────────────────────────────────────────────────────────┐
│              FUNCTIONAL CORE (core/*)                   │
│  • Pure functions only (no side effects)                │
│  • Business logic and rules                             │
│  • Domain calculations                                  │
│  • Testable without mocks                               │
└─────────────────────────────────────────────────────────┘
```

### Dependency Rules

| Direction | Allowed? | Rule |
|-----------|----------|------|
| Shell → Core | ✅ | Shell calls core functions with validated data |
| Core → Ports | ✅ | Core depends on abstract interfaces only |
| Shell → Adapters | ✅ | Shell provides concrete implementations |
| **Core → Shell** | ❌ | **NEVER** - Core must not depend on shell |
| **Core → Adapters** | ❌ | **NEVER** - Core must not depend on concrete impls |

---

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
| **HTMX** | Progressive enhancement | Dynamic web UI interactions |
| **Hiccup** | HTML generation | Server-side rendering |

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
- **HTMX over React/Vue**: Server-side rendering, no build step required

---

## Module Structure

### Standard Module Layout

```
src/boundary/{module}/
├── core/
│   ├── {domain1}.clj     # Pure business logic
│   ├── {domain2}.clj     # Pure calculations
│   └── ui.clj            # Pure UI generation (Hiccup)
├── shell/
│   ├── service.clj       # Business service orchestration
│   ├── persistence.clj   # Database adapter
│   ├── http.clj          # REST API routes
│   ├── cli.clj           # CLI commands
│   └── web_handlers.clj  # Web UI handlers
├── ports.clj             # Protocol definitions
└── schema.clj            # Malli schemas

test/boundary/{module}/
├── core/
│   ├── {domain1}_test.clj    # Unit tests (no mocks)
│   └── ui_test.clj            # Pure UI tests
└── shell/
    ├── service_test.clj       # Integration tests (mocked deps)
    └── persistence_test.clj   # Contract tests (real db)
```

### Example: User Module

```
src/boundary/user/
├── core/
│   ├── user.clj              # User business logic
│   ├── session.clj           # Session logic
│   ├── audit.clj             # Audit logic
│   ├── mfa.clj               # MFA business logic (Phase 4.3)
│   └── ui.clj                # UI components (Hiccup)
├── shell/
│   ├── service.clj           # UserService (orchestration)
│   ├── persistence.clj       # DatabaseUserRepository
│   ├── auth.clj              # Authentication with MFA support
│   ├── mfa.clj               # MFA service (TOTP, backup codes)
│   ├── http.clj              # REST routes (includes MFA endpoints)
│   ├── cli.clj               # CLI commands
│   └── web_handlers.clj      # Web handlers
├── ports.clj                 # IUserService, IUserRepository
└── schema.clj                # CreateUserRequest, UserEntity, MFA schemas
```

**MFA Features (Phase 4.3 - COMPLETE)**:
- TOTP authentication (6-digit codes, 30s window)
- 10 backup codes per user (single-use)
- QR code generation for authenticator apps
- Seamless integration with login flow
- See [MFA Setup Guide](./docs/guides/mfa-setup.md) for details

---

## Configuration Management

Boundary uses **Aero** for sophisticated configuration management with module-centric organization and environment-based profiles.

### Configuration Structure

```
resources/
└── conf/
    └── dev/
        └── config.edn       # Development configuration
```

### Environment Setup

```bash
# Development environment variables
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

### Current Development Configuration

**Example (`resources/conf/dev/config.edn`):**
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

### PostgreSQL Setup (Optional)

```bash
# Set environment variables for PostgreSQL
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=boundary_dev
export POSTGRES_USER=boundary_dev
export POSTGRES_PASSWORD=dev_password

# Verify configuration in REPL
clojure -M:repl-clj
user=> (require '[boundary.config :as config])
user=> (def cfg (config/load-config))
user=> (keys (:active cfg))  ; See active configuration sections
```

---

## Security Features

Boundary implements enterprise-grade security features with a focus on authentication, authorization, and data protection.

### Multi-Factor Authentication (MFA)

**Status**: ✅ Production Ready (Phase 4.3 - Complete)

Boundary includes comprehensive MFA support using TOTP (Time-based One-Time Password) authentication:

**Features**:
- **TOTP Support**: Compatible with Google Authenticator, Authy, 1Password, and all RFC 6238 compliant authenticator apps
- **Backup Codes**: 10 single-use backup codes (12 characters each, formatted with dashes)
- **QR Code Generation**: Automatic QR code URL generation for easy setup
- **Seamless Integration**: Zero-downtime integration with existing authentication flow
- **Security Best Practices**: Cryptographically secure secret generation (SecureRandom), Base32 encoding

**Quick Example**:
```bash
# 1. Setup MFA (returns QR code, secret, backup codes)
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <token>"

# 2. Enable MFA with verification code
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"secret": "JBSWY3DPEHPK3PXP", "backupCodes": [...], "verificationCode": "123456"}'

# 3. Login with MFA
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password", "mfa-code": "123456"}'
```

**Implementation Details**:
- **Core Logic**: `src/boundary/user/core/mfa.clj` (350 lines, pure functions)
- **Shell Service**: `src/boundary/user/shell/mfa.clj` (270 lines, I/O operations)
- **Migration**: `migrations/006_add_mfa_to_users.sql` (5 new columns + indexes)
- **Test Coverage**: 21 tests, 117 assertions, 100% passing

**API Endpoints**:
- `POST /api/auth/mfa/setup` - Initialize MFA setup
- `POST /api/auth/mfa/enable` - Enable MFA after verification
- `POST /api/auth/mfa/disable` - Disable MFA
- `GET /api/auth/mfa/status` - Check MFA status and remaining backup codes
- `POST /api/auth/login` - Login with optional MFA code

**Documentation**:
- **[MFA Setup Guide](./docs/guides/mfa-setup.md)** - Comprehensive user and developer guide
- **[MFA Completion Summary](./MFA_COMPLETION_SUMMARY.md)** - Technical implementation details

**Architecture (FC/IS Pattern)**:
```
Core (Pure):
  - should-require-mfa? (business logic)
  - can-enable-mfa? (validation)
  - prepare-mfa-enablement (data transformation)
  - is-valid-backup-code? (validation)

Shell (I/O):
  - generate-totp-secret (SecureRandom)
  - verify-totp-code (TOTP verification)
  - generate-backup-codes (cryptographic generation)
  - create-qr-code-url (external service)
```

**Security Considerations**:
- Secrets stored encrypted in database
- TOTP codes expire after 30 seconds
- Backup codes are single-use (marked used after first use)
- Rate limiting recommended (5 attempts per minute)
- Audit logging for all MFA events
- HTTPS required in production
- Compliant with NIST SP 800-63B Level 2

**Testing**:
```bash
# Run MFA tests
clojure -M:test:db/h2 --focus boundary.user.core.mfa-test --focus boundary.user.shell.mfa-test

# Check MFA status in REPL
clojure -M:repl-clj
user=> (require '[boundary.user.shell.mfa :as mfa])
user=> (mfa/verify-totp-code "123456" "JBSWY3DPEHPK3PXP")
```

---

## Secrets Management

Boundary implements a **defense-in-depth approach to secrets management**, progressing from basic environment variables to enterprise-grade secret vaults.

### Secrets Management Strategy

**Security Principles:**
1. **Never commit secrets to version control** - Use `.gitignore` for sensitive files
2. **Fail fast on missing secrets** - Application refuses to start without required configuration
3. **Separate secrets per environment** - Development, staging, production use different secrets
4. **Rotate secrets regularly** - Support zero-downtime secret rotation
5. **Audit secret access** - Log when secrets are retrieved (not values)

### Current Implementation: Environment Variables

**Development Setup:**

```bash
# Required secrets (application fails without these)
export JWT_SECRET="dev-secret-minimum-32-characters-long"
export DB_PASSWORD="dev_password"

# Optional secrets (have sensible defaults)
export SMTP_PASSWORD="optional_mail_password"
export SENTRY_DSN="optional_sentry_dsn"
```

**Reading Secrets in Code:**

```clojure
(ns boundary.user.shell.auth
  (:require [clojure.tools.logging :as log]))

;; SECURE: Fail fast if secret not configured
(def ^:private jwt-secret
  "JWT signing secret loaded from environment variable.

   SECURITY: Must be set via JWT_SECRET environment variable.
   Throws exception if not configured to prevent accidental use of default secret."
  (or (System/getenv "JWT_SECRET")
      (throw (ex-info "JWT_SECRET environment variable not configured. Set JWT_SECRET before starting the application."
                      {:type :configuration-error
                       :required-env-var "JWT_SECRET"}))))

;; Log that secret was loaded (never log the value)
(log/info "JWT secret loaded from environment variable")
```

**Aero Integration:**

```clojure
;; In resources/conf/dev/config.edn
{:active
 {:boundary/database
  {:password #env "DB_PASSWORD"}     ; Required environment variable

 :boundary/smtp
  {:password #or [#env "SMTP_PASSWORD" "default-dev-password"]}  ; Optional with default

 :boundary/auth
  {:jwt-secret #env "JWT_SECRET"}}}  ; Required, no default
```

### Production Secrets Management

#### Option 1: AWS Secrets Manager

**Installation:**

```clojure
;; Add to deps.edn
{:deps {com.amazonaws/aws-java-sdk-secretsmanager {:mvn/version "1.12.500"}}}
```

**Implementation:**

```clojure
(ns boundary.platform.shell.adapters.secrets.aws-secrets-manager
  "AWS Secrets Manager adapter for retrieving secrets.

   Supports:
   - Secret retrieval by name
   - Automatic credential handling (IAM roles)
   - Caching with TTL
   - Secret rotation without downtime"
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json])
  (:import [com.amazonaws.services.secretsmanager AWSSecretsManagerClientBuilder]
           [com.amazonaws.services.secretsmanager.model GetSecretValueRequest]))

(defn create-client
  "Create AWS Secrets Manager client.

   Uses default credential chain:
   1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
   2. EC2 instance profile
   3. ECS task role"
  []
  (-> (AWSSecretsManagerClientBuilder/standard)
      (.withRegion (or (System/getenv "AWS_REGION") "us-east-1"))
      (.build)))

(defn get-secret
  "Retrieve secret value from AWS Secrets Manager.

   Args:
     client - AWS Secrets Manager client
     secret-name - Name of secret in AWS Secrets Manager

   Returns:
     Secret value as string (for JSON secrets, parse with json/read-str)

   Example:
     (get-secret client \"prod/boundary/jwt-secret\")"
  [client secret-name]
  (try
    (log/info "Retrieving secret from AWS Secrets Manager" {:secret-name secret-name})
    (let [request (-> (GetSecretValueRequest.)
                      (.withSecretId secret-name))
          result (.getSecretValue client request)]
      (.getSecretString result))
    (catch Exception e
      (log/error e "Failed to retrieve secret from AWS Secrets Manager"
                 {:secret-name secret-name})
      (throw (ex-info "Secret retrieval failed"
                      {:type :secret-retrieval-error
                       :secret-name secret-name}
                      e)))))

(defn load-secrets-from-aws
  "Load all application secrets from AWS Secrets Manager.

   Returns:
     Map of secret keys to values

   Example:
     {:jwt-secret \"abc123...\"
      :db-password \"xyz789...\"}"
  []
  (let [client (create-client)
        secrets-json (get-secret client "prod/boundary/secrets")]
    (json/read-str secrets-json :key-fn keyword)))
```

**Usage in Application:**

```clojure
(ns boundary.config
  (:require [boundary.platform.shell.adapters.secrets.aws-secrets-manager :as aws-secrets]))

(defn load-production-secrets
  "Load secrets based on environment."
  []
  (case (System/getenv "BND_ENV")
    "production" (aws-secrets/load-secrets-from-aws)
    "staging"    (aws-secrets/load-secrets-from-aws)
    {}))  ; Development uses environment variables

(defn ig-config
  [config]
  (let [secrets (load-production-secrets)]
    (merge config secrets)))
```

**AWS Secrets Manager Setup:**

```bash
# Create secret in AWS
aws secretsmanager create-secret \
  --name prod/boundary/secrets \
  --secret-string '{
    "jwt-secret": "production-jwt-secret-32-chars",
    "db-password": "production-db-password",
    "smtp-password": "production-smtp-password"
  }'

# Grant EC2/ECS permission to read secret
aws secretsmanager put-resource-policy \
  --secret-id prod/boundary/secrets \
  --resource-policy '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"AWS": "arn:aws:iam::ACCOUNT_ID:role/boundary-app-role"},
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "*"
    }]
  }'
```

#### Option 2: HashiCorp Vault

**Installation:**

```clojure
;; Add to deps.edn
{:deps {vault-clj/vault-clj {:mvn/version "1.1.0"}}}
```

**Implementation:**

```clojure
(ns boundary.platform.shell.adapters.secrets.vault
  "HashiCorp Vault adapter for secret retrieval."
  (:require [clojure.tools.logging :as log]
            [vault.client.http]
            [vault.core :as vault]))

(defn create-client
  "Create Vault client with authentication.

   Supports:
   - Token authentication (VAULT_TOKEN env var)
   - AppRole authentication (VAULT_ROLE_ID, VAULT_SECRET_ID)
   - Kubernetes authentication (for K8s deployments)"
  []
  (let [vault-addr (or (System/getenv "VAULT_ADDR")
                       "http://localhost:8200")
        vault-token (System/getenv "VAULT_TOKEN")]
    (when-not vault-token
      (throw (ex-info "VAULT_TOKEN environment variable not set"
                      {:type :configuration-error})))
    (vault/new-client vault-addr vault-token)))

(defn get-secret
  "Retrieve secret from Vault.

   Args:
     client - Vault client
     path - Secret path (e.g., 'secret/data/boundary/prod')

   Returns:
     Map of secret key-value pairs"
  [client path]
  (try
    (log/info "Retrieving secret from Vault" {:path path})
    (let [response (vault/read-secret client path)]
      (:data response))
    (catch Exception e
      (log/error e "Failed to retrieve secret from Vault" {:path path})
      (throw (ex-info "Vault secret retrieval failed"
                      {:type :secret-retrieval-error
                       :path path}
                      e)))))

(defn load-secrets-from-vault
  "Load application secrets from Vault.

   Returns:
     Map of secret keys to values"
  []
  (let [client (create-client)
        env (or (System/getenv "BND_ENV") "dev")]
    (get-secret client (str "secret/data/boundary/" env))))
```

**Vault Setup:**

```bash
# Enable KV v2 secrets engine
vault secrets enable -version=2 kv

# Write secrets to Vault
vault kv put secret/boundary/prod \
  jwt-secret="production-jwt-secret-32-chars" \
  db-password="production-db-password" \
  smtp-password="production-smtp-password"

# Create policy for boundary application
vault policy write boundary-app - <<EOF
path "secret/data/boundary/*" {
  capabilities = ["read"]
}
EOF

# Create token for application
vault token create -policy=boundary-app -ttl=720h
```

### Configuration Validation on Startup

**Validate Required Secrets:**

```clojure
(ns boundary.platform.shell.validation
  "Startup configuration validation."
  (:require [clojure.tools.logging :as log]))

(def required-secrets
  "List of required secrets that must be present."
  [:jwt-secret
   :db-password])

(defn validate-secrets
  "Validate that all required secrets are present.

   Args:
     config - Configuration map

   Returns:
     config if valid

   Throws:
     ex-info if secrets are missing"
  [config]
  (let [missing (remove #(get config %) required-secrets)]
    (if (seq missing)
      (do
        (log/error "Missing required secrets" {:missing-secrets missing})
        (throw (ex-info "Configuration validation failed: missing required secrets"
                        {:type :configuration-error
                         :missing-secrets missing})))
      (do
        (log/info "Configuration validation passed"
                  {:validated-secrets (count required-secrets)})
        config))))

(defn validate-secret-format
  "Validate secret values meet security requirements.

   Example: JWT secret must be at least 32 characters"
  [config]
  (when-let [jwt-secret (:jwt-secret config)]
    (when (< (count jwt-secret) 32)
      (throw (ex-info "JWT_SECRET must be at least 32 characters"
                      {:type :configuration-error
                       :secret :jwt-secret
                       :length (count jwt-secret)}))))
  config)

(defn validate-config
  "Run all configuration validations.

   Call this at application startup before starting system."
  [config]
  (-> config
      validate-secrets
      validate-secret-format))
```

**Usage:**

```clojure
(ns boundary.core
  (:require [boundary.config :as config]
            [boundary.platform.shell.validation :as validation]
            [integrant.core :as ig]))

(defn -main
  [& args]
  (let [config (config/load-config)
        validated-config (validation/validate-config config)
        system (ig/init (config/ig-config validated-config))]
    ;; System started with validated configuration
    system))
```

### Secret Rotation Without Downtime

**Strategy:**

1. **Dual-Value Support**: Application accepts both old and new secrets during rotation window
2. **Gradual Migration**: Update secrets in vault, application picks up new values
3. **Health Checks**: Verify new secrets work before removing old ones

**Implementation:**

```clojure
(ns boundary.platform.shell.secrets.rotation
  "Support for zero-downtime secret rotation."
  (:require [clojure.tools.logging :as log]))

(defn get-jwt-secret-with-fallback
  "Get JWT secret with rotation support.

   During rotation period:
   1. Try JWT_SECRET_NEW first
   2. Fall back to JWT_SECRET

   This allows gradual migration:
   1. Deploy with both secrets
   2. Update vault to JWT_SECRET_NEW
   3. Wait for tokens issued with old secret to expire
   4. Remove JWT_SECRET, rename JWT_SECRET_NEW to JWT_SECRET"
  []
  (or (System/getenv "JWT_SECRET_NEW")
      (System/getenv "JWT_SECRET")
      (throw (ex-info "No JWT secret configured"
                      {:type :configuration-error}))))

(defn verify-jwt-token-with-rotation
  "Verify JWT token, trying both current and previous secrets.

   This allows tokens signed with old secret to remain valid
   during rotation window."
  [token]
  (let [current-secret (System/getenv "JWT_SECRET")
        previous-secret (System/getenv "JWT_SECRET_PREVIOUS")]
    (or (try-verify-token token current-secret)
        (when previous-secret
          (log/info "Token verification with current secret failed, trying previous secret")
          (try-verify-token token previous-secret))
        (throw (ex-info "JWT verification failed with all secrets"
                        {:type :authentication-error})))))
```

**Rotation Procedure:**

```bash
# Step 1: Generate new secret
NEW_JWT_SECRET=$(openssl rand -base64 32)

# Step 2: Deploy application with both secrets
export JWT_SECRET="old-secret"
export JWT_SECRET_NEW="$NEW_JWT_SECRET"
# Deploy application (supports both secrets)

# Step 3: Update Vault/Secrets Manager with new secret
aws secretsmanager update-secret \
  --secret-id prod/boundary/jwt-secret \
  --secret-string "$NEW_JWT_SECRET"

# Step 4: Wait for old tokens to expire (e.g., 24 hours)
sleep 86400

# Step 5: Deploy with only new secret
export JWT_SECRET="$NEW_JWT_SECRET"
unset JWT_SECRET_NEW
# Deploy application (old secret removed)
```

### Best Practices

#### Development Environment

```bash
# .env.example (commit to repository)
JWT_SECRET=dev-secret-minimum-32-characters-long
DB_PASSWORD=dev_password
SMTP_PASSWORD=optional_mail_password

# .env (DO NOT commit - add to .gitignore)
JWT_SECRET=actual-dev-secret-32-chars-min
DB_PASSWORD=actual_dev_password
```

#### Production Environment

**AWS EC2/ECS:**

```bash
# Use IAM roles, no credentials in environment
# Secrets retrieved from AWS Secrets Manager
# Application logs secret retrieval (not values)
```

**Kubernetes:**

```yaml
# Use Kubernetes secrets mounted as files
apiVersion: v1
kind: Pod
metadata:
  name: boundary-app
spec:
  containers:
  - name: app
    image: boundary:latest
    env:
    - name: JWT_SECRET
      valueFrom:
        secretKeyRef:
          name: boundary-secrets
          key: jwt-secret
    - name: DB_PASSWORD
      valueFrom:
        secretKeyRef:
          name: boundary-secrets
          key: db-password
```

#### Security Checklist

- [ ] No secrets in version control (check `.gitignore`)
- [ ] No secrets in logs (use `[REDACTED]` when logging)
- [ ] No secrets in error messages
- [ ] Secrets validated at startup (fail fast)
- [ ] Secrets rotated regularly (document schedule)
- [ ] Different secrets per environment
- [ ] Secret access audited
- [ ] Encryption at rest (Vault/Secrets Manager)
- [ ] Encryption in transit (TLS)
- [ ] Least privilege access (IAM policies)

#### Audit Logging

```clojure
(defn get-secret-with-audit
  "Retrieve secret with audit logging.

   IMPORTANT: Never log secret values, only metadata."
  [secret-name]
  (log/info "Secret accessed"
            {:secret-name secret-name
             :user (System/getProperty "user.name")
             :timestamp (java.time.Instant/now)})
  (get-secret-value secret-name))
```

### Migration Path

**Current State → Production Ready:**

1. **Phase 1: Environment Variables** ✅ (Current)
   - Development uses `.env` files
   - Secrets loaded via `System/getenv`
   - Fail-fast validation

2. **Phase 2: Secret Vault Integration** (Next)
   - Implement AWS Secrets Manager adapter
   - Add configuration for vault selection
   - Test secret rotation procedures

3. **Phase 3: Automated Rotation** (Future)
   - Schedule automatic secret rotation
   - Implement rotation validation
   - Monitor rotation success/failure

### See Also

- [Configuration Management](#configuration-management) - How Aero config works
- [Phase 1 Security Fixes](/PHASE1_SECURITY_FIXES.md) - JWT secret environment variable fix
- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [HashiCorp Vault Documentation](https://www.vaultproject.io/docs)
- [12-Factor App: Config](https://12factor.net/config)

---

## Development Workflow

### 1. Starting Development

```bash
# Start REPL
clojure -M:repl-clj

# In REPL
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)  ; Start system

# Verify system is running
user=> (require '[boundary.user.ports :as ports])
user=> (ports/list-users user-service {})  ; Test service

# Make changes in editor, then:
user=> (ig-repl/reset)  ; Reload and restart
```

### 2. Adding New Functionality

**Step-by-Step Process**:

1. **Define Schema** (in `{module}/schema.clj`)
   ```clojure
   (def UpdateUserRequest
     [:map
      [:id :uuid]
      [:name [:string {:min 1}]]
      [:email :email]])
   ```

2. **Write Core Logic** (in `{module}/core/{domain}.clj`)
   ```clojure
   (defn prepare-user-update
     "Pure function to prepare user update.
      
      Args:
        existing-user - Current user entity
        update-data - New data to apply
        
      Returns:
        Updated user entity map
        
      Pure: true"
     [existing-user update-data]
     (merge existing-user
            (select-keys update-data [:name :email])
            {:updated-at (java.time.Instant/now)}))
   ```

3. **Write Unit Tests** (in `test/{module}/core/{domain}_test.clj`)
   ```clojure
   (deftest prepare-user-update-test
     (testing "updates user fields"
       (let [existing {:id #uuid "..." :name "Old" :email "old@example.com"}
             updates {:name "New" :email "new@example.com"}
             result (core/prepare-user-update existing updates)]
         (is (= "New" (:name result)))
         (is (= "new@example.com" (:email result))))))
   ```

4. **Define Port** (in `{module}/ports.clj`)
   ```clojure
   (defprotocol IUserService
     (update-user [this user-id update-data]))
   ```

5. **Implement in Service** (in `{module}/shell/service.clj`)
   ```clojure
   (defrecord UserService [user-repository]
     IUserService
     (update-user [this user-id update-data]
       (let [existing (.find-by-id user-repository user-id)]
         (when-not existing
           (throw (ex-info "User not found" {:user-id user-id})))
         (let [updated (user-core/prepare-user-update existing update-data)]
           (.update-user user-repository updated)))))
   ```

6. **Add HTTP Endpoint** (in `{module}/shell/http.clj`)
   ```clojure
   ["/api/users/:id" {:put {:handler (handlers/update-user user-service config)}}]
   ```

### 3. Testing Workflow

```bash
# Run tests in watch mode while developing
clojure -M:test:db/h2 --watch --focus-meta :unit

# In another terminal, run full test suite periodically
clojure -M:test:db/h2

# Before committing, run lint
clojure -M:clj-kondo --lint src test
```

### 4. Debugging in REPL

```clojure
;; Check system state
user=> (keys integrant.repl.state/system)

;; Get service instance
user=> (def user-service (::user/service integrant.repl.state/system))

;; Test service directly
user=> (user-ports/list-users user-service {:limit 10})

;; Check database connection
user=> (def db-ctx (::db/context integrant.repl.state/system))
user=> (db/execute-one! db-ctx {:select [[1 :result]]})

;; Reload specific namespace
user=> (require '[boundary.user.core.user :as user-core] :reload)

;; Full system reset
user=> (ig-repl/reset)
```

---

## Module Scaffolding

Boundary includes a comprehensive scaffolder that generates complete, production-ready modules following FC/IS architecture patterns.

### Quick Usage

```bash
# Generate a complete module
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required:unique \
  --field price:decimal:required \
  --field description:text

# Dry-run to preview without creating files
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --dry-run
```

### Field Specification Format

Field specs follow the pattern: `name:type[:required][:unique]`

**Supported Types:**
- `string` - Variable length text
- `text` - Long-form text
- `int` / `integer` - Integer numbers
- `decimal` - Decimal numbers
- `boolean` - True/false values
- `email` - Email addresses (validated)
- `uuid` - UUID identifiers
- `enum` - Enumeration values
- `date` / `datetime` / `inst` - Timestamps
- `json` - JSON/map data

**Examples:**
```bash
--field email:email:required:unique      # Required unique email
--field name:string:required             # Required string
--field age:int                          # Optional integer
--field status:enum                      # Optional enum
--field created-date:date:required       # Required timestamp
```

### Generated Files

The scaffolder generates 12 files per module:

**Source Files (9):**
1. `src/boundary/{module}/schema.clj` - Malli schemas
2. `src/boundary/{module}/ports.clj` - Protocol definitions
3. `src/boundary/{module}/core/{entity}.clj` - Pure business logic
4. `src/boundary/{module}/core/ui.clj` - Hiccup UI components
5. `src/boundary/{module}/shell/service.clj` - Service orchestration
6. `src/boundary/{module}/shell/persistence.clj` - Database operations
7. `src/boundary/{module}/shell/http.clj` - HTTP routes
8. `src/boundary/{module}/shell/web_handlers.clj` - Web UI handlers
9. `migrations/NNN_create_{entities}.sql` - Database migration

**Test Files (3):**
10. `test/boundary/{module}/core/{entity}_test.clj` - Unit tests
11. `test/boundary/{module}/shell/{entity}_repository_test.clj` - Persistence tests
12. `test/boundary/{module}/shell/service_test.clj` - Service integration tests

### Integration Steps

After generating a module, integrate it into the system:

**1. Create Module Wiring**

Create `src/boundary/{module}/shell/module_wiring.clj`:

```clojure
(ns boundary.{module}.shell.module-wiring
  "Integrant wiring for the {module} module."
  (:require [boundary.{module}.shell.persistence :as persistence]
            [boundary.{module}.shell.service :as service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :boundary/{module}-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing {module} repository")
  (persistence/create-repository ctx))

(defmethod ig/halt-key! :boundary/{module}-repository
  [_ _repo]
  (log/info "{module} repository halted"))

(defmethod ig/init-key :boundary/{module}-service
  [_ {:keys [repository]}]
  (log/info "Initializing {module} service")
  (service/create-service repository))

(defmethod ig/halt-key! :boundary/{module}-service
  [_ _service]
  (log/info "{module} service halted"))

(defmethod ig/init-key :boundary/{module}-routes
  [_ {:keys [service config]}]
  (log/info "Initializing {module} routes")
  (require 'boundary.{module}.shell.http)
  (let [routes-fn (ns-resolve 'boundary.{module}.shell.http 'routes)]
    (routes-fn service config)))

(defmethod ig/halt-key! :boundary/{module}-routes
  [_ _routes]
  (log/info "{module} routes halted"))
```

**2. Add Module Configuration**

In `src/boundary/config.clj`, add the module config function:

```clojure
(defn- {module}-module-config
  "Return Integrant configuration for the {module} module."
  [config]
  {:boundary/{module}-repository
   {:ctx (ig/ref :boundary/db-context)}
   
   :boundary/{module}-service
   {:repository (ig/ref :boundary/{module}-repository)}
   
   :boundary/{module}-routes
   {:service (ig/ref :boundary/{module}-service)
    :config config}})
```

Then merge it into `ig-config`:

```clojure
(defn ig-config
  [config]
  (merge (core-system-config config)
         (user-module-config config)
         ({module}-module-config config)))  ; Add this line
```

**3. Wire Module into System**

In `src/boundary/shell/system/wiring.clj`, add the module wiring to requires:

```clojure
(:require ...
          [boundary.{module}.shell.module-wiring] ; Add this
          ...)
```

**4. Update HTTP Handler**

In `src/boundary/config.clj`, add the module routes to the HTTP handler:

```clojure
:boundary/http-handler
{:config config
 :user-routes (ig/ref :boundary/user-routes)
 :{module}-routes (ig/ref :boundary/{module}-routes)} ; Add this
```

In `src/boundary/shell/system/wiring.clj`, update the HTTP handler to accept and compose the new routes:

```clojure
(defmethod ig/init-key :boundary/http-handler
  [_ {:keys [config user-routes {module}-routes]}] ; Add {module}-routes
  ...
  ;; Extract and combine routes
  (let [user-api-routes (or (:api user-routes) [])
        {module}-api-routes (or (:api {module}-routes) []) ; Add this
        ...
        all-routes (concat ...
                           user-api-routes
                           {module}-api-routes)            ; Add this
        ...))
```

**5. Run Database Migration**

```bash
# Apply the generated migration
psql -U boundary_dev -d boundary_dev -f migrations/NNN_create_{entities}.sql
```

**6. Verify Integration**

```bash
# Lint the module
clojure -M:clj-kondo --lint src/boundary/{module}/ test/boundary/{module}/

# Run module tests
clojure -M:test:db/h2 --focus-meta :{module}

# Start the system and verify routes
clojure -M:repl-clj
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)
```

### Example: Generated Inventory Module

```bash
# Generate inventory module
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name inventory \
  --entity Item \
  --field name:string:required \
  --field sku:string:required:unique \
  --field quantity:int:required \
  --field location:string:required
```

**Result:**
- ✅ 12 files generated
- ✅ Zero linting errors
- ✅ Complete FC/IS architecture
- ✅ REST API + Web UI routes
- ✅ Database schema with indexes
- ✅ Comprehensive test coverage

### Customizing Generated Code

The scaffolder generates minimal but correct implementations. Enhance them by:

1. **Add Business Logic**: Implement validation and domain rules in `core/{entity}.clj`
2. **Enhance UI**: Add rich Hiccup templates in `core/ui.clj`
3. **Add Features**: Extend service methods in `shell/service.clj`
4. **Implement Queries**: Add complex queries in `shell/persistence.clj`
5. **Add Routes**: Extend HTTP routes in `shell/http.clj`

### Generated Route Formats

The scaffolder generates **only normalized routes** in `shell/http.clj`:

**Normalized Format** (framework-agnostic):
```clojure
(defn normalized-api-routes [service]
  [{:path "/items"
    :methods {:get {:handler ...}}}])  ; Framework-agnostic format

(defn {module}-routes-normalized [service config]
  {:api (normalized-api-routes service)
   :web (normalized-web-routes service config)
   :static []})
```

**Benefits of Normalized Routes:**
- Framework-agnostic (not tied to Reitit)
- Support for HTTP interceptors (see [HTTP Interceptors](#http-interceptors))
- Cleaner composition at top-level router
- Consistent pattern across all modules

**Note**: Legacy Reitit-specific route functions (`api-routes`, `web-ui-routes`, `user-routes`, `create-handler`, etc.) have been removed from the codebase. Use only the normalized format going forward.

---

## Testing Strategy

### Test Categories

| Category | Location | Purpose | Characteristics |
|----------|----------|---------|-----------------|
| **Unit** | `test/{module}/core/*` | Pure core functions | No mocks, fast, deterministic |
| **Integration** | `test/{module}/shell/*` | Service orchestration | Mocked dependencies |
| **Contract** | `test/{module}/shell/*` | Adapter implementations | Real database (H2) |

### Running Tests

```bash
# All tests (H2 database required)
clojure -M:test:db/h2

# By category
clojure -M:test:db/h2 --focus-meta :unit
clojure -M:test:db/h2 --focus-meta :integration
clojure -M:test:db/h2 --focus-meta :contract

# By module
clojure -M:test:db/h2 --focus-meta :user
clojure -M:test:db/h2 --focus-meta :billing

# Watch mode (auto-run on file changes)
clojure -M:test:db/h2 --watch --focus-meta :unit

# Single namespace
clojure -M:test:db/h2 -n boundary.user.core.user-test

# Fail fast (stop on first failure)
clojure -M:test:db/h2 --fail-fast
```

### Test Structure Examples

**Unit Test (Pure Core)**:
```clojure
(ns boundary.user.core.user-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.user.core.user :as user-core]))

(deftest calculate-membership-tier-test
  (testing "platinum tier for 5+ years"
    (let [user {:created-at #inst "2018-01-01"}
          current-date #inst "2024-01-01"
          result (user-core/calculate-membership-tier user current-date)]
      (is (= :platinum result)))))
```

**Integration Test (Service with Mocks)**:
```clojure
(ns boundary.user.shell.service-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.user.shell.service :as service]
            [boundary.user.ports :as ports]))

(deftest create-user-test
  (testing "creates user successfully"
    (let [mock-repo (reify ports/IUserRepository
                      (create-user [_ user] user))
          svc (service/->UserService mock-repo nil)
          result (ports/create-user svc valid-user-data)]
      (is (some? result))
      (is (= "test@example.com" (:email result))))))
```

**Contract Test (Real Database)**:
```clojure
(ns boundary.user.shell.persistence-test
  {:kaocha.testable/meta {:contract true :user true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.user.shell.persistence :as persistence]))

(use-fixtures :each test-database-fixture)

(deftest find-user-by-email-test
  (testing "finds existing user"
    (let [repo (persistence/->DatabaseUserRepository test-db-ctx)
          created (create-test-user! test-db-ctx)
          found (ports/find-user-by-email repo (:email created))]
      (is (some? found))
      (is (= (:id created) (:id found))))))
```

---

## Database Operations

### Start Local PostgreSQL

```bash
# Start local PostgreSQL (via Docker)
docker-compose -f docker/dev-compose.yml up -d postgres

# Connect to database
psql -h localhost -U boundary_dev -d boundary_dev

# Stop PostgreSQL
docker-compose -f docker/dev-compose.yml down
```

### Run Migrations

```bash
# Run migrations (if migration alias exists)
clojure -M:migrate up

# Reset test database (if alias exists)
clojure -M:test:db:reset
```

### Database Testing in REPL

```clojure
;; Test database connection
user=> (require '[next.jdbc :as jdbc])
user=> (def ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname "test"}))
user=> (jdbc/execute! ds ["SELECT 1"])

;; Check persistence layer
user=> (def repo (get-in integrant.repl.state/system [::user/repository]))
user=> (user-ports/list-users repo {:limit 1})
```

### Current Database Setup

- **Development**: SQLite (`dev-database.db`) - no additional setup required
- **Testing**: H2 in-memory database (configured in test profile)
- **Production**: PostgreSQL (configure via environment variables)

---

## API Pagination

Boundary provides enterprise-grade pagination for REST APIs with two strategies: offset-based (simple, page-based) and cursor-based (high performance).

### Quick Start

**Offset Pagination** (default):
```bash
# First page (default: 20 items)
curl "http://localhost:3000/api/users?limit=20&offset=0"

# Second page
curl "http://localhost:3000/api/users?limit=20&offset=20"
```

**Response Format**:
```json
{
  "users": [...],
  "pagination": {
    "type": "offset",
    "total": 1000,
    "offset": 0,
    "limit": 20,
    "hasNext": true,
    "hasPrev": false,
    "page": 1,
    "pages": 50
  }
}
```

**RFC 5988 Link Headers**:
```http
Link: </api/users?limit=20&offset=0>; rel="first",
      </api/users?limit=20&offset=20>; rel="next",
      </api/users?limit=20&offset=980>; rel="last"
```

### Pagination Strategies

| Strategy | Best For | Performance | Use When |
|----------|----------|-------------|----------|
| **Offset** | Small datasets | Degrades at high offsets | < 100K items, page jumping needed |
| **Cursor** | Large datasets | Consistent | > 100K items, infinite scroll |

**Cursor Pagination Example**:
```bash
# First page
curl "http://localhost:3000/api/users?limit=20"

# Use nextCursor from response
curl "http://localhost:3000/api/users?limit=20&cursor=eyJpZCI6MTIzfQ=="
```

### Implementation

**Core Pagination Functions**:
- `boundary.platform.core.pagination.pagination/calculate-offset-pagination` - Pure pagination logic
- `boundary.platform.core.pagination.pagination/calculate-cursor-pagination` - Cursor calculations
- `boundary.platform.shell.pagination.link-headers/build-link-header` - RFC 5988 Link headers
- `boundary.platform.shell.pagination.cursor/encode-cursor` - Base64 cursor encoding

**Add Pagination to Repository**:
```clojure
(ns boundary.mymodule.shell.persistence
  (:require [boundary.platform.core.pagination.pagination :as pagination]))

(defn find-items
  [repository {:keys [limit offset] :or {limit 20 offset 0}}]
  (let [count-query ["SELECT COUNT(*) AS total FROM items"]
        total (:total (jdbc/execute-one! db-ctx count-query))
        
        data-query ["SELECT * FROM items LIMIT ? OFFSET ?" limit offset]
        items (jdbc/execute! db-ctx data-query)
        
        pagination-meta (pagination/calculate-offset-pagination total offset limit)]
    {:items (mapv db->item-entity items)
     :pagination pagination-meta}))
```

**Add Link Headers to HTTP Handler**:
```clojure
(ns boundary.mymodule.shell.http
  (:require [boundary.platform.shell.pagination.link-headers :as link-headers]))

(defn list-items-handler [service]
  (fn [request]
    (let [limit (parse-int (get-in request [:query-params "limit"]) 20)
          offset (parse-int (get-in request [:query-params "offset"]) 0)
          
          {:keys [items pagination]} (service/find-items service {:limit limit :offset offset})
          
          link-header (link-headers/build-link-header
                        (get request :uri)
                        pagination
                        (get request :query-params))]
      {:status 200
       :headers {"Link" link-header}
       :body {:items items :pagination pagination}})))
```

### Configuration

```clojure
;; resources/conf/dev/config.edn
{:boundary/pagination
 {:default-limit 20
  :max-limit 100
  :default-type :offset
  :enable-link-headers true}}
```

### Testing

```clojure
;; Test pagination in repository (integration test)
(deftest pagination-test
  (testing "returns paginated results"
    (let [repo (create-test-repository)
          _ (create-test-items! 25)  ; Create test data
          
          {:keys [items pagination]} (repo/find-items repo {:limit 20 :offset 0})]
      
      (is (= 20 (count items)))
      (is (= 25 (:total pagination)))
      (is (:hasNext pagination))
      (is (not (:hasPrev pagination))))))
```

### Performance Guidelines

- **Offset < 1,000**: Good performance (< 50ms)
- **Offset > 10,000**: Use cursor pagination
- **Large datasets (> 100K)**: Always use cursor pagination
- **Cache COUNT(*) queries**: Use caching for total count (5 min TTL)

### See Also

- **[API Pagination Guide](docs/API_PAGINATION.md)** - Complete pagination documentation
- **[Phase 4.4 Completion Report](docs/PHASE4_4_API_VERSIONING_PAGINATION_COMPLETION.md)** - Implementation details

---

## Observability

Boundary includes built-in observability infrastructure with logging, metrics, and error reporting capabilities following the Functional Core/Imperative Shell pattern.

### Multi-Layer Interceptor Pattern

**Major Architecture Milestone**: Boundary implements a **multi-layer interceptor pattern** that eliminates observability boilerplate while preserving business logic integrity:

**Achievements:**
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

### Quick Integration

Feature modules can easily integrate observability by accepting the protocols as dependencies:

```clojure
(ns my-feature.service
  (:require [boundary.logging.ports :as logging]
            [boundary.metrics.ports :as metrics]
            [boundary.error-reporting.ports :as error-reporting]))

(defrecord MyFeatureService [logger metric-collector error-reporter]
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

See [https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc](https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc) for complete integration guide including custom adapters and advanced configuration.

---

## HTTP Interceptors

### Overview

HTTP interceptors provide bidirectional enter/leave/error semantics for Ring handlers, enabling declarative cross-cutting concerns like authentication, rate limiting, and audit logging.

**Key Benefits**:
- Declarative: Specify policies in route config
- Composable: Stack multiple interceptors per route
- Bidirectional: Process requests (enter) and responses (leave)
- Observable: Automatic logging, metrics, error reporting
- Testable: Easy to test in isolation

### Interceptor Shape

```clojure
{:name   :my-interceptor           ; Required: Keyword identifier
 :enter  (fn [context] ...)        ; Optional: Process request
 :leave  (fn [context] ...)        ; Optional: Process response
 :error  (fn [context] ...)}       ; Optional: Handle exceptions
```

**Phases**:
- `:enter` - Process request, can short-circuit with response
- `:leave` - Process response (runs in reverse order)
- `:error` - Handle exceptions, produce safe response

### HTTP Context

Interceptors operate on a context map:

```clojure
{:request       Ring request map
 :response      Ring response (built during pipeline)
 :route         Route metadata from Reitit
 :path-params   Extracted parameters
 :system        {:logger :metrics-emitter :error-reporter}
 :attrs         Additional attributes
 :correlation-id Unique request ID
 :started-at    Request timestamp}
```

### Using Interceptors in Routes

**Normalized Route Format**:

```clojure
[{:path "/api/admin"
  :methods {:post {:handler 'my.handlers/create-resource
                   :interceptors ['my.auth/require-admin
                                  'my.audit/log-action
                                  'my.rate-limit/admin-limit]
                   :summary "Create admin resource"}}}]
```

**Requirements**:
- Router config must include `:system` with observability services
- Interceptor symbols resolved at startup

### Common Interceptor Patterns

#### 1. Authentication

```clojure
(ns my.auth.interceptors)

(def require-admin
  "Require admin role."
  {:name :require-admin
   :enter (fn [ctx]
            (let [user (get-in ctx [:request :session :user])]
              (if (= "admin" (:role user))
                ctx
                (assoc ctx :response
                       {:status 403
                        :body {:error "Forbidden"}}))))})

(def require-authenticated
  "Require any authenticated user."
  {:name :require-authenticated
   :enter (fn [ctx]
            (if (get-in ctx [:request :session :user])
              ctx
              (assoc ctx :response
                     {:status 401
                      :body {:error "Unauthorized"}})))})
```

#### 2. Audit Logging

```clojure
(ns my.audit.interceptors)

(def log-action
  "Log successful actions in leave phase."
  {:name :log-action
   :leave (fn [ctx]
            (let [logger (get-in ctx [:system :logger])
                  status (get-in ctx [:response :status])]
              ;; Only log successful actions (2xx)
              (when (and logger (< 199 status 300))
                (.info logger "Action completed"
                       {:user-id (get-in ctx [:request :session :user :id])
                        :action (get-in ctx [:request :uri])
                        :status status}))
              ctx))})
```

#### 3. Rate Limiting

```clojure
(ns my.rate-limit.interceptors
  (:require [my.rate-limit.core :as rate-limit]))

(defn rate-limiter
  "Rate limiting interceptor factory."
  [limit-per-minute]
  {:name :rate-limiter
   :enter (fn [ctx]
            (let [client-id (or (get-in ctx [:request :session :user :id])
                                (get-in ctx [:request :remote-addr]))
                  allowed? (rate-limit/check-limit client-id limit-per-minute)]
              (if allowed?
                ctx
                (assoc ctx :response
                       {:status 429
                        :headers {"Retry-After" "60"}
                        :body {:error "Rate limit exceeded"}}))))})

(def admin-limit (rate-limiter 100))
(def public-limit (rate-limiter 30))
```

#### 4. Request Validation

```clojure
(ns my.validation.interceptors
  (:require [malli.core :as m]))

(defn validate-body
  "Validate request body against schema."
  [schema]
  {:name :validate-body
   :enter (fn [ctx]
            (let [body (get-in ctx [:request :body-params])
                  valid? (m/validate schema body)]
              (if valid?
                ctx
                (assoc ctx :response
                       {:status 400
                        :body {:error "Validation failed"
                               :details (m/explain schema body)}}))))})
```

#### 5. Metrics Collection

```clojure
(ns my.metrics.interceptors)

(def track-timing
  "Track request timing."
  {:name :track-timing
   :enter (fn [ctx]
            (assoc-in ctx [:attrs :start-time] (System/nanoTime)))
   
   :leave (fn [ctx]
            (let [metrics (get-in ctx [:system :metrics-emitter])
                  start (get-in ctx [:attrs :start-time])
                  duration-ms (/ (- (System/nanoTime) start) 1000000.0)]
              (when metrics
                (.emit metrics "http.request.duration"
                       {:value duration-ms
                        :tags {:route (get-in ctx [:route :path])
                               :status (get-in ctx [:response :status])}}))
              ctx))})
```

### Testing Interceptors

**Unit Test**:

```clojure
(deftest require-admin-test
  (testing "allows admin users"
    (let [ctx {:request {:session {:user {:role "admin"}}}}
          result ((:enter require-admin) ctx)]
      (is (nil? (:response result)))))
  
  (testing "rejects non-admin users"
    (let [ctx {:request {:session {:user {:role "user"}}}}
          result ((:enter require-admin) ctx)]
      (is (= 403 (get-in result [:response :status]))))))
```

**Integration Test**:

```clojure
(deftest interceptor-pipeline-test
  (let [router (create-reitit-router)
        system (create-mock-system)
        routes [{:path "/test"
                 :methods {:get {:handler test-handler
                                 :interceptors [auth audit]}}}]
        config {:system system}
        handler (ports/compile-routes router routes config)]
    
    (testing "auth rejects unauthenticated"
      (let [resp (handler {:request-method :get :uri "/test"})]
        (is (= 401 (:status resp)))))
    
    (testing "auth allows authenticated"
      (let [resp (handler {:request-method :get
                          :uri "/test"
                          :session {:user {:role "admin"}}})]
        (is (= 200 (:status resp)))))))
```

### Interceptor Composition

**Combine Related Interceptors**:

```clojure
(defn admin-endpoint-stack
  "Standard interceptor stack for admin endpoints."
  []
  [require-authenticated
   require-admin
   log-action
   track-timing])

;; Use in routes
[{:path "/api/admin/users"
  :methods {:post {:handler 'my.handlers/create-user
                   :interceptors (admin-endpoint-stack)}}}]
```

**Higher-Order Interceptors**:

```clojure
(defn with-role
  "Create role-checking interceptor."
  [required-role]
  {:name (keyword (str "require-" required-role))
   :enter (fn [ctx]
            (let [user-role (get-in ctx [:request :session :user :role])]
              (if (= required-role user-role)
                ctx
                (assoc ctx :response
                       {:status 403
                        :body {:error "Insufficient permissions"}}))))})

;; Use dynamically
[{:path "/api/manager"
  :methods {:post {:handler 'my.handlers/manager-action
                   :interceptors [(with-role "manager")]}}}]
```

### Default Interceptors

The framework provides default interceptors:

```clojure
(require '[boundary.platform.shell.http.interceptors :as http-int])

;; Available defaults:
http-int/http-request-logging      ; Log requests/responses
http-int/http-request-metrics      ; Emit timing metrics
http-int/http-error-reporting      ; Report exceptions
http-int/http-correlation-header   ; Add X-Correlation-ID
http-int/http-error-handler        ; Safe error responses
```

### Execution Order

```
Request Flow:
  enter:  global-1 → global-2 → route-1 → route-2 → handler
  leave:  route-2 → route-1 → global-2 → global-1 → response
```

**Tips**:
- Auth interceptors should run early (beginning of :enter)
- Logging/metrics should wrap everything (global)
- Validation runs after auth but before handler
- Audit logging in :leave phase (after success)

### Performance

**Typical Overhead**: ~0.25ms per request (3-5 interceptor stack)

**Optimization**:
- Minimize interceptor count
- Short-circuit early in :enter when possible
- Cache expensive computations in :attrs
- Use lazy evaluation

### Best Practices

1. **Keep Interceptors Focused**: One responsibility per interceptor
2. **Short-Circuit Early**: Return response in :enter to skip handler
3. **Use :attrs for Sharing**: Store temporary data in context :attrs
4. **Test in Isolation**: Unit test each interceptor separately
5. **Document Side Effects**: Clearly document what each phase does
6. **Handle Errors Gracefully**: Always provide safe fallback in :error

### Common Pitfalls

1. **❌ Modifying Request in :leave**: Request is immutable after handler
2. **❌ Side Effects in :enter without :leave**: Clean up in :leave phase
3. **❌ Throwing in :error Phase**: :error should produce response, not throw
4. **❌ Heavy Computation in Pipeline**: Keep interceptors fast
5. **❌ Missing System Services**: Interceptors need :system in config

### See Also

- [ADR-010: HTTP Interceptor Architecture](https://github.com/thijs-creemers/boundary-docs/tree/main/content/adr/ADR-010-http-interceptor-architecture.adoc) - Full technical specification
- [ADR-008: Normalized Routing](https://github.com/thijs-creemers/boundary-docs/tree/main/content/adr/ADR-008-normalized-routing-abstraction.adoc) - Route format documentation
- [Observability Guide](https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc) - Logging, metrics, errors

---

## Web UI Development

### Architecture: HTMX + Hiccup

**Key Principles**:
- Server-side rendering (Hiccup)
- Progressive enhancement (HTMX)
- No build step required
- Module-integrated (UI code in domain modules)

### File Organization

```
src/boundary/user/
├── core/
│   └── ui.clj              # Pure Hiccup generation
└── shell/
    ├── web_handlers.clj    # Web request handlers
    └── http.clj            # Route composition

resources/public/
├── css/
│   ├── pico.min.css        # Base framework
│   └── app.css             # Custom styles
└── js/
    └── htmx.min.js         # HTMX library
```

### HTMX Patterns

**1. Form with In-Place Update**:
```clojure
(defn create-user-form [data errors]
  [:div#create-user-form {:hx-target "#create-user-form"}
   [:form {:hx-post "/web/users"}
    [:label "Name"]
    [:input {:name "name" :value (:name data)}]
    (when-let [errs (:name errors)]
      [:span.error (first errs)])
    [:button {:type "submit"} "Create"]]])
```

**2. Table with Event-Based Refresh**:
```clojure
(defn users-table [users]
  [:div#users-table-container
   {:hx-get "/web/users/table"
    :hx-trigger "userCreated from:body, userUpdated from:body"
    :hx-target "#users-table-container"}
   [:table
    [:thead ...]
    [:tbody
     (for [user users]
       (user-row user))]]])
```

**3. Handler with Custom Event**:
```clojure
(defn create-user-handler [user-service config]
  (fn [request]
    (let [[valid? errors data] (validate-request schema request)]
      (if valid?
        {:status 201
         :headers {"HX-Trigger" "userCreated"}  ; Triggers table refresh
         :body (render-success-message)}
        {:status 400
         :body (render-form-with-errors data errors)}))))
```

### Routes

```clojure
;; In shell/http.clj
["/web"
 ["/users"
  {:get {:handler (web-handlers/users-page user-service config)}}
  ["/new"
   {:get {:handler (web-handlers/new-user-form user-service config)}}]
  ["/:id"
   {:get {:handler (web-handlers/user-detail user-service config)}
    :put {:handler (web-handlers/update-user-htmx user-service config)}
    :delete {:handler (web-handlers/delete-user-htmx user-service config)}}]
  ["/table"
   {:get {:handler (web-handlers/users-table-fragment user-service config)}}]]]
```

### Development Workflow

1. Edit UI functions in `core/ui.clj` (pure Hiccup)
2. Edit handlers in `shell/web_handlers.clj`
3. Run `(ig-repl/reset)` in REPL
4. Refresh browser (no build step!)
5. Check HTMX requests in browser console: `htmx.logAll()`

---

## Build and Deployment

### Build Application

```bash
# Build application using build.clj
clojure -T:build clean                 # Clean build artifacts
clojure -T:build uber                  # Create standalone JAR

# The uberjar will be created in target/ directory
ls target/*.jar
```

### Docker Build

```bash
# Build Docker image (if Dockerfile exists)
docker build -t boundary:latest .

# Run container
docker run -p 3000:3000 boundary:latest
```

### Environment-Specific Tasks

```bash
# Development
export BND_ENV=development
clojure -M:repl-clj

# Testing
export BND_ENV=test
clojure -M:test:db/h2

# Staging deployment (if configured)
export BND_ENV=staging
clojure -M:build:deploy
```

### Development Utilities

```bash
# Format code (if formatter configured)
clojure -M:format

# Dependency analysis
clojure -M:deps:tree                   # Show dependency tree
clojure -M:deps:outdated              # Check for updates

# Code generation (if scaffolding exists)
clojure -M:gen:module billing          # Create billing module structure
clojure -M:gen:entity user profile     # Add profile entity to user module
```

---

## Troubleshooting

### System Won't Start

```bash
# Clean build artifacts
rm -rf .cpcache target

# Restart REPL
clojure -M:repl-clj
user=> (ig-repl/go)

# Check for errors in config
user=> (require '[boundary.config :as config])
user=> (config/load-config)
```

### Tests Failing

```bash
# Run specific test with verbose output
clojure -M:test:db/h2 -n boundary.user.core.user-test --reporter documentation

# Check if database is issue
clojure -M:test:db/h2 --focus-meta :unit  # Should pass without DB

# Clear test database
rm -f test-database.db  # If using SQLite for tests
```

### REPL Issues

```clojure
;; System stuck, won't reset
user=> (ig-repl/halt)  ; Force stop
user=> (ig-repl/go)    ; Fresh start

;; defrecord changes not taking effect
user=> (ig-repl/halt)
;; Exit REPL, clear cache, restart
$ rm -rf .cpcache
$ clojure -M:repl-clj

;; Check what's in system
user=> (keys integrant.repl.state/system)
```

### Clojure-MCP Not Working

```bash
# Check if clojure-mcp is running
ps aux | grep clojure-mcp

# If not running, start it (method depends on your setup)
# Verify it's accessible before editing Clojure files
```

### Database Issues

```clojure
;; Test database connection
user=> (require '[next.jdbc :as jdbc])
user=> (def ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname "test"}))
user=> (jdbc/execute! ds ["SELECT 1"])

;; Check persistence layer
user=> (def repo (get-in integrant.repl.state/system [::user/repository]))
user=> (user-ports/list-users repo {:limit 1})
```

### Web UI Issues

```javascript
// In browser console
htmx.logAll();  // Enable HTMX debug logging

// Check for JavaScript errors
// Verify HTMX is loaded
console.log(htmx);

// Check network tab for failed requests
```

---

## Additional Resources

### Internal Documentation
- [https://github.com/thijs-creemers/boundary-docs](https://github.com/thijs-creemers/boundary-docs) - Main documentation index
- [https://github.com/thijs-creemers/boundary-docs/tree/main/content/architecture/](https://github.com/thijs-creemers/boundary-docs/tree/main/content/architecture/) - Architecture patterns and design
- [docs/guides/](docs/guides/) - Tutorials and how-to guides
- [https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc](https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc) - Logging, metrics, error reporting
- [docs/DECISIONS.md](docs/DECISIONS.md) - Technical and architectural decisions
- [PRD.adoc](https://github.com/thijs-creemers/boundary-docs/tree/main/content/reference/boundary-prd.adoc) - Product requirements and vision
- [AGENTS.md](AGENTS.md) - Full developer guide with detailed examples

### External References
- [Functional Core, Imperative Shell](https://www.destroyallsoftware.com/screencasts/catalog/functional-core-imperative-shell) - Original concept
- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/) - Ports and Adapters
- [Clojure Documentation](https://clojure.org/guides/getting_started)
- [Malli Documentation](https://github.com/metosin/malli)
- [HTMX Documentation](https://htmx.org/)

---

## Quick Reference Card

```
╔════════════════════════════════════════════════════════════════╗
║                  BOUNDARY FRAMEWORK CHEAT SHEET                ║
╠════════════════════════════════════════════════════════════════╣
║ TEST    │ clojure -M:test:db/h2 --watch --focus-meta :unit     ║
║ LINT    │ clojure -M:clj-kondo --lint src test                 ║
║ REPL    │ clojure -M:repl-clj                                  ║
║         │ (ig-repl/go)    (ig-repl/reset)    (ig-repl/halt)    ║
║ BUILD   │ clojure -T:build clean && clojure -T:build uber      ║
╠════════════════════════════════════════════════════════════════╣
║ CORE    │ Pure functions only, no side effects                 ║
║ SHELL   │ All I/O, validation, error handling                  ║
║ PORTS   │ Protocol definitions (abstractions)                  ║
║ SCHEMA  │ Malli schemas for validation                         ║
╠════════════════════════════════════════════════════════════════╣
║ PITFALL │ snake_case (DB) vs kebab-case (Clojure)              ║
║ PITFALL │ defrecord changes need full REPL restart             ║
║ PITFALL │ Use clojure-mcp for editing Clojure files            ║
╚════════════════════════════════════════════════════════════════╝
```

---

**Last Updated**: 2024-11-30
**Version**: 1.0.0
