# Boundary Framework

A module-centric Clojure framework implementing the Functional Core / Imperative Shell architectural paradigm.

## Documentation

| Resource | Description |
|----------|-------------|
| **[Documentation](https://github.com/thijs-creemers/boundary-docs)** | Complete documentation: architecture, tutorials, API reference, ADRs |
| **[AGENTS.md](./AGENTS.md)** | Developer guide: commands, patterns, conventions, troubleshooting |
| **[PUBLISHING_GUIDE.md](docs/PUBLISHING_GUIDE.md)** | Publishing libraries to Clojars |

## Library Architecture

Boundary is organized as a **monorepo** with 10 independently publishable libraries:

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
| **[external](libs/external/)** | External service adapters (In Development) |

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  scaffolder │     │   storage   │     │    cache    │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│    jobs     │     │  external   │     │    core     │◄────┐
└──────┬──────┘     └──────┬──────┘     └─────────────┘     │
       │                   │                   ▲             │
       ▼                   ▼                   │             │
       │           ┌─────────────┐     ┌──────┴──────┐      │
       └──────────►│  platform   │────►│    user     │      │
                   └──────┬──────┘     └──────┬──────┘      │
                          │                   │             │
                          ▼                   │             │
                  ┌─────────────┐             │             │
                  │observability│◄────────────┘             │
                  └─────────────┘                           │
                          │                                 │
                          ▼                                 │
                   ┌─────────────┐                          │
                   │    admin    │──────────────────────────┘
                   └─────────────┘
```

## Quick Start

```bash
# Prerequisites: JDK and Clojure CLI
brew install openjdk clojure/tools/clojure  # macOS

# Clone and verify
git clone <repo-url> boundary
cd boundary
clojure -M:test                              # Run tests
clojure -M:repl-clj                          # Start REPL
```

### Using Individual Libraries

```clojure
;; Use just core for validation
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "0.1.0"}}}

;; Use platform for full web application support
{:deps {io.github.thijs-creemers/boundary-platform {:mvn/version "0.1.0"}}}

;; Use the full stack
{:deps {io.github.thijs-creemers/boundary-user {:mvn/version "0.1.0"}
        io.github.thijs-creemers/boundary-admin {:mvn/version "0.1.0"}}}
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
