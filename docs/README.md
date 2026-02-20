# Boundary Framework Documentation

**⚠️ NOTICE: Documentation has moved!**

## New Documentation Location

All Boundary Framework documentation has been consolidated into a single location for better organization and maintainability.

**📚 Access the full documentation at:**
- **Main documentation site**: `/docs-site/` directory
- **Hugo-based website** (local): Run `hugo server` in `docs-site/` and visit http://localhost:1313

## What Moved

The following documentation has been migrated to `docs-site/content/`:

### Getting Started
- ✅ Quickstart → `docs-site/content/getting-started/quickstart.md`
- ✅ Tutorial → `docs-site/content/getting-started/tutorial.md`

### Guides
- ✅ Operations → `docs-site/content/guides/operations.adoc`
- ✅ IDE Setup → `docs-site/content/guides/ide-setup.md`
- ✅ Authentication → `docs-site/content/guides/authentication.md`
- ✅ Database Setup → `docs-site/content/guides/database-setup.md`
- ✅ Testing → `docs-site/content/guides/testing.md`
- ✅ Admin Testing → `docs-site/content/guides/admin-testing.md`
- ✅ Security Setup → `docs-site/content/guides/security-setup.md`
- ✅ Tenant Migration → `docs-site/content/guides/tenant-migration.md`

### API References
- ✅ API Pagination → `docs-site/content/api/pagination.md`
- ✅ MFA API → `docs-site/content/api/mfa.md`
- ✅ Search API → `docs-site/content/api/search.md`

### Reference Documentation
- ✅ Publishing Guide → `docs-site/content/reference/publishing.md`

## What Remains Here

This `docs/` directory now contains only:

- **This README** - Redirect notice
- **archive/** - Historical documents (ADRs, notes, old guides)
- **research/** - Research notes and explorations (if exists)
- **tasks/** - Task tracking documents (if exists)

## Why We Consolidated

Previously, documentation was scattered across three locations:
1. `boundary-docs` repository (separate repo)
2. `docs-site/` directory (Hugo-based site)
3. `docs/` directory (markdown files)

This caused:
- ❌ Duplication of content
- ❌ Confusion about canonical versions
- ❌ Difficulty maintaining consistency
- ❌ Harder to find documentation

**New structure benefits:**
- ✅ Single source of truth (`docs-site/`)
- ✅ Professional Hugo-based documentation site
- ✅ Clear organization (getting-started, guides, api, reference)
- ✅ Easy to maintain and update
- ✅ Simple to deploy (GitHub Pages ready)

## Quick Links

| Section | Location |
|---------|----------|
| **Getting Started** | `docs-site/content/getting-started/` |
| **How-To Guides** | `docs-site/content/guides/` |
| **API Reference** | `docs-site/content/api/` |
| **Reference Docs** | `docs-site/content/reference/` |
| **Architecture** | `docs-site/content/architecture/` |
| **ADRs** | `docs-site/content/adr/` |

## Building the Documentation Site

```bash
cd docs-site

# Install Hugo (if not already installed)
# macOS:
brew install hugo

# Linux:
snap install hugo

# Windows:
choco install hugo-extended

# Serve locally (with live reload)
hugo server

# Build for production
hugo --gc --minify
```

Visit http://localhost:1313 to view the documentation site.

## Contributing to Documentation

When updating documentation:

1. **All changes go to `docs-site/content/`** - Not this directory
2. Use markdown (.md) for simple docs, AsciiDoc (.adoc) for complex guides
3. Add Hugo frontmatter to all files:
   ```yaml
   ---
   title: "Document Title"
   weight: 10
   description: "Brief description"
   ---
   ```
4. Test locally with `hugo server` before committing
5. See `docs-site/README.md` for full contribution guidelines

## Questions?

- **General questions**: See root `README.md`
- **Development guide**: See `AGENTS.md` in root directory
- **Build instructions**: See `BUILD.md` in root directory
- **Documentation issues**: Open an issue on GitHub

---

**Documentation Migration Completed**: 2026-02-15  
**Total Files Migrated**: 18  
**New Documentation Home**: `docs-site/`
