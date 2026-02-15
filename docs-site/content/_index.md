---
title: "Boundary Framework"
date: 2024-01-05
draft: false
---

# Boundary Framework

> **Production-ready Clojure framework** with enforced Functional Core / Imperative Shell architecture, independently deployable modules, and enterprise features out of the box.

## Why Boundary?

### The Challenge

Building production Clojure systems involves balancing competing concerns:

- **Architecture vs. Speed** — How do you maintain clean separation without slowing down?
- **Testability vs. Pragmatism** — How do you keep tests fast without sacrificing coverage?
- **Flexibility vs. Structure** — How do you stay agile while avoiding chaos?
- **Monolith vs. Microservices** — How do you avoid premature decisions?

### Our Approach

Boundary is opinionated where it matters (architecture) and flexible where it should be (implementation). It enforces proven patterns while providing enterprise features out of the box.

#### 1. Enforced Functional Core / Imperative Shell

Boundary separates pure business logic from I/O operations:

```clojure
;; FUNCTIONAL CORE - Pure business logic (core/user.clj)
(defn create-user-decision
  "Pure function - decides what to do based on data.
   
   No I/O, no side effects, trivially testable."
  [user-data existing-user current-time]
  (cond
    (nil? (:email user-data))
    {:action :invalid-email
     :message "Email is required"}
    
    (not (re-matches #".+@.+\..+" (:email user-data)))
    {:action :invalid-email
     :message "Email format invalid"}
    
    existing-user
    {:action :duplicate-email
     :email (:email user-data)}
    
    :else
    {:action :create
     :user {:id (random-uuid)
            :email (:email user-data)
            :name (:name user-data)
            :created-at current-time
            :active true}}))

;; Test pure logic - no mocks, no database needed!
(deftest create-user-decision-test
  (testing "creates user when valid and unique"
    (let [user-data {:email "user@example.com" :name "John"}
          existing nil
          time (Instant/now)
          result (create-user-decision user-data existing time)]
      (is (= :create (:action result)))
      (is (= "user@example.com" (get-in result [:user :email])))))
  
  (testing "rejects duplicate email"
    (let [user-data {:email "user@example.com" :name "John"}
          existing {:email "user@example.com"}
          result (create-user-decision user-data existing nil)]
      (is (= :duplicate-email (:action result))))))

;; IMPERATIVE SHELL - Orchestrates I/O (shell/service.clj)
(defn create-user-service
  "Shell orchestrates I/O around pure core logic."
  [this user-data]
  (let [existing (.find-by-email user-repository (:email user-data))
        decision (user-core/create-user-decision 
                   user-data 
                   existing 
                   (java.time.Instant/now))]
    
    (case (:action decision)
      :create
      (let [user (:user decision)]
        (.create-user user-repository user)      ; I/O
        (.send-welcome-email email-service user) ; I/O
        {:status :created :user user})
      
      :duplicate-email
      {:status :conflict :message "Email already registered"}
      
      :invalid-email
      {:status :bad-request :message (:message decision)})))
```

**Benefits:**

✅ **Test pure logic without mocks** — Core functions run in milliseconds

✅ **Business logic clear and isolated** — All rules in one place

✅ **Swap implementations freely** — Change providers without touching core

✅ **Reuse logic everywhere** — Same core powers REST, CLI, jobs, REPL

✅ **Refactor with confidence** — Pure functions can't break external systems

#### 2. Production-Ready Features Out of the Box

Stop reinventing the wheel. Boundary includes enterprise features you'd otherwise build from scratch:

| Feature | Status | What You Get |
|---------|--------|--------------|
| **Background Jobs** | ✅ Production Ready | Redis-backed queues, priority levels, retries, dead letter queue, scheduled jobs |
| **Distributed Caching** | ✅ Production Ready | Redis/in-memory backends, LRU eviction, atomic operations, cache warming |
| **Multi-Factor Auth** | ✅ Production Ready | TOTP (Google Authenticator), backup codes, QR generation, seamless login flow |
| **Full-Text Search** | ✅ Production Ready | PostgreSQL native, relevance ranking, highlighting, <100ms latency |
| **File Storage** | ✅ Production Ready | S3/local backends, image processing, signed URLs, streaming uploads |
| **API Pagination** | ✅ Production Ready | Cursor & offset strategies, RFC 5988 link headers, performance optimized |
| **Observability** | ✅ Production Ready | Structured logging, metrics (Datadog), error reporting (Sentry), pluggable adapters |
| **HTTP Interceptors** | ✅ Production Ready | Auth, rate limiting, audit logging, request/response transformation |

**Example - Background Jobs:**
```clojure
;; Define job
(defn send-welcome-email-job [job-data]
  (let [user-id (:user-id job-data)]
    (email/send-welcome user-id)
    {:status :success}))

;; Enqueue from anywhere (API, CLI, REPL)
(jobs/enqueue job-service
              {:job-type :send-welcome-email
               :data {:user-id user-id}
               :priority :high
               :max-retries 3})

;; Automatic retry with exponential backoff
;; Dead letter queue for failed jobs
;; Monitoring dashboard included
```

**Example - Multi-Factor Authentication:**
```clojure
;; Setup MFA (returns QR code URL + backup codes)
(mfa/setup-mfa user-service user-id)

;; Enable MFA after verification
(mfa/enable-mfa user-service user-id verification-code)

;; Login flow automatically requires MFA code
(auth/login user-service email password mfa-code)
```

#### 3. Module-Centric Design (Microservices Ready)

Each module is **independently deployable** with zero rewrites:

```
src/boundary/user/
├── core/user.clj        # Pure business logic
├── shell/
│   ├── service.clj      # Orchestration
│   ├── persistence.clj  # Database adapter
│   ├── http.clj         # REST API
│   ├── cli.clj          # CLI commands
│   └── web_handlers.clj # Web UI
├── ports.clj            # Protocol definitions
└── schema.clj           # Malli validation
```

**Benefits:**

✅ **Run as monolith OR microservices** - Same code, different deployment
✅ **Clear boundaries** - Modules communicate via protocols, not direct calls
✅ **Easy testing** - Mock entire modules via protocol implementations
✅ **Independent scaling** - Deploy user module separately from billing module

#### 4. Developer Productivity

**Scaffold complete modules in seconds:**
```bash
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name blog \
  --entity Post \
  --field title:string:required \
  --field body:text:required

# Generates 12 production-ready files:
# - Core logic (pure functions)
# - Shell layer (HTTP, persistence, service)
# - Schemas (Malli validation)
# - Tests (unit + integration + contract)
# - Database migration
```

**REPL-driven development:**
```clojure
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)     ; Start system
user=> (ig-repl/reset)  ; Reload + restart
user=> (user-service/create-user ...)  ; Test directly
```

**Zero-config development:**
- SQLite default (no database setup)
- In-memory cache/jobs (no Redis required)
- Comprehensive test coverage out of the box

## Is Boundary Right for You?

### Boundary Works Well For

- Production Clojure systems that need to scale
- Teams who value testability and clean architecture
- Projects that may evolve from monolith to microservices
- Developers who prefer explicit over magical

### Consider Alternatives If

- You're building a quick prototype (simpler tools may suffice)
- You're new to functional programming (learn the basics first)
- You need a simple CRUD app (Boundary may be overkill)

We believe in using the right tool for the job. Boundary is designed for production systems where architecture and scalability matter.

## Quick Start

Get started in 5 minutes:

```bash
# Clone repository
git clone https://github.com/thijs-creemers/boundary
cd boundary

# Set required environment variable
export JWT_SECRET="your-secret-minimum-32-characters-long"

# Run tests to verify setup
clojure -M:test:db/h2

# Start REPL
clojure -M:repl-clj

# In REPL:
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)  ; Start system
;; => System started on http://localhost:3000

# Test API
curl http://localhost:3000/api/v1/users
```

## Next Steps

<div class="book-columns">
<div>

### Learn Boundary

- [5-Minute Quickstart](guides/quickstart)
- [Architecture Overview](architecture/overview)
- [Functional Core / Imperative Shell](guides/functional-core-imperative-shell)

</div>
<div>

### Build Something

- [Create Your First Module](guides/create-module)
- [Add REST Endpoints](guides/add-rest-endpoint)
- [Configure Database](guides/configure-db)

</div>
<div>

### Go to Production

- [Background Jobs Guide](guides/background-jobs)
- [Add Multi-Factor Auth](guides/mfa-setup)
- [Deploy to Production](guides/deployment)

</div>
</div>

## Documentation Structure

### 📚 [Architecture](architecture/)

Comprehensive architectural documentation covering core design principles, patterns, and technical decisions.

* [Overview](architecture/overview) - High-level architecture overview
* [Functional Core / Imperative Shell](guides/functional-core-imperative-shell) - Core architectural pattern
* [Ports and Adapters](architecture/ports-and-adapters) - Hexagonal architecture implementation
* [Clean Architecture Layers](architecture/clean-architecture-layers) - Layer organization and responsibilities
* [HTTP Interceptors](architecture/interceptors) - Request/response middleware
* [Error Handling and Observability](architecture/error-handling-observability) - Error management strategy

### 🎓 [Guides](guides/)

Step-by-step tutorials and how-to guides for working with Boundary Framework.

**Getting Started:**
* [Quickstart](guides/quickstart) - Get up and running quickly
* [Create a Module](guides/create-module) - Scaffold a new domain module
* [Add REST Endpoint](guides/add-rest-endpoint) - Expose new API endpoints

**Concepts:**
* [Modules and Ownership](guides/modules-and-ownership) - Module organization principles
* [Ports and Adapters](guides/ports-and-adapters) - Understanding the pattern
* [Validation System](guides/validation-system) - Request validation approach

**Features:**
* [Background Jobs](guides/background-jobs) - Async work processing
* [Distributed Caching](guides/caching) - Redis/in-memory caching
* [Multi-Factor Authentication](guides/mfa-setup) - TOTP-based MFA
* [Full-Text Search](guides/search) - PostgreSQL native search
* [File Storage](guides/file-storage) - S3/local storage
* [API Pagination](guides/pagination) - Cursor/offset pagination

**Operations:**
* [Configure Database](guides/configure-db) - Database setup
* [Run Tests and Watch](guides/run-tests-and-watch) - Testing workflow
* [Troubleshooting Guide](guides/troubleshooting) - Common issues

### 📖 [Reference](reference/)

Technical reference documentation for APIs, commands, and configuration.

* [Product Requirements Document](reference/boundary-prd) - Complete PRD
* [Commands](reference/commands) - CLI command reference
* [Configuration](reference/configuration) - Configuration options
* [Error Codes](reference/error-codes) - System error codes

### 📋 [ADR (Architecture Decision Records)](adr/)

Records of significant architectural decisions.

* [ADR-006: Web UI Architecture (HTMX + Hiccup)](adr/ADR-006-web-ui-architecture-htmx-hiccup)
* [ADR-008: Normalized Routing Abstraction](adr/ADR-008-normalized-routing-abstraction)
* [ADR-010: HTTP Interceptor Architecture](adr/ADR-010-http-interceptor-architecture)

## Contributing

Boundary Framework is open source and welcomes contributions!

* **GitHub Repository:** [github.com/thijs-creemers/boundary](https://github.com/thijs-creemers/boundary)
* **Documentation Repository:** [github.com/thijs-creemers/boundary-docs](https://github.com/thijs-creemers/boundary-docs)
* **Issue Tracker:** [GitHub Issues](https://github.com/thijs-creemers/boundary/issues)

## License

Boundary Framework is released under the MIT License.
