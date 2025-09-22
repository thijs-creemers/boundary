# Core PRD Insights for Project Overview

*Extracted from boundary.prd.adoc - Key insights for warp.md project overview section*

## Project Vision & Goals

### Primary Purpose
**Boundary** is a **module-centric software framework** that implements the "Functional Core / Imperative Shell" architectural paradigm with complete domain ownership. This approach creates highly composable, testable, and maintainable systems where each domain module owns its complete functionality stack.

### Strategic Vision
Boundary is designed as both a framework for building applications and a **foundation for creating reusable development toolchains**. The framework supports multiple interaction modes:
- **REST API** for external system integration and programmatic access
- **CLI** for operational tasks, automation, and administrative functions  
- **Web Frontend** for human user interaction and visual management

### Framework Evolution Strategy
The module-centric architecture enables Boundary to evolve into a comprehensive development platform:
1. **Library Extraction**: Core infrastructure can be extracted into reusable libraries
2. **Template Generation**: Automated module generation with consistent patterns
3. **Domain Frameworks**: Specialized frameworks for different industries (fintech, healthcare, e-commerce)
4. **Module Marketplace**: Shareable, installable modules for common functionality

## Framework Goals & Non-Goals

### Goals
- **Architectural Clarity**: Enforce clear separation between functional core and imperative shell
- **Developer Experience**: Provide excellent tooling, documentation, and examples for rapid development
- **Multi-Interface Consistency**: Ensure consistent behavior across REST, CLI, and Web interfaces
- **Domain Agnostic**: Support multiple business domains through extensible patterns
- **Production Ready**: Include observability, error handling, and operational tooling

### Non-Goals
- **Multi-tenancy Runtime Support**: Framework provides preparation but not active multi-tenant features
- **Mobile/Desktop Clients**: Focus on web-based and server-side interfaces only
- **Specific Domain Logic**: Framework provides patterns, not domain-specific implementations
- **Authentication Provider**: Framework supports auth patterns but doesn't include specific providers

### Module Ownership Principle
Each domain module (`user`, `billing`, `workflow`) contains its complete functionality:
- Pure business logic (core)
- Port definitions and schemas
- HTTP, CLI, and WebSocket interfaces
- Service orchestration
- Adapter implementations
- Feature flag integration

## Primary Personas

### Domain Developer
- Implements business logic in the functional core
- Needs: Clear core boundaries, rich domain examples, pure function patterns
- Success: Can add new entities and business rules without touching infrastructure

### Platform Engineer
- Maintains the shell layer and adapters
- Needs: Clear adapter patterns, infrastructure tooling, monitoring capabilities
- Success: Can add new data sources and interfaces without changing core logic

### API Integrator
- Consumes REST endpoints for system integration
- Needs: Complete API documentation, consistent error handling, reliable schemas
- Success: Can integrate with confidence using API documentation alone

### Operator/SRE
- Manages deployment and operational tasks
- Needs: CLI tools, observability, clear error reporting, operational runbooks
- Success: Can troubleshoot and manage systems effectively through CLI and monitoring

### QA Engineer
- Tests the system across all interfaces
- Needs: Clear acceptance criteria, test strategies, reproducible environments
- Success: Can create comprehensive test suites covering all interaction modes

## Architectural Principles

### Functional Core Principles
1. **Pure Functions Only**: No side effects, deterministic behavior, referential transparency
2. **Domain-Focused**: Contains only business rules, calculations, and decision logic
3. **Data In, Data Out**: Immutable data structures as inputs and outputs
4. **Port-Dependent**: Depends only on abstractions (ports), never concrete implementations
5. **Highly Testable**: Unit tests require no mocks or external dependencies

### Imperative Shell Principles
1. **Side Effect Boundary**: All I/O, networking, persistence, and system interactions
2. **Adapter Implementation**: Concrete implementations of ports used by the core
3. **Validation Gateway**: Input validation and coercion before calling core functions
4. **Error Translation**: Convert core data responses to appropriate interface formats
5. **Infrastructure Management**: Configuration, logging, monitoring, and operational concerns

### Dependency Rules
- **Shell → Core**: Shell depends on Core interfaces ✓
- **Core → Shell**: Core NEVER depends on Shell ✗
- **Core → Ports**: Core depends only on port abstractions ✓
- **Shell → Adapters**: Shell implements and wires concrete adapters ✓

## Module-Centric Architecture Benefits

### Complete Domain Ownership
Each domain module owns its complete functionality stack:
- **Team Autonomy**: Each team owns a complete vertical slice
- **Independent Evolution**: Modules can be versioned and deployed independently
- **Simplified Reasoning**: All related code co-located within module boundaries
- **Feature Flagging**: Entire modules can be enabled/disabled via configuration
- **Testing Isolation**: Complete module functionality can be tested independently

### Technology Stack Alignment
- **Clojure**: Functional programming natural fit for Functional Core pattern
- **Immutability**: Reduces complexity and bugs in concurrent environments
- **REPL-Driven Development**: Live system introspection and debugging
- **Integrant System**: Component lifecycle management and development workflow
- **Aero Configuration**: Profile-based, data-driven configuration management

### Interface Consistency
- **Multi-Interface**: REST API, CLI, and Web Frontend share same core logic
- **Consistent Validation**: Same schemas and error handling across all interfaces
- **Feature Flags**: Uniform feature control across all interaction modes

## Implementation Roadmap

### Phase 0: Module-Centric Foundation (Current - Q4 2025)
- ✅ Module-centric architecture implementation
- ✅ Complete domain ownership patterns
- ✅ Feature flag integration for module control
- ✅ Updated architecture documentation
- 🔄 Core module implementations (user, billing, workflow)

### Phase 1: Core Framework Stabilization (Q1 2026)
- Schema definitions and validation pipeline
- Port definitions and example adapters
- Error handling and RFC 7807 Problem Details
- Module-specific service orchestration
- Complete separation of concerns validation

### Phase 2: Interface Implementation (Q2 2026)
- Module-centric REST API endpoints
- Module-centric CLI commands
- WebSocket real-time integration
- Cross-interface consistency validation
- Comprehensive integration testing

### Phase 3: Framework Library Extraction (Q3 2026)
- **Core Infrastructure Library** (`boundary-core`)
- Library packaging and distribution
- Versioned releases and breaking change management

### Phase 4: Module Template System (Q4 2026)
- **Template Generator** (`boundary-gen`)
- Domain-specific templates
- Architecture pattern enforcement

### Phase 5: Development Platform (Q1-Q2 2027)
- **Module Marketplace**
- **Domain-Specific Frameworks**
- Community module registry

## Current Entity Model

### Core Entities
```clojure
;; Tenant
{:tenant/id   :uuid
 :tenant/name [:string {:min 1 :max 100}]}

;; User  
{:user/id         :uuid
 :user/email      [:string {:min 5 :max 255}]
 :user/tenant-id  :uuid
 :user/role       [:enum :admin :user :viewer]
 :user/active     :boolean
 :user/created-at :inst}

;; Job
{:job/id          :uuid
 :job/tenant-id   :uuid  
 :job/title       [:string {:min 1 :max 200}]
 :job/status      [:enum :created :in-progress :completed :cancelled]
 :job/created-by  :uuid
 :job/assigned-to [:maybe :uuid]
 :job/created-at  :inst
 :job/updated-at  :inst}
```

### Multi-Tenancy Preparation
- Framework provides preparation but not active multi-tenant features
- All entities include tenant context for future isolation
- Data access patterns support tenant-scoped operations

## Technology Stack Reference

### Core Technologies
| Component | Technology | Version |
|-----------|------------|----------|
| **Core Language** | Clojure | 1.11.1 |
| **HTTP Server** | Ring + Reitit | Latest |
| **CLI Framework** | tools.cli | Latest |
| **Frontend** | ClojureScript + Replicant | Latest |
| **Validation** | Malli | 0.17.0 |
| **Database** | PostgreSQL + next.jdbc | Latest |
| **Query Builder** | HoneySQL | 2.7+ |
| **Connection Pool** | HikariCP | 6.0.0 |
| **Configuration** | Aero | 1.1.6 |
| **Logging** | TeleMere + tools.logging | Latest |
| **Testing** | clojure.test + test.check | Latest |

### Interface Support
- **REST API**: JSON-based with RFC 7807 Problem Details error format
- **CLI**: tools.cli-based with comprehensive help and multiple output formats
- **Web Frontend**: ClojureScript with Replicant for reactive UI

## Key Success Factors

### Technical Excellence
- **Clean Architecture**: Maintainable, testable, scalable codebase
- **Developer Experience**: Comprehensive tooling and documentation  
- **Quality Assurance**: Automated testing and continuous integration
- **Performance Engineering**: Optimized for high-volume operations

### Market Execution
- **Customer-Centric Development**: Regular feedback integration
- **Rapid Iteration**: Quick response to market changes
- **Partnership Strategy**: Integration ecosystem development
- **Support Excellence**: Responsive customer success programs

### Organizational Capabilities
- **Cross-Functional Teams**: Product, engineering, and design collaboration
- **Continuous Learning**: Technology and market trend awareness
- **Scalable Processes**: Documentation, training, and knowledge management
- **Quality Culture**: Emphasis on excellence in all deliverables

---
*Extracted: 2025-01-10 18:24*
*Source: docs/boundary.prd.adoc*
