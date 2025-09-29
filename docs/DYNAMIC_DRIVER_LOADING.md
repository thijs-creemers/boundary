# Dynamic JDBC Driver Loading System

## Overview

The Boundary framework implements a **dynamic JDBC driver loading system** that automatically loads only the required database drivers based on your environment configuration. This eliminates the need for manual dependency management and prevents ClassNotFoundException errors.

## Key Benefits

- ✅ **Automatic Driver Loading**: Drivers are loaded dynamically based on active databases in configuration
- ✅ **Environment-Specific**: Each environment (dev/test/prod) loads only what it needs
- ✅ **Clear Error Messages**: Helpful suggestions when drivers are missing
- ✅ **No Manual Synchronization**: No need to keep aliases and config files in sync
- ✅ **Zero Configuration**: Works out of the box with standard setup

## How It Works

### 1. Configuration-Driven Loading

The system reads your environment configuration (`resources/conf/{env}/config.edn`) and determines which databases are marked as `:active`. Based on this, it automatically loads the corresponding JDBC drivers.

```clojure
;; Example config.edn
{:active 
 {:boundary/h2 {:memory true}
  :boundary/postgresql {:host "localhost" :port 5432 ...}}
 
 :inactive
 {:boundary/sqlite {:db "fallback.db"}}}
```

In this example, the system will automatically load:
- H2 driver (`org.h2.Driver`) 
- PostgreSQL driver (`org.postgresql.Driver`)

But **NOT** the SQLite driver since it's inactive.

### 2. Driver Mapping

The system maintains a mapping between configuration keys and JDBC driver classes:

| Config Key | JDBC Driver Class | Maven Dependency |
|------------|-------------------|-------------------|
| `:boundary/h2` | `org.h2.Driver` | `com.h2database/h2` |
| `:boundary/postgresql` | `org.postgresql.Driver` | `org.postgresql/postgresql` |
| `:boundary/sqlite` | `org.sqlite.JDBC` | `org.xerial/sqlite-jdbc` |
| `:boundary/mysql` | `com.mysql.cj.jdbc.Driver` | `com.mysql/mysql-connector-j` |

### 3. Runtime Loading

When you call `initialize-databases!`, the system:

1. **Analyzes Configuration**: Determines which databases are active
2. **Loads Required Drivers**: Uses `Class/forName` to load only needed drivers
3. **Provides Clear Errors**: If a driver is missing, shows exactly which dependency to add
4. **Initializes Connections**: Creates connection pools for loaded drivers

## Usage

### Running Tests
```bash
# New simplified approach - loads all drivers automatically
clojure -M:test

# All environments work the same way
clojure -M:dev    # Loads drivers based on dev config
clojure -M:prod   # Loads drivers based on prod config
```

### Programmatic Usage

```clojure
(require '[boundary.shell.adapters.database.driver-loader :as driver-loader])

;; Load drivers for current environment
(driver-loader/load-drivers-for-current-environment!)
;; => {:success true, :loaded ["org.h2.Driver"], :failed []}

;; Load drivers for specific environment
(driver-loader/load-drivers-for-environment! "prod")
;; => {:success true, :loaded ["org.postgresql.Driver"], :failed []}

;; Validate drivers without loading them
(driver-loader/validate-drivers-available config)
;; => {:valid true, :required [...], :missing []}
```

## Configuration Examples

### Development Environment (`resources/conf/dev/config.edn`)
```clojure
{:active 
 {:boundary/h2 {:memory false, :db "dev-database"}}
 
 :inactive
 {:boundary/postgresql {:host nil, :port nil, ...}}}
```
**Result**: Loads H2 driver only

### Test Environment (`resources/conf/test/config.edn`)
```clojure
{:active 
 {:boundary/h2 {:memory true}}
 
 :inactive
 {:boundary/sqlite {:db "test.db"}}}
```
**Result**: Loads H2 driver only (in-memory)

### Production Environment (`resources/conf/prod/config.edn`)
```clojure
{:active 
 {:boundary/postgresql 
  {:host #env "POSTGRES_HOST"
   :port #env "POSTGRES_PORT"
   :dbname #env "POSTGRES_DB"
   :user #env "POSTGRES_USER"
   :password #env "POSTGRES_PASSWORD"}}
 
 :inactive
 {:boundary/sqlite {:db "prod-fallback.db"}
  :boundary/h2 {:memory false, :db "prod-h2-fallback"}}}
```
**Result**: Loads PostgreSQL driver only

## Error Handling

### Missing Driver Example
If a required driver is not available, you get a clear error message:

```
Failed to load required JDBC drivers:
- org.postgresql.Driver (ClassNotFoundException)
  Add to deps.edn: org.postgresql/postgresql {:mvn/version "42.7.7"}

Either add the missing dependencies to deps.edn or move the corresponding 
databases to :inactive in your configuration.
```

### Validation Example
```clojure
(driver-loader/validate-drivers-available config)
;; => {:valid false
;;     :required ["org.postgresql.Driver"]
;;     :missing [{:driver "org.postgresql.Driver"
;;                :error "ClassNotFoundException"  
;;                :suggestion "org.postgresql/postgresql {:mvn/version \"42.7.7\"}"}]}
```

## Migration from Old System

### Before (Manual Alias Management)
```bash
# Had to remember specific aliases
clojure -M:test:db/h2           # For H2 tests
clojure -M:test:db/sqlite       # For SQLite tests  
clojure -M:dev:db/sqlite        # For dev with SQLite
clojure -M:prod:db/postgresql   # For prod with PostgreSQL
```

Problems:
- ❌ Had to keep aliases in sync with config files
- ❌ Easy to use wrong alias/config combination
- ❌ ClassNotFoundException when aliases didn't match config
- ❌ Manual maintenance of multiple aliases

### After (Dynamic Loading)
```bash
# Simple, works everywhere
clojure -M:test    # Automatically loads what test config needs
clojure -M:dev     # Automatically loads what dev config needs  
clojure -M:prod    # Automatically loads what prod config needs
```

Benefits:
- ✅ Configuration files fully control driver loading
- ✅ No more alias/config synchronization needed
- ✅ Clear error messages when drivers are missing
- ✅ Simplified dependency management

## Dependencies Configuration

The system requires all potential JDBC drivers to be available on the classpath. In `deps.edn`:

```clojure
{:aliases 
 {:db {:extra-deps {org.xerial/sqlite-jdbc      {:mvn/version "3.47.1.0"}
                    org.postgresql/postgresql   {:mvn/version "42.7.7"}
                    com.h2database/h2           {:mvn/version "2.3.232"}
                    com.mysql/mysql-connector-j {:mvn/version "9.4.0"}}}
                    
 :test {:extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}
                     org.xerial/sqlite-jdbc      {:mvn/version "3.47.1.0"}
                     org.postgresql/postgresql   {:mvn/version "42.7.7"}
                     com.h2database/h2           {:mvn/version "2.3.232"}
                     com.mysql/mysql-connector-j {:mvn/version "9.4.0"}}
        :jvm-opts    ["-Denv=test"]}}}
```

This ensures all drivers are available, but only the ones needed by your configuration are actually loaded and used.

## Implementation Details

The dynamic driver loading system is implemented in:
- `boundary.shell.adapters.database.driver-loader` - Core driver loading logic
- `boundary.shell.adapters.database.integration-example` - Integration with initialization
- Environment configuration files determine which drivers to load

Key functions:
- `load-drivers-for-current-environment!` - Load drivers for current env
- `load-drivers-for-environment!` - Load drivers for specific env  
- `validate-drivers-available` - Check driver availability without loading
- `determine-required-drivers` - Analyze config to find required drivers

## Testing

The system includes comprehensive tests that verify:
- ✅ Correct driver loading based on configuration
- ✅ Error handling for missing drivers
- ✅ Environment-specific behavior
- ✅ Integration with database initialization
- ✅ Clear error messages and suggestions

Run tests with:
```bash
clojure -M:test
```

All 82 tests pass, including specific tests for the driver loading functionality.