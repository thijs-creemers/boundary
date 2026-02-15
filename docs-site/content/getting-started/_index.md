---
title: "Getting Started with Boundary"
weight: 1
bookFlatSection: false
bookCollapseSection: false
---

# Getting Started with Boundary

Welcome to Boundary Framework! This section provides a structured learning path from installation to production deployment.

## Start Here

{{< columns >}}

### [Why Boundary?](why-boundary)
**Time:** 3 minutes | The pitch for developers who want to understand what Boundary offers and why.

<--->

### [At a Glance](boundary-at-a-glance)
**Time:** 1 minute | One-page cheat sheet with architecture diagram, commands, and quick reference.

{{< /columns >}}

---

## Learning Path

Follow these guides in order to master Boundary Framework:

### 1. [5-Minute Quickstart](../guides/quickstart)

**Time:** 5 minutes  
**Goal:** Get Boundary running on your machine

Install Clojure, clone the repository, run tests, and start your first system.

**What You'll Learn:**
- Prerequisites and installation
- Running tests to verify setup
- Starting the system via REPL
- Making your first API call

**Next:** Once you have Boundary running, build your first module.

---

### 2. [Your First Module](your-first-module)

**Time:** 30 minutes  
**Goal:** Build a complete blog module from scratch

Use the scaffolder to generate a production-ready module, understand the FC/IS architecture in practice, and add custom business logic.

**What You'll Learn:**
- Using the module scaffolder
- Understanding Functional Core / Imperative Shell separation
- Writing pure business logic (testable without mocks)
- Testing at different layers (unit, integration, contract)
- Adding HTTP endpoints
- Working with the database

**Next:** Understand the architectural patterns in depth.

---

### 3. [Understanding FC/IS Architecture](../guides/functional-core-imperative-shell)

**Time:** 20 minutes  
**Goal:** Deep dive into Functional Core / Imperative Shell pattern

Learn why Boundary enforces FC/IS, how it improves testability, and how to structure your code for maximum clarity.

**What You'll Learn:**
- The "why" behind FC/IS architecture
- Core layer responsibilities (pure functions only)
- Shell layer responsibilities (orchestrating I/O)
- Testing strategies for each layer
- Common patterns and anti-patterns

**Next:** Deploy your application to production.

---

### 4. [Production Deployment](deployment)

**Time:** 30 minutes  
**Goal:** Deploy Boundary to production

Configure environment variables, build the uberjar, deploy with Docker, and run database migrations.

**What You'll Learn:**
- Environment configuration and secrets management
- Building production artifacts (uberjar)
- Docker containerization
- Database migration workflow
- Health checks and monitoring

**Next:** Explore advanced features.

---

### 5. [Next Steps](next-steps)

**Time:** 10 minutes  
**Goal:** Explore advanced features and patterns

Discover Boundary's enterprise features: background jobs, caching, MFA, full-text search, file storage, and more.

**What You'll Learn:**
- Phase 4 features overview
- When to use each feature
- Links to comprehensive guides
- Community resources and support

---

## Alternative Paths

### Quick Reference Path

Already familiar with Clojure frameworks? Jump directly to:

1. [Architecture Overview](../architecture/overview) - High-level design
2. [Ports and Adapters](../guides/ports-and-adapters) - Hexagonal architecture
3. [Module Structure](../guides/modules-and-ownership) - Module organization
4. [HTTP Interceptors](../architecture/interceptors) - Request/response middleware

### Feature-First Path

Need a specific feature? Start here:

- [Background Jobs](../guides/background-jobs) - Async work processing
- [Multi-Factor Authentication](../guides/mfa-setup) - TOTP-based MFA
- [Full-Text Search](../guides/search) - PostgreSQL native search
- [Distributed Caching](../guides/caching) - Redis/in-memory caching
- [File Storage](../guides/file-storage) - S3/local storage
- [API Pagination](../guides/pagination) - Cursor/offset pagination

---

## Prerequisites

Before starting, ensure you have:

**Required:**
- Java 11 or higher
- Clojure CLI tools (latest version)
- Git

**Recommended:**
- PostgreSQL 12+ (or use SQLite default)
- Redis 6+ (for caching and jobs features)
- Docker Desktop (for containerized development)

**Development Tools:**
- Editor with Clojure support (Cursive, Emacs + CIDER, VSCode + Calva)
- Terminal with REPL support

---

## Common Questions

### How long does it take to learn Boundary?

- **Basic proficiency:** 2-3 hours (complete Getting Started path)
- **Production-ready:** 1-2 days (including feature integration)
- **Expert level:** 1-2 weeks (advanced patterns and customization)

### Do I need Clojure experience?

**Basic Clojure knowledge required:**
- Understand functions, maps, vectors
- Familiar with `let`, `def`, `defn`
- Comfortable with REPL workflow

**You don't need:**
- Deep macro knowledge
- Core.async expertise
- Advanced transducer experience

**New to Clojure?** Check out these resources first:
- [Clojure for the Brave and True](https://www.braveclojure.com/)
- [ClojureDocs](https://clojuredocs.org/)
- [Clojure Getting Started](https://clojure.org/guides/getting_started)

### Can I use Boundary for production?

**Yes!** Boundary is production-ready with:
- ✅ Comprehensive test coverage
- ✅ Security features (JWT auth, MFA, audit logging)
- ✅ Observability (logging, metrics, error reporting)
- ✅ Performance optimizations (connection pooling, caching)
- ✅ Enterprise features (background jobs, search, storage)

See the [Production Deployment Guide](deployment) for details.

### How does Boundary compare to Kit/Luminus?

**Key Differences:**

| Aspect | Boundary | Kit/Luminus |
|--------|----------|-------------|
| **Approach** | Runtime framework | Template generator |
| **Architecture** | FC/IS enforced | No enforcement |
| **Features** | Built-in (jobs, cache, MFA) | Bring your own |
| **Updates** | Framework updates | Manual per-project |
| **Flexibility** | Opinionated patterns | Maximum flexibility |

**Choose Boundary if:**
- You want architectural enforcement
- You need enterprise features without DIY
- You're building microservices-ready systems

**Choose Kit/Luminus if:**
- You prefer maximum flexibility
- You like choosing your own libraries
- You're building quick prototypes

See the [homepage comparison tables](../#quick-comparison) for more details.

---

## Getting Help

### Documentation

- [Architecture](../architecture/) - Design principles and patterns
- [Guides](../guides/) - Step-by-step tutorials
- [Reference](../reference/) - API and configuration reference
- [ADR](../adr/) - Architecture decision records

### Community

- **GitHub Issues:** [Report bugs and request features](https://github.com/thijs-creemers/boundary/issues)
- **GitHub Discussions:** [Ask questions and share ideas](https://github.com/thijs-creemers/boundary/discussions)
- **Documentation Issues:** [Report documentation problems](https://github.com/thijs-creemers/boundary-docs/issues)

### Contributing

Boundary is open source and welcomes contributions!

- **Code:** Submit pull requests to the [main repository](https://github.com/thijs-creemers/boundary)
- **Documentation:** Improve docs via [boundary-docs repository](https://github.com/thijs-creemers/boundary-docs)
- **Examples:** Share your projects and modules

---

## Ready to Start?

Begin with the [5-Minute Quickstart](../guides/quickstart) →
