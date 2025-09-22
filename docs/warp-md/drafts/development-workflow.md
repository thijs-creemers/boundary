# Development Workflow

*Concrete commands and steps for daily Boundary development*

## Quick Development Cycle

### 1. Start Development REPL

**Clojure REPL with CIDER support:**
```zsh
clojure -M:repl-clj
```

**ClojureScript REPL (if needed):**
```zsh
clojure -M:repl-cljs
```

### 2. Test-Driven Development

**Run all tests:**
```zsh
clojure -M:test
```

**Watch mode (rerun tests on file changes):**
```zsh
clojure -M:test --watch
```

**Focus on specific tests:**
```zsh
clojure -M:test --focus :user
clojure -M:test --focus boundary.user.core.user-test
```

**Skip tests temporarily:**
```zsh
clojure -M:test --skip :integration
```

### 3. Code Quality Checks

**Lint code:**
```zsh
clojure -M:clj-kondo --lint src test
```

**Check dependency updates:**
```zsh
clojure -M:outdated
```

## System Lifecycle Management

### Integrant-Based Development

Boundary uses Integrant for system lifecycle management. The development workflow relies on REPL-driven development with live system reloading.

**In your REPL:**
```clojure
;; Load integrant REPL tools
(require '[integrant.repl :as ig-repl])
(require '[integrant.core :as ig])

;; Set your system config (customize path as needed)
(ig-repl/set-prep! (constantly (ig/read-config "resources/conf/dev/config.edn")))

;; Start the system
(ig-repl/go)

;; Your system is now running!
;; - Database connections established
;; - Web server started (if configured)
;; - All components wired together

;; Make code changes, then reload
(ig-repl/reset)

;; Stop the system when done
(ig-repl/halt)
```

### System Configuration

**Development config location:**
- Primary: `resources/conf/dev/config.edn`
- Contains SQLite database for local development
- Inactive PostgreSQL config for production-like testing

**Key development settings:**
```clojure
{:boundary/settings
 {:name "boundary-dev"        ; Development environment name
  :version "0.1.0"}
 
 :boundary/sqlite            ; Fast local database
 {:db "dev-database.db"}
 
 ;; PostgreSQL available but inactive for dev
 :inactive
 {:boundary/postgresql {...}}}
```

## Module-Centric Development

### Adding a New Feature to Existing Module

**Example: Adding new user functionality**

1. **Start with the core (pure functions):**
   ```zsh
   $EDITOR src/boundary/user/core/user.clj
   ```

2. **Update schemas:**
   ```zsh
   $EDITOR src/boundary/user/schema.clj
   ```

3. **Add tests first:**
   ```zsh
   $EDITOR test/boundary/user/core/user_test.clj
   ```

4. **Run focused tests:**
   ```zsh
   clojure -M:test --focus boundary.user.core.user-test
   ```

5. **Update shell layer (adapters, services):**
   ```zsh
   $EDITOR src/boundary/user/shell/service.clj
   $EDITOR src/boundary/user/shell/adapters.clj
   ```

6. **Add integration tests:**
   ```zsh
   $EDITOR test/boundary/user/shell/adapters_test.clj
   ```

7. **Update interfaces (HTTP, CLI):**
   ```zsh
   $EDITOR src/boundary/user/http.clj
   $EDITOR src/boundary/user/cli.clj
   ```

### Creating a New Module

**Template structure for new module (e.g., `notifications`):**

```
src/boundary/notifications/
├── core/                          # Pure business logic
│   ├── notifications.clj          # Core notification functions
│   └── templates.clj              # Template processing logic
├── ports.clj                      # Abstract interfaces
├── schema.clj                     # Data validation schemas
├── http.clj                       # HTTP API endpoints
├── cli.clj                        # CLI commands
└── shell/                         # Infrastructure layer
    ├── adapters.clj               # Email, SMS adapters
    └── service.clj                # Service orchestration
```

**Development steps:**

1. **Create module structure:**
   ```zsh
   mkdir -p src/boundary/notifications/{core,shell}
   mkdir -p test/boundary/notifications/{core,shell}
   ```

2. **Start with ports (define interfaces):**
   ```zsh
   $EDITOR src/boundary/notifications/ports.clj
   ```

3. **Define schemas:**
   ```zsh
   $EDITOR src/boundary/notifications/schema.clj
   ```

4. **Implement core logic:**
   ```zsh
   $EDITOR src/boundary/notifications/core/notifications.clj
   ```

5. **Test core logic:**
   ```zsh
   $EDITOR test/boundary/notifications/core/notifications_test.clj
   clojure -M:test --focus boundary.notifications.core.notifications-test
   ```

6. **Implement adapters:**
   ```zsh
   $EDITOR src/boundary/notifications/shell/adapters.clj
   ```

7. **Add integration tests:**
   ```zsh
   $EDITOR test/boundary/notifications/shell/adapters_test.clj
   ```

## REPL-Driven Development Workflow

### Interactive Development Session

**Typical REPL session:**
```clojure
;; 1. Load your namespace
(require '[boundary.user.core.user :as user] :reload)

;; 2. Load test data
(def test-user-data {:email "dev@example.com"
                     :name "Dev User"
                     :role :user
                     :active true
                     :tenant-id #uuid "123e4567-e89b-12d3-a456-426614174000"})

;; 3. Test individual functions
(def ports {:user-repository (constantly nil)  ; Mock for testing
           :system-services {:current-timestamp (constantly (java.time.Instant/now))}})

(user/create-new-user test-user-data ports)
;; => {:status :success, :data {...}, :effects [...]}

;; 4. Experiment with variations
(user/create-new-user (assoc test-user-data :email "invalid-email") ports)
;; => {:status :error, :errors [...]}

;; 5. Test with real system (if running)
(require '[boundary.user.shell.service :as user-service])
(user-service/register-user @ig-repl/system test-user-data)
```

### Hot Code Reloading

**Make changes and reload:**
```clojure
;; After editing files
(ig-repl/reset)  ; Reloads all changed code and restarts system

;; Or reload specific namespace
(require '[boundary.user.core.user :as user] :reload-all)
```

**If system fails to start:**
```clojure
;; Check what failed
(ig-repl/prep)  ; Shows prep errors
(ig-repl/init)  ; Shows init errors

;; Fix issues and retry
(ig-repl/reset)
```

## Database Development

### Local Database Management

**SQLite (default development database):**
- Database file: `dev-database.db`
- Automatically created on first run
- Reset by deleting the file: `rm dev-database.db`

**Inspecting database:**
```zsh
sqlite3 dev-database.db
.tables
.schema users
SELECT * FROM users LIMIT 5;
.quit
```

**Running with PostgreSQL (optional):**
1. **Set up environment:**
   ```zsh
   export POSTGRES_HOST=localhost
   export POSTGRES_PORT=5432
   export POSTGRES_DB=boundary_dev
   export POSTGRES_USER=boundary_dev
   export POSTGRES_PASSWORD=dev_password
   ```

2. **Update config to activate PostgreSQL:**
   Edit `resources/conf/dev/config.edn` to move PostgreSQL from `:inactive` to `:active`

3. **Restart system:**
   ```clojure
   (ig-repl/reset)
   ```

### Database Migrations and Schema

**Module-specific database initialization:**
- Each module manages its own schema
- SQLite adapters automatically create tables
- See `src/boundary/user/shell/adapters.clj` for examples

## Build and Packaging

### Build Artifacts

**Clean build:**
```zsh
clojure -T:build clean
```

**Create uberjar:**
```zsh
clojure -T:build uber
```

**Built artifact location:**
```
target/boundary-*-standalone.jar
```

### Development Scripts

**Common development tasks (create as needed):**

**`dev-setup.sh`:**
```bash
#!/usr/bin/env zsh
echo "Setting up Boundary development environment..."

# Install dependencies
clojure -P -M:test:clj-kondo:outdated

# Run initial tests
clojure -M:test

# Check code quality
clojure -M:clj-kondo --lint src test

echo "✅ Development environment ready!"
echo "Start REPL with: clojure -M:repl-clj"
```

**`run-tests.sh`:**
```bash
#!/usr/bin/env zsh
echo "Running Boundary test suite..."

# Run all tests
clojure -M:test

# Check for outdated dependencies
clojure -M:outdated

# Lint code
clojure -M:clj-kondo --lint src test

echo "✅ All quality checks complete!"
```

## Debugging and Troubleshooting

### Common Development Issues

**1. REPL Connection Issues:**
```clojure
;; Check if REPL tools are loaded
(resolve 'ig-repl/go)  ; Should not be nil

;; Reload REPL tools
(require '[integrant.repl :as ig-repl] :reload)
```

**2. System Won't Start:**
```clojure
;; Check configuration
(ig-repl/prep)

;; Inspect config
(require '[integrant.core :as ig])
(ig/read-config "resources/conf/dev/config.edn")

;; Check for circular dependencies
(ig-repl/reset)  ; Look for detailed error messages
```

**3. Database Connection Issues:**
- Check if SQLite file has proper permissions
- Verify environment variables for PostgreSQL
- Look for connection errors in REPL output

**4. Test Failures:**
```zsh
# Run specific failing test
clojure -M:test --focus failing-test-name

# Run with more verbose output
clojure -M:test --reporter kaocha.report/documentation
```

### Performance Monitoring

**REPL profiling:**
```clojure
;; Time function execution
(time (user/create-new-user test-data ports))

;; Monitor system resources
(.. Runtime (getRuntime) (totalMemory))
(.. Runtime (getRuntime) (freeMemory))
```

## Editor Integration

### VS Code with Calva

**Recommended extensions:**
- Calva (Clojure & ClojureScript support)
- clj-kondo (linting)

**Connect to REPL:**
1. Start REPL: `clojure -M:repl-clj`
2. In VS Code: `Ctrl+Shift+P` → "Calva: Connect to a Running REPL"
3. Select "Generic" and enter port from REPL output

### Emacs with CIDER

**Connect to REPL:**
1. Start REPL: `clojure -M:repl-clj`
2. In Emacs: `M-x cider-connect-clj`
3. Enter localhost and port from REPL output

### Vim/Neovim with Conjure

**Setup:**
1. Install Conjure plugin
2. Start REPL: `clojure -M:repl-clj`
3. In Vim: `:ConjureConnect` and enter connection details

---
*Last Updated: 2025-01-10 18:32*
*Based on: Clojure CLI, Integrant, Kaocha, clj-kondo toolchain*
