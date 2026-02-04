# Boundary Framework Roadmap - 3-Month Gap Closure

## Context

### Original Request
Create a comprehensive 3-month roadmap to close identified gaps in the Boundary Framework and accelerate adoption. Cover 6 priorities in order:
1. Document Existing Features
2. Starter Templates CLI
3. Email/Notifications Module
4. 60-Second Demo & Starter Repo
5. Real-time/WebSocket Module
6. Multi-tenancy Design

### Interview Summary
**Key Discussions**:
- Timeline: 3 months (aggressive, requires dedicated effort)
- Demo video: User records manually (not part of plan execution)
- Email providers: SMTP only initially (simplest, works everywhere)
- Multi-tenancy: Schema-per-tenant strategy (stronger isolation)
- Interactive experience: Starter repo only (no web playground)

**Research Findings**:
- 10 library modules exist with consistent patterns (core/, shell/, ports.clj, schema.clj)
- Launch materials drafted in `docs/launch/` with TODO placeholders
- Scaffolder CLI exists at 761 lines - may need audit before extension
- Starter repo outline exists at `docs/launch/starter/outline.md`
- Jobs module README is 700+ lines - quality benchmark for new modules

### Metis Review
**Identified Gaps** (addressed):
- Added Phase 0 for scaffolder CLI audit (de-risks Phase 2)
- Scoped Phase 1 to maximum 5 docs (prevents scope creep)
- Required jobs integration for email module (production pattern)
- Added design-first gate for WebSocket (before implementation)
- Clarified Phase 6 is design document only (no implementation)

---

## Work Objectives

### Core Objective
Close critical gaps in the Boundary Framework over 13 weeks to enable successful launch and accelerate developer adoption.

### Concrete Deliverables
| Phase | Deliverable | Target |
|-------|-------------|--------|
| 0 | Scaffolder CLI audit report | Week 1 |
| 1 | 5 completed documentation guides | Weeks 1-3 |
| 2 | `boundary new` CLI command + templates | Weeks 4-6 |
| 3 | `libs/email/` module with SMTP + jobs integration | Weeks 7-9 |
| 4 | Public boundary-starter repo + updated launch materials | Week 10 |
| 5 | `libs/realtime/` module with WebSocket support | Weeks 11-12 |
| 6 | Multi-tenancy architecture design document | Week 13 |

### Definition of Done
- [ ] All new documentation passes link validation
- [ ] `boundary new myapp` creates runnable project that passes `clojure -M:test:db/h2`
- [ ] Email module has README matching `libs/jobs/README.md` quality
- [ ] boundary-starter repo is public and all TODO placeholders in launch materials are replaced
- [ ] WebSocket module has working authentication with existing JWT
- [ ] Multi-tenancy ADR is approved and published

### Must Have
- All deliverables follow existing FC/IS architecture patterns
- New library modules match existing README format
- Integration tests for all new modules
- Documentation for all new features

### Must NOT Have (Guardrails)
- No multiple project templates in Phase 2 (single "starter" template only)
- No interactive CLI prompts (flags only)
- No HTML email templates/rendering system in Phase 3
- No email verification flows (user module concern, not email module)
- No WebSocket rooms/channels/presence in Phase 5
- No Redis pub/sub for WebSocket scaling in Phase 5
- No multi-tenancy implementation code in Phase 6 (design doc only)
- No AsciiDoc format (use Markdown for all new docs)

---

## Verification Strategy (MANDATORY)

### Test Decision
- **Infrastructure exists**: YES
- **User wants tests**: YES (TDD for new modules)
- **Framework**: Kaocha with H2 in-memory

### TDD Pattern for New Modules (Phases 3, 5)
Each TODO follows RED-GREEN-REFACTOR:
1. **RED**: Write failing test first
2. **GREEN**: Implement minimum code to pass
3. **REFACTOR**: Clean up while keeping green

**Test Commands:**
```bash
clojure -M:test:db/h2 --focus-meta :unit     # Fast unit tests
clojure -M:test:db/h2                         # Full test suite
clojure -M:clj-kondo --lint libs/*/src        # Lint check
```

### Manual Verification (Documentation Phases)
- All Markdown files: Check links with markdown-link-check
- Documentation accuracy: Execute code examples manually
- Starter repo: Clone fresh, follow README, verify working

---

## Task Flow

```
Phase 0 (Week 1) ─────────────────────────────────────────────┐
                                                              │
Phase 1 (Weeks 1-3) ──────────────────────────────────────────┼─┐
                                                              │ │
                              ┌───────────────────────────────┘ │
                              ▼                                 │
Phase 2 (Weeks 4-6) ────────────────────────────────────────────┼─┐
                                                                │ │
Phase 3 (Weeks 7-9) ────────────────────────────────────────────┘ │
                                                                  │
Phase 4 (Week 10) ────────────────────────────────────────────────┼─┐
                                                                  │ │
Phase 5 (Weeks 11-12) ────────────────────────────────────────────┘ │
                                                                    │
Phase 6 (Week 13) ──────────────────────────────────────────────────┘
```

## Parallelization

| Group | Tasks | Reason |
|-------|-------|--------|
| A | Phase 0 + Phase 1 (Weeks 1-3) | Audit happens in Week 1; docs parallel |

| Phase | Depends On | Reason |
|-------|------------|--------|
| 2 | 0 | Scaffolder audit informs extension approach |
| 3 | - | Independent module |
| 4 | 2 | Starter repo uses `boundary new` |
| 5 | - | Independent module |
| 6 | 3, 5 | Design considers email and WebSocket modules |

---

## TODOs

---

### PHASE 0: Scaffolder CLI Audit (Week 1)

- [x] 0.1. Audit scaffolder CLI for `boundary new` extension feasibility

  **What to do**:
  - Read `libs/scaffolder/src/boundary/scaffolder/cli.clj` (the main CLI dispatch, 761 lines)
  - Note: `cli_entry.clj` is just a 44-line wrapper that calls `cli.clj`
  - Study `dispatch-command` function (line 413-431) - commands registered here
  - Study command options pattern (generate-options, field-options, etc.)
  - Identify where `new` command would be added (new `new-options` def + case in dispatch)
  - Document extension approach - should be clean (add new case + options)
  - Write audit report to `docs/adr/ADR-002-boundary-new-command.md`

  **ADR Numbering Rule**: Next ADR number is 002 (only ADR-001-library-split.md exists currently)

  **Must NOT do**:
  - Modify any scaffolder code in this phase
  - Start implementation

  **Parallelizable**: YES (with Phase 1 docs)

  **References**:
  - `libs/scaffolder/src/boundary/scaffolder/cli.clj` - Main CLI dispatch (761 lines, the actual audit target)
  - `libs/scaffolder/src/boundary/scaffolder/shell/cli_entry.clj` - Wrapper entry point (44 lines)
  - `libs/scaffolder/README.md` - Current CLI documentation
  - `docs/launch/starter/outline.md` - Template structure to generate
  - `docs/adr/ADR-001-library-split.md` - Existing ADR for format reference

  **Acceptance Criteria**:
  - [ ] ADR document created at `docs/adr/ADR-002-boundary-new-command.md`
  - [ ] Document answers: Can extend cleanly? Or needs refactoring?
  - [ ] Document lists: Files to modify, functions to add, estimated LOC
  - [ ] Document: Where to add `new-options`, `execute-new`, extend `dispatch-command`
  - [ ] Document: Clarify that README mentions `--output-dir`/`--force` but cli.clj doesn't have them (doc drift to address in Phase 2)

  **Commit**: YES
  - Message: `docs(adr): add boundary new command design decision`
  - Files: `docs/adr/ADR-002-boundary-new-command.md`

---

- [x] 0.2. Reconcile docs path structure (docs hub vs roadmap)

  **What to do**:
  - Review `docs/README.md` links vs actual file locations
  - Note: `docs/README.md` links to `DATABASE_SETUP.md`, `AUTHENTICATION.md`, `TESTING.md` at root
  - Decision: Create new docs in `docs/guides/` (consistent with existing `docs/guides/mfa-setup.md`)
  - Update `docs/README.md` links to point to `docs/guides/*.md` paths
  - Verify IDE_SETUP.md Emacs/Vim references - if present, note that roadmap says defer to community

  **Must NOT do**:
  - Create the actual documentation files (that's Phase 1)

  **Parallelizable**: YES (with Phase 0.1)

  **References**:
  - `docs/README.md` - Documentation hub with status table
  - `docs/guides/` - Target directory for new guides

  **Acceptance Criteria**:
  - [ ] `docs/README.md` links updated to `docs/guides/*.md` paths
  - [ ] Emacs/Vim references in IDE_SETUP.md noted (roadmap says defer to community)

  **Commit**: YES
  - Message: `docs: update docs hub links to use docs/guides/ path`
  - Files: `docs/README.md`

---

### PHASE 1: Document Existing Features (Weeks 1-3)

- [x] 1.1. Complete TUTORIAL.md - Full hands-on tutorial

  **What to do**:
  - Create `docs/TUTORIAL.md` (currently "(Coming Soon)")
  - Build a complete task management API from scratch
  - Cover: user auth, CRUD operations, validation, tests
  - Include time estimates per section
  - All code examples must be runnable

  **Must NOT do**:
  - WebSocket/real-time features (not built yet)
  - Email features (not built yet)
  - Advanced topics (save for later)

  **Parallelizable**: YES (with 1.2, 1.3, 1.4, 1.5)

  **References**:
  - `docs/QUICKSTART.md` - Existing quickstart to build upon
  - `libs/user/README.md` - Auth features to document
  - `libs/admin/README.md` - Admin features to document
  - `libs/scaffolder/README.md` - Scaffolder usage patterns

  **Acceptance Criteria**:
  - [ ] `docs/TUTORIAL.md` exists (1000+ lines)
  - [ ] All code examples execute successfully
  - [ ] Time estimate header: "Time: 1-2 hours"
  - [ ] Covers: setup, auth, CRUD, validation, tests

  **Commit**: YES
  - Message: `docs(tutorial): add complete hands-on tutorial`
  - Files: `docs/TUTORIAL.md`

---

- [x] 1.2. Review and finalize IDE_SETUP.md

  **What to do**:
  - Review existing `docs/IDE_SETUP.md` (already 792 lines, very complete)
  - Verify all instructions work on fresh install
  - Add any missing sections if found
  - Update status in `docs/README.md` from "In Progress" to "Complete"

  **Must NOT do**:
  - Rewrite from scratch (file is already complete)
  - Add Emacs/Vim sections (defer to community)

  **Parallelizable**: YES (with 1.1, 1.3, 1.4, 1.5)

  **References**:
  - `docs/IDE_SETUP.md` - Already comprehensive (792 lines)
  - `AGENTS.md` - REPL workflow section

  **Acceptance Criteria**:
  - [ ] `docs/IDE_SETUP.md` verified working on fresh install
  - [ ] VSCode + Calva section tested
  - [ ] Status updated to "Complete" in docs/README.md

  **Commit**: YES
  - Message: `docs(ide): finalize IDE setup guide and update status`
  - Files: `docs/IDE_SETUP.md`, `docs/README.md`

---

- [x] 1.3. Complete DATABASE_SETUP.md - Database configuration

  **What to do**:
  - Create `docs/guides/DATABASE_SETUP.md` (currently "(Coming Soon)")
  - Cover SQLite (development)
  - Cover PostgreSQL (production)
  - Cover H2 (testing)
  - Include connection pooling, migration workflow
  - Add troubleshooting section

  **Must NOT do**:
  - MySQL (defer)
  - Multi-database setup (defer to advanced)

  **Parallelizable**: YES (with 1.1, 1.2, 1.4, 1.5)

  **References**:
  - `libs/platform/README.md` - Database infrastructure
  - `resources/conf/dev/config.edn` - Example configuration
  - `AGENTS.md` - Database configuration section

  **Acceptance Criteria**:
  - [ ] `docs/guides/DATABASE_SETUP.md` exists (300+ lines)
  - [ ] SQLite, PostgreSQL, H2 covered
  - [ ] Migration commands documented
  - [ ] Connection pooling explained

  **Commit**: YES
  - Message: `docs(database): add database setup guide`
  - Files: `docs/guides/DATABASE_SETUP.md`

---

- [x] 1.4. Complete AUTHENTICATION.md - Auth guide

  **What to do**:
  - Create `docs/guides/AUTHENTICATION.md` (currently "(Coming Soon)")
  - Document JWT-based authentication
  - Document session management
  - Document MFA setup and enforcement
  - Include API examples with curl
  - Reference existing `docs/MFA_API_REFERENCE.md`

  **Must NOT do**:
  - OAuth2/OIDC (not built)
  - Social login (not built)

  **Parallelizable**: YES (with 1.1, 1.2, 1.3, 1.5)

  **References**:
  - `libs/user/README.md` - User module features
  - `docs/MFA_API_REFERENCE.md` - Existing MFA docs
  - `docs/guides/mfa-setup.md` - MFA setup guide

  **Acceptance Criteria**:
  - [ ] `docs/guides/AUTHENTICATION.md` exists (400+ lines)
  - [ ] JWT workflow documented with examples
  - [ ] MFA integration explained
  - [ ] All curl examples tested

  **Commit**: YES
  - Message: `docs(auth): add authentication guide`
  - Files: `docs/guides/AUTHENTICATION.md`

---

- [x] 1.5. Complete TESTING.md - Testing guide

  **What to do**:
  - Create `docs/guides/TESTING.md` (currently "(Coming Soon)")
  - Document unit testing pure functions
  - Document integration testing with mocks
  - Document contract testing with database
  - Include test commands and watch mode
  - Cover snapshot testing for validation

  **Must NOT do**:
  - Property-based testing (defer)
  - Performance testing (defer)

  **Parallelizable**: YES (with 1.1, 1.2, 1.3, 1.4)

  **References**:
  - `AGENTS.md` - Testing section
  - `libs/user/test/` - Example test patterns
  - `docs/testing/ADMIN_TESTING_GUIDE.md` - Admin testing

  **Acceptance Criteria**:
  - [ ] `docs/guides/TESTING.md` exists (500+ lines)
  - [ ] Unit/integration/contract testing explained
  - [ ] Test commands documented
  - [ ] Snapshot testing covered

  **Commit**: YES
  - Message: `docs(testing): add testing guide`
  - Files: `docs/guides/TESTING.md`

---

- [x] 1.6. Update docs/README.md to mark completed docs

  **What to do**:
  - Update status table in `docs/README.md`
  - Change "(Coming Soon)" to "✅ Complete" for finished docs
  - Verify all links work
  - Update "Last updated" timestamp

  **Must NOT do**:
  - Change structure of docs hub
  - Add new planned docs

  **Parallelizable**: NO (depends on 1.1-1.5)

  **References**:
  - `docs/README.md` - Documentation hub (lines 200-220 status table)

  **Acceptance Criteria**:
  - [ ] Status table updated with ✅ for completed docs
  - [ ] All links verified working
  - [ ] Timestamp updated

  **Commit**: YES
  - Message: `docs: update documentation status table`
  - Files: `docs/README.md`

---

### PHASE 2: Starter Templates CLI (Weeks 4-6)

- [x] 2.1. Implement `boundary new` command in scaffolder

  **What to do**:
  - Based on Phase 0 audit, extend scaffolder CLI
  - Add `new` command (not `init` - decision made)
  - Implement flags: `--name`, `--output-dir`, `--dry-run`
  - Generate project structure matching `docs/launch/starter/outline.md`
  - No interactive prompts - flags only

  **CLI Contract** (consistent invocation):
  - Primary invocation: `clojure -M -m boundary.scaffolder.shell.cli-entry new --name myapp`
  - Short form in docs: `boundary new myapp` (requires shell alias, documented in README)
  - Shell alias suggestion: `alias boundary='clojure -M -m boundary.scaffolder.shell.cli-entry'`

  **Must NOT do**:
  - Multiple templates (single "starter" only)
  - Interactive prompts or wizard
  - Template versioning system

  **Parallelizable**: NO (depends on Phase 0)

  **References**:
  - `docs/adr/ADR-XXX-boundary-new-command.md` - Design decision from Phase 0
  - `libs/scaffolder/src/boundary/scaffolder/cli.clj:413-431` - `dispatch-command` function to extend (add `"new"` case)
  - `docs/launch/starter/outline.md` - Template structure (lines 9-38)
  - `libs/scaffolder/README.md` - CLI documentation pattern

  **Acceptance Criteria**:
  - [ ] `clojure -M -m boundary.scaffolder.shell.cli-entry new --name myapp` works
  - [ ] `--dry-run` shows files without writing
  - [ ] `--help` shows command usage
  - [ ] Generated project matches outline.md structure

  **Manual Verification**:
  - [ ] `boundary new myapp --dry-run` shows expected file list
  - [ ] Created project directory contains all expected files
  - [ ] No unexpected files or directories created

  **Commit**: YES
  - Message: `feat(scaffolder): add boundary new command for project creation`
  - Files: `libs/scaffolder/src/boundary/scaffolder/**/*.clj`

---

- [x] 2.2. Create project template resources

  **What to do**:
  - Create `resources/scaffolder/project-templates/starter/` directory (does not exist yet - this task creates it)
  - Create template files in the new directory
  - Include all files from `docs/launch/starter/outline.md`
  - **Important**: Template config.edn should default to SQLite (development-friendly), not PostgreSQL
    - Note: Current `resources/conf/dev/config.edn` uses PostgreSQL, but starter template needs SQLite for zero-config quickstart

  **Templating Approach** (decision made):
  - Use scaffolder's existing template system in `boundary.scaffolder.core.template` namespace
  - Template files use `.template` extension (not mustache - scaffolder uses string interpolation)
  - Substitution variables: `{{project-name}}`, `{{project-name-snake}}`, `{{project-name-pascal}}`
  - Use existing functions: `kebab->pascal`, `kebab->snake` from `core/template.clj`
  - Template loading: Add `load-project-template` function in `core/generators.clj`
  - Template discovery: Scan `resources/scaffolder/project-templates/starter/` at generation time
  - Static files (no templating): Copy directly (e.g., .gitignore, images)
  - Path templating: Replace `{{project-name}}` in directory names (e.g., `src/{{project-name}}/core/`)

  **Must NOT do**:
  - Create multiple template variants
  - Add external templating library (use existing scaffolder system)
  - External template registry

  **Parallelizable**: NO (depends on 2.1 structure)

  **References**:
  - `docs/launch/starter/outline.md` - Complete structure to replicate
  - `libs/scaffolder/src/boundary/scaffolder/core/template.clj` - Existing template functions (string transformations)
  - `libs/scaffolder/src/boundary/scaffolder/core/generators.clj` - Where to add project template loading
  - `libs/scaffolder/README.md:249-262` - Template customization section
  - `resources/conf/dev/config.edn` - Reference for config structure (but use SQLite, not PostgreSQL)

  **Acceptance Criteria**:
  - [ ] `resources/scaffolder/project-templates/starter/` directory exists
  - [ ] Contains: deps.edn.template, config.edn.template, README.md.template
  - [ ] Contains: user module template, product module template
  - [ ] All templates render with `{{project-name}}` substitution

  **Commit**: YES
  - Message: `feat(scaffolder): add starter project templates`
  - Files: `resources/scaffolder/project-templates/starter/**/*`

---

- [x] 2.3. Add tests for `boundary new` command

  **What to do**:
  - Write unit tests for template generation (pure functions)
  - Write integration tests for CLI command
  - Test `--dry-run` mode
  - Test error handling (existing directory, invalid name)

  **Must NOT do**:
  - Test actual project execution (that's manual verification)

  **Parallelizable**: NO (depends on 2.1, 2.2)

  **References**:
  - `libs/scaffolder/test/` - Existing test patterns

  **Acceptance Criteria**:
  - [ ] Unit tests for template rendering
  - [ ] Integration test for CLI command
  - [ ] `clojure -M:test:db/h2 :scaffolder` passes
  - [ ] Tests cover: success, --dry-run, error cases

  **Commit**: YES
  - Message: `test(scaffolder): add tests for boundary new command`
  - Files: `libs/scaffolder/test/**/*_test.clj`

---

- [x] 2.4. Verify generated project is fully functional

  **What to do**:
  - Run `boundary new testapp` in temp directory
  - Execute: `cd testapp && clojure -M:test:db/h2` → all tests pass
  - Execute: `clojure -M:repl-clj` → `(ig-repl/go)` → server starts
  - Verify: `http://localhost:3000` loads
  - Verify: `/web/admin` accessible
  - Clean up temp directory

  **Must NOT do**:
  - Modify framework code based on results (log issues only)

  **Parallelizable**: NO (depends on 2.1-2.3)

  **References**:
  - `docs/launch/starter/outline.md` - Expected quickstart commands

  **Acceptance Criteria**:
  - [ ] `clojure -M:test:db/h2` in generated project passes
  - [ ] Server starts with `(ig-repl/go)`
  - [ ] Home page loads at localhost:3000
  - [ ] Admin UI accessible at /web/admin

  **Manual Verification**:
  Using interactive_bash (tmux session):
  - [ ] Command: `cd /tmp && boundary new testapp`
  - [ ] Command: `cd /tmp/testapp && clojure -M:test:db/h2`
  - [ ] Expected output contains: "0 failures"
  - [ ] Command: Start REPL and verify server

  **Commit**: NO (verification only)

---

- [x] 2.5. Update scaffolder README with `new` command documentation

  **What to do**:
  - Add `new` command section to `libs/scaffolder/README.md`
  - Document all flags
  - Add examples
  - Update features table

  **Must NOT do**:
  - Major README restructure

  **Parallelizable**: NO (depends on 2.1-2.4)

  **References**:
  - `libs/scaffolder/README.md` - Current documentation

  **Acceptance Criteria**:
  - [ ] `new` command documented with examples
  - [ ] Flags documented (--name, --output-dir, --dry-run)
  - [ ] Features table updated

  **Commit**: YES
  - Message: `docs(scaffolder): add boundary new command documentation`
  - Files: `libs/scaffolder/README.md`

---

### PHASE 3: Email Module (Weeks 7-9)

- [x] 3.1. Create libs/email directory structure

  **What to do**:
  - Create `libs/email/` following existing library pattern
  - Create: `src/boundary/email/core/`, `src/boundary/email/shell/`
  - Create: `ports.clj`, `schema.clj`
  - Create: `deps.edn` for library
  - Create: `README.md` skeleton

  **Must NOT do**:
  - Implement functionality yet

  **Parallelizable**: NO (first step of Phase 3)

  **References**:
  - `libs/jobs/` - Pattern to follow (most mature module)
  - `libs/jobs/README.md` - Documentation quality benchmark

  **Acceptance Criteria**:
  - [ ] `libs/email/deps.edn` exists
  - [ ] `libs/email/src/boundary/email/` structure matches jobs module
  - [ ] `libs/email/README.md` skeleton exists

  **Commit**: YES
  - Message: `feat(email): add email module directory structure`
  - Files: `libs/email/**/*`

---

- [x] 3.2. Implement email core layer (pure functions)

  **What to do**:
  - Create `libs/email/src/boundary/email/core/email.clj`
  - Implement: `prepare-email`, `validate-recipients`, `format-headers`
  - All functions must be pure (no I/O)
  - Create `libs/email/src/boundary/email/schema.clj` with Malli schemas

  **Must NOT do**:
  - SMTP connection logic (that's shell layer)
  - HTML rendering (out of scope)

  **Parallelizable**: YES (with 3.3)

  **References**:
  - `libs/jobs/src/boundary/jobs/core/` - Core layer pattern
  - `libs/jobs/src/boundary/jobs/schema.clj` - Schema pattern

  **Acceptance Criteria**:
  - [ ] `prepare-email` creates email map with: to, from, subject, body
  - [ ] `validate-recipients` validates email addresses
  - [ ] All functions are pure (no side effects)
  - [ ] Malli schemas for email, recipient, attachment

  **Commit**: YES
  - Message: `feat(email): add core email processing functions`
  - Files: `libs/email/src/boundary/email/core/*.clj`, `libs/email/src/boundary/email/schema.clj`

---

- [x] 3.3. Implement email ports (protocols)

  **What to do**:
  - Create `libs/email/src/boundary/email/ports.clj`
  - Define `EmailSenderProtocol` with: `send-email`, `send-email-async`
  - Define `EmailQueueProtocol` with: `queue-email`, `process-queue`

  **Must NOT do**:
  - Implementations (that's shell layer)

  **Parallelizable**: YES (with 3.2)

  **References**:
  - `libs/jobs/src/boundary/jobs/ports.clj` - Ports pattern

  **Acceptance Criteria**:
  - [ ] `EmailSenderProtocol` defined
  - [ ] `EmailQueueProtocol` defined
  - [ ] Docstrings for all protocol methods

  **Commit**: YES
  - Message: `feat(email): add email port definitions`
  - Files: `libs/email/src/boundary/email/ports.clj`

---

- [x] 3.4. Implement SMTP adapter (shell layer)

  **What to do**:
  - Create `libs/email/src/boundary/email/shell/smtp_adapter.clj`
  - Implement `SmtpEmailSender` record implementing `EmailSenderProtocol`
  - Use javax.mail or postal library for SMTP
  - Support: host, port, username, password, TLS configuration
  - Implement synchronous `send-email`

  **Must NOT do**:
  - HTML email templates
  - Attachment handling (defer to v2)
  - Connection pooling (defer to v2)

  **Parallelizable**: NO (depends on 3.2, 3.3)

  **References**:
  - `libs/jobs/src/boundary/jobs/shell/` - Shell layer pattern
  - Java mail docs for SMTP implementation

  **Acceptance Criteria**:
  - [ ] `SmtpEmailSender` implements `EmailSenderProtocol`
  - [ ] Configurable: host, port, username, password, tls
  - [ ] `send-email` sends actual SMTP email
  - [ ] Proper error handling with typed exceptions

  **Commit**: YES
  - Message: `feat(email): add SMTP email adapter`
  - Files: `libs/email/src/boundary/email/shell/smtp_adapter.clj`

---

- [x] 3.5. Integrate email with jobs module for async sending

  **What to do**:
  - Create `libs/email/src/boundary/email/shell/jobs_integration.clj`
  - Implement `EmailJobProcessor` that processes email jobs
  - Register email job type with jobs module
  - `send-email-async` queues job via jobs module

  **Optional Dependency Mechanism** (decision made):
  - `libs/email/deps.edn` does NOT include jobs as a dependency
  - Jobs integration namespace uses dynamic loading via `requiring-resolve`
  - Pattern: `(when-let [enqueue-fn (requiring-resolve 'boundary.jobs.ports/enqueue-job!)] ...)`
  - Fallback behavior: `send-email-async` throws descriptive error if jobs module not present
  - README documents: "For async email, add boundary/jobs to your deps.edn"
  - This is standard Clojure pattern for optional dependencies using `requiring-resolve`

  **Must NOT do**:
  - Hard `:require` of jobs namespaces in email module
  - Make jobs a transitive dependency of email
  - Modify jobs module code

  **Parallelizable**: NO (depends on 3.4)

  **References**:
  - `libs/jobs/README.md` - Job processor pattern
  - `libs/jobs/src/boundary/jobs/ports.clj` - Job protocols (`IJobQueue` with `enqueue-job!`, `schedule-job!`, `dequeue-job!`)

  **Acceptance Criteria**:
  - [ ] `send-email-async` queues email job
  - [ ] `EmailJobProcessor` processes queued emails
  - [ ] Integration is optional (email works without jobs for sync)
  - [ ] README documents both sync and async patterns

  **Commit**: YES
  - Message: `feat(email): add jobs module integration for async sending`
  - Files: `libs/email/src/boundary/email/shell/jobs_integration.clj`

---

- [x] 3.6. Add email module tests

  **What to do**:
  - Create unit tests for core layer (pure functions)
  - Create integration tests with mock SMTP server
  - Create contract tests for SMTP adapter
  - Test job integration if jobs module available

  **Must NOT do**:
  - Test against real SMTP servers in CI

  **Parallelizable**: NO (depends on 3.4, 3.5)

  **References**:
  - `libs/jobs/test/` - Test patterns

  **Acceptance Criteria**:
  - [ ] `clojure -M:test:db/h2 :email` passes
  - [ ] Unit tests for prepare-email, validate-recipients
  - [ ] Integration test with mock SMTP
  - [ ] 80%+ code coverage for core layer

  **Commit**: YES
  - Message: `test(email): add email module tests`
  - Files: `libs/email/test/**/*_test.clj`

---

- [x] 3.7. Complete email module README

  **What to do**:
  - Write comprehensive README matching `libs/jobs/README.md` quality
  - Document: installation, quick start, SMTP configuration
  - Document: sync vs async sending patterns
  - Document: integration with jobs module
  - Include API reference, examples, troubleshooting

  **Must NOT do**:
  - Document features not implemented

  **Parallelizable**: NO (depends on 3.6)

  **References**:
  - `libs/jobs/README.md` - Quality benchmark (700+ lines)

  **Acceptance Criteria**:
  - [ ] `libs/email/README.md` is 400+ lines
  - [ ] Features table complete
  - [ ] Quick start with working examples
  - [ ] Configuration reference complete
  - [ ] Troubleshooting section included

  **Commit**: YES
  - Message: `docs(email): complete email module documentation`
  - Files: `libs/email/README.md`

---

### PHASE 4: Demo & Starter Repo (Week 10)

- [ ] 4.1. Create boundary-starter GitHub repository

  **What to do**:
  - Create public repo: `github.com/thijs-creemers/boundary-starter`
  - Use `boundary new boundary-starter` to generate initial content
  - Add comprehensive README with quickstart
  - Add Dockerfile for container builds
  - Push to GitHub

  **Must NOT do**:
  - Add advanced examples beyond starter outline
  - Include experimental features

  **Parallelizable**: NO (first step of Phase 4)

  **References**:
  - `docs/launch/starter/outline.md` - Complete structure
  - Phase 2 `boundary new` command output

  **Acceptance Criteria**:
  - [ ] https://github.com/thijs-creemers/boundary-starter is public
  - [ ] README has working quickstart
  - [ ] Clone → follow README → server starts
  - [ ] Tests pass out of the box

  **Manual Verification**:
  Using Playwright browser:
  - [ ] Navigate to: https://github.com/thijs-creemers/boundary-starter
  - [ ] Verify: Repository is public and visible
  - [ ] Verify: README displays with quickstart

  **Commit**: N/A (external repo)

---

- [ ] 4.2. Update TODO placeholders in launch materials

  **What to do**:
  - Replace `TODO:starter-repo-url` with `https://github.com/thijs-creemers/boundary-starter` in:
    - `docs/launch/demo/script.md` (lines 9, 71)
    - `docs/launch/starter/outline.md` (line 101)
  - Replace `TODO:docs-site-url` with `https://github.com/thijs-creemers/boundary-docs` in:
    - `docs/launch/demo/script.md` (line 72)
  - Verify all starter repo links work

  **TODO Policy (demo-video-url)**:
  - `TODO:demo-video-url` placeholders REMAIN until user records video (out of scope for this roadmap)
  - Do NOT replace these with placeholder text or remove them
  - Files containing demo-video-url TODOs that will remain as-is:
    - `docs/launch/posts/why-boundary.md` (line 81)
    - `docs/launch/announcements/slack.md` (line 12)
    - `docs/launch/announcements/reddit.md` (line 20)
    - `docs/launch/announcements/clojureverse.md` (line 23)
  - After user records video, they will manually replace these TODOs

  **Must NOT do**:
  - Change content beyond placeholder replacement
  - Fill in demo video URL (user will provide after recording)
  - Replace demo-video-url TODOs with "Coming soon" or other placeholders

  **Parallelizable**: NO (depends on 4.1)

  **References**:
  - `docs/launch/demo/script.md:9,71,72` - TODO placeholders (starter-repo-url, docs-site-url)
  - `docs/launch/starter/outline.md:101` - TODO placeholder (starter-repo-url)
  - `docs/launch/posts/why-boundary.md:81` - demo video TODO (leave as-is)
  - `docs/launch/announcements/*.md` - announcement drafts with demo-video-url TODOs (leave as-is)

  **Acceptance Criteria**:
  - [ ] `grep -r "TODO:starter-repo-url" docs/launch/` returns 0 matches
  - [ ] `grep -r "TODO:docs-site-url" docs/launch/` returns 0 matches
  - [ ] `grep -r "TODO:demo-video-url" docs/launch/` returns exactly 4 matches (expected - leave as-is)
  - [ ] Starter repo URL is correct and accessible
  - [ ] All non-video links verified working

  **Commit**: YES
  - Message: `docs(launch): update placeholder URLs in launch materials`
  - Files: `docs/launch/**/*.md`

---

- [ ] 4.3. Update main README with starter repo link

  **What to do**:
  - Add "Quick Start" section to main `README.md`
  - Link to boundary-starter repo
  - Update Quick Start in main README
  - Ensure consistency with docs/QUICKSTART.md

  **Must NOT do**:
  - Major restructure of main README

  **Parallelizable**: NO (depends on 4.1)

  **References**:
  - `README.md` - Main project README
  - `docs/QUICKSTART.md` - Existing quickstart

  **Acceptance Criteria**:
  - [ ] Main README links to boundary-starter
  - [ ] Quick Start section is prominent
  - [ ] Links verified working

  **Commit**: YES
  - Message: `docs: add starter repo link to main README`
  - Files: `README.md`

---

### PHASE 5: Real-time/WebSocket Module (Weeks 11-12)

- [x] 5.0. Create WebSocket architecture mini-document

  **What to do**:
  - Before implementation, write `docs/adr/ADR-003-websocket-architecture.md`
  - Define: primary use case (live updates, notifications)
  - Define: authentication approach (JWT in connection params)
  - Define: message format (EDN or JSON)
  - Define: channel/topic structure
  - Explicitly document: no rooms, no presence, no Redis pub/sub

  **Must NOT do**:
  - Start implementation before design doc approved

  **Parallelizable**: NO (first step of Phase 5)

  **References**:
  - `docs/adr/` - Existing ADR format
  - Phoenix Channels documentation - inspiration

  **Acceptance Criteria**:
  - [ ] `docs/adr/ADR-003-websocket-architecture.md` exists
  - [ ] Primary use case documented
  - [ ] Authentication approach documented
  - [ ] Scope boundaries (no rooms, no Redis) documented

  **Commit**: YES
  - Message: `docs(adr): add WebSocket architecture decision`
  - Files: `docs/adr/ADR-003-websocket-architecture.md`

---

- [x] 5.1. Create libs/realtime directory structure

  **What to do**:
  - Create `libs/realtime/` following existing library pattern
  - Create: `src/boundary/realtime/core/`, `src/boundary/realtime/shell/`
  - Create: `ports.clj`, `schema.clj`
  - Create: `deps.edn` for library
  - Create: `README.md` skeleton

  **Must NOT do**:
  - Implement functionality yet

  **Parallelizable**: NO (depends on 5.0)

  **References**:
  - `libs/jobs/` - Pattern to follow

  **Acceptance Criteria**:
  - [ ] `libs/realtime/deps.edn` exists
  - [ ] Directory structure matches jobs module
  - [ ] `libs/realtime/README.md` skeleton exists

  **Commit**: YES
  - Message: `feat(realtime): add realtime module directory structure`
  - Files: `libs/realtime/**/*`

---

- [x] 5.2. Implement realtime core layer (pure functions)

  **What to do**:
  - Create `libs/realtime/src/boundary/realtime/core/messages.clj`
  - Implement: `parse-message`, `format-message`, `validate-message`
  - Create `libs/realtime/src/boundary/realtime/schema.clj` with Malli schemas
  - All functions must be pure (no I/O)

  **Must NOT do**:
  - WebSocket connection handling (that's shell layer)
  - Authentication logic (that's shell layer)

  **Parallelizable**: YES (with 5.3)

  **References**:
  - `libs/email/src/boundary/email/core/` - Core layer pattern (from Phase 3)

  **Acceptance Criteria**:
  - [ ] Message parsing/formatting functions are pure
  - [ ] Malli schemas for messages, topics, subscriptions
  - [ ] No I/O in core layer

  **Commit**: YES
  - Message: `feat(realtime): add core message processing functions`
  - Files: `libs/realtime/src/boundary/realtime/core/*.clj`, `libs/realtime/src/boundary/realtime/schema.clj`

---

- [x] 5.3. Implement realtime ports (protocols)

  **What to do**:
  - Create `libs/realtime/src/boundary/realtime/ports.clj`
  - Define `WebSocketConnectionProtocol`: `connect`, `disconnect`, `send-message`
  - Define `PubSubProtocol`: `subscribe`, `unsubscribe`, `broadcast`
  - Define `AuthenticationProtocol`: `authenticate-connection`

  **Must NOT do**:
  - Implementations (that's shell layer)

  **Parallelizable**: YES (with 5.2)

  **References**:
  - `libs/email/src/boundary/email/ports.clj` - Ports pattern

  **Acceptance Criteria**:
  - [ ] All protocols defined with docstrings
  - [ ] Clear separation of concerns

  **Commit**: YES
  - Message: `feat(realtime): add realtime port definitions`
  - Files: `libs/realtime/src/boundary/realtime/ports.clj`

---

- [x] 5.4. Implement WebSocket handler (shell layer)

  **What to do**:
  - Create `libs/realtime/src/boundary/realtime/shell/websocket_handler.clj`
  - Implement connection lifecycle: open, message, close, error
  - Implement JWT authentication from query params
  - Integrate with existing user module for token validation

  **Implementation Decisions** (made):
  - **WebSocket library**: Ring/Jetty with WebSocket upgrade (platform uses `ring/ring-jetty-adapter`, not http-kit)
  - **Query param for token**: `?token=<jwt>` (standard pattern, works with browser WebSocket API)
  - **JWT validation function**: Call `boundary.user.shell.auth/validate-jwt-token` 
    - Located at: `libs/user/src/boundary/user/shell/auth.clj:90-100`
    - Returns: `{:valid? true :claims {...}}` or `{:valid? false :error string}`
    - Claims contain: `:sub` (user-id), `:email`, `:role`, `:iat`, `:exp`
  - **Connection flow**:
    1. Client connects with `ws://host/ws?token=eyJhbG...`
    2. Handler extracts token from query params
    3. Handler calls `validate-jwt-token` 
    4. If valid, associate user-id with connection; if invalid, close with 4401

  **Must NOT do**:
  - Implement rooms/channels beyond simple topics
  - Implement presence tracking
  - Implement reconnection logic (client responsibility)

  **Parallelizable**: NO (depends on 5.2, 5.3)

  **References**:
  - `libs/platform/src/boundary/platform/shell/interfaces/web/websockets.clj` - Empty WebSocket file (to implement)
  - `libs/platform/src/boundary/platform/shell/http/ring_jetty_server.clj` - HTTP server (Jetty-based)
  - `libs/platform/src/boundary/platform/shell/http/reitit_router.clj` - Router for WebSocket route registration
  - `libs/user/src/boundary/user/shell/auth.clj:90-100` - `validate-jwt-token` function
  - `libs/user/src/boundary/user/shell/auth.clj:69-88` - `create-jwt-token` for claims format
  - Ring WebSocket adapter documentation: https://github.com/ring-clojure/ring/wiki/WebSockets

  **Acceptance Criteria**:
  - [ ] WebSocket endpoint accepts connections
  - [ ] JWT authentication works from query param
  - [ ] Messages can be sent and received
  - [ ] Graceful connection close handling

  **Commit**: YES
  - Message: `feat(realtime): add WebSocket handler with JWT auth`
  - Files: `libs/realtime/src/boundary/realtime/shell/websocket_handler.clj`

---

- [ ] 5.5. Implement in-memory pub/sub (shell layer)

  **What to do**:
  - Create `libs/realtime/src/boundary/realtime/shell/pubsub.clj`
  - Implement simple in-memory pub/sub using core.async
  - Support: subscribe to topic, unsubscribe, broadcast to topic
  - Single-server only (no Redis)

  **Must NOT do**:
  - Redis integration (defer to v2)
  - Multi-server support
  - Persistence of messages

  **Parallelizable**: NO (depends on 5.4)

  **References**:
  - core.async documentation
  - `libs/cache/` - In-memory pattern

  **Acceptance Criteria**:
  - [ ] Clients can subscribe to topics
  - [ ] Broadcast to topic reaches all subscribers
  - [ ] Unsubscribe removes from topic
  - [ ] Clean resource cleanup on disconnect

  **Commit**: YES
  - Message: `feat(realtime): add in-memory pub/sub implementation`
  - Files: `libs/realtime/src/boundary/realtime/shell/pubsub.clj`

---

- [x] 5.6. Add realtime module tests

  **What to do**:
  - Create unit tests for core layer (message parsing)
  - Create integration tests for WebSocket connections
  - Test authentication flow
  - Test pub/sub functionality

  **Must NOT do**:
  - Load testing (defer)

  **Parallelizable**: NO (depends on 5.5)

  **References**:
  - `libs/email/test/` - Test patterns from Phase 3

  **Acceptance Criteria**:
  - [ ] `clojure -M:test:db/h2 :realtime` passes
  - [ ] Unit tests for message parsing
  - [ ] Integration test for WebSocket connection
  - [ ] Test for authenticated connection

  **Commit**: YES
  - Message: `test(realtime): add realtime module tests`
  - Files: `libs/realtime/test/**/*_test.clj`

---

- [x] 5.7. Complete realtime module README

  **What to do**:
  - Write comprehensive README matching `libs/jobs/README.md` quality
  - Document: installation, quick start, WebSocket endpoint
  - Document: authentication, message format, pub/sub
  - Include client-side JavaScript example
  - Document limitations (single-server, no presence)

  **Must NOT do**:
  - Document features not implemented

  **Parallelizable**: NO (depends on 5.6)

  **References**:
  - `libs/jobs/README.md` - Quality benchmark

  **Acceptance Criteria**:
  - [ ] `libs/realtime/README.md` is 400+ lines
  - [ ] Features table complete
  - [ ] Quick start with working examples
  - [ ] JavaScript client example included
  - [ ] Limitations clearly documented

  **Commit**: YES
  - Message: `docs(realtime): complete realtime module documentation`
  - Files: `libs/realtime/README.md`

---

### PHASE 6: Multi-tenancy Design (Week 13)

- [ ] 6.1. Research multi-tenancy patterns in Clojure ecosystem

  **What to do**:
  - Research schema-per-tenant in PostgreSQL
  - Research row-level security alternatives
  - Document pros/cons of each approach
  - Identify patterns from other frameworks (Rails, Django)
  - Create research notes

  **Must NOT do**:
  - Write implementation code

  **Parallelizable**: NO (first step of Phase 6)

  **References**:
  - PostgreSQL schema documentation
  - Rails multi-tenancy gems (apartment, acts_as_tenant)
  - Django multi-tenancy packages

  **Acceptance Criteria**:
  - [ ] Research notes document exists
  - [ ] Schema-per-tenant pros/cons listed
  - [ ] Row-level security pros/cons listed
  - [ ] 3+ external references cited

  **Commit**: YES (research notes)
  - Message: `docs(research): add multi-tenancy pattern research`
  - Files: `docs/research/multi-tenancy-patterns.md`

---

- [ ] 6.2. Write multi-tenancy architecture design document

  **What to do**:
  - Create `docs/adr/ADR-004-multi-tenancy-architecture.md`
  - Document chosen approach: schema-per-tenant
  - Document tenant identification strategies (subdomain, header, JWT claim)
  - Document database migration strategy
  - Document integration with: jobs, cache, email, realtime modules
  - Estimate implementation effort

  **Must NOT do**:
  - Implement any code
  - Make final decisions on tenant identification (document options)

  **Parallelizable**: NO (depends on 6.1)

  **References**:
  - `docs/research/multi-tenancy-patterns.md` - Research from 6.1
  - `docs/adr/` - ADR format
  - `libs/jobs/`, `libs/cache/`, `libs/realtime/` - Modules to consider

  **Acceptance Criteria**:
  - [ ] ADR document is 500+ lines
  - [ ] Schema-per-tenant approach documented
  - [ ] Tenant identification options documented (not decided)
  - [ ] Module integration considerations documented
  - [ ] Implementation effort estimate included (weeks, not days)

  **Commit**: YES
  - Message: `docs(adr): add multi-tenancy architecture design`
  - Files: `docs/adr/ADR-004-multi-tenancy-architecture.md`

---

- [ ] 6.3. Create multi-tenancy implementation roadmap

  **What to do**:
  - Based on ADR, create implementation plan
  - Break down into phases/milestones
  - Estimate timeline for full implementation
  - Identify prerequisites and dependencies
  - Document as appendix to ADR or separate plan

  **Must NOT do**:
  - Start implementation

  **Parallelizable**: NO (depends on 6.2)

  **References**:
  - `docs/adr/ADR-004-multi-tenancy-architecture.md` - ADR from 6.2

  **Acceptance Criteria**:
  - [ ] Implementation roadmap exists
  - [ ] Phases/milestones defined
  - [ ] Timeline estimate provided
  - [ ] Dependencies identified

  **Commit**: YES
  - Message: `docs(planning): add multi-tenancy implementation roadmap`
  - Files: `docs/adr/ADR-XXX-multi-tenancy-architecture.md` (appendix) or `docs/planning/multi-tenancy-implementation.md`

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 0.1 | `docs(adr): add boundary new command design decision` | ADR doc | Read review |
| 1.1-1.5 | `docs(*)` per doc | Each doc file | Link validation |
| 1.6 | `docs: update documentation status table` | docs/README.md | - |
| 2.1-2.5 | `feat(scaffolder)` / `test` / `docs` | scaffolder files | `clojure -M:test:db/h2 :scaffolder` |
| 3.1-3.7 | `feat(email)` / `test` / `docs` | email module files | `clojure -M:test:db/h2 :email` |
| 4.1-4.3 | `docs(launch)` / `docs` | launch materials | Link validation |
| 5.0-5.7 | `docs(adr)` / `feat(realtime)` / `test` / `docs` | realtime module | `clojure -M:test:db/h2 :realtime` |
| 6.1-6.3 | `docs(research)` / `docs(adr)` / `docs(planning)` | design docs | Read review |

---

## Success Criteria

### Verification Commands
```bash
# All tests pass
clojure -M:test:db/h2                              # Expected: 0 failures

# New modules have tests
clojure -M:test:db/h2 :email                       # Expected: 0 failures
clojure -M:test:db/h2 :realtime                    # Expected: 0 failures

# Lint passes
clojure -M:clj-kondo --lint libs/*/src             # Expected: 0 errors

# boundary new works
clojure -M -m boundary.scaffolder.shell.cli-entry new --name testapp --dry-run
# Expected: File list output

# Starter repo accessible
curl -I https://github.com/thijs-creemers/boundary-starter
# Expected: HTTP 200
```

### Final Checklist
- [ ] Phase 0: Scaffolder CLI audit complete
- [ ] Phase 1: 5 documentation guides complete (TUTORIAL, IDE_SETUP, DATABASE_SETUP, AUTHENTICATION, TESTING)
- [ ] Phase 2: `boundary new` command works end-to-end
- [ ] Phase 3: Email module with SMTP + jobs integration complete
- [ ] Phase 4: boundary-starter repo public, starter-repo-url and docs-site-url TODOs replaced (demo-video-url TODOs remain for user)
- [ ] Phase 5: WebSocket module with JWT auth complete
- [ ] Phase 6: Multi-tenancy ADR and implementation roadmap complete
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] All tests pass
- [ ] All links validated
