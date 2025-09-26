# Conditional Database Dependencies Guide

## Overview

All database drivers are now **completely optional** and loaded only when your configuration requires them. This eliminates unused dependencies and gives you full control over what gets loaded.

## How It Works

### 1. Zero Database Dependencies by Default

The main `:deps` section only includes database abstraction layers:
```clojure
:deps {
  com.github.seancorfield/next.jdbc {...}  ; Database abstraction
  com.github.seancorfield/honeysql  {...}  ; Query builder
  com.zaxxer/HikariCP               {...}  ; Connection pooling
  ;; NO JDBC drivers included by default
}
```

### 2. Database-Specific Aliases

Each database has its own alias:
```clojure
:db/sqlite     ; Loads SQLite JDBC driver
:db/postgresql ; Loads PostgreSQL JDBC driver  
:db/h2         ; Loads H2 JDBC driver
:db/mysql      ; Loads MySQL JDBC driver
:db/all-drivers ; Loads all JDBC drivers
```

### 3. Environment Aliases

Pure environment configuration (no drivers):
```clojure
:env/dev   ; Sets -Denv=dev
:env/test  ; Sets -Denv=test  
:env/prod  ; Sets -Denv=prod
```

### 4. Combined Convenience Aliases

Common combinations for easy use:
```clojure
:dev-sqlite  ; SQLite + dev environment
:dev-h2      ; H2 + dev environment
:test-h2     ; H2 + test environment
:prod-pg     ; PostgreSQL + prod environment
```

## Usage Examples

### Method 1: Combine Environment + Database Aliases

```bash
# Development with SQLite
clj -M:env/dev:db/sqlite -m your.app

# Development with H2  
clj -M:env/dev:db/h2 -m your.app

# Testing with H2
clj -M:env/test:db/h2 -m your.test.runner

# Production with PostgreSQL
clj -M:env/prod:db/postgresql -m your.app

# Development with multiple databases
clj -M:env/dev:db/sqlite:db/postgresql -m your.app
```

### Method 2: Use Convenience Aliases

```bash
# These are equivalent to the above
clj -M:dev-sqlite -m your.app
clj -M:dev-h2 -m your.app  
clj -M:test-h2 -m your.test.runner
clj -M:prod-pg -m your.app
```

### Method 3: Load All Drivers (Development/Testing)

```bash
# Development with all drivers available
clj -M:env/dev:db/all-drivers -m your.app

# Testing with all drivers for integration tests
clj -M:env/test:db/all-drivers -m your.test.runner
```

## Configuration Alignment

Your `config.edn` files determine which databases are initialized, but you need the corresponding JDBC drivers loaded:

### Development Config (SQLite Active)
```clojure
;; config/dev/config.edn
:active {
  :boundary/sqlite {:db "dev-database.db"}
}
```
**Command**: `clj -M:dev-sqlite -m your.app`

### Test Config (H2 Active)
```clojure  
;; config/test/config.edn
:active {
  :boundary/h2 {:memory true}
}
```
**Command**: `clj -M:test-h2 -m your.test.runner`

### Production Config (PostgreSQL Active)
```clojure
;; config/prod/config.edn  
:active {
  :boundary/postgresql {:host "..." :dbname "..."}
}
```
**Command**: `clj -M:prod-pg -m your.app`

### Multi-Database Config
```clojure
;; config/dev/config.edn
:active {
  :boundary/sqlite {:db "cache.db"}
  :boundary/postgresql {:host "localhost" :dbname "main"}  
}
```
**Command**: `clj -M:env/dev:db/sqlite:db/postgresql -m your.app`

## REPL Development

For REPL development, choose the drivers you need:

```bash
# REPL with SQLite only
clj -M:repl-clj:db/sqlite

# REPL with H2 only  
clj -M:repl-clj:db/h2

# REPL with all drivers (most flexible)
clj -M:repl-clj:db/all-drivers
```

## Benefits

### 1. **Minimal Dependencies**
- Only load what you actually use
- Faster startup times
- Smaller memory footprint
- No version conflicts from unused drivers

### 2. **Explicit Configuration**
- Clear relationship between config and dependencies
- Easy to see what databases are available
- No surprises about missing drivers

### 3. **Environment Flexibility**
- Different driver combinations per environment
- Easy to test with different database backends
- Simple production deployment

### 4. **Development Friendly**
- Use `:db/all-drivers` during development for maximum flexibility
- Switch to specific drivers for production optimization
- Clear error messages when drivers are missing

## Error Handling

If your configuration tries to use a database without the corresponding driver:

```
ClassNotFoundException: org.h2.Driver
```

**Solution**: Add the appropriate alias:
```bash
# If config uses H2 but driver not loaded
clj -M:your-current-aliases:db/h2 -m your.app
```

## Recommended Workflow

### 1. **Development**
Use convenience aliases for common combinations:
```bash
clj -M:dev-sqlite -m your.app      # Quick SQLite development
clj -M:dev-h2 -m your.app          # H2 for advanced features
```

### 2. **Testing**  
Use H2 for fast, isolated tests:
```bash
clj -M:test-h2 -m your.test.runner
```

### 3. **Integration Testing**
Use all drivers to test compatibility:
```bash
clj -M:env/test:db/all-drivers -m your.integration.tests
```

### 4. **Production**
Use only the specific driver needed:
```bash
clj -M:prod-pg -m your.app
```

This conditional dependency system gives you complete control while maintaining simplicity and flexibility! 🎯