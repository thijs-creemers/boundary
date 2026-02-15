# Documentation Consolidation Plan

## Current State Analysis

### Three Documentation Locations

1. **`boundary-docs` repository** (separate GitHub repo)
   - Hugo-based documentation site
   - Published to GitHub Pages
   - Production-ready with search, navigation
   - Contains ~95 migrated files

2. **`docs-site/` directory** (in main repo)
   - Hugo-based site (duplicate of boundary-docs)
   - 124+ content files
   - Hugo Book theme as git submodule
   - Built for local/GitHub Pages deployment

3. **`docs/` directory** (in main repo)
   - 41 Markdown files
   - Mix of guides, API references, operations docs
   - No static site generator
   - Simple markdown documentation

### Content Overlap

| Content Type | boundary-docs | docs-site/ | docs/ |
|-------------|---------------|------------|-------|
| Architecture guides | ✅ | ✅ | Partial |
| ADRs | ✅ | ✅ | ❌ |
| Getting Started | ✅ | ✅ | ✅ (QUICKSTART.md) |
| API Reference | ✅ | ✅ | ✅ (partial) |
| MFA Guide | ✅ | ✅ | ✅ (different versions) |
| Operations | ❌ | ❌ | ✅ (OPERATIONS.md) |
| IDE Setup | ❌ | ❌ | ✅ (IDE_SETUP.md) |
| Tutorial | ❌ | ❌ | ✅ (TUTORIAL.md) |

### Key Issues

1. **Duplication**: Same content in 2-3 locations (e.g., mfa-setup.md)
2. **Inconsistency**: Different formats (.md vs .adoc), different detail levels
3. **Confusion**: Contributors don't know where to add/update docs
4. **Maintenance burden**: Updates need to be made in multiple places
5. **Outdated content**: Some docs reference old structure

---

## Recommended Strategy: Single Source of Truth

### Goal

- **One location** for all documentation
- **Clear, concise** content (eliminate verbosity)
- **Easy to maintain** (simple tooling)
- **Good UX** (searchable, navigable, versioned)

### Proposed Solution: Consolidate to `docs-site/`

**Rationale:**
- Hugo provides professional documentation UX (search, navigation, mobile-friendly)
- Already set up with theme and deployment
- Supports both Markdown (.md) and AsciiDoc (.adoc)
- Can be published to GitHub Pages automatically
- Lives in main repo (no separate boundary-docs repo needed)

---

## Implementation Plan

### Phase 1: Audit & Inventory (1-2 hours)

**Goal**: Understand exactly what content exists and where

#### Tasks

1. **Create content inventory spreadsheet**
   ```
   Document | docs/ | docs-site/ | boundary-docs | Status | Action
   ```

2. **Identify canonical versions**
   - For each duplicated doc, determine which version is most up-to-date
   - Mark outdated/obsolete content for archival

3. **Categorize documentation**
   - Getting Started (quickstart, tutorial)
   - Guides (how-to, best practices)
   - Architecture (ADRs, design docs)
   - API Reference
   - Operations (deployment, monitoring)
   - Library-specific (libs/*/AGENTS.md, libs/*/README.md)

#### Deliverable

- `DOCS_INVENTORY.csv` with complete audit

---

### Phase 2: Content Migration (2-4 hours)

**Goal**: Move all content to `docs-site/content/`

#### Tasks

1. **Migrate from `docs/` to `docs-site/content/`**
   
   | Source | Destination | Notes |
   |--------|-------------|-------|
   | `docs/QUICKSTART.md` | `docs-site/content/getting-started/quickstart.md` | Convert to .adoc if needed |
   | `docs/TUTORIAL.md` | `docs-site/content/getting-started/tutorial.md` | Keep as .md |
   | `docs/OPERATIONS.md` | `docs-site/content/guides/operations.adoc` | Convert to .adoc |
   | `docs/IDE_SETUP.md` | `docs-site/content/guides/ide-setup.md` | Keep as .md |
   | `docs/API_PAGINATION.md` | `docs-site/content/api/pagination.md` | Keep as .md |
   | `docs/MFA_API_REFERENCE.md` | `docs-site/content/api/mfa.md` | Keep as .md |
   | `docs/guides/*` | `docs-site/content/guides/` | Merge with existing |

2. **Reconcile duplicates**
   - For `mfa-setup.md`: Choose canonical version (likely docs-site version)
   - For architecture docs: Use docs-site versions (already migrated from boundary-docs)
   - Delete duplicates after migration

3. **Update internal links**
   - Change `../docs/QUICKSTART.md` → `/getting-started/quickstart`
   - Update AGENTS.md references to point to docs-site
   - Update README.md to point to docs-site

4. **Archive old content**
   - Move `docs/archive/` content to `docs-site/content/archive/` if still relevant
   - Delete obsolete content (PHASE_*_COMPLETION.md can stay in docs/archive/)

#### Deliverable

- All content in `docs-site/content/`
- `docs/` contains only:
  - `README.md` (redirect to docs-site)
  - `archive/` (historical records)

---

### Phase 3: Content Cleanup (2-3 hours)

**Goal**: Make documentation clear, concise, and consistent

#### Tasks

1. **Reduce verbosity**
   - Cut unnecessary preamble/fluff
   - Use tables instead of prose where appropriate
   - Remove redundant explanations
   - Focus on "what" and "how", minimize "why" unless critical

2. **Standardize format**
   - Use `.adoc` for architecture/ADRs/complex guides
   - Use `.md` for simple guides, getting started, API reference
   - Consistent heading structure
   - Consistent code block formatting

3. **Improve navigation**
   - Update `_index.md` files with clear section descriptions
   - Set appropriate `weight` values for ordering
   - Add breadcrumbs where helpful

4. **Update frontmatter**
   - Ensure all pages have `title` and `weight`
   - Add `description` for SEO
   - Add `draft: false` where needed

5. **Review library-specific docs**
   - Keep `libs/*/README.md` (library overview, usage)
   - Keep `libs/*/AGENTS.md` (AI agent quick reference)
   - Ensure they link to docs-site for detailed guides

#### Deliverable

- Clean, concise documentation in `docs-site/`
- No redundant content
- Consistent formatting

---

### Phase 4: Update References (1 hour)

**Goal**: Ensure all references point to new location

#### Tasks

1. **Update root README.md**
   ```markdown
   ## Documentation
   
   Complete documentation is available at:
   - **Online**: https://thijs-creemers.github.io/boundary/ (GitHub Pages)
   - **Local**: `cd docs-site && hugo server`
   
   Quick reference: See [AGENTS.md](./AGENTS.md) for commands and patterns.
   ```

2. **Update AGENTS.md**
   - Change documentation links to point to docs-site
   - Keep it as a concise quick reference (commands, common patterns)
   - Link to docs-site for detailed guides

3. **Update library AGENTS.md files**
   - Change links from `docs/` to `docs-site/content/`
   - Use relative paths where possible

4. **Update docs/README.md**
   ```markdown
   # Documentation
   
   **Documentation has moved to `docs-site/`**
   
   View documentation:
   - Online: https://thijs-creemers.github.io/boundary/
   - Local: `cd docs-site && hugo server`
   
   This directory contains only archived historical records.
   ```

5. **Update CONTRIBUTING.md** (if exists)
   - Document where to add new docs (`docs-site/content/`)
   - How to test locally (`hugo server`)
   - Markdown vs AsciiDoc guidelines

#### Deliverable

- All references point to `docs-site/`
- Clear instructions for contributors

---

### Phase 5: Deprecate Old Locations (1 hour)

**Goal**: Remove confusion, single source of truth

#### Tasks

1. **Archive `docs/` directory**
   ```bash
   # Keep only:
   docs/
   ├── README.md          (redirect message)
   └── archive/           (historical records)
   ```
   
2. **Handle boundary-docs repository**
   
   **Option A: Archive it**
   - Add deprecation notice to README
   - Point to main repo's docs-site
   - Archive the GitHub repository
   
   **Option B: Make it a mirror**
   - Set up CI to sync from main repo's docs-site
   - Useful if external sites link to boundary-docs
   
   **Recommended: Option A** (simpler, less maintenance)

3. **Update GitHub repository description**
   - Main repo: "Documentation available at docs-site/"
   - boundary-docs: "⚠️ ARCHIVED - See main repository"

#### Deliverable

- Single source of truth: `docs-site/`
- Old locations archived with clear redirects

---

### Phase 6: Validation & Testing (1 hour)

**Goal**: Ensure everything works

#### Tasks

1. **Build Hugo site**
   ```bash
   cd docs-site
   hugo --gc --minify
   ```
   - Verify no broken links
   - Check all internal references resolve

2. **Test locally**
   ```bash
   hugo server
   ```
   - Navigate through all sections
   - Test search functionality
   - Verify mobile responsiveness

3. **Review content**
   - Spot-check 10-20 pages for accuracy
   - Verify code examples work
   - Check links to external resources

4. **Deploy to GitHub Pages**
   - Commit changes
   - Verify CI/CD deploys successfully
   - Check live site

#### Deliverable

- Working docs-site with all content
- No broken links
- Successfully deployed

---

## Maintenance Going Forward

### Documentation Structure

```
boundary/
├── README.md                    # Project overview, links to docs-site
├── AGENTS.md                    # Quick reference (commands, patterns)
├── CONTRIBUTING.md              # How to contribute (including docs)
│
├── docs-site/                   # 📚 PRIMARY DOCUMENTATION
│   ├── content/
│   │   ├── getting-started/    # Quickstart, tutorial
│   │   ├── guides/             # How-to guides
│   │   ├── architecture/       # ADRs, design docs
│   │   ├── api/                # API reference
│   │   ├── reference/          # Configuration, CLI
│   │   └── archive/            # Historical docs
│   ├── hugo.toml
│   └── README.md               # Hugo setup instructions
│
└── libs/
    ├── core/
    │   ├── README.md           # Library overview & usage
    │   └── AGENTS.md           # AI agent quick ref (links to docs-site)
    └── ...
```

### Rules for Contributors

1. **All new docs** go in `docs-site/content/`
2. **Use .adoc** for architecture, ADRs, complex guides
3. **Use .md** for getting started, simple guides, API reference
4. **Test locally** with `hugo server` before committing
5. **Keep AGENTS.md concise** - it's a quick reference, not a tutorial
6. **Link to docs-site** for detailed explanations

### Update Process

1. Edit content in `docs-site/content/`
2. Test: `cd docs-site && hugo server`
3. Commit and push
4. CI/CD auto-deploys to GitHub Pages

---

## Effort Estimate

| Phase | Time | Dependencies |
|-------|------|--------------|
| Phase 1: Audit | 1-2h | None |
| Phase 2: Migration | 2-4h | Phase 1 complete |
| Phase 3: Cleanup | 2-3h | Phase 2 complete |
| Phase 4: Update References | 1h | Phase 3 complete |
| Phase 5: Deprecation | 1h | Phase 4 complete |
| Phase 6: Validation | 1h | Phase 5 complete |
| **Total** | **8-12h** | Sequential |

**Can be done over 2-3 work sessions.**

---

## Alternative: Minimal Approach

If full consolidation is too much work right now:

### Quick Win (2-3 hours)

1. **Designate `docs-site/` as canonical**
   - Update root README.md to say "Primary docs at docs-site/"
   
2. **Add deprecation notices**
   - `docs/README.md`: "⚠️ See docs-site/ for up-to-date documentation"
   - boundary-docs: "⚠️ Archived - See main repo"

3. **Copy critical missing content**
   - Copy OPERATIONS.md, IDE_SETUP.md, TUTORIAL.md to docs-site/
   - Don't worry about cleanup yet

4. **Stop updating old locations**
   - All new docs go to docs-site/ only

### Pros
- Quick to implement
- Reduces confusion immediately
- Can do full consolidation later

### Cons
- Still have duplication/inconsistency
- Need to remember to ignore docs/

---

## Recommendation

**Proceed with Full Consolidation (8-12 hours)**

**Reasoning:**
- Clean break, no ongoing confusion
- Better long-term maintainability
- Professional documentation UX (Hugo site)
- Only needs to be done once
- 8-12 hours is reasonable for project of this size

**Schedule:**
- **Session 1** (3-4h): Phase 1 + Phase 2 (Audit + Migration)
- **Session 2** (3-4h): Phase 3 + Phase 4 (Cleanup + References)
- **Session 3** (2-4h): Phase 5 + Phase 6 (Deprecation + Validation)

---

## Next Steps

1. **Review this plan** - Approve approach
2. **Create tracking issue** - Break down into subtasks
3. **Schedule consolidation** - Block out time
4. **Execute phases** - Follow plan sequentially
5. **Announce change** - Update contributors/users

**Ready to proceed?**
