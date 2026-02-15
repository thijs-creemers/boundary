# Boundary Framework

## For Developers

**Boundary** brings Django's productivity and Rails' conventions to Clojure—with functional programming rigor. It's a batteries-included web framework that enforces the Functional Core / Imperative Shell (FC/IS) pattern: pure business logic in `core/`, side effects in `shell/`, and clean interfaces through `ports.clj` protocols.

You get 13 independently-publishable libraries via Clojars—use just `boundary-core` for validation utilities, or go full-stack with `boundary-user` for JWT + MFA auth, `boundary-admin` for auto-generated CRUD UIs (think Django Admin for Clojure), `boundary-storage` for S3 uploads, `boundary-email` for production-ready SMTP, `boundary-realtime` for WebSocket support, and `boundary-tenant` for multi-tenancy. Every module follows the same FC/IS structure, making any Boundary codebase instantly familiar.

Ship faster: The scaffolder generates production-ready modules (entity + routes + tests) in seconds. The admin UI auto-generates CRUD interfaces from your database schema—no manual forms. Built-in observability (Datadog/Sentry), API pagination (RFC 5988), and declarative interceptors mean you write business logic, not plumbing.

**Zero lock-in**: Each library is a standard deps.edn dependency. Swap what doesn't fit.

---

## For Decision Makers

**Boundary** accelerates software delivery by eliminating repetitive infrastructure work. Teams ship features 3x faster with built-in authentication, admin interfaces, and background processing—no custom development needed. The enforced architecture pattern ensures codebases stay maintainable as teams grow, reducing onboarding time from weeks to days.

Lower costs through reduced defects: comprehensive testing and proven design patterns catch bugs early. Scale confidently: modular design lets you add capacity without rewrites. From MVP to enterprise, one framework adapts to your growth—no platform migrations, no architecture rewrites, no vendor lock-in.

**Built for teams who value speed and reliability equally.**

## Documentation

| Resource | Description |
|----------|-------------|
| **[Documentation](https://github.com/thijs-creemers/boundary-docs)** | Complete documentation: architecture, tutorials, API reference, ADRs |
| **[AGENTS.md](./AGENTS.md)** | Developer guide: commands, patterns, conventions, troubleshooting |
| **[PUBLISHING_GUIDE.md](docs/PUBLISHING_GUIDE.md)** | Publishing libraries to Clojars |

## Library Architecture

Boundary is organized as a **monorepo** with 13 independently publishable libraries:

| Library | Description |
|---------|-------------|
| **[core](libs/core/)** | Foundation: validation, utilities, interceptors |
| **[observability](libs/observability/)** | Logging, metrics, error reporting |
| **[platform](libs/platform/)** | HTTP, database, CLI infrastructure |
| **[user](libs/user/)** | Authentication, authorization, MFA |
| **[admin](libs/admin/)** | Auto-CRUD admin interface |
| **[storage](libs/storage/)** | File storage (local & S3) |
| **[scaffolder](libs/scaffolder/)** | Module code generator |
| **[cache](libs/cache/)** | Distributed caching (Redis/in-memory) |
| **[jobs](libs/jobs/)** | Background job processing |
| **[email](libs/email/)** | Production-ready email sending (SMTP, async, jobs integration) |
| **[realtime](libs/realtime/)** | WebSocket/SSE for real-time features (Phoenix Channels for Clojure) |
| **[tenant](libs/tenant/)** | Multi-tenancy with PostgreSQL schema-per-tenant isolation |
| **[external](libs/external/)** | External service adapters (In Development) |

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐    ┌─────────────┐
│  scaffolder │     │   storage   │     │    cache    │◄───│   tenant    │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘    └──────┬──────┘
       │                   │                   │                   │
       ▼                   ▼                   ▼                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│    jobs     │◄─── │    email    │     │  external   │     │    core     │◄────┐
└──────┬──────┘     └─────────────┘     └──────┬──────┘     └─────────────┘     │
       │                                       │                  ▲             │
       ▼                                       ▼                  │             │
       │                               ┌─────────────┐     ┌──────┴──────┐      │
       └──────────────────────────────►│  platform   │────►│    user     │◄────┐│
                                       └──────┬──────┘     └──────┬──────┘     ││
                                              │                   │            ││
                                              ▼                   │            ││
                                      ┌─────────────┐             │            ││
                                      │observability│◄────────────┘            ││
                                      └─────────────┘                          ││
                                              │                                ││
                                              ▼                                ││
                                       ┌─────────────┐                         ││
                                       │    admin    │─────────────────────────┘│
                                       └─────────────┘                          │
                                              │                                 │
                                              ▼                                 │
                                       ┌─────────────┐                          │
                                       │  realtime   │──────────────────────────┘
                                       └─────────────┘
```

## Quick Start

### Try Boundary (Recommended for New Users)

Get started in **3 commands** using the [**boundary-starter**](https://github.com/thijs-creemers/boundary-starter) template:

```bash
git clone https://github.com/thijs-creemers/boundary-starter
cd boundary-starter

# Required environment variables for JWT auth / app environment:
# Customize these values as needed for your setup.
export JWT_SECRET="change-me-dev-secret-min-32-chars"
export BND_ENV="development"

clojure -M:repl-clj
```

In the REPL:
```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
;; Visit http://localhost:3000
```

**What you get:**
- ✅ SQLite database (zero-config)
- ✅ HTTP server on port 3000
- ✅ Complete Integrant system
- ✅ REPL-driven development
- ✅ Production-ready Dockerfile

**Next steps:** See the [**full documentation**](https://github.com/thijs-creemers/boundary-docs) or explore the [**5-minute quickstart**](./docs/QUICKSTART.md).

---

### Develop Boundary Framework (For Contributors)

**Prerequisites: JDK 11+ and Clojure CLI**

#### macOS
```bash
brew install openjdk clojure/tools/clojure
```

#### Linux (Debian/Ubuntu)
```bash
# Install OpenJDK
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# Install Clojure CLI
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

#### Linux (RHEL/Fedora/CentOS)
```bash
# Install OpenJDK
sudo dnf install -y java-17-openjdk java-17-openjdk-devel

# Install Clojure CLI
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

#### Windows
```powershell
# Install using Scoop (recommended)
scoop install git
scoop bucket add java
scoop install openjdk17
scoop bucket add scoop-clojure
scoop install clojure

# Alternative: Install using Chocolatey
choco install openjdk17
choco install clojure
```

#### Clone and Verify
```bash
git clone <repo-url> boundary
cd boundary
clojure -M:test:db/h2                        # Run tests
clojure -M:repl-clj                          # Start REPL
```

### Using Individual Libraries

```clojure
;; Use just core for validation
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0-alpha"}}}

;; Use platform for full web application support
{:deps {io.github.thijs-creemers/boundary-platform {:mvn/version "1.0.0-alpha"}}}

;; Use the full stack
{:deps {io.github.thijs-creemers/boundary-user {:mvn/version "1.0.0-alpha"}
        io.github.thijs-creemers/boundary-admin {:mvn/version "1.0.0-alpha"}}}
```

## Essential Commands

See **[AGENTS.md](./AGENTS.md)** for the complete command reference.

```bash
# Testing
clojure -M:test:db/h2                        # All tests
clojure -M:test:db/h2 :core                  # Core library
clojure -M:test:db/h2 :user                  # User library

# Code quality
clojure -M:clj-kondo --lint libs/*/src       # Lint all code

# REPL
clojure -M:repl-clj                          # Start nREPL on port 7888
```

## Architecture

Boundary implements the **Functional Core / Imperative Shell** paradigm:

- **Functional Core** (`core/`): Pure business logic, no side effects
- **Imperative Shell** (`shell/`): I/O, validation, adapters
- **Ports** (`ports.clj`): Protocol definitions for dependency injection

See the **[Documentation](https://github.com/thijs-creemers/boundary-docs)** for detailed architecture guides.

## License

Copyright 2024-2025 Thijs Creemers. All rights reserved.
