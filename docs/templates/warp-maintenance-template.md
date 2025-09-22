# Warp.md Maintenance Template

This template guides future updates to the `warp.md` developer guide to ensure it remains accurate and valuable.

## When to Update Warp.md

Update the developer guide when any of the following changes occur:

### 🏗️ **Structural Changes**
- [ ] New module added to `src/boundary/`
- [ ] Module structure patterns change
- [ ] New interface types added (beyond HTTP, CLI, Web)
- [ ] Build system changes (new aliases, different tools)

### 🔧 **Tooling Changes**
- [ ] Dependencies updated in `deps.edn` (especially major versions)
- [ ] New development tools added (linter, formatter, test runner)
- [ ] Development workflow changes (REPL startup, system lifecycle)
- [ ] Configuration management changes

### 📚 **Documentation Changes**
- [ ] New architecture documents added to `docs/`
- [ ] PRD updates that affect developer experience
- [ ] Changes to module examples or code patterns

## Update Checklist

### 1. **Review and Update Content**

- [ ] **Project Overview**: Verify goals, non-goals, and user types are current
- [ ] **Quick Start**: Test all installation and setup commands
- [ ] **Architecture Summary**: Ensure ADRs and principles are up-to-date
- [ ] **Module Structure**: Update examples if module patterns change
- [ ] **Key Technologies**: Add/remove dependencies, update rationale
- [ ] **Testing Strategy**: Verify test commands and organization
- [ ] **Configuration**: Update config examples and environment setup
- [ ] **Common Tasks**: Test all command examples
- [ ] **References**: Check all internal and external links

### 2. **Validate All Commands**

Test every command mentioned in the guide:

```zsh
# Prerequisites test
echo $SHELL  # Should show zsh

# Core commands
clojure -M:test
clojure -M:repl-clj
clojure -M:clj-kondo --lint src
clojure -M:outdated
clojure -T:build clean

# Quick verification
ls -la deps.edn README.md warp.md
find docs -name "*.adoc" | wc -l
```

### 3. **Update Code Examples**

- [ ] Verify all code snippets against actual source files
- [ ] Update namespace examples if module structure changes
- [ ] Refresh schema and port examples from real code
- [ ] Test configuration loading examples in REPL

### 4. **Link Validation**

```zsh
# Check internal documentation links exist
ls -1 docs/boundary.prd.adoc \
      docs/PRD-IMPROVEMENT-SUMMARY.adoc \
      docs/architecture/overview.adoc \
      docs/architecture/components.adoc \
      docs/architecture/data-flow.adoc

# Optional: Use markdown link checker
# npx -y markdown-link-check warp.md
```

## Template Structure

When making major updates, maintain this structure:

```markdown
# Boundary Framework Developer Guide

## Table of Contents
[Standard TOC with all sections]

## 1. Project Overview
- What Boundary is (keep current)
- Key characteristics (update if architecture changes)
- User types (update if new personas emerge)
- Goals/Non-goals (sync with PRD updates)

## 2. Quick Start
- Prerequisites (verify versions)
- Getting started (test all commands)
- Verification (ensure accuracy)

## 3. Architecture Summary
- Principles (keep stable unless major changes)
- Dependency rules (update if boundaries change)
- Key decisions (add new ADRs)

## 4. Development Workflow
- Environment setup (update for new tools)
- Lifecycle management (verify with integrant changes)
- Module workflow (update if patterns change)

## 5. Module Structure
- General template (keep stable)
- Concrete examples (refresh from real modules)
- Code snippets (verify against source)

## 6. Key Technologies
- Dependency tables (sync with deps.edn)
- Technology decisions (add new rationale)

## 7. Testing Strategy
- Layer alignment (keep stable)
- Commands (test all examples)
- Organization (update if structure changes)

## 8. Configuration Management
- Structure (update if config changes)
- Environment setup (verify examples work)
- Loading examples (test in REPL)

## 9. Common Development Tasks
- Essential commands (verify all work)
- Task categories (add new workflows)
- Troubleshooting (update common issues)

## 10. References
- Internal docs (check all links)
- External links (verify still valid)
- Community resources (update as needed)
```

## Quality Standards

### ✅ **Accuracy Requirements**
- All commands must work as documented
- All internal links must resolve
- All code examples must be based on real source code
- Configuration examples must reflect actual config structure

### ✅ **Consistency Requirements**
- Use zsh syntax throughout
- Maintain architectural terminology (FC/IS, modules, ports/adapters)
- Keep section structure and formatting consistent
- Use same citation patterns for citations rule compliance

### ✅ **Completeness Requirements**
- Cover all major development tasks
- Include troubleshooting for common issues
- Provide both minimal and comprehensive examples
- Link to detailed architecture documentation

## Maintenance Schedule

### 🗓️ **Regular Reviews**
- **Monthly**: Verify commands still work
- **Per Release**: Update for any structural changes
- **Quarterly**: Check all external links
- **Per ADR**: Update architecture decisions section

### 🚨 **Immediate Updates Required**
- New module addition
- Build system changes
- Major dependency updates
- Configuration structure changes
- REPL workflow changes

## Testing New Changes

Before committing updates:

1. **Full command test**: Run every command in a clean environment
2. **Link validation**: Check all internal documentation links
3. **Code example sync**: Verify all snippets against source
4. **Fresh developer test**: Have someone unfamiliar test the quick start

---

*Template Version: 1.0*  
*Last Updated: 2025-01-10*
