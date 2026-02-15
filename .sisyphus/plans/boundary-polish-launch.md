# Boundary Framework - Polish & Launch Preparation Plan

## TL;DR

> **Quick Summary**: Transform Boundary Framework from feature-complete to launch-ready by polishing the admin UI with bold, colorful Open Props styling, publishing all libraries to Clojars as 1.0.0, consolidating documentation into a single AsciiDoc-powered Hugo site, and creating compelling elevator pitches plus an interactive cheat-sheet.
> 
> **Deliverables**:
> - Refreshed admin UI with vibrant color palette (Open Props CSS)
> - GitHub Actions workflow publishing 10 libraries to Clojars (lockstep 1.0.0)
> - Consolidated documentation site (boundary-docs merged into main repo)
> - Developer elevator pitch (technical focus, ~150 words)
> - Management elevator pitch (business value, ~100 words)
> - Interactive searchable cheat-sheet (vanilla HTML/CSS/JS)
> 
> **Estimated Effort**: Large (1 month sprint)
> **Parallel Execution**: YES - 4 waves after Week 1 blockers resolved
> **Critical Path**: Blocker Validation → Clojars Publish → Pitches

---

## Context

### Original Request
User wants to make Boundary Framework "irresistible for devs and companies" by polishing:
1. Admin frontend with stunning CSS
2. GitHub Actions for Clojars publishing
3. Great, humanized documentation
4. Developer elevator pitch
5. Management/customer elevator pitch
6. Boundary cheat-sheet

### Interview Summary
**Key Discussions**:
- **CSS Direction**: Bold & colorful using Open Props (CSS custom properties, no build step)
- **Color Palette**: Refresh to more vibrant colors (current is "functional, calm" - pivot to bold)
- **Versioning**: Lockstep versioning - all libraries share version number (1.0.0)
- **Documentation**: Consolidate boundary-docs repo into main repo, AsciiDoc → Hugo
- **Priority**: Admin UI first, then parallel work on remaining deliverables
- **Timeline**: 1 month focused sprint

**Research Findings**:
- Admin UI has solid foundation: 57 Hiccup components, Alpine.js, HTMX, 50+ Lucide icons
- CSS uses Pico CSS base + custom design tokens (`resources/public/css/tokens.css`)
- 10 libraries have `build.clj` ready; 3 missing (email, tenant, external)
- CI pipeline exists (`.github/workflows/ci.yml`), but NO publish workflow
- AGENTS.md is excellent (24KB), but ~0 inline docstrings in 220+ functions
- TUTORIAL.md and examples marked "Coming Soon"

### Metis Review
**Identified Gaps** (addressed):
- Need to validate Clojars credentials and GPG setup BEFORE publishing tasks
- Need to count actual docstrings vs assumed ~220
- Need visual reference for "bold & colorful" (design direction ambiguous)
- Missing acceptance criteria for accessibility and browser support
- Scope creep risk: component refactoring, layout changes, tutorial writing

---

## Work Objectives

### Core Objective
Polish Boundary Framework for 1.0.0 launch - making it irresistible for developers and companies through stunning visuals, easy installation (Clojars), excellent documentation, and compelling messaging.

### Concrete Deliverables
1. **Refreshed Admin UI**: Updated `tokens.css` + `admin.css` with Open Props, vibrant color palette
2. **Clojars Publishing**: `.github/workflows/publish.yml` deploying 10 libraries as 1.0.0
3. **Documentation Site**: Hugo-powered site with consolidated content from boundary-docs
4. **Developer Pitch**: 150-word technical elevator pitch (README section)
5. **Management Pitch**: 100-word business value pitch (README section)
6. **Interactive Cheat-sheet**: Single-page HTML with search, derived from AGENTS.md

### Definition of Done
- [ ] All 10 libraries published to Clojars: `clojure -Sdeps '{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0"}}}' -e '(require '\''boundary.core.validation)'` → success
- [ ] Admin UI renders without console errors: `curl -s http://localhost:3000/admin | grep -q "admin-shell"`
- [ ] Hugo docs build clean: `hugo --gc --minify 2>&1 | grep -c error` → 0
- [ ] Cheat-sheet search works: Playwright test with 100ms response time
- [ ] Both pitches reviewed and approved by user

### Must Have
- All 10 libraries published to Clojars with lockstep version 1.0.0
- Admin UI with refreshed color palette (Open Props)
- Dark mode preserved and functional
- Documentation consolidated into single repo
- Interactive cheat-sheet with client-side search
- Both elevator pitches in README

### Must NOT Have (Guardrails)
- NO CSS build tooling (PostCSS, Tailwind, Sass) - Open Props CDN/static only
- NO Hiccup component signature changes - CSS-only modifications
- NO layout changes to admin UI - color and styling only
- NO publishing `external` library (skeleton, not ready)
- NO tutorials or example projects - reference docs only
- NO library code changes while publishing - publish current state
- NO marketing website or landing pages - README sections only
- NO JavaScript framework additions - vanilla JS only for cheat-sheet

---

## Verification Strategy (MANDATORY)

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> ALL tasks in this plan MUST be verifiable WITHOUT any human action.
> ALL verification is executed by the agent using tools (Playwright, Bash, curl, etc.).

### Test Decision
- **Infrastructure exists**: YES (existing test suite with Kaocha)
- **Automated tests**: NO (this is UI/docs/tooling work, not code logic)
- **Framework**: Existing `clojure -M:test:db/h2` for regression only

### Agent-Executed QA Scenarios (MANDATORY — ALL tasks)

**Verification Tool by Deliverable Type:**

| Type | Tool | How Agent Verifies |
|------|------|-------------------|
| **Admin UI/CSS** | Playwright | Navigate pages, screenshot, verify no console errors |
| **Clojars Publishing** | Bash (curl/clojure) | Verify artifact exists, test dependency resolution |
| **Documentation** | Bash (hugo) | Build site, verify no errors, check links |
| **Cheat-sheet** | Playwright | Load page, test search, verify responsiveness |
| **Pitches** | Bash (grep) | Verify word count, presence in README |

---

## Execution Strategy

### Parallel Execution Waves

```
Week 1 - Blockers (Sequential):
├── Task 1: Validate Clojars credentials + GPG setup
├── Task 2: Count actual docstrings needing addition
├── Task 3: Create visual reference moodboard for bold/colorful
└── Task 4: Assess boundary-docs consolidation strategy

Week 2 - Foundation (Parallel):
├── Task 5: Create missing build.clj (email, tenant)
├── Task 6: Create publish.yml GitHub Actions workflow
├── Task 7: Design new color palette with Open Props
└── Task 8: Set up Hugo site structure

Week 2-3 - Implementation (Parallel):
├── Task 9: Apply new CSS to admin UI
├── Task 10: Test and execute first Clojars publish
├── Task 11: Add docstrings to public functions
└── Task 12: Migrate boundary-docs content

Week 3-4 - Completion (Parallel after critical path):
├── Task 13: Write developer elevator pitch
├── Task 14: Write management elevator pitch  
├── Task 15: Build interactive cheat-sheet
└── Task 16: Final polish and verification

Critical Path: Task 1 → Task 6 → Task 10 → Task 13/14
```

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|---------------------|
| 1 (Clojars validation) | None | 6, 10 | 2, 3, 4 |
| 2 (Docstring count) | None | 11 | 1, 3, 4 |
| 3 (Visual moodboard) | None | 7, 9 | 1, 2, 4 |
| 4 (Docs strategy) | None | 8, 12 | 1, 2, 3 |
| 5 (build.clj files) | 1 | 10 | 6, 7, 8 |
| 6 (publish.yml) | 1 | 10 | 5, 7, 8 |
| 7 (Color palette) | 3 | 9 | 5, 6, 8 |
| 8 (Hugo setup) | 4 | 12 | 5, 6, 7 |
| 9 (CSS implementation) | 7 | 16 | 10, 11, 12 |
| 10 (Clojars publish) | 5, 6 | 13, 14 | 9, 11, 12 |
| 11 (Docstrings) | 2 | 16 | 9, 10, 12 |
| 12 (Docs migration) | 8 | 16 | 9, 10, 11 |
| 13 (Dev pitch) | 10 | 16 | 14, 15 |
| 14 (Mgmt pitch) | 10 | 16 | 13, 15 |
| 15 (Cheat-sheet) | None | 16 | 13, 14 |
| 16 (Final polish) | 9, 11, 12, 13, 14, 15 | None | None (final) |

### Agent Dispatch Summary

| Wave | Tasks | Recommended Approach |
|------|-------|---------------------|
| Week 1 | 1, 2, 3, 4 | `task(category="quick", ...)` - validation only |
| Week 2a | 5, 6 | `task(category="unspecified-low", load_skills=["git-master"])` |
| Week 2b | 7, 8 | `task(category="visual-engineering", load_skills=["frontend-ui-ux"])` |
| Week 2-3 | 9, 10, 11, 12 | Mixed categories based on task |
| Week 3-4 | 13, 14, 15, 16 | `task(category="writing")` for pitches |

---

## TODOs

### Week 1: Blocker Validation

- [x] 1. Validate Clojars Credentials and GPG Setup

  **What to do**:
  - Check if `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` can be obtained/configured
  - Verify GPG key is set up for JAR signing (required by deps-deploy)
  - Verify `io.github.thijs-creemers` group is claimed on Clojars
  - Test dry-run publish of boundary-core to verify setup works
  - Document any missing setup steps

  **Must NOT do**:
  - Actually publish anything yet
  - Modify library code
  - Create GitHub secrets (document what's needed only)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Validation task, no implementation, just checking prerequisites
  - **Skills**: `[]`
    - No special skills needed - bash commands and web verification

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 4)
  - **Blocks**: Tasks 5, 6, 10
  - **Blocked By**: None

  **References**:
  - `docs/PUBLISHING_GUIDE.md` - Existing manual publishing instructions
  - `libs/core/build.clj:deploy` - Deploy function with deps-deploy usage
  - `deps.edn:build` alias - deps-deploy dependency configuration
  - https://github.com/slipset/deps-deploy - deps-deploy documentation
  - https://clojars.org/tokens - Clojars deploy token setup

  **Acceptance Criteria**:
  - [ ] Document in `.sisyphus/evidence/task-1-clojars-validation.md`:
    - Clojars account status (exists/needs creation)
    - GPG key status (exists/needs generation)
    - Group ownership status (verified/needs claim)
    - Any blockers identified

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify Clojars group page exists
    Tool: Bash (curl)
    Preconditions: None
    Steps:
      1. curl -s -o /dev/null -w "%{http_code}" https://clojars.org/groups/io.github.thijs-creemers
      2. Assert: HTTP status is 200 OR 404
      3. Document result in evidence file
    Expected Result: HTTP status captured
    Evidence: .sisyphus/evidence/task-1-clojars-validation.md

  Scenario: Verify build.clj deploy function syntax
    Tool: Bash (clojure)
    Preconditions: In libs/core directory
    Steps:
      1. clojure -T:build clean
      2. clojure -T:build jar
      3. Assert: JAR file created in target/
      4. Document JAR path
    Expected Result: JAR builds successfully
    Evidence: Terminal output captured
  ```

  **Commit**: NO

---

- [x] 2. Count Actual Docstrings Needing Addition

  **What to do**:
  - Write REPL script to count public functions across all libraries
  - Count how many have docstrings vs don't
  - Generate report by library: `{library: {total: N, with_doc: M, without_doc: P}}`
  - Identify which libraries have best/worst coverage
  - Prioritize: Start with core, platform (most used)

  **Must NOT do**:
  - Add any docstrings yet
  - Modify any code
  - Change function signatures

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: REPL scripting task, analysis only
  - **Skills**: `[]`
    - Standard Clojure REPL usage

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3, 4)
  - **Blocks**: Task 11
  - **Blocked By**: None

  **References**:
  - `libs/core/src/boundary/core/` - Core library public functions
  - `libs/platform/src/boundary/platform/` - Platform library
  - Clojure `ns-publics`, `meta`, `:doc` - For counting docstrings

  **Acceptance Criteria**:
  - [ ] Report saved to `.sisyphus/evidence/task-2-docstring-audit.md`
  - [ ] Total count of public functions without docstrings
  - [ ] Breakdown by library
  - [ ] Priority list for docstring addition

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Count docstrings via REPL
    Tool: Bash (clojure REPL)
    Preconditions: REPL starts successfully
    Steps:
      1. Start REPL: clojure -M:repl-clj
      2. Load all library namespaces
      3. Run count script:
         (doseq [ns-sym ['boundary.core.validation 'boundary.platform.shell.http]]
           (require ns-sym)
           (println ns-sym (count (filter #(nil? (:doc (meta %))) (vals (ns-publics ns-sym))))))
      4. Capture output
    Expected Result: Numeric counts per namespace
    Evidence: .sisyphus/evidence/task-2-docstring-audit.md
  ```

  **Commit**: NO

---

- [x] 3. Create Visual Reference Moodboard for Bold/Colorful Design

  **What to do**:
  - Research modern admin dashboard designs (Linear, Notion, Vercel, Stripe)
  - Identify 3-5 reference designs that exemplify "bold & colorful"
  - Document color combinations, accent usage, contrast patterns
  - Map to Open Props modules: which to use (colors, gradients, shadows, animations)
  - Create design direction document with:
    - Primary color suggestion (replacing Navy #1E3A5F)
    - Accent color(s) (replacing Forest Green #3A7F3F)
    - Dark mode considerations
    - Example color combinations

  **Must NOT do**:
  - Implement any CSS yet
  - Create elaborate mockups
  - Use paid design tools

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Design research and visual direction
  - **Skills**: `["frontend-ui-ux"]`
    - UI/UX expertise for color theory and design patterns

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 4)
  - **Blocks**: Task 7
  - **Blocked By**: None

  **References**:
  - `resources/public/css/tokens.css` - Current design tokens to replace
  - https://open-props.style/ - Open Props documentation
  - https://open-props.style/#colors - Open Props color system
  - Current brand: Navy (#1E3A5F), Forest Green (#3A7F3F), Slate Gray (#4B5563)

  **Acceptance Criteria**:
  - [ ] Moodboard document at `.sisyphus/evidence/task-3-design-moodboard.md`
  - [ ] 3-5 reference links with annotations
  - [ ] Proposed primary + accent color hex codes
  - [ ] Open Props modules to include listed
  - [ ] Dark mode color variants proposed

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify Open Props CDN availability
    Tool: Bash (curl)
    Preconditions: None
    Steps:
      1. curl -s -o /dev/null -w "%{http_code}" https://unpkg.com/open-props
      2. Assert: HTTP status is 200
    Expected Result: Open Props CDN accessible
    Evidence: Terminal output captured

  Scenario: Document current color tokens
    Tool: Bash (grep)
    Preconditions: tokens.css exists
    Steps:
      1. grep -E "^\s*--.*:" resources/public/css/tokens.css | head -50
      2. Document current color variables
    Expected Result: Current tokens captured
    Evidence: .sisyphus/evidence/task-3-design-moodboard.md
  ```

  **Commit**: NO

---

- [x] 4. Assess boundary-docs Consolidation Strategy

  **What to do**:
  - Clone/examine boundary-docs repository structure
  - Document current AsciiDoc files and organization
  - Identify content to migrate vs deprecate
  - Propose directory structure in main repo (`docs/` reorganization)
  - Check for URL/link dependencies that need redirects
  - Document Hugo theme requirements

  **Must NOT do**:
  - Actually migrate any content
  - Delete anything from boundary-docs
  - Set up Hugo site yet

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Repository analysis, documentation only
  - **Skills**: `[]`
    - Standard file system navigation

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3)
  - **Blocks**: Task 8
  - **Blocked By**: None

  **References**:
  - https://github.com/thijs-creemers/boundary-docs - External docs repo
  - `docs/` directory - Current internal docs structure
  - `AGENTS.md` - References to boundary-docs content

  **Acceptance Criteria**:
  - [ ] Strategy document at `.sisyphus/evidence/task-4-docs-consolidation-strategy.md`
  - [ ] List of files to migrate with target paths
  - [ ] Hugo theme recommendation
  - [ ] Redirect requirements (if any)
  - [ ] Timeline estimate for migration

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Analyze boundary-docs structure
    Tool: Bash (git + ls)
    Preconditions: Can access GitHub
    Steps:
      1. Clone boundary-docs to temp directory
      2. find . -name "*.adoc" | wc -l
      3. find . -name "*.md" | wc -l
      4. Document file types and counts
    Expected Result: File inventory captured
    Evidence: .sisyphus/evidence/task-4-docs-consolidation-strategy.md
  ```

  **Commit**: NO

---

### Week 2: Foundation Work

- [x] 5. Create Missing build.clj Files (email, tenant)

  **What to do**:
  - Create `libs/email/build.clj` following pattern from `libs/core/build.clj`
  - Create `libs/tenant/build.clj` following same pattern
  - Update artifact names: `boundary-email`, `boundary-tenant`
  - Verify both can build JARs locally: `clojure -T:build jar`
  - Verify local install works: `clojure -T:build install`

  **Must NOT do**:
  - Create build.clj for `external` (skeleton, not ready)
  - Modify any library code
  - Publish to Clojars yet

  **Recommended Agent Profile**:
  - **Category**: `unspecified-low`
    - Reason: Straightforward file creation following existing pattern
  - **Skills**: `[]`
    - Standard Clojure knowledge

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2a (with Task 6)
  - **Blocks**: Task 10
  - **Blocked By**: Task 1

  **References**:
  - `libs/core/build.clj` - Template to copy (all standard fields present)
  - `libs/email/deps.edn` - Email library dependencies
  - `libs/tenant/deps.edn` - Tenant library dependencies
  - Pattern: `io.github.thijs-creemers/boundary-{name}` naming convention

  **Acceptance Criteria**:
  - [ ] `libs/email/build.clj` created and functional
  - [ ] `libs/tenant/build.clj` created and functional
  - [ ] `cd libs/email && clojure -T:build jar` → JAR in target/
  - [ ] `cd libs/tenant && clojure -T:build jar` → JAR in target/

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Email library builds JAR
    Tool: Bash
    Preconditions: build.clj created
    Steps:
      1. cd libs/email
      2. clojure -T:build clean
      3. clojure -T:build jar
      4. ls target/*.jar
      5. Assert: JAR file exists matching boundary-email-*.jar
    Expected Result: JAR file created
    Evidence: Terminal output with JAR path

  Scenario: Tenant library builds JAR
    Tool: Bash
    Preconditions: build.clj created
    Steps:
      1. cd libs/tenant
      2. clojure -T:build clean
      3. clojure -T:build jar
      4. ls target/*.jar
      5. Assert: JAR file exists matching boundary-tenant-*.jar
    Expected Result: JAR file created
    Evidence: Terminal output with JAR path
  ```

  **Commit**: YES
  - Message: `build: add build.clj for email and tenant libraries`
  - Files: `libs/email/build.clj`, `libs/tenant/build.clj`
  - Pre-commit: `clojure -T:build jar` in both directories

---

- [x] 6. Create publish.yml GitHub Actions Workflow

  **What to do**:
  - Create `.github/workflows/publish.yml`
  - Trigger: Manual dispatch OR tag push matching `v*`
  - Input: Version number (for manual dispatch)
  - Jobs: Build and deploy each library in dependency order
  - Add 30-second delays between libraries (Clojars indexing)
  - Use GitHub Secrets: `CLOJARS_USERNAME`, `CLOJARS_PASSWORD`
  - Create GitHub Release after successful publish

  **Must NOT do**:
  - Auto-trigger on every commit
  - Publish `external` library
  - Modify existing `ci.yml`

  **Recommended Agent Profile**:
  - **Category**: `unspecified-low`
    - Reason: YAML configuration, follows CI patterns
  - **Skills**: `["git-master"]`
    - Git tagging and release workflow knowledge

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2a (with Task 5)
  - **Blocks**: Task 10
  - **Blocked By**: Task 1

  **References**:
  - `.github/workflows/ci.yml` - Existing CI pattern to follow
  - `docs/PUBLISHING_GUIDE.md` - Manual process to automate
  - Dependency order: core → observability → platform → (user, storage, scaffolder, cache, jobs, realtime parallel) → admin → (email, tenant parallel)

  **Acceptance Criteria**:
  - [ ] `.github/workflows/publish.yml` created
  - [ ] Workflow syntax valid: `actionlint .github/workflows/publish.yml` → 0 errors
  - [ ] Manual dispatch trigger works (dry-run test)
  - [ ] Tag trigger configured: `on: push: tags: ['v*']`
  - [ ] All 12 libraries included (excluding external)
  - [ ] Correct dependency ordering with waits

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Validate workflow YAML syntax
    Tool: Bash
    Preconditions: publish.yml created
    Steps:
      1. Install actionlint if not present: go install github.com/rhysd/actionlint/cmd/actionlint@latest
      2. actionlint .github/workflows/publish.yml
      3. Assert: Exit code 0, no errors
    Expected Result: Valid workflow syntax
    Evidence: Terminal output

  Scenario: Verify dependency order in workflow
    Tool: Bash (grep)
    Preconditions: publish.yml created
    Steps:
      1. grep -n "needs:" .github/workflows/publish.yml
      2. Verify core has no needs
      3. Verify observability needs core
      4. Verify platform needs observability
      5. Verify admin needs user
    Expected Result: Correct dependency chain
    Evidence: grep output captured
  ```

  **Commit**: YES
  - Message: `ci: add Clojars publish workflow with lockstep versioning`
  - Files: `.github/workflows/publish.yml`
  - Pre-commit: `actionlint` validation

---

- [x] 7. Design New Color Palette with Open Props

  **What to do**:
  - Based on moodboard from Task 3, select specific Open Props colors
  - Create new `tokens-openprops.css` extending Open Props
  - Define semantic color mappings: `--color-primary`, `--color-accent`, etc.
  - Create both light and dark mode variants
  - Ensure WCAG AA contrast ratios (4.5:1 minimum)
  - Document color usage guidelines

  **Must NOT do**:
  - Apply to actual components yet (Task 9)
  - Remove existing `tokens.css` (keep as backup)
  - Add CSS build tooling

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Color theory and design system work
  - **Skills**: `["frontend-ui-ux"]`
    - UI/UX expertise for color accessibility

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2b (with Task 8)
  - **Blocks**: Task 9
  - **Blocked By**: Task 3

  **References**:
  - `.sisyphus/evidence/task-3-design-moodboard.md` - Design direction
  - `resources/public/css/tokens.css` - Current tokens to replace
  - https://open-props.style/#colors - Open Props color system
  - https://webaim.org/resources/contrastchecker/ - Contrast verification

  **Acceptance Criteria**:
  - [ ] `resources/public/css/tokens-openprops.css` created
  - [ ] Primary color defined with light/dark variants
  - [ ] Accent color(s) defined with light/dark variants
  - [ ] All colors pass WCAG AA contrast (documented)
  - [ ] Color usage guide in file comments

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify Open Props import works
    Tool: Bash
    Preconditions: tokens-openprops.css created
    Steps:
      1. grep "@import" resources/public/css/tokens-openprops.css
      2. Start dev server: clojure -M:repl-clj &
      3. curl -s http://localhost:3000/css/tokens-openprops.css
      4. Assert: CSS loads without 404
    Expected Result: CSS file accessible
    Evidence: curl output

  Scenario: Document contrast ratios
    Tool: Bash
    Preconditions: Colors defined
    Steps:
      1. Extract primary/background color pairs
      2. Use contrast ratio formula or online tool
      3. Document ratios in tokens-openprops.css comments
      4. Assert: All ratios >= 4.5:1
    Expected Result: WCAG AA compliance documented
    Evidence: Comments in CSS file
  ```

  **Commit**: YES
  - Message: `style: add Open Props color palette with WCAG AA compliance`
  - Files: `resources/public/css/tokens-openprops.css`
  - Pre-commit: None (CSS file, no tests)

---

- [x] 8. Set Up Hugo Site Structure

  **What to do**:
  - Initialize Hugo site in `docs-site/` directory
  - Select and configure Hugo theme (recommend: Docsy, Book, or Doks)
  - Set up AsciiDoc support via asciidoctor
  - Create basic navigation structure matching consolidation plan
  - Configure for GitHub Pages deployment
  - Create placeholder pages for main sections

  **Must NOT do**:
  - Migrate actual content yet (Task 12)
  - Deploy to production
  - Create elaborate custom theming

  **Recommended Agent Profile**:
  - **Category**: `unspecified-low`
    - Reason: Hugo setup is well-documented, straightforward
  - **Skills**: `[]`
    - Standard tooling

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2b (with Task 7)
  - **Blocks**: Task 12
  - **Blocked By**: Task 4

  **References**:
  - `.sisyphus/evidence/task-4-docs-consolidation-strategy.md` - Target structure
  - https://gohugo.io/getting-started/quick-start/ - Hugo setup
  - https://github.com/google/docsy - Docsy theme (good for technical docs)
  - https://github.com/alex-shpak/hugo-book - Book theme (simpler)

  **Acceptance Criteria**:
  - [ ] `docs-site/` directory with Hugo site
  - [ ] `hugo server` starts without errors
  - [ ] Theme installed and configured
  - [ ] AsciiDoc files render correctly
  - [ ] Navigation structure matches plan
  - [ ] GitHub Pages config in `config.toml`

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Hugo site builds
    Tool: Bash
    Preconditions: Hugo site initialized
    Steps:
      1. cd docs-site
      2. hugo --gc --minify
      3. Assert: Exit code 0
      4. ls public/index.html
      5. Assert: index.html exists
    Expected Result: Static site generated
    Evidence: Terminal output + public/ contents

  Scenario: AsciiDoc renders
    Tool: Bash
    Preconditions: Test .adoc file created
    Steps:
      1. Create docs-site/content/test.adoc with sample content
      2. hugo server &
      3. curl -s http://localhost:1313/test/
      4. Assert: HTML contains rendered AsciiDoc content
      5. Kill hugo server
    Expected Result: AsciiDoc converted to HTML
    Evidence: curl output
  ```

  **Commit**: YES
  - Message: `docs: initialize Hugo site structure for documentation`
  - Files: `docs-site/` (entire directory)
  - Pre-commit: `cd docs-site && hugo --gc --minify`

---

### Week 2-3: Implementation

- [x] 9. Apply New CSS to Admin UI

  **What to do**:
  - Replace `tokens.css` import with `tokens-openprops.css`
  - Update `admin.css` to use new color variables
  - Update component-specific styles in Hiccup (CSS classes only)
  - Test all 50+ admin pages for visual consistency
  - Verify dark mode toggle still works
  - Take before/after screenshots for comparison
  - Ensure no console errors on any page

  **Must NOT do**:
  - Change any Hiccup component signatures
  - Modify Alpine.js or HTMX patterns
  - Add new JavaScript
  - Change layout or spacing (color only for now)

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: CSS styling and visual polish
  - **Skills**: `["frontend-ui-ux", "playwright"]`
    - UI expertise + browser automation for testing

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 12)
  - **Blocks**: Task 16
  - **Blocked By**: Task 7

  **References**:
  - `resources/public/css/tokens-openprops.css` - New color palette
  - `resources/public/css/admin.css` - Admin-specific styles
  - `libs/admin/src/boundary/shared/ui/core/layout.clj` - Page layout (CSS classes to update)
  - `libs/admin/src/boundary/admin/core/ui.clj` - Admin components (CSS classes)

  **Acceptance Criteria**:
  - [ ] All admin pages render with new colors
  - [ ] `resources/public/css/tokens.css` import replaced
  - [ ] Dark mode toggle functional
  - [ ] No console errors on any page
  - [ ] Lighthouse accessibility score >= 90
  - [ ] Before/after screenshots captured

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Admin dashboard renders with new colors
    Tool: Playwright
    Preconditions: Dev server running on localhost:3000
    Steps:
      1. Navigate to: http://localhost:3000/admin
      2. Wait for: .admin-shell visible (timeout: 5s)
      3. Capture console: Assert no errors
      4. Screenshot: .sisyphus/evidence/task-9-admin-after.png
      5. Toggle dark mode (click theme toggle)
      6. Wait for: body.dark-mode (timeout: 2s)
      7. Screenshot: .sisyphus/evidence/task-9-admin-dark.png
    Expected Result: Pages render, no errors, dark mode works
    Evidence: .sisyphus/evidence/task-9-admin-*.png

  Scenario: All admin pages load without error
    Tool: Playwright
    Preconditions: Dev server running, admin logged in
    Steps:
      1. Define page list: ["/admin", "/admin/users", "/admin/settings", ...]
      2. For each page:
         a. Navigate to URL
         b. Wait for page load
         c. Capture console errors
         d. Assert: No console errors
    Expected Result: Zero console errors across all pages
    Evidence: Console log summary in .sisyphus/evidence/task-9-console-audit.txt

  Scenario: Lighthouse accessibility audit
    Tool: Bash (lighthouse CLI)
    Preconditions: Dev server running
    Steps:
      1. lighthouse http://localhost:3000/admin --output json --only-categories=accessibility
      2. Parse JSON: extract accessibility score
      3. Assert: Score >= 90
    Expected Result: Accessibility score 90+
    Evidence: .sisyphus/evidence/task-9-lighthouse.json
  ```

  **Commit**: YES
  - Message: `style: apply Open Props color palette to admin UI`
  - Files: `resources/public/css/admin.css`, CSS class changes in Hiccup files
  - Pre-commit: `clojure -M:test:db/h2 :admin`

---

- [ ] 10. Test and Execute First Clojars Publish

  **What to do**:
  - Set GitHub Secrets: `CLOJARS_USERNAME`, `CLOJARS_PASSWORD`
  - Create git tag: `v1.0.0`
  - Trigger publish workflow (manual or via tag push)
  - Monitor each library publication
  - Verify all 12 libraries appear on Clojars
  - Test dependency resolution from fresh project
  - Create GitHub Release with changelog

  **Must NOT do**:
  - Publish `external` library (not in workflow)
  - Modify library code
  - Rush if any library fails (fix and retry)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-low`
    - Reason: Execution of prepared workflow
  - **Skills**: `["git-master"]`
    - Git tagging and release management

  **Parallelization**:
  - **Can Run In Parallel**: YES (but careful - this is the critical path)
  - **Parallel Group**: Wave 3 (with Tasks 9, 11, 12)
  - **Blocks**: Tasks 13, 14
  - **Blocked By**: Tasks 5, 6

  **References**:
  - `.github/workflows/publish.yml` - Publish workflow
  - `docs/PUBLISHING_GUIDE.md` - Troubleshooting reference
  - https://clojars.org/io.github.thijs-creemers - Verification URL

  **Acceptance Criteria**:
  - [ ] All 12 libraries published to Clojars
  - [ ] Version 1.0.0 for all libraries
  - [ ] GitHub Release created with tag v1.0.0
  - [ ] Fresh project can depend on published libraries
  - [ ] README updated with correct Maven coordinates

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify boundary-core on Clojars
    Tool: Bash (curl)
    Preconditions: Publish workflow completed
    Steps:
      1. curl -s https://clojars.org/api/artifacts/io.github.thijs-creemers/boundary-core
      2. Parse JSON response
      3. Assert: "latest_version" equals "1.0.0"
    Expected Result: boundary-core 1.0.0 on Clojars
    Evidence: API response JSON

  Scenario: Test fresh project dependency resolution
    Tool: Bash (clojure)
    Preconditions: Libraries published
    Steps:
      1. Create temp directory
      2. Create deps.edn:
         {:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0"}
                 io.github.thijs-creemers/boundary-platform {:mvn/version "1.0.0"}}}
      3. clojure -e "(require 'boundary.core.validation) (println :success)"
      4. Assert: Prints :success, exit code 0
    Expected Result: Dependencies resolve and load
    Evidence: Terminal output

  Scenario: Verify all 12 libraries on Clojars
    Tool: Bash
    Preconditions: Publish completed
    Steps:
      1. For each library in [core, observability, platform, user, admin, storage, scaffolder, cache, jobs, realtime, email, tenant]:
         curl -s "https://clojars.org/api/artifacts/io.github.thijs-creemers/boundary-$lib"
      2. Assert: All return valid JSON with version 1.0.0
    Expected Result: All 12 libraries published
    Evidence: Summary table in .sisyphus/evidence/task-10-clojars-verification.md
  ```

  **Commit**: YES
  - Message: `release: publish Boundary Framework 1.0.0 to Clojars`
  - Files: Updated README.md with installation instructions
  - Pre-commit: None (release commit)

---

- [ ] 11. Add Docstrings to Public Functions

  **What to do**:
  - Using Task 2 audit, prioritize libraries: core → platform → user → others
  - Add docstrings to all public functions (not internal/private)
  - Follow consistent format: one-line summary + params + return + example
  - Run tests after each library to ensure no breakage
  - Aim for 80%+ coverage of public functions

  **Must NOT do**:
  - Change function signatures
  - Fix bugs found while documenting (separate PR)
  - Document private/internal functions
  - Add elaborate examples (brief only)

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Documentation writing task
  - **Skills**: `[]`
    - Clojure knowledge for understanding functions

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 9, 10, 12)
  - **Blocks**: Task 16
  - **Blocked By**: Task 2

  **References**:
  - `.sisyphus/evidence/task-2-docstring-audit.md` - Priority list
  - `libs/core/src/boundary/core/validation.clj` - Example of well-documented code
  - Clojure docstring conventions: https://clojure.org/guides/repl/guidelines_for_repl_aided_development

  **Acceptance Criteria**:
  - [ ] 80%+ of public functions have docstrings
  - [ ] All tests still pass: `clojure -M:test:db/h2`
  - [ ] Docstrings follow consistent format
  - [ ] Priority libraries fully covered: core, platform, user

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify docstring coverage increased
    Tool: Bash (REPL)
    Preconditions: Docstrings added
    Steps:
      1. Start REPL
      2. Run same count script as Task 2
      3. Compare before/after counts
      4. Calculate coverage percentage
      5. Assert: Coverage >= 80%
    Expected Result: Significant coverage increase
    Evidence: Before/after comparison in .sisyphus/evidence/task-11-docstring-after.md

  Scenario: All tests pass after docstring additions
    Tool: Bash
    Preconditions: Docstrings added
    Steps:
      1. clojure -M:test:db/h2
      2. Assert: Exit code 0
      3. Assert: No test failures
    Expected Result: Green test suite
    Evidence: Test output summary
  ```

  **Commit**: YES (multiple commits, one per library)
  - Message: `docs(core): add docstrings to public functions`
  - Files: `libs/*/src/**/*.clj`
  - Pre-commit: `clojure -M:test:db/h2 :{library}`

---

- [ ] 12. Migrate boundary-docs Content

  **What to do**:
  - Following Task 4 strategy, migrate content from boundary-docs
  - Convert AsciiDoc to Hugo-compatible format
  - Update internal links to new paths
  - Preserve ADRs, architecture guides, integration docs
  - Update README references to point to new docs site
  - Test all links resolve

  **Must NOT do**:
  - Write new content (migration only)
  - Change content substance
  - Deploy to production yet

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Documentation migration
  - **Skills**: `[]`
    - Standard file operations

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 9, 10, 11)
  - **Blocks**: Task 16
  - **Blocked By**: Task 8

  **References**:
  - `.sisyphus/evidence/task-4-docs-consolidation-strategy.md` - Migration plan
  - `docs-site/` - Target Hugo site
  - boundary-docs repo - Source content

  **Acceptance Criteria**:
  - [ ] All AsciiDoc files migrated to `docs-site/content/`
  - [ ] Hugo builds without errors: `cd docs-site && hugo --gc --minify`
  - [ ] All internal links resolve
  - [ ] Navigation reflects migrated content

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Hugo builds after migration
    Tool: Bash
    Preconditions: Content migrated
    Steps:
      1. cd docs-site
      2. hugo --gc --minify 2>&1
      3. Assert: No error output
      4. grep -r "404" public/ | wc -l
      5. Assert: Zero 404 references
    Expected Result: Clean build, no broken links
    Evidence: Build output

  Scenario: All major sections accessible
    Tool: Bash (curl)
    Preconditions: Hugo server running
    Steps:
      1. hugo server &
      2. For each section in [/architecture, /guides, /adr, /reference]:
         curl -s http://localhost:1313$section/ -o /dev/null -w "%{http_code}"
      3. Assert: All return 200
      4. Kill server
    Expected Result: All sections serve
    Evidence: HTTP status codes
  ```

  **Commit**: YES
  - Message: `docs: migrate boundary-docs content to Hugo site`
  - Files: `docs-site/content/**/*`
  - Pre-commit: `cd docs-site && hugo --gc --minify`

---

### Week 3-4: Completion

- [ ] 13. Write Developer Elevator Pitch

  **What to do**:
  - Write ~150 word technical pitch targeting Clojure developers
  - Highlight: FC/IS architecture, library modularity, rapid development
  - Reference published Clojars coordinates
  - Include quick comparison positioning (vs Luminus, Kit, Pedestal)
  - Add to README.md in prominent position
  - Get user approval before finalizing

  **Must NOT do**:
  - Create separate landing page
  - Make claims about unpublished features
  - Write more than ~150 words
  - Create comparison matrix

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Marketing copy writing
  - **Skills**: `[]`
    - Writing expertise

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 14, 15)
  - **Blocks**: Task 16
  - **Blocked By**: Task 10

  **References**:
  - Published libraries on Clojars - Accurate claims
  - `README.md` - Current content to enhance
  - `PROJECT_SUMMARY.md` - Feature overview (root level)
  - `killer-features.md` - Feature highlights (root level)

  **Acceptance Criteria**:
  - [ ] ~150 words (140-160 range)
  - [ ] Added to README.md
  - [ ] References accurate Clojars coordinates
  - [ ] User approved content

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify word count
    Tool: Bash
    Preconditions: Pitch written
    Steps:
      1. Extract pitch section from README.md
      2. wc -w
      3. Assert: Count between 140-160
    Expected Result: ~150 words
    Evidence: Word count output

  Scenario: Verify Clojars links work
    Tool: Bash (curl)
    Preconditions: Pitch references Clojars
    Steps:
      1. Extract Clojars URLs from pitch
      2. curl each URL, check for 200 status
      3. Assert: All URLs valid
    Expected Result: All links work
    Evidence: HTTP status codes
  ```

  **Commit**: YES (after user approval)
  - Message: `docs: add developer elevator pitch to README`
  - Files: `README.md`
  - Pre-commit: None

---

- [ ] 14. Write Management Elevator Pitch

  **What to do**:
  - Write ~100 word business value pitch targeting decision makers
  - Highlight: Reduced time-to-market, proven patterns, maintainability
  - Focus on ROI: faster development, lower bugs, easier scaling
  - Avoid technical jargon - business outcomes only
  - Add to README.md (separate section from dev pitch)
  - Get user approval before finalizing

  **Must NOT do**:
  - Create separate document
  - Include code examples
  - Make unsubstantiated ROI claims
  - Write more than ~100 words

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Business copy writing
  - **Skills**: `[]`
    - Writing expertise

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 13, 15)
  - **Blocks**: Task 16
  - **Blocked By**: Task 10

  **References**:
  - `PROJECT_SUMMARY.md` - Feature overview (root level)
  - `killer-features.md` - Business benefits section (root level)

  **Acceptance Criteria**:
  - [ ] ~100 words (90-110 range)
  - [ ] Added to README.md
  - [ ] No technical jargon
  - [ ] User approved content

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Verify word count
    Tool: Bash
    Preconditions: Pitch written
    Steps:
      1. Extract business pitch section from README.md
      2. wc -w
      3. Assert: Count between 90-110
    Expected Result: ~100 words
    Evidence: Word count output

  Scenario: Jargon check
    Tool: Bash (grep)
    Preconditions: Pitch written
    Steps:
      1. Extract pitch text
      2. grep -i -E "(clojure|jvm|repl|hiccup|htmx|malli)" 
      3. Assert: Zero matches (no technical terms)
    Expected Result: No jargon found
    Evidence: grep output (should be empty)
  ```

  **Commit**: YES (after user approval)
  - Message: `docs: add management elevator pitch to README`
  - Files: `README.md`
  - Pre-commit: None

---

- [ ] 15. Build Interactive Cheat-sheet

  **What to do**:
  - Create single HTML page at `docs/cheatsheet.html`
  - Derive content from AGENTS.md (commands, patterns, quick reference)
  - Implement client-side search (vanilla JS, no framework)
  - Make mobile responsive (375px minimum)
  - Categories: Commands, Architecture, Workflows, Troubleshooting
  - Copy-to-clipboard functionality for commands
  - Host on GitHub Pages alongside docs

  **Must NOT do**:
  - Add build tooling (npm, webpack, etc.)
  - Create interactive REPL
  - Include video tutorials
  - Duplicate full documentation (quick reference only)

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Interactive HTML/CSS/JS page
  - **Skills**: `["frontend-ui-ux"]`
    - UI/UX for usability

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 13, 14)
  - **Blocks**: Task 16
  - **Blocked By**: None (can start early, but better after docs migration)

  **References**:
  - `AGENTS.md` - Source content for cheat-sheet
  - `resources/public/css/tokens-openprops.css` - Color system to match
  - Modern cheat-sheet examples: devhints.io, overapi.com

  **Acceptance Criteria**:
  - [ ] `docs/cheatsheet.html` created
  - [ ] Page loads in < 2 seconds
  - [ ] Search finds content within 100ms
  - [ ] Mobile responsive at 375px
  - [ ] Copy-to-clipboard works for commands
  - [ ] Matches new color palette

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Cheat-sheet loads quickly
    Tool: Playwright
    Preconditions: cheatsheet.html exists
    Steps:
      1. Measure navigation start time
      2. Navigate to: docs/cheatsheet.html (file://)
      3. Wait for: #search-input visible
      4. Measure load complete time
      5. Assert: Load time < 2000ms
    Expected Result: Fast load
    Evidence: Timing measurement

  Scenario: Search works quickly
    Tool: Playwright
    Preconditions: Cheat-sheet loaded
    Steps:
      1. Type in #search-input: "test"
      2. Measure time to first result visible
      3. Assert: Results visible within 100ms
      4. Assert: Results contain "test" related items
    Expected Result: Fast search
    Evidence: .sisyphus/evidence/task-15-search-test.png

  Scenario: Mobile responsive
    Tool: Playwright
    Preconditions: Cheat-sheet loaded
    Steps:
      1. Resize viewport to 375x667 (iPhone SE)
      2. Assert: No horizontal scroll needed
      3. Assert: All sections accessible
      4. Screenshot: .sisyphus/evidence/task-15-mobile.png
    Expected Result: Mobile-friendly layout
    Evidence: .sisyphus/evidence/task-15-mobile.png

  Scenario: Copy-to-clipboard works
    Tool: Playwright
    Preconditions: Cheat-sheet loaded
    Steps:
      1. Click copy button on first command
      2. Read clipboard content via JS
      3. Assert: Clipboard contains expected command text
    Expected Result: Clipboard populated
    Evidence: Console log of clipboard content
  ```

  **Commit**: YES
  - Message: `docs: add interactive cheat-sheet with search`
  - Files: `docs/cheatsheet.html`, `docs/cheatsheet.css`, `docs/cheatsheet.js`
  - Pre-commit: None (HTML file)

---

- [x] 16. Final Polish and Verification

  **What to do**:
  - Run full test suite: `clojure -M:test:db/h2`
  - Run linting: `clojure -M:clj-kondo --lint libs/*/src`
  - Verify all acceptance criteria from previous tasks
  - Check all links in documentation
  - Verify Clojars artifacts are correct
  - Create CHANGELOG.md with 1.0.0 release notes (ALREADY EXISTS - comprehensive)
  - Create final before/after comparison document

  **Must NOT do**:
  - Add new features
  - Fix non-critical issues (document for future)
  - Deploy documentation to production without approval

  **Recommended Agent Profile**:
  - **Category**: `unspecified-low`
    - Reason: Verification and cleanup
  - **Skills**: `[]`
    - Standard verification

  **Parallelization**:
  - **Can Run In Parallel**: NO (final task)
  - **Parallel Group**: Sequential (final)
  - **Blocks**: None (end of plan)
  - **Blocked By**: Tasks 9, 11, 12, 13, 14, 15

  **References**:
  - All previous task acceptance criteria
  - `CHANGELOG.md` - Release notes template

  **Acceptance Criteria**:
  - [ ] All tests pass: `clojure -M:test:db/h2` → 0 failures
  - [ ] No lint errors: `clojure -M:clj-kondo --lint libs/*/src` → 0 errors
  - [ ] All 12 libraries on Clojars verified
  - [ ] Documentation site builds clean
  - [ ] Cheat-sheet functional
  - [ ] Both pitches approved
  - [ ] CHANGELOG.md updated

  **Agent-Executed QA Scenarios**:

  ```
  Scenario: Full test suite passes
    Tool: Bash
    Preconditions: All tasks complete
    Steps:
      1. clojure -M:test:db/h2 2>&1 | tee test-output.txt
      2. grep -E "^\d+ tests, \d+ assertions, \d+ failures" test-output.txt
      3. Assert: 0 failures
    Expected Result: Green test suite
    Evidence: test-output.txt

  Scenario: No lint errors
    Tool: Bash
    Preconditions: All code changes committed
    Steps:
      1. clojure -M:clj-kondo --lint libs/*/src libs/*/test 2>&1 | tee lint-output.txt
      2. grep -c ":error" lint-output.txt
      3. Assert: 0 errors
    Expected Result: Clean lint
    Evidence: lint-output.txt

  Scenario: Documentation site live
    Tool: Bash
    Preconditions: Hugo site built
    Steps:
      1. cd docs-site && hugo --gc --minify
      2. Assert: Exit code 0
      3. Count pages: find public -name "*.html" | wc -l
      4. Assert: Count > 20 (reasonable page count)
    Expected Result: Site builds with content
    Evidence: Page count and build output
  ```

  **Commit**: YES
  - Message: `release: finalize Boundary Framework 1.0.0 launch`
  - Files: `CHANGELOG.md`, any final fixes
  - Pre-commit: `clojure -M:test:db/h2 && clojure -M:clj-kondo --lint libs/*/src`

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 5 | `build: add build.clj for email and tenant libraries` | libs/email/build.clj, libs/tenant/build.clj | JAR builds |
| 6 | `ci: add Clojars publish workflow with lockstep versioning` | .github/workflows/publish.yml | actionlint |
| 7 | `style: add Open Props color palette with WCAG AA compliance` | resources/public/css/tokens-openprops.css | None |
| 8 | `docs: initialize Hugo site structure for documentation` | docs-site/* | hugo build |
| 9 | `style: apply Open Props color palette to admin UI` | resources/public/css/admin.css, Hiccup files | Admin tests |
| 10 | `release: publish Boundary Framework 1.0.0 to Clojars` | README.md | Clojars verify |
| 11 | `docs(core): add docstrings to public functions` (per library) | libs/*/src/**/*.clj | Tests pass |
| 12 | `docs: migrate boundary-docs content to Hugo site` | docs-site/content/**/* | hugo build |
| 13 | `docs: add developer elevator pitch to README` | README.md | User approval |
| 14 | `docs: add management elevator pitch to README` | README.md | User approval |
| 15 | `docs: add interactive cheat-sheet with search` | docs/cheatsheet.* | Playwright |
| 16 | `release: finalize Boundary Framework 1.0.0 launch` | CHANGELOG.md | Full suite |

---

## Success Criteria

### Verification Commands
```bash
# All tests pass
clojure -M:test:db/h2
# Expected: 0 failures

# All lint clean
clojure -M:clj-kondo --lint libs/*/src libs/*/test
# Expected: 0 errors

# Clojars verification
for lib in core observability platform user admin storage scaffolder cache jobs realtime email tenant; do
  curl -s "https://clojars.org/api/artifacts/io.github.thijs-creemers/boundary-$lib" | jq .latest_version
done
# Expected: All return "1.0.0"

# Fresh project test
cd $(mktemp -d) && cat > deps.edn << 'EOF'
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0"}}}
EOF
clojure -e "(require 'boundary.core.validation) (println :success)"
# Expected: :success

# Hugo docs build
cd docs-site && hugo --gc --minify
# Expected: Exit code 0, no errors

# Admin UI no console errors
# Run via Playwright, capture console, assert 0 errors
```

### Final Checklist
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] All 12 libraries on Clojars 1.0.0
- [ ] Admin UI renders with new colors, no errors
- [ ] Dark mode functional
- [ ] Documentation site builds clean
- [ ] Cheat-sheet search works < 100ms
- [ ] Both pitches in README, approved
- [ ] CHANGELOG.md updated
- [ ] All tests pass
- [ ] All lint clean
