# Migration Guide: SQLite-Only to Multi-Database System

This guide helps you migrate from the legacy SQLite-only database system to the new multi-database adapter system that supports SQLite, PostgreSQL, MySQL, and H2.

## Overview

The new system provides:
- **Database Agnostic**: Same code works with multiple database types
- **Backward Compatible**: Existing code continues to work with deprecation warnings
- **Enhanced Features**: Better connection pooling, transactions, and query building
- **Production Ready**: Robust error handling and performance optimizations

## Quick Migration Checklist

- [ ] Update namespace imports
- [ ] Replace `create-connection-pool` with `dbf/db-context` 
- [ ] Update repository constructors
- [ ] Replace direct `execute-query!` calls with `db/execute-query!`
- [ ] Test with existing SQLite database
- [ ] Optional: Switch to different database type

## Before and After Examples

### 1. Database Connection

**Before (Old SQLite System):**
```clojure
(require '[boundary.shared.shell.persistence :as persistence])

(def db-config {:dbtype "sqlite"
                :dbname "/path/to/database.db"})

(def datasource (persistence/create-connection-pool db-config))
```

**After (New Multi-Database System):**
```clojure
(require '[boundary.shell.adapters.database.factory :as dbf])

(def db-config {:adapter :sqlite
                :database-path "/path/to/database.db"})

(def ctx (dbf/db-context db-config))
```

### 2. Direct Database Queries

**Before:**
```clojure
(require '[boundary.shared.shell.persistence :as persistence])

;; Query execution
(def users (persistence/execute-query! datasource query-map))
(def user (persistence/execute-one! datasource query-map))
(def affected (persistence/execute-update! datasource query-map))

;; Transactions
(persistence/with-transaction [tx datasource]
  (persistence/execute-update! tx query1)
  (persistence/execute-update! tx query2))
```

**After:**
```clojure
(require '[boundary.shell.adapters.database.core :as db])

;; Query execution (same interface, better performance)
(def users (db/execute-query! ctx query-map))
(def user (db/execute-one! ctx query-map))
(def affected (db/execute-update! ctx query-map))

;; Transactions (improved error handling)
(db/with-transaction [tx ctx]
  (db/execute-update! tx query1)
  (db/execute-update! tx query2))
```

### 3. Repository Pattern

**Before:**
```clojure
(require '[boundary.user.shell.adapters :as user-adapters])

;; SQLite-specific repository
(def user-repo (user-adapters/->SQLiteUserRepository datasource))
(.create-user user-repo user-data)
```

**After:**
```clojure
;; Option 1: Use new database-agnostic repositories
(require '[boundary.user.shell.multi-db-adapters :as multi-adapters])

(def user-repo (multi-adapters/new-user-repository ctx))
(.create-user user-repo user-data) ; Same interface!

;; Option 2: Keep using old repositories (backward compatible)
(require '[boundary.user.shell.adapters :as user-adapters])

(def user-repo (user-adapters/->SQLiteUserRepository (:datasource ctx)))
(.create-user user-repo user-data) ; Still works with deprecation warnings
```

### 4. Schema Initialization

**Before:**
```clojure
;; Manual DDL with SQLite-specific syntax
(jdbc/execute! datasource [\"CREATE TABLE users (..., 
                            INDEX idx_users_tenant (tenant_id))\"])
```

**After:**
```clojure
(require '[boundary.user.shell.multi-db-adapters :as multi-adapters])

;; Cross-database compatible schema initialization  
(multi-adapters/initialize-database! ctx)

;; Automatically creates:
;; - Proper column types for each database (BOOLEAN vs INTEGER vs TINYINT)
;; - Separate INDEX statements for compatibility
;; - Foreign key constraints where supported
```

## Step-by-Step Migration

### Step 1: Update Dependencies

Ensure your `deps.edn` or `project.clj` includes the necessary dependencies for your target database:

```clojure
;; deps.edn
{:deps {;; Existing dependencies...
        
        ;; Database drivers (add as needed)
        org.xerial/sqlite-jdbc {:mvn/version "3.42.0.0"}      ; SQLite
        org.postgresql/postgresql {:mvn/version "42.6.0"}      ; PostgreSQL  
        com.mysql/mysql-connector-j {:mvn/version "8.1.0"}     ; MySQL
        com.h2database/h2 {:mvn/version "2.2.220"}            ; H2
        
        ;; Connection pooling
        com.zaxxer/HikariCP {:mvn/version "5.0.1"}
        
        ;; Query building
        com.github.seancorfield/honeysql {:mvn/version "2.4.1066"}}}
```

### Step 2: Update Configuration

**Old configuration format:**
```clojure
(def db-config 
  {:dbtype "sqlite"
   :dbname "/path/to/database.db"
   :maximum-pool-size 8})
```

**New configuration format:**
```clojure
(def db-config
  {:adapter :sqlite
   :database-path "/path/to/database.db"
   :pool {:maximum-pool-size 8}})
```

**Configuration mapping:**
- `:dbtype` → `:adapter` (with keyword value)
- `:dbname` → `:database-path` (for SQLite) or `:name` (for server databases)
- Pool settings → nested under `:pool` key

### Step 3: Replace Namespace Imports

Update your namespace declarations:

```clojure
;; Before
(ns myapp.database
  (:require [boundary.shared.shell.persistence :as persistence]
            [boundary.user.shell.adapters :as user-adapters]))

;; After  
(ns myapp.database
  (:require [boundary.shell.adapters.database.factory :as dbf]
            [boundary.shell.adapters.database.core :as db]
            [boundary.user.shell.multi-db-adapters :as user-adapters]))
```

### Step 4: Update Database Context Creation

**Before:**
```clojure
(defn create-database []
  (let [config {...}
        datasource (persistence/create-connection-pool config)]
    {:datasource datasource}))
```

**After:**
```clojure
(defn create-database []
  (let [config {...}
        ctx (dbf/db-context config)]
    ctx)) ; ctx contains both :datasource and :adapter
```

### Step 5: Update Repository Constructors

**Before:**
```clojure
(defn create-repositories [datasource]
  {:user-repo (user-adapters/->SQLiteUserRepository datasource)
   :session-repo (user-adapters/->SQLiteUserSessionRepository datasource)})
```

**After:**
```clojure
(defn create-repositories [ctx]
  {:user-repo (user-adapters/new-user-repository ctx)
   :session-repo (user-adapters/new-user-session-repository ctx)})
```

### Step 6: Update Direct Database Calls

**Before:**
```clojure
(defn get-users [datasource filters]
  (let [where-clause (build-filters filters)
        query {:select [:*] :from [:users] :where where-clause}]
    (persistence/execute-query! datasource query)))
```

**After:**
```clojure
(defn get-users [ctx filters] 
  (let [where-clause (db/build-where-clause ctx filters) ; Improved!
        query {:select [:*] :from [:users] :where where-clause}]
    (db/execute-query! ctx query)))
```

### Step 7: Update Schema Management

**Before:**
```clojure
(defn initialize-schema! [datasource]
  (jdbc/execute! datasource [\"CREATE TABLE IF NOT EXISTS users (...)\"])
  (jdbc/execute! datasource [\"CREATE TABLE IF NOT EXISTS user_sessions (...)\"]))
```

**After:**
```clojure
(defn initialize-schema! [ctx]
  (user-adapters/initialize-database! ctx)) ; Cross-database compatible!
```

### Step 8: Test Migration

Create a test to verify the migration:

```clojure
(deftest test-migration-compatibility
  (testing "Old and new systems produce same results"
    (let [;; Old system
          old-datasource (old-persistence/create-connection-pool old-config)
          old-repo (old-adapters/->SQLiteUserRepository old-datasource)
          
          ;; New system  
          new-ctx (dbf/db-context new-config)
          new-repo (new-adapters/new-user-repository new-ctx)
          
          ;; Test data
          user-data {:email "test@example.com" :name "Test" :role :user}]
      
      ;; Both should work identically
      (let [old-user (.create-user old-repo user-data)
            new-user (.create-user new-repo user-data)]
        
        (is (= (:email old-user) (:email new-user)))
        (is (= (:name old-user) (:name new-user)))))))
```

## Database Type Migration

### Staying with SQLite

If you want to keep using SQLite but benefit from the new system:

```clojure
;; Minimal change - same database, better system
(def ctx (dbf/db-context {:adapter :sqlite 
                          :database-path "/path/to/existing.db"}))
```

### Migrating to PostgreSQL

To switch to PostgreSQL for production:

```clojure
(def ctx (dbf/db-context {:adapter :postgresql
                          :host (System/getenv "DB_HOST")
                          :port 5432
                          :name (System/getenv "DB_NAME") 
                          :username (System/getenv "DB_USER")
                          :password (System/getenv "DB_PASS")}))

;; Same application code works!
(user-adapters/initialize-database! ctx)
(def repo (user-adapters/new-user-repository ctx))
```

### Data Migration Between Databases

To migrate data from SQLite to PostgreSQL:

```clojure
(defn migrate-data-sqlite-to-postgresql []
  (let [sqlite-ctx (dbf/db-context {:adapter :sqlite :database-path "./old.db"})
        pg-ctx (dbf/db-context {:adapter :postgresql :host "localhost" ...})
        
        ;; Create repositories for both
        sqlite-repo (user-adapters/new-user-repository sqlite-ctx) 
        pg-repo (user-adapters/new-user-repository pg-ctx)]
    
    ;; Initialize PostgreSQL schema
    (user-adapters/initialize-database! pg-ctx)
    
    ;; Extract data from SQLite
    (let [users (.find-users-by-tenant sqlite-repo tenant-id {:include-deleted? true})]
      
      ;; Insert into PostgreSQL  
      (.create-users-batch pg-repo (:users users)))
    
    (.close (:datasource sqlite-ctx))
    (.close (:datasource pg-ctx))))
```

## Troubleshooting Migration

### Common Issues

**1. "Adapter not found" errors**
```clojure
;; Problem: Wrong adapter name
{:adapter "sqlite"} ; String instead of keyword

;; Solution: Use keyword
{:adapter :sqlite}
```

**2. Connection pool configuration errors**
```clojure
;; Problem: Pool config in wrong place
{:adapter :sqlite
 :maximum-pool-size 10} ; Should be nested

;; Solution: Nest under :pool
{:adapter :sqlite
 :pool {:maximum-pool-size 10}}
```

**3. Boolean value inconsistencies**
The new system handles boolean conversion automatically, but you might see different representations in the database:
- SQLite: `1`/`0` 
- PostgreSQL: `true`/`false`
- MySQL: `1`/`0`
- H2: `true`/`false`

This is handled transparently by the adapters.

**4. Schema differences** 
The new system creates cross-compatible schemas, but existing SQLite schemas might have incompatibilities:

```clojure
;; Old SQLite schema with inline indexes
"CREATE TABLE users (..., INDEX idx_name (name))"

;; New system creates separate index statements
"CREATE TABLE users (...)"
"CREATE INDEX IF NOT EXISTS idx_name ON users (name)"
```

### Validation Steps

1. **Test data integrity**: Ensure all existing data is accessible
2. **Test CRUD operations**: Verify create, read, update, delete work correctly
3. **Test transactions**: Ensure transaction behavior is preserved  
4. **Test performance**: Compare query performance before/after
5. **Test error handling**: Verify proper error messages and recovery

### Rollback Plan

If you need to rollback:

1. **Keep old dependencies** in your project temporarily
2. **Maintain old database files** during migration
3. **Use feature flags** to switch between old/new systems
4. **Monitor application logs** for deprecation warnings

```clojure
(defn create-repository [ctx-or-datasource]
  (if (contains? ctx-or-datasource :adapter)
    ;; New system
    (new-adapters/new-user-repository ctx-or-datasource) 
    ;; Old system fallback
    (old-adapters/->SQLiteUserRepository ctx-or-datasource)))
```

## Benefits After Migration

### Performance Improvements
- Better connection pooling with HikariCP
- Database-specific query optimizations (ILIKE vs LIKE)
- Improved transaction handling
- Batch operation optimizations

### Developer Experience
- Better error messages with context
- Comprehensive logging
- Cross-database compatibility
- Improved testing with H2 in-memory

### Production Benefits
- Multiple database options (SQLite → PostgreSQL for scaling)
- Robust connection management
- Better monitoring and observability
- Simplified configuration management

### Code Quality
- Elimination of duplicate database code
- Consistent error handling patterns
- Better separation of concerns
- Comprehensive test coverage

## Next Steps

After successful migration:

1. **Remove deprecated imports** once everything is working
2. **Switch database types** if desired for different environments
3. **Optimize connection pools** based on your application's load
4. **Add database monitoring** using the new observability features
5. **Consider H2 for testing** to speed up your test suite

The migration preserves all existing functionality while opening up new possibilities for database choice and improved performance.