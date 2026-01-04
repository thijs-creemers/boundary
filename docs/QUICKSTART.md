# Boundary Framework - 5-Minute Quickstart

Get your first Boundary API endpoint running in **5 minutes**.

---

## Prerequisites

- **Java 11+** (OpenJDK or Amazon Corretto)
- **Clojure CLI** (`brew install clojure/tools/clojure` or [install guide](https://clojure.org/guides/install_clojure))
- **Database** (SQLite, PostgreSQL, MySQL, or H2)
- **Your favorite editor** (we'll help you set it up later)

**Quick check:**
```bash
java -version   # Should show 11 or higher
clojure -version
```

---

## Step 1: Clone & Setup (1 minute)

```bash
# Clone the repository
git clone https://github.com/yourusername/boundary.git
cd boundary

# Install dependencies (downloads once, cached after)
clojure -P -M:dev
```

---

## Step 2: Configure Database (1 minute)

**Option A: SQLite (Easiest)**
```bash
# Already configured in conf/dev/config.edn
# No setup needed!
```

**Option B: PostgreSQL (Recommended for production)**
```bash
# 1. Create database
createdb boundary_dev

# 2. Set connection string
export DATABASE_URL="postgresql://localhost:5432/boundary_dev"

# 3. Update conf/dev/config.edn
# (Or just use the environment variable - it works!)
```

**Option C: H2 (In-memory)**
```clojure
;; Edit conf/dev/config.edn
:active {:h2 {:memory true}}
```

---

## Step 3: Run Migrations (30 seconds)

```bash
# Initialize migration system
clojure -M:migrate init

# Run migrations
clojure -M:migrate migrate

# Verify
clojure -M:migrate status
# Should show: "Applied migrations: 5"
```

---

## Step 4: Start the Server (30 seconds)

```bash
# Start HTTP server on port 3000
clojure -M:dev -m boundary.cli server start

# Or use the REPL (recommended for development)
clojure -M:repl-clj
```

```clojure
;; In REPL:
(require '[integrant.repl :as ig-repl])
(require '[boundary.system])

(ig-repl/set-prep! #(boundary.system/system-config))
(ig-repl/go)
;; Server started at http://localhost:3000
```

**Expected output:**
```
INFO  boundary.server - Starting HTTP server on port 3000
INFO  boundary.server - Swagger UI available at http://localhost:3000/api-docs/
INFO  boundary.server - Server started successfully
```

---

## Step 5: Test Your API (1 minute)

**Check health endpoint:**
```bash
curl http://localhost:3000/health
```

**Expected response:**
```json
{
  "status": "healthy",
  "service": "boundary",
  "version": "0.1.0",
  "timestamp": "2026-01-03T14:30:00Z"
}
```

**View API documentation:**
```bash
# Open in browser
open http://localhost:3000/api-docs/
```

You'll see an interactive Swagger UI with all available endpoints!

**Create your first user:**
```bash
curl -X POST http://localhost:3000/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "email": "developer@example.com",
    "name": "Developer",
    "password": "securepass123",
    "role": "admin"
  }'
```

**Expected response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "developer@example.com",
  "name": "Developer",
  "role": "admin",
  "active": true,
  "createdAt": "2026-01-03T14:30:00Z"
}
```

**Login to get a JWT token:**
```bash
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "developer@example.com",
    "password": "securepass123"
  }'
```

**Expected response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "userId": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## Step 6: Create Your First Module (2 minutes)

Let's create a "tasks" module using the scaffolder:

```bash
clojure -M:dev -m boundary.scaffolder.core \
  --name tasks \
  --fields title:string,description:string,completed:boolean \
  --http true \
  --web true
```

**What this creates:**
```
src/boundary/tasks/
  ├── core/
  │   ├── task.clj           # Pure business logic
  │   └── validation.clj     # Validation rules
  ├── ports.clj              # Port definitions
  ├── schema.clj             # Data schemas
  └── shell/
      ├── http.clj           # HTTP handlers & routes
      ├── persistence.clj    # Database adapter
      └── service.clj        # Shell orchestration

Generated files: 12
Generated tests: 473
Lines of code: ~2,500
```

**Wire up the module:**
```clojure
;; In src/boundary/system.clj, add to routes:
(require '[boundary.tasks.shell.http :as tasks-http])

(defn all-routes [config]
  (concat
    (user/user-routes config)
    (tasks-http/task-routes config)))  ;; ADD THIS
```

**Restart server and test:**
```bash
# In REPL
(ig-repl/halt)
(ig-repl/go)

# Or restart the process
```

**Create a task:**
```bash
curl -X POST http://localhost:3000/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{
    "title": "Learn Boundary Framework",
    "description": "Complete the quickstart guide",
    "completed": false
  }'
```

---

## 🎉 Congratulations!

In 5 minutes you've:

✅ Set up a production-ready Clojure web framework
✅ Connected to a database with automated migrations
✅ Started an HTTP server with Swagger UI
✅ Created and authenticated a user
✅ Generated a complete CRUD module with tests
✅ Made API calls to your new endpoints

---

## Next Steps

### 1. Explore the Interactive API Docs
```bash
open http://localhost:3000/api-docs/
```

- Try out all endpoints in the browser
- See request/response schemas
- Authenticate and make real API calls

### 2. Run the Tests
```bash
# Run all tests
clojure -M:test

# Run specific module tests
clojure -M:test --focus boundary.tasks
```

### 3. Set Up Your IDE

**VSCode + Calva:**
```bash
# Install Calva extension
code --install-extension betterthantomorrow.calva

# Open project
code .

# Connect to REPL: Ctrl+Alt+C Ctrl+Alt+J
# Choose "deps.edn" and "dev" profile
```

**IntelliJ + Cursive:**
- Install Cursive plugin
- Import as Clojure project
- Run REPL configuration with `:dev` alias

**Emacs + CIDER:**
```elisp
;; M-x cider-jack-in
;; Choose "clojure-cli"
;; Add alias: ":dev"
```

See [IDE Setup Guide](IDE_SETUP.md) for detailed instructions.

### 4. Read the Documentation

- **[Architecture Guide](ARCHITECTURE.md)** - Understand FC/IS pattern
- **[Module Design](MODULE_DESIGN.md)** - Learn module structure
- **[Operations Guide](OPERATIONS.md)** - Production deployment
- **[Build Guide](../BUILD.md)** - Development workflow

### 5. Try the Examples

```bash
# Explore example applications
cd examples/

# Simple REST API
cd todo-api/
clojure -M:dev

# Full-stack web app
cd ../blog/
clojure -M:dev
```

---

## Common Issues & Solutions

### Port 3000 already in use
```bash
# Find and kill process using port 3000
lsof -ti:3000 | xargs kill -9

# Or use a different port
export PORT=8080
```

### Database connection errors
```bash
# Check database is running
pg_isready  # PostgreSQL

# Verify connection string
echo $DATABASE_URL

# Check conf/dev/config.edn configuration
```

### REPL won't start
```bash
# Clear Clojure cache
rm -rf .cpcache/

# Re-download dependencies
clojure -P -M:dev

# Try again
clojure -M:repl-clj
```

### Tests failing
```bash
# Run with verbose output
clojure -M:test --reporter documentation

# Check test database is clean
clojure -M:migrate reset  # WARNING: Deletes all data
clojure -M:migrate migrate
```

---

## Development Workflow Tips

### REPL-Driven Development
```clojure
;; Hot reload on file save (in REPL)
(require '[clojure.tools.namespace.repl :refer [refresh]])

;; Make code changes, then:
(refresh)

;; Restart system with new code
(ig-repl/halt)
(ig-repl/go)
```

### Database Migrations
```bash
# Create new migration
clojure -M:migrate create add-task-priority

# Edit migrations/TIMESTAMP-add-task-priority.up.sql
# Edit migrations/TIMESTAMP-add-task-priority.down.sql

# Apply migration
clojure -M:migrate migrate

# Rollback if needed
clojure -M:migrate rollback
```

### Adding New Endpoints
```clojure
;; In src/boundary/tasks/shell/http.clj

;; Add to normalized-api-routes:
{:path "/tasks/:id/complete"
 :methods {:post {:handler (complete-task-handler task-service)
                  :summary "Mark task as complete"
                  :tags ["tasks"]
                  :parameters {:path [:map [:id :string]]}}}}
```

### Testing Your Code
```clojure
;; In test/boundary/tasks/core/task_test.clj
(deftest complete-task-test
  (testing "completing a task"
    (let [task {:id "123" :title "Test" :completed false}
          result (task/complete task)]
      (is (= true (:completed result))))))
```

---

## Getting Help

- **Documentation:** `docs/` directory
- **Examples:** `examples/` directory
- **Issues:** [GitHub Issues](https://github.com/yourusername/boundary/issues)
- **Discussions:** [GitHub Discussions](https://github.com/yourusername/boundary/discussions)
- **Slack:** `#boundary-framework` channel

---

## What Makes Boundary Different?

### Functional Core / Imperative Shell (FC/IS)
- **Pure business logic** in `core/` - no side effects, easy to test
- **Side effects** in `shell/` - I/O, database, HTTP
- **Clean boundaries** - ports define contracts

### Module-Centric Architecture
- Each module owns all its layers (core, ports, shell)
- Modules are **independently deployable**
- True microservices-ready design

### Production Scaffolder
- Generates **12 files** with **473 tests** in seconds
- Zero lint errors, production-ready code
- **80% reduction** in boilerplate time

### Protocol-Based Extensibility
- Swap implementations without changing core
- Easy mocking for tests
- Database agnostic (SQLite, PostgreSQL, MySQL, H2)

---

**Ready to build production applications?** 🚀

**Next:** [Full Tutorial](TUTORIAL.md) - Build a complete task management API from scratch
