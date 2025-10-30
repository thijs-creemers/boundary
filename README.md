# Boundary Framework

A module-centric Clojure framework implementing the Functional Core / Imperative Shell architectural paradigm.

## Quick Start

**→ [See the complete Developer Guide (warp.md)](./warp.md) ←**

For a comprehensive guide including setup, architecture overview, development workflow, and common tasks.

### Minimal Setup

```zsh
# Prerequisites: JDK and Clojure CLI
brew install openjdk clojure/tools/clojure  # macOS

# Clone and verify
git clone <repo-url> boundary
cd boundary
clojure -M:test                           # Run tests (auto-loads required drivers)
clojure -M:repl-clj                       # Start REPL
clojure -X:mcp # runs clojure-mcp server as configured in ~/.clojure/deps.edn

opencode setup, Auto starts mcp but startup fails when the repl is not running.
``` json
{"mcp":
    "clojure-mcp": {
      "type": "local",
      "command": [
        "clojure",
        "-X:mcp"
      ]
    }
}
```

## Architecture

Boundary implements a **clean architecture** pattern with proper separation of concerns. Each domain module (`user`, `billing`, `workflow`) follows a layered structure:

### Core Principles
- **Domain Layer**: Pure business entities and validation rules (Malli schemas)
- **Ports Layer**: Repository interfaces and contracts
- **Application Layer**: Database-agnostic business services using dependency injection
- **Infrastructure Layer**: Database adapters, external APIs, and I/O implementations
- **Multi-Interface Support**: Consistent behavior across REST, CLI, and Web

### User Module Example
```
src/boundary/user/
├── schema.clj              # Domain entities (Malli schemas)
├── ports.clj               # Repository interfaces
├── shell/
│   └── service.clj         # Database-agnostic business services
└── infrastructure/
    └── database.clj        # Database-specific implementations
```

**Key Benefits:**
- Business logic completely separated from infrastructure
- Easy to test with mocked dependencies
- Database-agnostic services that work with any storage implementation
- Clear dependency flow: Infrastructure → Ports ← Services

See [Architecture Documentation](docs/architecture/) for detailed technical specifications.

## Documentation

### Development
- **[Developer Guide (warp.md)](./warp.md)** - Complete development reference
- **[PRD Summary](docs/PRD-IMPROVEMENT-SUMMARY.adoc)** - Project requirements and improvements
- **[Full PRD](docs/boundary.prd.adoc)** - Comprehensive product requirements

### Architecture
- **[Architecture Overview](docs/architecture/overview.adoc)** - High-level architectural decisions
- **[Component Architecture](docs/architecture/components.adoc)** - Detailed component interactions
- **[Data Flow](docs/architecture/data-flow.adoc)** - Request processing patterns
- **[Ports and Adapters](docs/architecture/ports-and-adapters.adoc)** - Hexagonal architecture guide
- **[User Module Architecture](docs/user-module-architecture.md)** - Clean architecture implementation example

### Infrastructure & Migration
- **[Migration Guide](docs/migration-guide.md)** - Step-by-step migration to new infrastructure
- **[Infrastructure Examples](examples/user_infrastructure_example.clj)** - Working code examples
- **[Refactoring Summary](INFRASTRUCTURE-REFACTOR-SUMMARY.md)** - Complete overview of changes
- **[Dynamic Driver Loading](docs/DYNAMIC_DRIVER_LOADING.md)** - Automatic JDBC driver loading system

### Build Documentation

```bash
# Build individual documents
asciidoctor -D build/docs docs/architecture/components.adoc
asciidoctor -D build/docs docs/architecture/data-flow.adoc

# Build all architecture docs
``` bash
```
cd docs/architecture && for doc in *.adoc; do asciidoctor -D ../../build/docs "$doc"; done
cd -
```
```
```
