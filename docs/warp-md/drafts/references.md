# References

*Links to documentation, guides, and external resources*

## Internal Documentation

### Core Project Documentation

- **[Project Requirements (PRD)](../PRD-IMPROVEMENT-SUMMARY.adoc)** - Complete project requirements and improvement summary
- **[Architecture Overview](../architecture/overview.adoc)** - High-level architectural decisions and patterns  
- **[Architecture Index](../architecture/index.adoc)** - Navigation hub for all architectural documentation

### Architecture Deep Dives

- **[Functional Core / Imperative Shell](../architecture/layer-separation.adoc)** - FC/IS pattern implementation
- **[Ports and Adapters](../architecture/ports-and-adapters.adoc)** - Hexagonal architecture details
- **[Component Architecture](../architecture/components.adoc)** - Component lifecycle and dependencies
- **[Integration Patterns](../architecture/integration-patterns.adoc)** - Inter-system communication patterns
- **[Configuration and Environment](../architecture/configuration-and-env.adoc)** - Environment management strategies

### Implementation Guides

- **[User Module Implementation](../implementation/user-module-implementation.adoc)** - Reference implementation walkthrough
- **[Module Development Patterns](../warp-md/drafts/module-structure.md)** - Guide to creating new modules
- **[Development Workflow](../warp-md/drafts/development-workflow.md)** - Daily development practices

### Development Resources

- **[Quick Start Guide](../warp-md/drafts/quick-start.md)** - Get up and running fast
- **[Key Technologies](../warp-md/drafts/key-technologies.md)** - Technology stack overview
- **[Configuration Guide](../warp-md/drafts/configuration.md)** - Environment and secrets management
- **[Testing Strategy](../warp-md/drafts/testing-strategy.md)** - Testing approaches across layers
- **[Common Tasks](../warp-md/drafts/common-tasks.md)** - Developer cheatsheet

## Module Documentation

### User Module
- **Source**: `src/boundary/user/`
- **Tests**: `test/boundary/user/`
- **API**: User management, preferences, membership
- **Schema**: `src/boundary/user/schema.clj`

### Billing Module  
- **Source**: `src/boundary/billing/`
- **Tests**: `test/boundary/billing/`
- **API**: Pricing, discounts, invoicing
- **Schema**: `src/boundary/billing/schema.clj`

### Workflow Module
- **Source**: `src/boundary/workflow/`
- **Tests**: `test/boundary/workflow/`
- **API**: Process orchestration and state management
- **Schema**: `src/boundary/workflow/schema.clj`

## External Library Documentation

### Core Technologies

- **[Clojure](https://clojure.org/guides/getting_started)** - Main programming language
- **[Clojure CLI](https://clojure.org/guides/deps_and_cli)** - Dependency management and tooling
- **[Integrant](https://github.com/weavejester/integrant)** - System lifecycle management
- **[Aero](https://github.com/juxt/aero)** - Configuration management

### Data and Persistence

- **[next.jdbc](https://github.com/seancorfield/next-jdbc)** - Database connectivity
- **[HoneySQL](https://github.com/seancorfield/honeysql)** - SQL generation
- **[HikariCP](https://github.com/brettwooldridge/HikariCP)** - Connection pooling
- **[Malli](https://github.com/metosin/malli)** - Schema validation

### Development Tools

- **[Kaocha](https://github.com/lambdaisland/kaocha)** - Test runner
- **[clj-kondo](https://github.com/clj-kondo/clj-kondo)** - Static analysis and linting
- **[tools.build](https://github.com/clojure/tools.build)** - Build automation
- **[CIDER](https://docs.cider.mx/)** - Emacs development environment

### HTTP and Web (when applicable)

- **[Ring](https://github.com/ring-clojure/ring)** - HTTP abstraction
- **[Reitit](https://github.com/metosin/reitit)** - Routing library
- **[Cheshire](https://github.com/dakrone/cheshire)** - JSON processing

## Learning Resources

### Clojure Learning

- **[Clojure for the Brave and True](https://www.braveclojure.com/)** - Comprehensive Clojure tutorial
- **[ClojureDocs](https://clojuredocs.org/)** - Community-driven documentation
- **[4Clojure](https://4clojure.oxal.org/)** - Interactive Clojure problems
- **[Clojure Koans](https://github.com/functional-koans/clojure-koans)** - Learn Clojure through tests

### Architecture Patterns

- **[Functional Core, Imperative Shell](https://www.destroyallsoftware.com/screencasts/catalog/functional-core-imperative-shell)** - Gary Bernhardt's screencast
- **[Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)** - Alistair Cockburn's original article
- **[Domain-Driven Design](https://martinfowler.com/bliki/DomainDrivenDesign.html)** - Martin Fowler overview
- **[CQRS](https://martinfowler.com/bliki/CQRS.html)** - Command Query Responsibility Segregation

### System Design

- **[Integration Patterns](https://www.enterpriseintegrationpatterns.com/)** - Enterprise integration patterns
- **[Building Microservices](https://www.oreilly.com/library/view/building-microservices/9781491950340/)** - Sam Newman's book
- **[Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)** - Uncle Bob's Clean Architecture

## Community Resources

### Clojure Community

- **[Clojurians Slack](https://clojurians.slack.com/)** - Active community chat
- **[ClojureVerse](https://clojureverse.org/)** - Community forum
- **[r/Clojure](https://reddit.com/r/Clojure)** - Reddit community
- **[Clojure News](https://news.clojure.org/)** - Official news aggregator

### Conferences and Events

- **[Clojure/conj](https://2023.clojure-conj.org/)** - Annual Clojure conference
- **[ClojureD](https://clojured.de/)** - European Clojure conference  
- **[re:Clojure](https://www.reclojure.org/)** - Community-driven online conference
- **[London Clojurians](https://londonclojurians.org/)** - London meetup group

### Podcasts and Media

- **[Cognicast](https://blog.cognitect.com/cognicast/)** - Official Clojure podcast
- **[defn Podcast](https://defnpodcast.com/)** - Community-driven podcast
- **[ClojureStream](https://www.youtube.com/c/ClojureStream)** - YouTube channel with talks

## Tools and IDE Setup

### Editor Integration

- **[Calva](https://calva.io/)** - VS Code extension for Clojure
- **[Cursive](https://cursive-ide.com/)** - IntelliJ IDEA plugin
- **[Conjure](https://github.com/Olical/conjure)** - Neovim plugin
- **[Fireplace](https://github.com/tpope/vim-fireplace)** - Vim plugin

### Development Environment

- **[direnv](https://direnv.net/)** - Environment variable management
- **[nREPL](https://nrepl.org/)** - Network REPL protocol
- **[Portal](https://github.com/djblue/portal)** - Data visualization tool
- **[Reveal](https://github.com/vlaaad/reveal)** - Read-eval-visualize loop

## Troubleshooting and Support

### Common Issues

- **[Clojure FAQ](https://clojure.org/guides/faq)** - Frequently asked questions
- **[ClojureDocs Examples](https://clojuredocs.org/)** - Code examples for functions
- **[Stack Overflow](https://stackoverflow.com/questions/tagged/clojure)** - Q&A tagged with Clojure

### Getting Help

1. **Check existing documentation** - Start with internal docs and external library docs
2. **Search ClojureDocs** - Often has practical examples
3. **Ask on Clojurians Slack** - Very active and helpful community
4. **Post on ClojureVerse** - For longer-form discussions
5. **Create GitHub issues** - For bugs or feature requests

### Debug Resources

- **[Clojure Debugging](https://clojure.org/guides/debugging)** - Official debugging guide
- **[REPL Debugging Techniques](https://practical.li/clojure/repl-driven-development/)** - REPL-driven development
- **[Error Handling in Clojure](https://clojure.org/guides/error_handling)** - Exception handling patterns

## Contribution Guidelines

### Project Contribution

- **[CONTRIBUTING.md](../../CONTRIBUTING.md)** - How to contribute to this project
- **[Code of Conduct](../../CODE_OF_CONDUCT.md)** - Community guidelines (if exists)
- **[Pull Request Template](../../.github/pull_request_template.md)** - PR guidelines (if exists)

### Documentation Maintenance

- **Keep links current** - Verify external links periodically
- **Update version references** - When upgrading dependencies
- **Add examples** - Include practical examples for new features
- **Cross-reference** - Link related sections together

## Version Information

### Current Versions (as of last update)

- **Clojure**: 1.12.1
- **Integrant**: 0.13.1
- **Aero**: 1.1.6
- **next.jdbc**: 1.3.1048
- **Malli**: 0.19.1
- **Kaocha**: 1.91.1392

### Upgrade Guides

- **[Clojure Upgrade Guide](https://clojure.org/releases/devchangelog)** - Breaking changes between versions
- **[Integrant Migration](https://github.com/weavejester/integrant/blob/master/CHANGELOG.md)** - Integrant version changes
- **[Library Upgrade Checklist](../warp-md/drafts/key-technologies.md#upgrading-strategy)** - Internal upgrade process

## Quick Navigation

### By Role

**New Developer**:
1. [Quick Start](../warp-md/drafts/quick-start.md)
2. [Architecture Summary](../warp-md/drafts/architecture-summary.md)
3. [Development Workflow](../warp-md/drafts/development-workflow.md)

**Experienced Clojure Developer**:
1. [Key Technologies](../warp-md/drafts/key-technologies.md)  
2. [Module Structure](../warp-md/drafts/module-structure.md)
3. [Common Tasks](../warp-md/drafts/common-tasks.md)

**Architect/Tech Lead**:
1. [PRD](../PRD-IMPROVEMENT-SUMMARY.adoc)
2. [Architecture Overview](../architecture/overview.adoc) 
3. [Component Architecture](../architecture/components.adoc)

### By Task

**Setting up development environment**:
- [Quick Start](../warp-md/drafts/quick-start.md)
- [Configuration](../warp-md/drafts/configuration.md)

**Adding new features**:
- [Module Structure](../warp-md/drafts/module-structure.md)
- [Testing Strategy](../warp-md/drafts/testing-strategy.md) 
- [Development Workflow](../warp-md/drafts/development-workflow.md)

**Understanding the architecture**:
- [Architecture Summary](../warp-md/drafts/architecture-summary.md)
- [FC/IS Layer Separation](../architecture/layer-separation.adoc)
- [Ports and Adapters](../architecture/ports-and-adapters.adoc)

**Troubleshooting**:
- [Common Tasks](../warp-md/drafts/common-tasks.md#troubleshooting)
- [Configuration Debugging](../warp-md/drafts/configuration.md#troubleshooting-configuration)
- Community resources above

---
*Last Updated: 2025-01-10 18:55*
*Keep this page bookmarked for quick access to all project resources!*
