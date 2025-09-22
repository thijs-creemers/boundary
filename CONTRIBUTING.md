# Contributing to Boundary Framework

Thank you for your interest in contributing to Boundary! This guide will help you understand our development process and how to make effective contributions.

## Quick Start for Contributors

**📖 Start Here**: Read the [Developer Guide (warp.md)](./warp.md) for comprehensive setup and development information.

## Development Setup

```zsh
# Prerequisites: JDK and Clojure CLI
brew install openjdk clojure/tools/clojure  # macOS

# Clone and setup
git clone <repo-url> boundary
cd boundary
clojure -M:test                            # Verify setup
clojure -M:repl-clj                        # Start development REPL
```

## How to Contribute

### 🐛 **Bug Reports**

When reporting bugs:

- Use the issue template
- Include steps to reproduce
- Provide relevant system information (OS, JDK version, Clojure version)
- Include error messages and stack traces
- Test against the latest version

### 💡 **Feature Requests**

Before proposing new features:

- Check existing issues and PRD documentation
- Ensure alignment with [Functional Core / Imperative Shell principles](docs/architecture/overview.adoc)
- Consider how it fits the module-centric architecture
- Discuss in an issue before implementing

### 🔧 **Code Contributions**

#### Architecture Guidelines

Boundary follows strict architectural principles:

**Functional Core Requirements:**
- Pure functions only - no side effects
- Deterministic behavior
- Immutable data structures
- Dependencies only on ports (abstractions)

**Imperative Shell Requirements:**
- Handle ALL side effects (I/O, logging, configuration)
- Implement concrete adapters for ports
- Validate input before calling core functions
- No business logic in shell layer

#### Module Structure

When adding new functionality:

1. **Choose appropriate module** (user, billing, workflow) or create new one
2. **Start with core functions** - implement pure business logic
3. **Define ports** if external capabilities needed
4. **Implement adapters** for concrete implementations  
5. **Add shell services** to orchestrate core and adapters
6. **Create interfaces** (HTTP, CLI) using shell services

#### Code Style

- Follow existing naming conventions
- Use comprehensive docstrings for public functions
- Include schema validation for all inputs
- Write tests at appropriate architectural layers

#### Testing Requirements

- **Core Functions**: Pure unit tests, no mocks required
- **Shell Services**: Integration tests with mock adapters
- **Adapters**: Contract tests against actual external systems
- All tests must pass: `clojure -M:test`

## Development Workflow

### 🔄 **Standard Development Cycle**

```zsh
# Start development environment
clojure -M:repl-clj

# In REPL - load development utilities  
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)                          # Start system

# Make changes in editor

user=> (ig-repl/reset)                       # Reload and restart system

# Run tests
clojure -M:test

# Lint code
clojure -M:clj-kondo --lint src test
```

### 🎯 **Pull Request Process**

1. **Create feature branch**: `git checkout -b feature/your-feature-name`
2. **Make changes** following architecture guidelines
3. **Test thoroughly**: All tests must pass
4. **Lint code**: Fix any linting issues
5. **Update documentation** if needed (see Documentation Updates below)
6. **Create PR** with clear description and rationale

### 📝 **Documentation Updates**

#### When to Update warp.md

**🚨 Always update [warp.md](./warp.md) when making these changes:**

- [ ] **New Module Added**: Update module structure examples and lists
- [ ] **Build System Changes**: Update command examples and build instructions  
- [ ] **Development Workflow Changes**: Update REPL startup, system lifecycle instructions
- [ ] **Configuration Changes**: Update configuration examples and environment setup
- [ ] **New Dependencies**: Update key technologies section and rationale
- [ ] **Architecture Changes**: Update principles, dependency rules, or ADRs

#### How to Update warp.md

1. **Use the maintenance template**: See [docs/templates/warp-maintenance-template.md](docs/templates/warp-maintenance-template.md)
2. **Test all commands**: Ensure every command in the guide works
3. **Verify links**: Check all internal documentation links  
4. **Update code examples**: Refresh examples to match actual source code
5. **Test with fresh environment**: Have someone unfamiliar verify the quick start

#### Architecture Documentation

For changes to core architecture:

- Update relevant files in [docs/architecture/](docs/architecture/)
- Consider if PRD updates are needed
- Update architecture diagrams if structural changes occur

## Module Development Guidelines

### 🏗️ **Creating a New Module**

When adding a new domain module:

1. **Create module structure**:
   ```
   src/boundary/new-module/
   ├── core/           # Pure business logic
   ├── ports.clj       # Abstract interfaces  
   ├── schema.clj      # Domain schemas
   ├── http.clj        # HTTP handlers
   ├── cli.clj         # CLI commands
   └── shell/          # Shell orchestration
       ├── adapters.clj
       └── service.clj
   ```

2. **Update warp.md**: Add to module examples and structure section
3. **Update build system**: Add to any module-specific build configurations
4. **Add tests**: Create corresponding test structure
5. **Update documentation**: Reference in architecture docs

### 🔌 **Port and Adapter Development**

When adding new external integrations:

1. **Define port (abstract interface)** in appropriate module's `ports.clj`
2. **Implement adapter** in `shell/adapters.clj` 
3. **Add configuration** for the new adapter
4. **Create mock implementation** for testing
5. **Update system wiring** to inject the adapter

## Quality Standards

### ✅ **Definition of Done**

All contributions must meet these standards:

- [ ] **Architecture Compliance**: Follows FC/IS principles
- [ ] **Tests Pass**: All existing and new tests pass
- [ ] **Code Quality**: Passes linting with no errors
- [ ] **Documentation**: Updates warp.md if needed
- [ ] **Schema Validation**: All inputs validated with Malli schemas
- [ ] **Error Handling**: Proper error handling and logging
- [ ] **Review**: Code reviewed by maintainer

### 🧪 **Testing Standards**

- **Core functions**: Must be pure and testable without mocks
- **Shell services**: Test orchestration logic with mock adapters
- **Adapters**: Test integration with actual external systems
- **End-to-end**: Test complete workflows across architectural boundaries

### 📐 **Code Review Criteria**

Reviewers will check for:

- Architectural boundary compliance (no core → shell dependencies)
- Proper separation of concerns
- Comprehensive error handling
- Schema validation at boundaries
- Adequate test coverage
- Clear documentation
- Performance considerations

## Getting Help

### 📚 **Resources**

- **[Developer Guide (warp.md)](./warp.md)**: Complete development reference
- **[Architecture Documentation](docs/architecture/)**: Detailed architectural guides
- **[PRD](docs/boundary.prd.adoc)**: Complete product requirements

### 💬 **Communication**

- **GitHub Issues**: Bug reports and feature requests
- **Pull Requests**: Code discussions and reviews
- **Architecture Questions**: Reference architecture documentation first

### 🔍 **Common Issues**

**Build Problems**: 
```zsh
rm -rf .cpcache target
clojure -M:test
```

**REPL Issues**:
```zsh
user=> (ig-repl/halt)
user=> (ig-repl/go)
```

**Configuration Issues**: Check `resources/conf/dev/config.edn` matches expected structure

## Recognition

Contributors who make significant improvements will be recognized in:

- Release notes
- Project documentation  
- Contributor list

Thank you for helping make Boundary better! 🚀

---

For questions about this contributing guide, please open an issue.
