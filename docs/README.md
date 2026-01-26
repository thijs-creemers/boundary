# Boundary Framework Documentation

**Production-ready Clojure web framework with Functional Core / Imperative Shell architecture**

---

## 🚀 Getting Started (Start Here!)

New to Boundary? Start with these guides:

### [**5-Minute Quickstart →**](QUICKSTART.md)
Get your first API endpoint running in 5 minutes. Perfect for trying out Boundary.

**What you'll learn:**
- Install and setup
- Connect to a database
- Start the HTTP server
- Make your first API call
- Generate a complete CRUD module

**Time:** 5 minutes | **Level:** Beginner

---

### [**Full Tutorial →**](TUTORIAL.md) *(Coming Soon)*
Build a complete task management API from scratch with authentication, validation, and tests.

**What you'll build:**
- User authentication with JWT
- Task CRUD operations
- Real-time WebSocket updates
- Comprehensive test suite

**Time:** 1-2 hours | **Level:** Beginner to Intermediate

---

## 📚 Core Documentation

### Architecture & Design

#### [**Architecture Overview →**](../README.md#architecture)
Understand the Functional Core / Imperative Shell (FC/IS) pattern that powers Boundary.

- Why FC/IS?
- Core vs Shell responsibilities
- Module structure
- Port-based architecture

#### [**Module Design Guide →**](../AGENTS.md)
Deep dive into how modules are structured and how to design new ones.

- Module anatomy
- Core layer (pure functions)
- Shell layer (side effects)
- Ports and adapters
- Testing strategies

#### [**Development Guide →**](../BUILD.md)
Everything you need for local development.

- Setting up your environment
- REPL workflow
- Running tests
- Building for production
- CI/CD integration

---

### Guides & How-Tos

#### [**IDE Setup Guide →**](IDE_SETUP.md)
Configure your editor for the best Boundary development experience.

**Covered IDEs:**
- VSCode + Calva ⭐ Recommended
- IntelliJ IDEA + Cursive
- Emacs + CIDER
- Vim + vim-fireplace

**What's included:**
- Installation instructions
- REPL connection setup
- Keyboard shortcuts
- Debugging configuration
- Recommended extensions

#### [**Database Setup Guide →**](guides/DATABASE_SETUP.md) *(Coming Soon)*
Configure databases for development and production.

- SQLite (easiest, for development)
- PostgreSQL (recommended for production)
- MySQL (enterprise compatibility)
- H2 (in-memory testing)
- Connection pooling
- Migration strategies

#### [**Authentication Guide →**](guides/AUTHENTICATION.md)
Implement authentication and authorization in your app.

- JWT-based authentication
- Session management
- Role-based access control (RBAC)
- MFA setup
- OAuth2 integration

#### [**Testing Guide →**](guides/TESTING.md) *(Coming Soon)*
Write comprehensive tests for your Boundary application.

- Unit testing pure functions
- Integration testing with database
- HTTP endpoint testing
- Property-based testing
- Test fixtures and helpers

---

## 🛠️ Operations & Deployment

### [**Operations Runbook →**](OPERATIONS.md)
Complete guide for deploying and operating Boundary in production.

**What's covered:**
- Deployment (JAR, Docker, Kubernetes)
- Monitoring & observability
- Database operations & migrations
- Incident response playbooks
- Security operations
- Performance tuning
- Troubleshooting guide

**Sections:** 140+ | **Length:** 1,200+ lines

---

### [**Security Guide →**](SECURITY.md) *(Coming Soon)*
Security best practices for Boundary applications.

- Secrets management
- Security headers
- Rate limiting
- CSRF protection
- SQL injection prevention
- XSS protection
- Security audit checklist

---

## 📖 API Reference

### [**API Pagination Guide →**](API_PAGINATION.md)
Complete guide to using pagination in Boundary APIs.

**What's covered:**
- Offset-based pagination (simple, page-based)
- Cursor-based pagination (high performance)
- RFC 5988 Link headers
- Client examples (JavaScript, Python, Clojure)
- Performance guidelines
- Troubleshooting

### [**MFA API Reference →**](MFA_API_REFERENCE.md)
Multi-factor authentication API documentation.

**What's covered:**
- TOTP setup and verification
- Backup codes management
- QR code generation
- API endpoints and examples
- Security best practices

---

## 💡 Examples & Tutorials

Learn by example with complete, working applications:

### [**todo-api**](../examples/todo-api/) *(Coming Soon)*
Simple REST API for task management.

**Demonstrates:**
- Basic CRUD operations
- Database persistence
- Input validation
- Error handling
- API documentation

**Complexity:** ⭐ Simple | **Time:** 30 minutes

---

### [**blog**](../examples/blog/) *(Coming Soon)*
Full-stack blog with web UI and admin panel.

**Demonstrates:**
- Server-side rendering with Hiccup
- User authentication
- Rich text editing
- File uploads
- Comment system
- Admin dashboard

**Complexity:** ⭐⭐ Moderate | **Time:** 2 hours

---

### [**microservice**](../examples/microservice/) *(Coming Soon)*
Production-ready microservice template.

**Demonstrates:**
- Health checks & readiness probes
- Metrics & distributed tracing
- Circuit breakers
- Service discovery
- Docker & Kubernetes deployment
- CI/CD pipeline

**Complexity:** ⭐⭐⭐ Advanced | **Time:** 4 hours

---

## 🔧 Reference Documentation

### API Reference

#### [**Core API →**](api/CORE.md) *(Coming Soon)*
Complete reference for core business logic functions.

- Data transformation functions
- Validation utilities
- Business rules
- Domain models

#### [**Ports API →**](api/PORTS.md) *(Coming Soon)*
Port (interface) definitions for all modules.

- IRepository ports
- ILogger ports
- IMetrics ports
- IErrorReporter ports

#### [**HTTP API →**](api/HTTP.md)
HTTP routing, handlers, and middleware reference.

- Route definitions
- Request/response handling
- Middleware
- Interceptors
- Session management

---

### Configuration Reference

#### [**Configuration Guide →**](CONFIGURATION.md) *(Coming Soon)*
Complete configuration options for Boundary applications.

- Environment variables
- config.edn structure
- Database configuration
- HTTP server settings
- Logging configuration
- Metrics configuration
- Error reporting settings

---

### CLI Reference

#### [**Scaffolder CLI →**](cli/SCAFFOLDER.md) *(Coming Soon)*
Generate modules, endpoints, and adapters with the scaffolder.

```bash
# Generate complete module
clojure -M:dev -m boundary.scaffolder.core --name tasks

# Add field to existing module
clojure -M:dev -m boundary.scaffolder.field --module tasks --name priority:string

# Add endpoint
clojure -M:dev -m boundary.scaffolder.endpoint --module tasks --path /tasks/:id/complete
```

#### [**Migration CLI →**](cli/MIGRATIONS.md)
Database migration commands and workflow.

```bash
# Commands covered
clojure -M:migrate status    # Check migration status
clojure -M:migrate migrate   # Run pending migrations
clojure -M:migrate rollback  # Rollback last migration
clojure -M:migrate create    # Create new migration
```

---

## 📦 Advanced Topics

### [**Multi-Database Support →**](advanced/MULTI_DATABASE.md) *(Coming Soon)*
Use multiple databases in a single application.

- Primary and replica databases
- Read/write splitting
- Database per module
- Cross-database transactions

### [**Background Jobs →**](advanced/BACKGROUND_JOBS.md) *(Coming Soon)*
Implement asynchronous job processing.

- Job queue setup
- Scheduled jobs
- Retry logic
- Job monitoring

### [**Caching Strategies →**](advanced/CACHING.md) *(Coming Soon)*
Improve performance with caching.

- In-memory caching
- Redis integration
- HTTP caching headers
- Cache invalidation patterns

### [**WebSockets & Real-time →**](advanced/WEBSOCKETS.md) *(Coming Soon)*
Add real-time features to your application.

- WebSocket setup
- Event broadcasting
- Presence tracking
- Real-time notifications

---

## 🎓 Learning Resources

### Conceptual Guides

#### [**FC/IS Pattern Explained →**](concepts/FC_IS_PATTERN.md) *(Coming Soon)*
Deep dive into Functional Core / Imperative Shell.

- Historical context
- Benefits and trade-offs
- Common patterns
- Refactoring to FC/IS

#### [**Port-Based Architecture →**](concepts/PORTS_ADAPTERS.md) *(Coming Soon)*
Understanding ports and adapters (Hexagonal Architecture).

- Port definitions
- Adapter implementations
- Testing with test doubles
- Swapping adapters

#### [**Module Boundaries →**](concepts/MODULE_BOUNDARIES.md) *(Coming Soon)*
How to properly scope modules and maintain boundaries.

- Cohesion vs coupling
- Shared kernels
- Anti-corruption layers
- Module communication patterns

---

### Ecosystem & Community

#### [**Contributing Guide →**](../CONTRIBUTING.md)
How to contribute to Boundary Framework.

- Code of conduct
- Development setup
- Pull request process
- Coding standards

#### [**Project Status →**](../PROJECT_STATUS.adoc)
Current state of the project and roadmap.

- Feature completeness
- Production readiness
- Upcoming features
- Version history

#### [**Changelog →**](../CHANGELOG.md) *(Coming Soon)*
Version history and release notes.

---

## 🆘 Getting Help

### Troubleshooting

#### [**Common Issues →**](TROUBLESHOOTING.md) *(Coming Soon)*
Solutions to frequently encountered problems.

- Port already in use
- Database connection errors
- REPL won't start
- Tests failing
- Build errors

#### [**FAQ →**](FAQ.md) *(Coming Soon)*
Frequently asked questions about Boundary.

---

### Support Channels

- **Issues:** [GitHub Issues](https://github.com/yourusername/boundary/issues) - Bug reports and feature requests
- **Discussions:** [GitHub Discussions](https://github.com/yourusername/boundary/discussions) - Questions and community chat
- **Slack:** `#boundary-framework` - Real-time help (link coming soon)
- **Email:** support@boundary-framework.dev - Private support

---

## 📝 Documentation Organization

```
docs/
├── README.md                    # This file - documentation hub
├── QUICKSTART.md               # 5-minute getting started guide
├── TUTORIAL.md                 # Full hands-on tutorial
├── IDE_SETUP.md                # Editor configuration
├── OPERATIONS.md               # Production operations guide
├── PHASE2_COMPLETION.md        # Phase 2 completion report
│
├── guides/                     # How-to guides
│   ├── DATABASE_SETUP.md
│   ├── AUTHENTICATION.md
│   ├── TESTING.md
│   ├── CONFIGURATION.md
│   └── DEPLOYMENT.md
│
├── api/                        # API reference
│   ├── CORE.md
│   ├── PORTS.md
│   └── HTTP.md
│
├── cli/                        # CLI tool documentation
│   ├── SCAFFOLDER.md
│   └── MIGRATIONS.md
│
├── advanced/                   # Advanced topics
│   ├── MULTI_DATABASE.md
│   ├── BACKGROUND_JOBS.md
│   ├── CACHING.md
│   └── WEBSOCKETS.md
│
└── concepts/                   # Conceptual explanations
    ├── FC_IS_PATTERN.md
    ├── PORTS_ADAPTERS.md
    └── MODULE_BOUNDARIES.md
```

---

## 🚦 Documentation Status

| Document | Status | Priority | ETA |
|----------|--------|----------|-----|
| QUICKSTART.md | ✅ Complete | P0 | Done |
| OPERATIONS.md | ✅ Complete | P0 | Done |
| IDE_SETUP.md | ✅ Complete | P1 | Phase 3 |
| TUTORIAL.md | 📝 Planned | P1 | Phase 3 |
| guides/DATABASE_SETUP.md | 📝 Planned | P2 | Phase 3 |
| guides/AUTHENTICATION.md | ✅ Complete | P2 | Phase 4 |
| guides/TESTING.md | 📝 Planned | P2 | Phase 4 |
| Examples (todo-api, blog) | 📝 Planned | P1 | Phase 3 |

**Legend:**
- ✅ Complete and reviewed
- 🟡 In progress
- 📝 Planned but not started
- ⏸️ On hold

---

## 📖 Reading Paths

Different learning paths depending on your goal:

### Path 1: "I want to try Boundary" (15 minutes)
1. [Quickstart Guide](QUICKSTART.md)
2. [Swagger UI](http://localhost:3000/api-docs/) (after starting server)
3. [Architecture Overview](../README.md#architecture)

### Path 2: "I want to build a real application" (2-4 hours)
1. [Quickstart Guide](QUICKSTART.md)
2. [Full Tutorial](TUTORIAL.md)
3. [IDE Setup](IDE_SETUP.md)
4. [Testing Guide](guides/TESTING.md)
5. [Example: todo-api](../examples/todo-api/)

### Path 3: "I want to understand the architecture" (1-2 hours)
1. [Architecture Overview](../README.md#architecture)
2. [Module Design Guide](../AGENTS.md)
3. [FC/IS Pattern Explained](concepts/FC_IS_PATTERN.md)
4. [Port-Based Architecture](concepts/PORTS_ADAPTERS.md)

### Path 4: "I want to deploy to production" (4 hours)
1. [Operations Runbook](OPERATIONS.md)
2. [Security Guide](SECURITY.md)
3. [Database Setup](guides/DATABASE_SETUP.md)
4. [Configuration Guide](CONFIGURATION.md)
5. [Example: microservice](../examples/microservice/)

---

## 🤝 Contributing to Documentation

Found an issue or want to improve the docs?

1. **Quick fixes:** Edit directly on GitHub and submit a PR
2. **New content:** Open an issue first to discuss
3. **Examples:** PRs with working code examples are highly valued!

See [CONTRIBUTING.md](../CONTRIBUTING.md) for full guidelines.

---

**Happy coding with Boundary!** 🚀

*Last updated: 2026-01-26*
*Documentation version: 1.0.0*
