# User Module Architecture

## Overview

The boundary user module follows a clean architecture pattern with proper separation of concerns. Database-specific infrastructure is now properly isolated in the user module's own infrastructure layer.

## Directory Structure

```
src/boundary/user/
├── schema.clj              # Domain schemas (Malli)
├── ports.clj               # Repository interfaces/contracts
├── shell/
│   └── service.clj         # Database-agnostic business services
│   ├── adapters.clj        # DEPRECATED - use infrastructure.database
│   └── multi_db_adapters.clj # DEPRECATED - use infrastructure.database
└── infrastructure/
    └── database.clj        # Database-specific repository implementations

src/boundary/shell/adapters/database/
└── user.clj                # DEPRECATED - use boundary.user.infrastructure.database
```

## Architecture Layers

### 1. Domain Layer (`boundary.user.schema`)
- **Responsibility**: Define canonical domain entities using Malli schemas
- **Dependencies**: None (pure domain)
- **Contains**: 
  - `User` schema with validation rules
  - `UserSession` schema
  - Domain value types and constraints

### 2. Ports Layer (`boundary.user.ports`) 
- **Responsibility**: Define repository interfaces and contracts
- **Dependencies**: Domain schemas only
- **Contains**:
  - `IUserRepository` protocol
  - `IUserSessionRepository` protocol
  - Input/output contracts

### 3. Application/Service Layer (`boundary.user.shell.service`)
- **Responsibility**: Business logic and orchestration
- **Dependencies**: Ports (interfaces) only - NOT infrastructure
- **Contains**:
  - Database-agnostic business services
  - Validation and business rules
  - Transaction coordination
  - Service composition via dependency injection

### 4. Infrastructure Layer (`boundary.user.infrastructure.database`)
- **Responsibility**: Database-specific repository implementations  
- **Dependencies**: Ports, shared database utilities, domain schemas
- **Contains**:
  - `DatabaseUserRepository` record implementing `IUserRepository`
  - `DatabaseUserSessionRepository` record implementing `IUserSessionRepository`
  - Database entity transformations
  - Database-specific query optimizations

## Key Principles

### Dependency Direction
```
Infrastructure → Ports ← Services
Infrastructure → Domain ← Services
Infrastructure → Shared Utils
```

- **Services depend on ports (interfaces), not implementations**
- **Infrastructure implements ports**
- **No circular dependencies**

### Database Abstraction
- Generic database layer (`boundary.shell.adapters.database.*`) remains reusable
- User-specific database code lives in user infrastructure
- Entity transformations handled in infrastructure layer
- Business services remain database-agnostic

### Separation of Concerns
- **Domain**: Pure business entities and rules
- **Ports**: Contracts and interfaces
- **Services**: Business logic and orchestration  
- **Infrastructure**: Database, external APIs, I/O

## Usage Examples

### Creating Repositories (Recommended)
```clojure
(require '[boundary.user.infrastructure.database :as user-db]
         '[boundary.shell.adapters.database.factory :as db-factory])

;; Create database context
(def ctx (db-factory/create-database-context config))

;; Create repositories using infrastructure
(def user-repo (user-db/create-user-repository ctx))
(def session-repo (user-db/create-session-repository ctx))
```

### Using Business Services
```clojure
(require '[boundary.user.shell.service :as user-service])

;; Create database-agnostic service
(def service (user-service/create-user-service user-repo session-repo))

;; Use business operations
(user-service/create-user service user-data)
(user-service/authenticate service email password)
```

### Schema Initialization
```clojure
(require '[boundary.user.infrastructure.database :as user-db])

;; Initialize database schema from Malli definitions
(user-db/initialize-user-schema! ctx)
```

## Migration Guide

### From Deprecated Namespaces

**Old (Deprecated):**
```clojure
(require '[boundary.shell.adapters.database.user :as db-user]     ; DEPRECATED
         '[boundary.user.shell.adapters :as user-adapters]        ; DEPRECATED  
         '[boundary.user.shell.multi-db-adapters :as multi-db])   ; DEPRECATED
```

**New (Recommended):**
```clojure
(require '[boundary.user.infrastructure.database :as user-db]     ; NEW
         '[boundary.user.shell.service :as user-service])         ; NEW
```

### Update Function Calls

**Old:**
```clojure
(def user-repo (db-user/create-user-repository ctx))              ; DEPRECATED
(db-user/initialize-user-schema! ctx)                             ; DEPRECATED
```

**New:**
```clojure
(def user-repo (user-db/create-user-repository ctx))              ; NEW
(user-db/initialize-user-schema! ctx)                             ; NEW
```

## Benefits

1. **Clean Separation**: Database concerns isolated to infrastructure layer
2. **Testability**: Business services can be tested with mock repositories
3. **Maintainability**: Clear boundaries between layers
4. **Flexibility**: Easy to swap infrastructure implementations
5. **Modularity**: Each domain module owns its infrastructure
6. **Scalability**: Architecture scales to multiple domain modules

## Files to Update

When migrating to the new architecture:

1. **Update imports** from deprecated namespaces
2. **Use infrastructure factory functions** instead of direct record construction
3. **Inject repositories into services** via dependency injection
4. **Remove direct database dependencies** from business services

The deprecated namespaces will log warnings but continue to work during the transition period.