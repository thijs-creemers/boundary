# Boundary User Module - Clean Architecture

This document describes the new clean, database-agnostic architecture of the boundary user module.

## Architecture Overview

The user module now follows clean architecture principles with proper separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│                    BUSINESS LAYER                           │
│                (Database Agnostic)                          │
├─────────────────────────────────────────────────────────────┤
│  boundary.user.shell.service                               │
│    - Pure business logic                                   │
│    - Uses dependency injection                             │
│    - No database dependencies                              │
├─────────────────────────────────────────────────────────────┤
│  boundary.user.schema                                      │
│    - Canonical domain models (Malli)                      │
│    - Single source of truth for data structures           │
├─────────────────────────────────────────────────────────────┤
│  boundary.user.ports                                       │
│    - Repository interface contracts                       │
│    - Defines what the business layer needs                │
└─────────────────────────────────────────────────────────────┘
                              │
                    depends on (interfaces)
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                  INFRASTRUCTURE LAYER                       │
│                   (Database Specific)                       │
├─────────────────────────────────────────────────────────────┤
│  boundary.shell.adapters.database.user                     │
│    - Implements repository interfaces                      │
│    - Entity transformations (domain ↔ database)           │
│    - Database-specific query logic                        │
├─────────────────────────────────────────────────────────────┤
│  boundary.shell.adapters.database.schema                   │
│    - Schema-to-DDL generation                             │
│    - Malli schema → SQL DDL conversion                    │
│    - Cross-database compatibility                         │
├─────────────────────────────────────────────────────────────┤
│  boundary.shell.adapters.database.core                     │
│    - Core database operations                             │
│    - Query execution, transactions                        │
│    - Connection pool management                           │
├─────────────────────────────────────────────────────────────┤
│  boundary.shell.adapters.database.protocols                │
│    - Database adapter interfaces                          │
│    - Database-specific behavior abstractions              │
└─────────────────────────────────────────────────────────────┘
```

## Key Principles

### 1. **Separation of Concerns**
- **Business Logic**: Completely database-agnostic
- **Infrastructure**: Handles all database-specific concerns
- **Domain Models**: Canonical source of truth (Malli schemas)

### 2. **Dependency Injection**
- Services receive repository implementations as dependencies
- No direct database context dependencies in business layer
- Easy to test with mock implementations

### 3. **Interface-Based Design**
- Business layer depends on interfaces (`boundary.user.ports`)
- Infrastructure layer implements these interfaces
- Clean contracts between layers

### 4. **Database Agnosticism**
- Same business logic works with any database
- Database-specific optimizations handled in infrastructure layer
- Easy to switch between databases

## File Structure

```
src/boundary/
├── user/                           # BUSINESS LAYER
│   ├── core/                       # Domain logic
│   ├── shell/
│   │   └── service.clj             # Business services (database-agnostic)
│   ├── ports.clj                   # Repository interfaces
│   └── schema.clj                  # Domain models (Malli)
│
└── shell/adapters/database/        # INFRASTRUCTURE LAYER
    ├── user.clj                    # User repository implementations
    ├── schema.clj                  # Schema-to-DDL generation
    ├── core.clj                    # Core database operations
    ├── protocols.clj               # Database adapter interfaces
    └── factory.clj                 # Database context factory
```

## Usage Pattern

### 1. System Creation (Composition Root)
```clojure
(ns my-app.system
  (:require [boundary.shell.adapters.database.factory :as db-factory]
            [boundary.shell.adapters.database.user :as db-user]
            [boundary.user.shell.service :as user-service]))

(defn create-system [db-config]
  ;; Infrastructure layer
  (let [db-context (db-factory/db-context db-config)
        _ (db-user/initialize-user-schema! db-context)
        
        ;; Repository implementations
        user-repo (db-user/create-user-repository db-context)
        session-repo (db-user/create-session-repository db-context)
        
        ;; Business layer (with dependency injection)
        user-service (user-service/create-user-service user-repo session-repo)]
    
    {:user-service user-service
     :user-repo user-repo
     :session-repo session-repo
     :db-context db-context}))
```

### 2. Business Operations (Database Agnostic)
```clojure
;; Use service for all business operations
(.create-user user-service {:email "user@example.com"
                           :name "John Doe"
                           :role :user
                           :tenant-id tenant-id})

(.find-user-by-email user-service "user@example.com" tenant-id)
(.update-user user-service updated-user)
```

## Benefits

### ✅ **Testability**
- Mock repositories for unit tests
- No database required for business logic tests
- Clear interfaces make mocking easy

### ✅ **Maintainability** 
- Changes to database don't affect business logic
- Changes to business rules don't affect database code
- Clear boundaries between concerns

### ✅ **Flexibility**
- Easy to switch database implementations
- Same business code works with any database
- Database-specific optimizations possible

### ✅ **Extensibility**
- Easy to add new repository methods
- Easy to add new business services
- Clear places to add new functionality

### ✅ **Performance**
- Database-specific optimizations in infrastructure layer
- Business logic stays lean and focused
- Efficient entity transformations

## Database Support

The infrastructure layer supports multiple databases with the same business interface:

- **SQLite**: For development and embedded use
- **PostgreSQL**: For production applications
- **MySQL**: For legacy system integration
- **H2**: For testing and rapid prototyping

All databases use the same:
- Business logic code
- Service interfaces
- Domain models
- Repository contracts

Only the infrastructure implementation varies by database type.

## Migration Path

For existing code using the old mixed approach:

1. **Replace imports**: Use new infrastructure layer modules
2. **Update initialization**: Use new schema initialization
3. **Use service layer**: Replace direct repository usage with service calls
4. **Test thoroughly**: Verify same behavior with new architecture

See `MIGRATION_GUIDE.md` for detailed migration instructions.

## Summary

This clean architecture provides:
- **Database-agnostic business logic**
- **Clear separation of concerns**
- **Dependency injection throughout**
- **Easy testing and maintenance**
- **Support for multiple databases**
- **Performance optimizations where needed**

The user module is now a perfect example of clean architecture in Clojure, with proper boundaries between business logic and infrastructure concerns.