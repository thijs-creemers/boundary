# Architecture Summary

*Synthesized from comprehensive architecture documentation for warp.md*

## Core Architectural Pattern: Functional Core / Imperative Shell

Boundary implements the **Functional Core / Imperative Shell** (FC/IS) pattern with strict separation of concerns:

### Functional Core (Pure Business Logic)
- **Location**: `src/boundary/{module}/core/`
- **Characteristics**: Pure functions, no side effects, deterministic behavior
- **Contains**: Business rules, calculations, decision logic, domain validations
- **Dependencies**: Only on port abstractions, never concrete implementations
- **Testing**: Unit tests with no mocks required

### Imperative Shell (Infrastructure)
- **Location**: `src/boundary/{module}/shell/`, adapters, handlers
- **Characteristics**: All I/O, side effects, infrastructure concerns
- **Contains**: Database operations, HTTP handling, external API calls, logging
- **Dependencies**: Implements ports, calls core functions with validated data
- **Testing**: Integration tests with real dependencies

### Dependency Rules
```
┌─────────────────┐    ┌─────────────────┐
│ Imperative Shell│───▶│ Functional Core │
└─────────────────┘    └─────────────────┘
         │                       │
         ▼                       ▼
┌─────────────────┐    ┌─────────────────┐
│    Adapters     │    │     Ports       │
│  (Concrete)     │    │  (Abstract)     │
└─────────────────┘    └─────────────────┘
```

- ✅ **Shell → Core**: Shell depends on Core interfaces
- ❌ **Core → Shell**: Core NEVER depends on Shell
- ✅ **Core → Ports**: Core depends only on port abstractions
- ✅ **Shell → Adapters**: Shell implements concrete adapters

## Module-Centric Architecture

### Complete Domain Ownership
Each module (`user`, `billing`, `workflow`) owns its complete vertical stack:

```
src/boundary/user/                    # USER MODULE
├── core/                          # Pure business logic
│   ├── user.clj                   # User domain functions
│   ├── membership.clj             # Membership calculations
│   └── validation.clj             # Business rule validation
├── ports.clj                      # Abstract interfaces (protocols)
├── schema.clj                     # Data validation schemas
├── http.clj                       # HTTP endpoints & routing
├── cli.clj                        # CLI commands & parsing
└── shell/                         # Infrastructure layer
    ├── adapters.clj               # Port implementations
    └── service.clj                # Service orchestration
```

### Benefits of Module-Centric Design
- **Team Autonomy**: Clear ownership boundaries
- **Independent Evolution**: Deploy modules independently
- **Feature Isolation**: Add capabilities without disruption
- **Testing Isolation**: Test complete module functionality
- **Feature Flagging**: Enable/disable entire modules

## Ports and Adapters Pattern

### Port Definition (Abstract Interfaces)
```clojure
(ns boundary.user.ports)

(defprotocol IUserRepository
  "User data persistence interface"
  (find-user-by-id [this user-id])
  (create-user [this user-data])
  (update-user [this user-data]))
```

### Adapter Implementation (Concrete)
```clojure
(ns boundary.user.shell.adapters)

(defrecord PostgreSQLUserRepository [datasource]
  ports/IUserRepository
  (find-user-by-id [_ user-id]
    (jdbc/execute-one! datasource 
      (sql/format {:select [:*] :from [:users] 
                  :where [:= :id user-id]}))))
```

### Dependency Injection
```clojure
;; System wiring
(defn create-system [config]
  {:user-repository (make-postgresql-user-repository (:database config))
   :user-service    (make-user-service)})

;; Core function call with injected dependencies
(defn register-user [system user-data]
  (user-core/create-new-user user-data (:user-repository system)))
```

## Data Flow and Boundaries

### Request Processing Flow
```
HTTP/CLI Request
       │
       ▼
   Validation ──────┐ (Malli schemas)
       │            │
       ▼            ▼
  Core Function ◄──Error
       │
       ▼
   Side Effects ──────┐ (Database, Email, etc.)
       │              │
       ▼              ▼
   Response ◄────────Error Handling
```

### Layer Responsibilities

**Presentation Layer** (HTTP/CLI/Web):
- Route requests to appropriate handlers
- Parse input data and headers
- Format responses for interface

**Shell Layer**:
- Input validation using Malli schemas
- Call pure core functions with clean data
- Execute side effects based on core results
- Error translation to appropriate formats

**Core Layer**:
- Pure business logic processing
- Return success/error data structures
- Generate domain events and side effect descriptions
- No I/O or infrastructure concerns

## Configuration-Driven Design

### Profile-Based Configuration
```clojure
;; Base configuration with environment profiles
{:database {:host #profile {:development "localhost"
                           :staging #env "DB_HOST"
                           :production #env "DB_HOST"}}
 :feature-flags {:modules {:user {:enabled? true}
                          :billing {:enabled? #profile {:production false}}}}}
```

### Module Feature Flags
- **Module-Level**: Enable/disable entire modules
- **Feature-Level**: Control individual capabilities
- **Environment-Specific**: Different flags per environment
- **Runtime Control**: Modify behavior without code changes

## Error Handling Strategy

### Consistent Error Format (RFC 7807)
```clojure
;; Core returns structured error data
{:status :error
 :errors [{:field "email"
           :code :already-exists
           :message "Email address already in use"}]}

;; Shell translates to Problem Details format
{:type "https://boundary.example.com/problems/validation-error"
 :title "Validation Error"
 :status 400
 :detail "One or more fields failed validation"
 :errors [...]}
```

## Multi-Interface Consistency

### Shared Core Logic
All interfaces (REST API, CLI, Web Frontend) use the same:
- **Validation Schemas**: Malli schemas for input validation
- **Core Functions**: Business logic implementation
- **Error Handling**: Consistent error codes and messages
- **Feature Flags**: Uniform capability control

### Interface-Specific Adaptations
- **REST API**: JSON serialization, HTTP status codes
- **CLI**: Table/JSON output formatting, exit codes
- **Web Frontend**: Real-time updates, interactive forms

## System Lifecycle Management

### Integrant-Based System
```clojure
;; System configuration
{:boundary/database {:host "localhost" :port 5432}
 :boundary/user-repository {:database #ig/ref :boundary/database}
 :boundary/user-service {:repository #ig/ref :boundary/user-repository}
 :boundary/http-server {:service #ig/ref :boundary/user-service :port 3000}}

;; REPL development workflow
(ig-repl/go)     ; Start system
(ig-repl/reset)  ; Reload and restart
(ig-repl/halt)   ; Stop system
```

## Key Design Patterns

### Domain-Driven Design (DDD)
- **Bounded Contexts**: Each module represents a business domain
- **Ubiquitous Language**: Business concepts in code structure
- **Value Objects**: Immutable domain data structures

### Event-Driven Architecture
- **Domain Events**: Communicate changes between modules
- **Asynchronous Processing**: Decouple time-sensitive operations
- **Audit Trail**: Complete event history for debugging

### CQRS (Command Query Responsibility Segregation)
- **Commands**: State-changing operations with validation
- **Queries**: Read-only operations optimized for views
- **Separate Handlers**: Independent scaling of reads and writes

## Testing Strategy Alignment

### Unit Testing (Core)
- **No Mocks Required**: Pure functions test with simple data
- **Property-Based**: Comprehensive scenario coverage with test.check
- **High Coverage**: >95% for business logic

### Integration Testing (Shell)
- **Real Dependencies**: Test with actual databases and services
- **Transaction Testing**: Cross-module coordination
- **Adapter Contracts**: Validate port implementations

### System Testing (Full Stack)
- **End-to-End**: Complete workflows across all interfaces
- **Interface Consistency**: REST/CLI/Web produce identical results
- **Performance**: Load and stress testing

## Architectural Decision Record Summary

| Decision | Rationale | Trade-offs |
|----------|-----------|------------|
| **FC/IS Pattern** | Maximize testability and maintainability | Learning curve for new developers |
| **Module-Centric** | Team autonomy and independent evolution | More complex inter-module coordination |
| **Clojure Language** | Functional programming, REPL workflow | Smaller talent pool, JVM dependency |
| **Integrant System** | Configuration-driven lifecycle | Runtime dependency injection complexity |
| **Malli Validation** | Data-first schemas, performance | Less mature ecosystem than alternatives |
| **Multi-Interface** | Consistent core with flexible presentation | Additional interface maintenance overhead |

## Glossary

**Functional Core**: Pure business logic layer with no side effects

**Imperative Shell**: Infrastructure layer handling all I/O and side effects

**Port**: Abstract interface (Clojure protocol) defining contracts

**Adapter**: Concrete implementation of a port for specific technology

**Module**: Complete vertical domain slice owning all functionality

**Schema**: Malli-based data validation and coercion specification

**Feature Flag**: Configuration-driven capability control

**Domain Event**: Immutable record of significant business occurrence

**Problem Details**: RFC 7807 standard error response format

---
*Synthesized: 2025-01-10 18:32*
*Sources: docs/architecture/ (overview.md, functional-core.md, modules.md, integrant.md)*
