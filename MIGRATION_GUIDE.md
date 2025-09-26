# Migration Guide: Database-Agnostic User Architecture

This guide explains how to migrate from the old database-specific user adapters to the new database-agnostic architecture.

## Overview

The user module has been completely refactored to achieve proper separation of concerns:

- ✅ **Business logic is now database-agnostic**
- ✅ **Database code moved to infrastructure layer**
- ✅ **Clean dependency injection pattern**
- ✅ **Improved testability and maintainability**

## Architecture Changes

### Old Architecture (Removed)
```
boundary.user.shell.adapters           ← Mixed business + database code
boundary.user.shell.multi_db_adapters  ← Mixed business + database code
```

### New Architecture (Current)
```
boundary.user.shell.service                    ← Pure business logic
         ↓ (depends on interfaces)
boundary.user.ports                           ← Repository contracts
         ↓ (implemented by)
boundary.shell.adapters.database.user         ← Database implementations
boundary.shell.adapters.database.schema       ← Schema generation
```

## Migration Steps

### 1. Replace Old Imports

**Before:**
```clojure
(require '[boundary.user.shell.adapters :as user-adapters]
         '[boundary.user.shell.multi-db-adapters :as multi-db])
```

**After:**
```clojure
(require '[boundary.shell.adapters.database.user :as db-user]
         '[boundary.user.shell.service :as user-service])
```

### 2. Replace Repository Creation

**Before:**
```clojure
;; Old way - database-specific adapters
(def user-repo (multi-db/->UserRepository ctx))
(def session-repo (multi-db/->UserSessionRepository ctx))
```

**After:**
```clojure
;; New way - database layer creates repositories
(def user-repo (db-user/create-user-repository ctx))
(def session-repo (db-user/create-session-repository ctx))

;; Create service with dependency injection
(def service (user-service/create-user-service user-repo session-repo))
```

### 3. Replace Schema Initialization

**Before:**
```clojure
(user-adapters/initialize-database! ctx)
```

**After:**
```clojure
(db-user/initialize-user-schema! ctx)
```

### 4. Use Service Layer for Business Operations

**Before:**
```clojure
;; Direct repository usage
(.create-user user-repo user-data)
(.find-user-by-id user-repo user-id)
```

**After:**
```clojure
;; Use service layer (includes business logic, validation, etc.)
(.create-user service user-data)
(.find-user-by-id service user-id)
```

## Complete Example

```clojure
(ns my-app.user-system
  (:require [boundary.shell.adapters.database.factory :as db-factory]
            [boundary.shell.adapters.database.user :as db-user]
            [boundary.user.shell.service :as user-service]))

(defn create-user-system [db-config]
  ;; 1. Create database context
  (let [ctx (db-factory/db-context db-config)]
    
    ;; 2. Initialize schema
    (db-user/initialize-user-schema! ctx)
    
    ;; 3. Create repositories (database layer)
    (let [user-repo (db-user/create-user-repository ctx)
          session-repo (db-user/create-session-repository ctx)
          
          ;; 4. Create service (business layer)
          service (user-service/create-user-service user-repo session-repo)]
      
      {:service service
       :user-repo user-repo
       :session-repo session-repo
       :db-context ctx})))

;; Usage
(let [system (create-user-system {:adapter :postgresql
                                  :host "localhost"
                                  :port 5432
                                  :name "myapp"
                                  :username "user"
                                  :password "pass"})
      service (:service system)]
  
  ;; Use service for all business operations
  (.create-user service {:email "user@example.com"
                         :name "John Doe"
                         :role :user
                         :tenant-id (java.util.UUID/randomUUID)})
  
  (.find-user-by-email service "user@example.com" tenant-id))
```

## Benefits of New Architecture

### 1. **Database Agnostic Business Logic**
- Service layer works with any database
- Same code for PostgreSQL, SQLite, MySQL, H2
- Easy to switch between databases

### 2. **Clean Separation of Concerns**
- Business logic in `boundary.user.shell.service`
- Database logic in `boundary.shell.adapters.database.user`
- Domain models in `boundary.user.schema`

### 3. **Improved Testability**
- Mock repositories for unit tests
- No database required for business logic tests
- Clear interfaces make testing easier

### 4. **Better Maintainability**
- Database changes don't affect business logic
- Business rule changes don't affect database code
- Clear boundaries between layers

### 5. **Dependency Injection**
- Services receive dependencies, not create them
- Easy to configure different implementations
- Better control over object lifecycle

## File Changes Summary

### Removed Files
- `src/boundary/user/shell/adapters.clj` ❌
- `src/boundary/user/shell/multi_db_adapters.clj` ❌  
- `test/boundary/user/shell/multi_db_adapters_test.clj` ❌

### New Files
- `src/boundary/shell/adapters/database/user.clj` ✅
- `src/boundary/shell/adapters/database/schema.clj` ✅
- `examples/database_agnostic_user_example.clj` ✅

### Updated Files
- `src/boundary/user/shell/service.clj` ✅ (now database-agnostic)
- `src/boundary/shell/adapters/database/core.clj` ✅ (schema notes)

## Testing the Migration

After migration, verify your system works by:

1. **Run existing tests** - they should pass with the new architecture
2. **Create a simple user** - verify CRUD operations work
3. **Test with different databases** - switch between SQLite/PostgreSQL easily
4. **Check logs** - no deprecation warnings should appear

## Need Help?

- Check `examples/database_agnostic_user_example.clj` for working examples
- Review `src/boundary/shell/adapters/database/user.clj` for available methods
- Look at `src/boundary/user.ports.clj` for interface definitions