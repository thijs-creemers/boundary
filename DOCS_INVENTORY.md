# Documentation Inventory - Phase 1

**Generated:** 2026-02-15  
**Purpose:** Complete audit of all documentation for consolidation into `docs-site/`

---

## Executive Summary

### Current State
- **Total docs/ files:** 28 (excluding archive/)
- **Total docs-site/content files:** 105
- **Duplicates:** 1 file (mfa-setup.md)
- **Library docs:** 13 libraries × 2 files (AGENTS.md, README.md) = 26 files

### Key Findings
1. **Minimal duplication** - Only 1 duplicate file between locations
2. **docs/ contains valuable unique content** - OPERATIONS.md, IDE_SETUP.md, TUTORIAL.md, API references
3. **docs-site/ is production-ready** - Hugo-based, comprehensive, well-structured
4. **Clear migration path** - Most files just need to be moved and organized

---

## Duplication Analysis

### Duplicate Files (1 found)

| File | docs/ | docs-site/ | Canonical | Action |
|------|-------|------------|-----------|--------|
| `mfa-setup.md` | guides/mfa-setup.md (764 lines) | content/guides/mfa-setup.md (917 lines) | **docs-site version** (has frontmatter, more detailed) | Delete docs/ version after verification |

**Decision:** Use docs-site version - has Hugo frontmatter, more comprehensive, better formatted.

---

## Content by Category

### 1. Getting Started

| Document | Location | Lines | Status | Migration Target |
|----------|----------|-------|--------|------------------|
| QUICKSTART.md | docs/ | 463 | ✅ Keep | docs-site/content/getting-started/quickstart.md |
| TUTORIAL.md | docs/ | 1040 | ✅ Keep | docs-site/content/getting-started/tutorial.md |
| quickstart.md | docs/launch/posts/ | 95 | Archive | Move to archive or delete (launch material) |
| boundary-at-a-glance.adoc | docs-site/content/getting-started/ | 181 | ✅ Keep | Already in place |
| deployment.md | docs-site/content/getting-started/ | 87 | ✅ Keep | Already in place |
| next-steps.md | docs-site/content/getting-started/ | 43 | ✅ Keep | Already in place |
| why-boundary.adoc | docs-site/content/getting-started/ | 171 | ✅ Keep | Already in place |

**Action:** Migrate QUICKSTART.md and TUTORIAL.md to docs-site/content/getting-started/

---

### 2. Guides (How-To)

| Document | Location | Lines | Status | Migration Target |
|----------|----------|-------|--------|------------------|
| OPERATIONS.md | docs/ | 1108 | ✅ Keep | docs-site/content/guides/operations.adoc (convert) |
| IDE_SETUP.md | docs/ | 783 | ✅ Keep | docs-site/content/guides/ide-setup.md |
| AUTHENTICATION.md | docs/guides/ | 320 | ✅ Keep | docs-site/content/guides/authentication.md |
| DATABASE_SETUP.md | docs/guides/ | 313 | ✅ Keep | docs-site/content/guides/database-setup.md |
| TESTING.md | docs/guides/ | 524 | ✅ Keep | docs-site/content/guides/testing.md |
| ADMIN_TESTING_GUIDE.md | docs/testing/ | 685 | ✅ Keep | docs-site/content/guides/admin-testing.md |
| SECURITY_SETUP.md | docs/ | 271 | ✅ Keep | docs-site/content/guides/security-setup.md |
| SINGLE_TO_MULTI_TENANT_MIGRATION.md | docs/guides/ | 635 | ✅ Keep | docs-site/content/guides/tenant-migration.md |
| mfa-setup.md | docs/guides/ | 764 | 🗑️ Delete | Already in docs-site (better version) |

**Existing in docs-site/content/guides/:**
- add-entity.adoc (115 lines)
- add-rest-endpoint.adoc (213 lines)
- background-jobs.md (803 lines)
- caching.md (621 lines)
- code-comparisons.md (276 lines)
- configure-db.adoc (155 lines)
- create-module.adoc (470 lines)
- enable-validation-devex.adoc (313 lines)
- file-storage.md (835 lines)
- functional-core-imperative-shell.adoc (428 lines)
- implement-port-adapter.adoc (278 lines)
- integrate-observability.adoc (1089 lines)
- mfa-setup.md (917 lines) ✅ CANONICAL
- modules-and-ownership.adoc (142 lines)
- pagination.md (639 lines)
- ports-and-adapters.adoc (316 lines)
- quickstart.adoc (261 lines)
- run-tests-and-watch.adoc (281 lines)
- search.md (863 lines)
- troubleshooting.adoc (481 lines)
- use-cli.adoc (109 lines)
- validation-system.adoc (1048 lines)

**Action:** Migrate 8 unique guides from docs/ to docs-site/content/guides/

---

### 3. API Reference

| Document | Location | Lines | Status | Migration Target |
|----------|----------|-------|--------|------------------|
| API_PAGINATION.md | docs/ | 638 | ✅ Keep | docs-site/content/api/pagination.md |
| MFA_API_REFERENCE.md | docs/ | 799 | ✅ Keep | docs-site/content/api/mfa.md |
| SEARCH_API_REFERENCE.md | docs/ | 1290 | ✅ Keep | docs-site/content/api/search.md |

**Existing in docs-site/content/api/:**
- index.adoc (6 lines)
- post-users-example.adoc (99 lines)
- README.adoc (51 lines)

**Action:** Migrate 3 API reference documents to docs-site/content/api/

---

### 4. Architecture

**All in docs-site/content/architecture/:**
- clean-architecture-layers.adoc (324 lines)
- components.adoc (352 lines)
- configuration-and-env.adoc (265 lines)
- data-flow.adoc (340 lines)
- database-adapters.adoc (333 lines)
- dynamic-driver-loading.adoc (134 lines)
- error-handling-observability.adoc (252 lines)
- integration-patterns.adoc (370 lines)
- layer-separation.adoc (211 lines)
- middleware-architecture.adoc (214 lines)
- observability-integration.adoc (409 lines)
- overview.adoc (353 lines)
- pedestal-adapter-analysis.adoc (155 lines)
- ports-and-adapters.adoc (260 lines)
- shared-utilities.adoc (207 lines)

**Action:** No migration needed - already comprehensive

---

### 5. ADRs (Architecture Decision Records)

**All in docs-site/content/adr/:**
- ADR-001-library-split.md (447 lines)
- ADR-002-boundary-new-command.md (100 lines)
- ADR-003-websocket-architecture.md (830 lines)
- ADR-004-multi-tenancy-architecture.md (2084 lines)
- ADR-005-validation-devex-foundations.adoc (798 lines)
- ADR-006-web-ui-architecture-htmx-hiccup.adoc (540 lines)
- ADR-007-routing-architecture.adoc (495 lines)
- ADR-008-normalized-routing-abstraction.adoc (618 lines)
- ADR-009-reitit-exclusive-router.adoc (298 lines)
- ADR-010-http-interceptor-architecture.adoc (617 lines)
- ADR-pluggable-auth.adoc (78 lines)
- README.adoc (140 lines)

**Action:** No migration needed - already comprehensive

---

### 6. Reference Documentation

**In docs-site/content/reference/:**
- acceptance-criteria-template.adoc (35 lines)
- boundary-prd.adoc (1094 lines)
- commands.adoc (408 lines)
- configuration.adoc (1127 lines)
- development-commands.adoc (322 lines)
- endpoint-template.adoc (94 lines)
- glossary.adoc (93 lines)
- modules.adoc (305 lines)
- quality-criteria.adoc (214 lines)
- workflows.adoc (177 lines)

**To migrate:**
| Document | Location | Migration Target |
|----------|----------|------------------|
| PUBLISHING_GUIDE.md | docs/ | docs-site/content/reference/publishing.md |

**Action:** Migrate PUBLISHING_GUIDE.md

---

### 7. Examples

**In docs-site/content/examples/:**
- blog-app.adoc (199 lines)
- ecommerce-api.adoc (168 lines)
- minimal-api.adoc (97 lines)
- multi-tenant-saas.adoc (206 lines)
- task-api.adoc (144 lines)
- README.adoc (96 lines)

**Action:** No migration needed - examples already present

---

### 8. Archive / Status Documents

| Document | Location | Action |
|----------|----------|--------|
| PHASE8_COMPLETION.md | docs/ | Keep in docs/archive/ |
| PHASE8_CRITICAL_FIXES_COMPLETE.md | docs/ | Keep in docs/archive/ |
| SECURITY_CONFIGURED.md | docs/ | Keep in docs/archive/ |
| alpha-release-checklist.adoc | docs-site/content/ | Move to docs-site/content/archive/ or reference/ |

**Action:** Historical documents stay in docs/archive/, no migration needed

---

### 9. Launch Materials

| Document | Location | Action |
|----------|----------|--------|
| script.md | docs/launch/demo/ | Archive - launch material |
| quickstart.md | docs/launch/posts/ | Archive - launch material |
| why-boundary.md | docs/launch/posts/ | Archive - launch material |
| slack.md | docs/launch/announcements/ | Archive - launch material |
| clojureverse.md | docs/launch/announcements/ | Archive - launch material |
| reddit.md | docs/launch/announcements/ | Archive - launch material |
| outline.md | docs/launch/starter/ | Archive - launch material |
| README.md | docs/launch/ | Archive - launch material |

**Action:** All launch/ directory stays in docs/archive/launch/ - historical reference

---

### 10. Research / Task Documents

| Document | Location | Action |
|----------|----------|--------|
| multi-tenancy-patterns.md | docs/research/ | Keep in docs/research/ or archive |
| TASK-6-ADMIN-TENANT-INTEGRATION.md | docs/tasks/ | Keep in docs/tasks/ or archive |

**Action:** Keep as-is in docs/ - research and task tracking

---

### 11. Other / Root Level

| Document | Location | Lines | Action |
|----------|----------|-------|--------|
| README.md | docs/ | 521 | Update to redirect to docs-site |
| DOCS_LINT.md | docs/ | 142 | Migrate to docs-site/content/DOCS_LINT.adoc (already exists) |
| cheatsheet.html | docs/ | 1382 | Archive - generated file |

**Action:** Update README.md, delete/archive others

---

## Library Documentation

**13 libraries, each with:**
- `libs/{library}/AGENTS.md` - AI agent quick reference
- `libs/{library}/README.md` - Library overview and usage

**Total:** 26 files

**Action:** Keep as-is, update links to point to docs-site in Phase 4

---

## Migration Summary

### Files to Migrate (18 total)

#### Getting Started (2)
1. docs/QUICKSTART.md → docs-site/content/getting-started/quickstart.md
2. docs/TUTORIAL.md → docs-site/content/getting-started/tutorial.md

#### Guides (8)
3. docs/OPERATIONS.md → docs-site/content/guides/operations.adoc (convert to .adoc)
4. docs/IDE_SETUP.md → docs-site/content/guides/ide-setup.md
5. docs/guides/AUTHENTICATION.md → docs-site/content/guides/authentication.md
6. docs/guides/DATABASE_SETUP.md → docs-site/content/guides/database-setup.md
7. docs/guides/TESTING.md → docs-site/content/guides/testing.md
8. docs/testing/ADMIN_TESTING_GUIDE.md → docs-site/content/guides/admin-testing.md
9. docs/SECURITY_SETUP.md → docs-site/content/guides/security-setup.md
10. docs/guides/SINGLE_TO_MULTI_TENANT_MIGRATION.md → docs-site/content/guides/tenant-migration.md

#### API Reference (3)
11. docs/API_PAGINATION.md → docs-site/content/api/pagination.md
12. docs/MFA_API_REFERENCE.md → docs-site/content/api/mfa.md
13. docs/SEARCH_API_REFERENCE.md → docs-site/content/api/search.md

#### Reference (1)
14. docs/PUBLISHING_GUIDE.md → docs-site/content/reference/publishing.md

#### Delete (duplicates) (1)
15. docs/guides/mfa-setup.md → DELETE (better version in docs-site)

#### Archive (3)
16. docs/launch/* → docs/archive/launch/
17. docs/DOCS_LINT.md → DELETE (already in docs-site)
18. docs/cheatsheet.html → docs/archive/ or DELETE

---

## Files to Keep in docs/

### After Migration, docs/ will contain:

```
docs/
├── README.md              # Updated redirect to docs-site
├── archive/               # Historical documents
│   ├── AGENTS_FULL.md
│   ├── PHASE_*_COMPLETION.md
│   ├── SECURITY_CONFIGURED.md
│   └── launch/            # Launch materials
├── research/              # Research documents
│   └── multi-tenancy-patterns.md
└── tasks/                 # Task tracking
    └── TASK-6-ADMIN-TENANT-INTEGRATION.md
```

---

## Canonical Versions Decision

For the single duplicate:

| File | Winner | Reason |
|------|--------|--------|
| mfa-setup.md | **docs-site version** | Has Hugo frontmatter, more comprehensive (917 vs 764 lines), better structure, includes code examples upfront |

---

## Content Quality Assessment

### Verbose / Needs Cleanup
- docs-site/content/guides/integrate-observability.adoc (1089 lines) - Very detailed, could be more concise
- docs-site/content/reference/configuration.adoc (1127 lines) - Comprehensive but verbose
- docs/SEARCH_API_REFERENCE.md (1290 lines) - Very detailed API reference

### Well-Structured / Keep As-Is
- ADRs in docs-site/content/adr/ - Good technical depth
- Architecture docs in docs-site/content/architecture/ - Clear and focused
- docs/TUTORIAL.md (1040 lines) - Detailed tutorial is appropriate length

### Missing Hugo Frontmatter
Most docs/ files need frontmatter added when migrated:
```yaml
---
title: "Document Title"
weight: 10
description: "Brief description for SEO"
---
```

---

## Phase 2 Migration Checklist

### Preparation
- [ ] Back up current state
- [ ] Create feature branch: `docs/consolidation`
- [ ] Ensure Hugo is installed and working

### Migration Tasks

#### Getting Started
- [ ] Migrate QUICKSTART.md with frontmatter
- [ ] Migrate TUTORIAL.md with frontmatter
- [ ] Verify navigation order (weight values)

#### Guides
- [ ] Migrate OPERATIONS.md (convert to .adoc)
- [ ] Migrate IDE_SETUP.md
- [ ] Migrate AUTHENTICATION.md
- [ ] Migrate DATABASE_SETUP.md
- [ ] Migrate TESTING.md
- [ ] Migrate ADMIN_TESTING_GUIDE.md
- [ ] Migrate SECURITY_SETUP.md
- [ ] Migrate SINGLE_TO_MULTI_TENANT_MIGRATION.md
- [ ] Delete docs/guides/mfa-setup.md (duplicate)
- [ ] Update _index.md if needed

#### API Reference
- [ ] Migrate API_PAGINATION.md
- [ ] Migrate MFA_API_REFERENCE.md
- [ ] Migrate SEARCH_API_REFERENCE.md
- [ ] Update _index.md

#### Reference
- [ ] Migrate PUBLISHING_GUIDE.md
- [ ] Update _index.md

#### Cleanup
- [ ] Archive docs/launch/* to docs/archive/launch/
- [ ] Delete docs/DOCS_LINT.md (exists in docs-site)
- [ ] Archive or delete docs/cheatsheet.html
- [ ] Update docs/README.md with redirect

### Verification
- [ ] Build Hugo site: `hugo --gc --minify`
- [ ] Check for broken links
- [ ] Test local server: `hugo server`
- [ ] Verify all migrated content renders correctly

---

## Notes

### Format Decisions
- **Use .md** for: Getting started, simple guides, API reference
- **Use .adoc** for: Architecture, ADRs, complex guides with lots of code/diagrams
- **Reason:** Markdown is simpler for most content, AsciiDoc better for technical depth

### Link Updates (Phase 4)
All these will need link updates:
- Root README.md
- Root AGENTS.md
- 13 × libs/*/AGENTS.md files
- 13 × libs/*/README.md files

---

## Estimated Effort

| Task | Estimate |
|------|----------|
| Phase 1 Complete (this document) | ✅ DONE |
| Phase 2 Migration | 2-3 hours |
| Phase 3 Cleanup | 2-3 hours |
| Phase 4 Update References | 1 hour |
| Phase 5 Deprecation | 1 hour |
| Phase 6 Validation | 1 hour |
| **Total Remaining** | **7-9 hours** |

---

**Status:** Phase 1 Complete ✅  
**Next:** Begin Phase 2 Migration  
**Date:** 2026-02-15
