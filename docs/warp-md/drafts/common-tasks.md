# Common Tasks

*Essential developer commands for daily workflows*

## Project Setup

### Initial Setup
```zsh
# Clone and verify
git clone <repository-url> elara
cd elara
clojure -M:test                     # Verify everything works
```

### Development Environment
```zsh
# Start REPL with dev dependencies
clojure -M:repl-clj

# Alternative: Start with specific profile
ELARA_PROFILE=dev clojure -M:repl-clj
```

## Testing

### Running Tests
```zsh
# Run all tests
clojure -M:test

# Run tests continuously (watch mode)
clojure -M:test --watch

# Run specific test namespaces
clojure -M:test --namespace elara.user.core-test

# Run module-specific tests
clojure -M:test --focus :user
clojure -M:test --focus :billing
clojure -M:test --focus :workflow

# Run with different reporters
clojure -M:test --reporter documentation  # Detailed output
clojure -M:test --reporter progress        # Progress bar
clojure -M:test --reporter pretty          # Pretty printed
```

### Test Debugging
```zsh
# Run single test with verbose output
clojure -M:test --namespace elara.user.core-test --verbose

# Run failing tests only
clojure -M:test --fail-fast

# Skip slow integration tests
clojure -M:test --skip-meta :integration
```

## REPL and System Management

### System Lifecycle (Integrant)
```clojure
;; In REPL
(require '[integrant.repl :as ig-repl])

;; Start system
(ig-repl/go)

;; Stop system
(ig-repl/halt)

;; Reload code and restart system
(ig-repl/reset)

;; Clear system configuration
(ig-repl/clear)

;; Check system status
(ig-repl/system)
```

### Configuration Management
```clojure
;; Load and inspect configuration
(require '[elara.config :as config])
(config/read-config "dev")

;; Debug configuration with pretty printing
(require '[clojure.pprint :refer [pprint]])
(pprint (config/read-config "dev"))

;; Check environment variables
(System/getenv "POSTGRES_HOST")
```

### Module Exploration
```clojure
;; Load and explore modules
(require '[elara.user.core :as user])
(require '[elara.billing.core :as billing])
(require '[elara.workflow.core :as workflow])

;; Check available functions
(dir user)
(doc user/create-user)
```

## Code Quality

### Linting
```zsh
# Lint all code
clojure -M:clj-kondo --lint src test

# Lint specific directories
clojure -M:clj-kondo --lint src/elara/user

# Lint with specific config
clojure -M:clj-kondo --lint src --config .clj-kondo/config.edn
```

### Dependency Management
```zsh
# Check for outdated dependencies
clojure -M:outdated

# Update dependencies (manual process - edit deps.edn)
# Then verify tests still pass
clojure -M:test
```

## Build and Deployment

### Building Artifacts
```zsh
# Create uberjar for deployment
clojure -T:build uber

# Check build output
ls -la target/
```

### Running Built Application
```zsh
# Run the uberjar
java -jar target/elara-0.1.0-standalone.jar

# Run with specific profile
ELARA_PROFILE=staging java -jar target/elara-0.1.0-standalone.jar

# Run with custom JVM options
java -Xmx2g -jar target/elara-0.1.0-standalone.jar
```

## Database Operations

### Development Database (SQLite)
```zsh
# Check database file
ls -la dev-database.db

# Connect to SQLite database
sqlite3 dev-database.db

# View tables
sqlite3 dev-database.db ".tables"

# Run SQL query
sqlite3 dev-database.db "SELECT * FROM users LIMIT 10;"
```

### PostgreSQL (if configured)
```zsh
# Connect to PostgreSQL
psql -h localhost -U postgres -d elara_dev

# Create development database
createdb elara_dev

# Drop and recreate database
dropdb elara_dev && createdb elara_dev
```

## Module Development

### Creating New Module
```zsh
# Create module directory structure
mkdir -p src/elara/newmodule/{core,shell}
mkdir -p test/elara/newmodule/{core,shell}

# Create basic files
touch src/elara/newmodule/core.clj
touch src/elara/newmodule/ports.clj
touch src/elara/newmodule/schema.clj
touch src/elara/newmodule/shell/service.clj
```

### Module Testing
```zsh
# Test specific module
clojure -M:test --focus :newmodule

# Test core vs shell separately
clojure -M:test --namespace elara.newmodule.core-test
clojure -M:test --namespace elara.newmodule.shell.service-test
```

## Git Workflows

### Feature Development
```zsh
# Create feature branch
git checkout -b feature/new-user-preferences

# Regular development cycle
git add .
git commit -m "Add user preference core logic"

# Push and create PR
git push -u origin feature/new-user-preferences
```

### Before Committing
```zsh
# Pre-commit checklist
clojure -M:test                                # Run tests
clojure -M:clj-kondo --lint src test         # Lint code
git add .                                     # Stage changes
git commit -m "Descriptive commit message"   # Commit
```

## Performance and Profiling

### REPL-based Profiling
```clojure
;; Time function execution
(time (your-function args))

;; Profile with more detail
(require '[clojure.pprint :refer [pprint]])
(defn profile [f & args]
  (let [start (System/nanoTime)
        result (apply f args)
        end (System/nanoTime)]
    (println (str "Execution time: " (/ (- end start) 1e6) " ms"))
    result))

(profile your-function args)
```

### Database Performance
```clojure
;; In REPL - check connection pool
(require '[elara.shell.system.components.postgresql :as pg])
(when-let [ds (:datasource pg/ds)]
  (.getMaximumPoolSize (.getHikariPoolMXBean ds)))
```

## Debugging

### REPL Debugging
```clojure
;; Add debugging to functions
(defn debug-function [x]
  (println "Debug:" x)  ; Add debugging
  (your-function x))

;; Inspect data structures
(require '[clojure.pprint :refer [pprint]])
(pprint complex-data-structure)

;; Check function source
(require '[clojure.repl :refer [source doc]])
(source function-name)
(doc function-name)
```

### Error Investigation
```clojure
;; Get full stack trace
(require '[clojure.repl :refer [pst]])
(pst)  ; Print stack trace of last exception

;; Investigate exception data
(when-let [e *e]  ; Last exception
  (ex-data e))
```

## Environment Management

### Development Environment
```zsh
# Set development profile
export ELARA_PROFILE=dev

# Set database to SQLite
unset POSTGRES_HOST POSTGRES_PORT POSTGRES_DB

# Use direnv for automatic env loading
echo 'export ELARA_PROFILE=dev' > .envrc
direnv allow
```

### Staging/Production Environment
```zsh
# Set environment variables
export ELARA_PROFILE=staging
export POSTGRES_HOST=staging-db.example.com
export POSTGRES_PORT=5432
export POSTGRES_DB=elara_staging
export POSTGRES_USER=app_user
export POSTGRES_PASSWORD="$(vault kv get -field=password secret/elara/staging)"
```

## Log Management

### Viewing Logs
```zsh
# Application logs (if file logging enabled)
tail -f logs/web-service.log

# Follow logs with filtering
tail -f logs/web-service.log | grep ERROR

# View logs in REPL console (default dev setup)
# Logs appear directly in REPL output
```

### Log Configuration
```clojure
;; Change log level in REPL
(require '[elara.shell.logging :as log])
;; (Adjust based on actual logging setup)
```

## Documentation

### Generate Documentation
```zsh
# If autodoc tools are configured
clojure -M:autodoc  # (if available)

# View current docs
ls -la docs/
open docs/architecture/index.html  # (if available)
```

### API Documentation
```clojure
;; In REPL - get function documentation
(doc function-name)

;; Find functions in namespace
(dir elara.user.core)

;; Search for functions
(apropos "user")
```

## CI/CD Integration

### Local CI Simulation
```zsh
# Run full CI-like check locally
clojure -M:test && \
clojure -M:clj-kondo --lint src test && \
clojure -T:build uber

# Clean build test
rm -rf target/ .cpcache/
clojure -M:test
clojure -T:build uber
```

## Troubleshooting

### Common Issues
```zsh
# Port conflicts
lsof -i :8080
kill -9 <PID>

# Dependency issues
rm -rf ~/.m2/repository/
clojure -P  # Re-download dependencies

# REPL won't start
clojure -M:repl-clj -v  # Verbose output
```

### System Recovery
```clojure
;; In REPL - reset everything
(ig-repl/halt)
(ig-repl/clear)
(require :reload-all '[your.namespace :as ns])
(ig-repl/go)
```

## Quick Reference Commands

### Most Common Daily Commands
```zsh
clojure -M:test --watch                    # Continuous testing
clojure -M:repl-clj                        # Start development
clojure -M:clj-kondo --lint src test      # Code quality check
git add . && git commit -m "message"      # Commit changes
```

### Emergency Recovery
```zsh
# If everything is broken
git stash                # Save current work
git checkout main        # Go to known good state
clojure -M:test         # Verify main works
git stash pop           # Restore work
```

---
*Bookmark this page for quick reference during development!*

*Tip: Use `Ctrl+R` in terminal to search command history for these commands.*
