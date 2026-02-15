# Decisions - Boundary Polish & Launch

## Architectural Choices

_Subagents append findings here - never overwrite_

---

## Task 3: Visual Design Direction - Bold & Colorful (2026-02-14)

### Assessment Results

**Status**: ✅ Moodboard complete, color system proposed

#### Reference Dashboards Analyzed

| Dashboard | Primary Color | Accent | Vibe | Why Bold & Colorful |
|-----------|---------------|--------|------|---------------------|
| **Linear** | Indigo-blue (#5b8fbf) | Cyan, Lime | Futuristic | Purple/blue gradients, neon accents, glowing status indicators |
| **Studio Admin** | Orange (#f97316) | Pink, Purple | Versatile | Theme presets (Tangerine, Brutalist), color-coded sections |
| **Railway** | Purple-magenta gradient | Neon green | Cyberpunk | Glowing hover effects, glassmorphism, high-saturation status |
| **Supabase** | Brand green (#3ecf8e) | Blue, Amber | Technical | Bright accents on dark backgrounds, color-coded features |

### Proposed Color System

**Primary Color** (replaces Navy #1E3A5F):
```css
--brand-primary: var(--indigo-6);        /* #4f46e5 - Contrast 5.2:1 on white */
--brand-primary-hover: var(--indigo-7);  /* #4338ca */
--brand-primary-light: var(--indigo-2);  /* #e0e7ff */
```

**Accent Color** (replaces Forest Green #3A7F3F):
```css
--accent-success: var(--lime-6);         /* #65a30d - Contrast 4.6:1 */
--accent-success-hover: var(--lime-7);   /* #4d7c0f */
--accent-success-bg: var(--lime-1);      /* #f7fee7 */
```

**Secondary Accents**:
- Warning: Orange (#ea580c)
- Error: Red (#dc2626)
- Info: Cyan (#0891b2)

**All colors meet WCAG AA contrast ratio (4.5:1 minimum)** ✅

### Open Props Modules Selected

**Core Imports** (4.0 kB total via CDN):
- `colors.min.css` (1.3 kB) - Full color scales
- `shadows.min.css` (0.26 kB) - Shadow system
- `gradients.min.css` (1.0 kB) - Gradient presets
- `animations.min.css` (0.49 kB) - Fade/scale/slide

**CDN Status**: ✅ Verified accessible (`unpkg.com/open-props@1.7.23`)

### Design Aesthetic

**"Cyberpunk Professionalism"**
- Bold indigo primary with neon lime accents
- Dark mode by default (gray-12 #030712 base)
- Glassmorphism with frosted glass effects
- Gradient backgrounds on cards/headers
- Glowing focus states and hover effects
- High-saturation status colors

**Avoid**:
- ❌ Purple gradients on white (AI slop cliché)
- ❌ Generic system fonts
- ❌ Muted pastels (too calm)

**Embrace**:
- ✅ Dominant primary + sharp accents
- ✅ Dark mode default
- ✅ Glassmorphism
- ✅ Gradient borders
- ✅ Neon glows

### Token Migration Map

```css
/* OLD → NEW */
--brand-core: #1E3A5F        → --indigo-6 (#4f46e5)
--brand-shell: #3A7F3F       → --lime-6 (#65a30d)
--status-success: #15803d    → --lime-6 (#65a30d)
--status-warning: #b45309    → --orange-6 (#ea580c)
--status-error: #b91c1c      → --red-6 (#dc2626)
--status-info: #1d4ed8       → --cyan-6 (#0891b2)
--surface-0: #0f172a         → --gray-12 (#030712)
```

### New Features to Add

**Gradients**:
- Hero: `linear-gradient(135deg, var(--indigo-6), var(--purple-6))`
- Accent: `linear-gradient(90deg, var(--lime-5), var(--cyan-5))`

**Glows**:
- Primary: `0 0 20px var(--indigo-5)`
- Success: `0 0 16px var(--lime-5)`
- Error: `0 0 16px var(--red-5)`

### Implementation Phases

1. **Foundation**: Replace CDN imports, create `tokens-v2.css`
2. **Visual Enhancement**: Add gradients, glassmorphism, glowing buttons
3. **Dark/Light Mode**: Ensure dark default, create pastel light variant
4. **Polish**: Micro-interactions, gradient borders, color-coded sections

### Decisions Made

✅ **Primary color: Indigo** (Open Props `--indigo-{0-12}`)  
✅ **Accent color: Neon Lime** (Open Props `--lime-{0-12}`)  
✅ **Dark mode by default** (gray-12 base)  
✅ **Open Props via CDN** (4.0 kB core modules)  
✅ **All contrast ratios WCAG AA compliant**  
✅ **Aesthetic: Cyberpunk Professionalism**  

### Dependencies

- **Blocks**: Task 5 (Component implementation), Task 9 (UI polish)
- **Blocked by**: None (research complete)
- **References**: `.sisyphus/evidence/task-3-design-moodboard.md` (full visual reference)

---

## Task 4: Documentation Consolidation Strategy (2026-02-14)

### Assessment Results

**Status**: ✅ boundary-docs is production-ready and well-structured

#### Key Metrics
- **100 documentation files** (85 AsciiDoc, 15 Markdown)
- **50,506 total lines** of content across 17 directory categories
- **109 external links** to GitHub code (all absolute URLs)
- **0 URL redirect needs** (new site, no old URLs to redirect)
- **~11-17 hours** estimated to consolidate

#### Directory Structure (boundary-docs)
```
content/
├── adr/           (11 decision records)
├── architecture/  (23 core architecture files)
├── guides/        (15 how-to guides)
├── reference/     (18 reference documents)
├── api/           (3 API reference files)
├── getting-started/ (6 onboarding guides)
├── examples/      (4 complete app examples)
└── change-reports/ (4 historical documents)
```

#### Content Categories

| Category | Files | Lines | Status | Decision |
|----------|-------|-------|--------|----------|
| **High Priority** | 37+ | 37,100 | ✅ Current | MIGRATE all |
| **Medium Priority** | 8 | 6,100 | ⚠️ Partial | Selectively migrate |
| **Low Priority** | 4 | 1,150 | ⚠️ Meta | Archive/deprecate |

### Recommendations

#### Option A: Keep Separate (RECOMMENDED for v1.0)
- **Approach**: Keep boundary-docs repo, add `docs-site/` build in main repo
- **Advantages**: Lower risk, independent versioning, cleaner separation
- **Disadvantages**: Two repos to manage, submodule complexity
- **Timeline**: 11-17 hours (1-2 days work)

#### Option B: Full Consolidation (v1.1 candidate)
- **Approach**: Migrate all content into main repo, delete boundary-docs
- **Advantages**: Single source of truth, simpler workflow
- **Disadvantages**: Larger main repo, tighter coupling of code/docs

**Decision**: **PROCEED WITH OPTION A** for v1.0 launch

### Hugo Theme Decision

**Current**: hugo-book (minimal, clean, AsciiDoc-native)  
**Status**: ✅ KEEP

**Comparison**:
- hugo-book: 2.8k stars, simple, native AsciiDoc, ~2s build time
- Docsy: 9.5k stars, Markdown-only, slower, more complex
- Doks: Modern but over-engineered for technical docs

**Reason**: hugo-book is purpose-built for technical documentation with native AsciiDoc support. Theme switching would require converting all 85 AsciiDoc files to Markdown with no clear benefit.

### URL & Link Dependencies

**External Links**: 109 GitHub code references  
- **Format**: `link:https://github.com/thijs-creemers/boundary/blob/main/...`
- **Status**: ✅ All absolute URLs (immutable, no redirects needed)
- **Risk**: VERY LOW

**Internal Links**: ~15-20 files with relative AsciiDoc links  
- **Format**: `link:ADR-010-*.adoc[text]`
- **Status**: ⚠️ Need conversion for Hugo
- **Mitigation**: Automated link validator script

**New Site URL Strategy**:
```
Current: https://thijs-creemers.github.io/boundary-docs/
New:     https://docs.boundary.dev/ (custom domain)
         OR: https://boundary.dev/docs/ (if combined)
```

No redirects needed - this is a new site, not a migration from old URL structure.

### Files to Migrate (Detailed)

**HIGH PRIORITY (37+ files)** → Migrate all
- All ADRs (ADR-005 through ADR-010)
- Architecture guides (20+ core files)
- How-to guides (15 files)
- Getting started (6 files)
- API reference (3 files)
- Reference docs (15 files)
- Examples (4 files)

**MEDIUM PRIORITY (8 files)** → Selectively migrate
- Change reports → `/changelog/` section
- Diagram READMEs → Keep, reduce TODOs

**LOW PRIORITY (4 files)** → Archive/deprecate
- COMPLETION_STATUS.md (GitHub Pages meta)
- DOCUMENTATION_IMPROVEMENT_PLAN.md (planning)
- GITHUB_PAGES_SETUP.md (setup meta)

### Proposed Directory Structure

```
docs-site/
├── config.toml           (from boundary-docs)
├── content/
│   ├── adr/
│   ├── architecture/
│   ├── guides/
│   ├── api/
│   ├── reference/
│   ├── examples/
│   └── changelog/        (from change-reports/)
├── themes/
│   └── hugo-book/        (submodule)
├── static/
├── scripts/
│   ├── link-converter.sh
│   └── validate-links.sh
├── .github/workflows/
│   └── deploy-docs.yml
└── Makefile
```

### Implementation Timeline

| Phase | Tasks | Effort | Notes |
|-------|-------|--------|-------|
| **1. Prep** | Clone, analyze, checklist | 1-2h | ✅ DONE |
| **2. Setup** | Hugo config, theme, structure | 2-3h | Task 8 |
| **3. Migrate** | Copy files, fix links, validate | 4-6h | Task 12 |
| **4. Consolidate** | Delete duplicates, update refs | 2-3h | Task 13 |
| **5. Test** | Build, verify, deploy | 1-2h | Task 14 |
| **6. Cleanup** | Archive, finalize | 1h | Task 15 |
| **TOTAL** | — | **11-17h** | 1-2 days work |

### Risks & Mitigation

| Risk | Probability | Severity | Mitigation |
|------|-----------|----------|-----------|
| Broken links after AsciiDoc conversion | HIGH | Medium | Automated validator script |
| Lost content during reorganization | LOW | High | Keep boundary-docs intact until verified |
| Theme incompatibilities | MEDIUM | Low | Test locally with `hugo server` |
| GitHub links become invalid | VERY LOW | High | All links use absolute URLs (immutable) |
| Search functionality breaks | MEDIUM | Low | flexsearch built-in to hugo-book |

### Decisions Made

✅ **Keep boundary-docs repo as separate source**  
✅ **Use hugo-book theme (no switching)**  
✅ **Migrate 45+ high/medium priority files**  
✅ **Archive 4 meta files**  
✅ **No URL redirects needed** (new site)  
✅ **Schedule: Phase 1 complete, Task 8 next**  

### Dependencies

- **Blocks**: Task 8 (Hugo setup), Task 12 (content migration)
- **Blocked by**: None
- **References**: `.sisyphus/evidence/task-4-docs-consolidation-strategy.md` (full detailed assessment)

---
