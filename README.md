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
clojure -M:test                            # Run tests
clojure -M:repl-clj                        # Start REPL
clojure -X:mcp # runs clojure-mcp server as configured in ~/.clojure/deps.edn 

opencode setup, startup fails when the repl is not running.
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

Boundary implements a **module-centric architecture** where each domain module (`user`, `billing`, `workflow`) owns its complete functionality stack:

- **Functional Core**: Pure business logic with no side effects
- **Imperative Shell**: All I/O, validation, and infrastructure concerns  
- **Ports and Adapters**: Hexagonal architecture for dependency inversion
- **Multi-Interface Support**: Consistent behavior across REST, CLI, and Web

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

### Build Documentation

```bash
# Build individual documents
asciidoctor -D build/docs docs/architecture/components.adoc
asciidoctor -D build/docs docs/architecture/data-flow.adoc

# Build all architecture docs
cd docs/architecture && for doc in *.adoc; do asciidoctor -D ../../build/docs "$doc"; done
cd -
```
