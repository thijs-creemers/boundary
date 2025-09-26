# Migration Guide: Infrastructure Reorganization

## Overview

The user module has been refactored to achieve proper separation of concerns between business logic and infrastructure. Database adapters have been moved from the generic shell layer into the user module's own infrastructure layer.

## What Changed

### Directory Structure
```
OLD STRUCTURE:
src/boundary/
├── shell/adapters/database/
│   └── user.clj              # Database adapters mixed with shell
└── user/shell/
    ├── adapters.clj          # Deprecated
    └── multi_db_adapters.clj # Deprecated

NEW STRUCTURE:  
src/boundary/
├── shell/adapters/database/
│   └── user.clj              # DEPRECATED - delegates to new location
└── user/
    ├── infrastructure/
    │   └── database.clj      # NEW - User-specific database adapters
    └── shell/
        ├── service.clj       # NEW - Database-agnostic business services
        ├── adapters.clj      # DEPRECATED - delegates to infrastructure
        └── multi_db_adapters.clj # DEPRECATED - delegates to infrastructure
```

### Key Changes

1. **Database adapters moved**: From `boundary.shell.adapters.database.user` to `boundary.user.infrastructure.database`
2. **New service layer**: Database-agnostic business services in `boundary.user.shell.service`
3. **Backward compatibility**: Old namespaces deprecated but still work (with warnings)
4. **Clean architecture**: Proper separation between business logic and infrastructure

## Migration Steps

### Step 1: Update Imports

**Replace deprecated imports:**
```clojure
;; OLD (will show deprecation warnings)
(require '[boundary.shell.adapters.database.user :as db-user]
         '[boundary.user.shell.adapters :as user-adapters]  
         '[boundary.user.shell.multi-db-adapters :as multi-db])

;; NEW (recommended)
(require '[boundary.user.infrastructure.database :as user-db]
         '[boundary.user.shell.service :as user-service])
```

### Step 2: Update Repository Creation

**Old approach:**
```clojure
(def user-repo (db-user/create-user-repository ctx))
(def session-repo (db-user/create-session-repository ctx))
```

**New approach:**
```clojure
(def user-repo (user-db/create-user-repository ctx))  
(def session-repo (user-db/create-session-repository ctx))
```

### Step 3: Update Schema Initialization

**Old approach:**
```clojure
(db-user/initialize-user-schema! ctx)
```

**New approach:**
```clojure
(user-db/initialize-user-schema! ctx)
```

### Step 4: Use Database-Agnostic Services (Recommended)

Instead of using repositories directly, consider using the new service layer:

```clojure
(require '[boundary.user.shell.service :as user-service]
         '[boundary.user.infrastructure.database :as user-db])

;; Create repositories
(def user-repo (user-db/create-user-repository ctx))
(def session-repo (user-db/create-session-repository ctx))

;; Create database-agnostic service
(def service (user-service/create-user-service user-repo session-repo))

;; Use business operations
(user-service/create-user service {:email "user@example.com" 
                                   :password "secure-password"
                                   :role :user
                                   :tenant-id tenant-id})

(user-service/authenticate service "user@example.com" "secure-password")
```

## Migration Examples

### Example 1: Basic Repository Usage

**Before:**
```clojure
(ns my.app.users
  (:require [boundary.shell.adapters.database.user :as db-user]
            [boundary.shell.adapters.database.factory :as db-factory]))

(def ctx (db-factory/create-database-context config))
(def user-repo (db-user/create-user-repository ctx))

;; Initialize schema
(db-user/initialize-user-schema! ctx)

;; Use repository directly
(defn create-user [user-data]
  (.create-user user-repo user-data))
```

**After:**
```clojure
(ns my.app.users
  (:require [boundary.user.infrastructure.database :as user-db]
            [boundary.user.shell.service :as user-service]
            [boundary.shell.adapters.database.factory :as db-factory]))

(def ctx (db-factory/create-database-context config))
(def user-repo (user-db/create-user-repository ctx))
(def session-repo (user-db/create-session-repository ctx))

;; Initialize schema
(user-db/initialize-user-schema! ctx)

;; Create service (recommended)
(def service (user-service/create-user-service user-repo session-repo))

;; Use service instead of repository directly
(defn create-user [user-data]
  (user-service/create-user service user-data))
```

### Example 2: With Session Management

**Before:**
```clojure
(ns my.app.auth
  (:require [boundary.shell.adapters.database.user :as db-user]))

(def user-repo (db-user/create-user-repository ctx))
(def session-repo (db-user/create-session-repository ctx))

(defn login [email password]
  (when-let [user (.find-user-by-email user-repo email tenant-id)]
    (when (verify-password password (:password-hash user))
      (let [session-data {:user-id (:id user)
                          :tenant-id (:tenant-id user)
                          :expires-at (expire-time)}]
        (.create-session session-repo session-data)))))
```

**After:**
```clojure
(ns my.app.auth
  (:require [boundary.user.infrastructure.database :as user-db]
            [boundary.user.shell.service :as user-service]))

(def user-repo (user-db/create-user-repository ctx))
(def session-repo (user-db/create-session-repository ctx))
(def service (user-service/create-user-service user-repo session-repo))

(defn login [email password]
  ;; Use service method that handles business logic
  (user-service/authenticate service email password))
```

## Compatibility Notes

### Deprecated Namespaces Still Work

The following namespaces are deprecated but continue to function:
- `boundary.shell.adapters.database.user`
- `boundary.user.shell.adapters`  
- `boundary.user.shell.multi-db-adapters`

They will:
- Log deprecation warnings
- Delegate to the new infrastructure layer
- Continue to work during transition period
- Be removed in a future version

### No Breaking Changes

All existing function signatures remain the same. You can migrate gradually:

1. **Phase 1**: Update imports only (immediate)
2. **Phase 2**: Adopt service layer (recommended)  
3. **Phase 3**: Remove deprecated imports (when ready)

## Benefits After Migration

### 1. Clean Architecture
- Business logic separated from infrastructure
- Database-agnostic services
- Testable components

### 2. Better Organization
- User-specific code in user module
- Clear boundaries between layers
- Easier maintenance

### 3. Enhanced Testing
```clojure
;; Easy to test business logic with mocks
(def mock-user-repo (reify boundary.user.ports/IUserRepository ...))
(def service (user-service/create-user-service mock-user-repo mock-session-repo))

;; Test business logic without database
(deftest user-creation-validation
  (testing "should validate email format"
    (is (thrown? ExceptionInfo 
                 (user-service/create-user service {:email "invalid"})))))
```

### 4. Future Extensibility
- Easy to add other infrastructure implementations
- Services can be composed and extended
- Clear patterns for other domain modules

## Troubleshooting

### Common Issues

**1. Import errors after migration:**
```clojure
;; Make sure to require the new namespace
(require '[boundary.user.infrastructure.database :as user-db])
```

**2. Deprecation warnings in logs:**
```
WARN - boundary.shell.adapters.database.user/create-user-repository is deprecated. 
       Use boundary.user.infrastructure.database/create-user-repository instead.
```
→ Update your imports to the new namespace

**3. Missing service functions:**
```clojure
;; If you need business logic, use the service layer
(require '[boundary.user.shell.service :as user-service])
```

### Getting Help

If you encounter issues during migration:
1. Check the deprecation warnings for guidance
2. Refer to the architecture documentation
3. Look at the examples in this guide
4. Test incrementally with small changes

## Timeline

- **Now**: New infrastructure available, old namespaces deprecated
- **Next release**: More business services added to service layer
- **Future release**: Deprecated namespaces will be removed

Start migrating now to avoid disruption in future releases!