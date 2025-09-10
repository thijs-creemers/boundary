# Key Technologies

*Practical guide to Elara's technology stack with usage examples*

## Build System

### Clojure CLI (Primary)
- **Library**: Using `deps.edn` with modern Clojure CLI tooling
- **Version**: Compatible with Clojure 1.12.1
- **Why**: Modern, flexible dependency management with excellent REPL integration
- **Usage**: 
  ```zsh
  clojure -M:repl-clj    # Start development REPL
  clojure -M:test        # Run tests
  clojure -T:build uber  # Build uberjar
  ```

**No Leiningen**: Project uses pure Clojure CLI approach (no `project.clj`)

## Core Runtime

### Clojure 1.12.1
- **Why**: Latest stable Clojure with performance improvements and new features
- **Key Features Used**:
  - Namespaced maps for configuration
  - Improved error messages
  - Performance optimizations for functional programming patterns

### Java Dependencies
- **JVM Requirement**: Compatible with Java 11+
- **Thread Model**: Leverages JVM thread pools and concurrent data structures
- **Memory Management**: JVM garbage collection optimized for functional programming

## System Lifecycle & Configuration

### Integrant 0.13.1
- **Purpose**: Component lifecycle management and dependency injection
- **Why Chosen**: Configuration-driven system composition, excellent REPL workflow
- **Key Features**:
  - Declarative system configuration
  - Hot reloading in development
  - Clean shutdown and startup
- **Usage**:
  ```clojure
  (require '[integrant.repl :as ig-repl])
  (ig-repl/go)     ; Start system
  (ig-repl/reset)  ; Reload and restart
  (ig-repl/halt)   ; Stop system
  ```
- **Integrant REPL 0.4.0**: Development-time utilities for system management

### Aero 1.1.6
- **Purpose**: Data-driven configuration with profile support
- **Why Chosen**: Sophisticated profile-based configuration, environment variable integration
- **Key Features**:
  - Profile-based configuration (`#profile`)
  - Environment variable substitution (`#env`)
  - Configuration validation
- **Usage**:
  ```clojure
  {:database {:host #profile {:development "localhost"
                             :production #env "DB_HOST"}}}
  ```

### Environ 1.2.0
- **Purpose**: Environment variable access
- **Why Chosen**: Simple, reliable environment variable handling
- **Integration**: Works with Aero for environment-specific configuration

### dotenv 0.2.5
- **Purpose**: Load environment variables from `.env` files
- **Why Chosen**: Convenient development environment setup
- **Usage**: Automatically loads `.env` files in development

## Data Persistence

### Database Connectivity
- **next.jdbc 1.3.1048**: Modern JDBC wrapper for Clojure
  - **Why**: Performance, simplicity, and excellent HikariCP integration
  - **Features**: Connection pooling, transaction management, result set processing
- **HoneySQL 2.7.1340**: Clojure DSL for SQL generation
  - **Why**: Type-safe SQL generation, composable queries
  - **Usage**:
    ```clojure
    (sql/format {:select [:*] :from [:users] :where [:= :active true]})
    ;; => ["SELECT * FROM users WHERE active = ?" true]
    ```

### Database Drivers
- **SQLite JDBC**: Primary development database
  - **Version**: `*******` (latest)
  - **Why**: Zero-configuration, file-based database for development
  - **Usage**: Automatic schema creation, no setup required
- **PostgreSQL 42.7.7**: Production database support
  - **Why**: Robust, feature-rich RDBMS for production deployments
  - **Features**: JSONB support, advanced indexing, replication
- **HikariCP 7.0.0**: High-performance JDBC connection pool
  - **Why**: Fastest connection pool, excellent monitoring
  - **Features**: Connection leak detection, metrics, health checks

## Data Validation & Transformation

### Malli 0.19.1
- **Purpose**: Data-driven schema definition and validation
- **Why Chosen**: Performance, composability, and transformation capabilities
- **Key Features**:
  - Schema-first development
  - Data transformation pipelines
  - Error humanization
  - Code generation
- **Usage**:
  ```clojure
  (def User
    [:map
     [:id :uuid]
     [:email [:string {:min 5 :max 255}]]
     [:role [:enum :admin :user :viewer]]])
  
  (m/validate User user-data)
  (m/transform User input-data transformers)
  ```
- **Integration**: Used across all modules for consistent validation

## HTTP & Web

### Ring & Reitit (Mentioned in Architecture)
- **Ring**: HTTP abstraction layer (standard Clojure web stack)
- **Reitit**: Data-driven routing library
- **Why**: Standard, mature web stack with excellent performance
- **Integration**: Used in module HTTP interfaces

## JSON Processing

### Cheshire 6.0.0
- **Purpose**: JSON parsing and generation
- **Why Chosen**: Fast, mature JSON library for Clojure
- **Features**: 
  - Custom encoders/decoders
  - Streaming support
  - Date handling
- **Usage**:
  ```clojure
  (cheshire/generate-string {:user "john" :active true})
  (cheshire/parse-string json-string true)  ; keywordize keys
  ```

## Logging & Observability

### TeleMere 1.0.1
- **Purpose**: Modern structured logging and telemetry
- **Why Chosen**: High-performance, structured logging with telemetry features
- **Features**:
  - Structured event logging
  - Metrics and tracing support
  - Low overhead
- **Integration**: Primary logging system across all modules

### Clojure Tools Logging 1.3.0
- **Purpose**: Logging abstraction layer
- **Why**: Standard logging interface, compatible with multiple backends
- **Usage**: Provides logging abstraction that TeleMere implements

## Development Tools

### REPL & Editor Integration

**nREPL 1.3.1** (`:repl-clj` alias):
- **Purpose**: Network REPL server
- **Why**: Standard REPL protocol for editor integration
- **Usage**: Enables IDE/editor connection to running REPL

**CIDER nREPL 0.57.0**:
- **Purpose**: Enhanced REPL middleware for Emacs CIDER (and other editors)
- **Features**: Code completion, debugging, documentation, refactoring
- **Compatibility**: Works with VS Code Calva, Vim Conjure, and other editors

**Piggieback 0.6.0** (`:repl-cljs` alias):
- **Purpose**: ClojureScript REPL integration
- **Usage**: Enables ClojureScript development when needed

### Code Quality

**clj-kondo 2025.07.28**:
- **Purpose**: Static analysis and linting
- **Why Chosen**: Fast, comprehensive linting with excellent IDE integration
- **Features**: 
  - Syntax checking
  - Unused variable detection
  - Type hints and warnings
  - Configuration-based rules
- **Usage**: `clojure -M:clj-kondo --lint src test`
- **Integration**: Built into development workflow

### Testing

**Kaocha 1.91.1392**:
- **Purpose**: Modern test runner with advanced features
- **Why Chosen**: Flexible, powerful test execution with watch mode
- **Features**:
  - Multiple test types (unit, integration)
  - Watch mode for continuous testing
  - Configurable reporting
  - Plugin ecosystem
- **Usage**: 
  ```zsh
  clojure -M:test              # Run all tests
  clojure -M:test --watch      # Watch mode
  clojure -M:test --focus :user # Focus on specific tests
  ```

### Build Tools

**tools.build** (Git dependency):
- **Purpose**: Modern Clojure build tool
- **Version**: `v0.10.9` (Git tag: `e405aac4`)
- **Why Chosen**: Official Clojure build tool, replaces Leiningen for build tasks
- **Features**: Uberjar creation, dependency resolution, artifact building
- **Usage**: `clojure -T:build uber`

### Dependency Management

**Depot 2.4.1**:
- **Purpose**: Dependency version checking
- **Why**: Keep dependencies up-to-date
- **Usage**: `clojure -M:outdated`
- **Features**: Lists outdated dependencies with available updates

## Technology Stack Rationale

### Why This Stack?

**Functional Programming First**:
- Clojure's immutable data structures align perfectly with FC/IS architecture
- Pure functions in the core are naturally testable
- REPL-driven development enables rapid iteration

**Modern Tooling**:
- Clojure CLI provides flexible, modern dependency management
- Integrant enables sophisticated system lifecycle management
- Malli provides data-first development patterns

**Production Ready**:
- Battle-tested libraries (Ring, next.jdbc, HikariCP)
- Excellent observability with structured logging
- Robust error handling and monitoring capabilities

**Developer Experience**:
- Outstanding REPL integration across editors
- Live system reloading with Integrant
- Comprehensive code quality tools

### Performance Considerations

**Database Layer**:
- HikariCP: Fastest JVM connection pool
- next.jdbc: Minimal overhead over raw JDBC
- HoneySQL: Compile-time SQL generation

**Application Layer**:
- Malli: High-performance schema validation
- TeleMere: Low-overhead structured logging
- Clojure: JVM performance with functional programming benefits

**Development Efficiency**:
- Hot reloading: Instant feedback during development
- REPL integration: Live system introspection
- Static analysis: Catch errors before runtime

## External Documentation Links

### Core Libraries
- [Clojure CLI Guide](https://clojure.org/guides/deps_and_cli)
- [Integrant Documentation](https://github.com/weavejester/integrant)
- [Aero Configuration](https://github.com/juxt/aero)
- [Malli Data Validation](https://github.com/metosin/malli)

### Database & Persistence
- [next.jdbc Guide](https://github.com/seancorfield/next-jdbc)
- [HoneySQL Documentation](https://github.com/seancorfield/honeysql)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP)

### Development Tools
- [Kaocha Test Runner](https://github.com/lambdaisland/kaocha)
- [clj-kondo Linting](https://github.com/clj-kondo/clj-kondo)
- [CIDER Setup Guide](https://docs.cider.mx/)

### Build & Deployment
- [tools.build Guide](https://github.com/clojure/tools.build)
- [Clojure CLI Reference](https://clojure.org/reference/deps_and_cli)

## Library Selection Criteria

When choosing libraries for Elara, we prioritize:

1. **Maturity**: Established libraries with active maintenance
2. **Performance**: Libraries optimized for production workloads
3. **Composability**: Tools that work well together
4. **Documentation**: Comprehensive guides and examples
5. **Community**: Active community support and ecosystem
6. **Architectural Fit**: Alignment with FC/IS patterns

## Upgrading Strategy

### Dependency Updates
- **Regular**: Use `clojure -M:outdated` to check for updates
- **Testing**: Run full test suite before upgrading major versions
- **Staging**: Test upgrades in staging environment first
- **Documentation**: Update version numbers in this document

### Migration Planning
- **Breaking Changes**: Review changelogs for breaking changes
- **Compatibility**: Ensure library compatibility across the stack
- **Performance**: Benchmark critical paths after upgrades
- **Rollback Plan**: Maintain ability to rollback if issues arise

---
*Last Updated: 2025-01-10 18:32*
*Based on: deps.edn analysis and toolchain validation*
