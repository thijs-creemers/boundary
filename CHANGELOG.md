# Changelog

All notable changes to the Boundary Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **`boundary-admin`**: `schema_repository/get-entity-config` now uses `:table-name` from manual entity config when fetching table metadata, so entities whose key differs from their table name (e.g. `:users` → `auth_users`) resolve correctly (BOU-28).
- **`boundary-admin`**: `bulk-delete-entities` now targets `:soft-delete-table` instead of `:table-name` when soft-deleting, fixing bulk deletes in split-table setups (BOU-28).
- **`boundary-admin`**: `update-entity` and `update-entity-field` now use `execute-update!` for DML statements instead of `execute-one!`, fixing UPDATE execution in both split-table and single-table paths (BOU-28).
- **`boundary-admin`**: Added `:soft-delete true` to the default `users` admin entity config so soft-delete is enabled out of the box (BOU-28).
- **`boundary-tools`**: `bb create-admin` now works in freshly generated projects (BOU-27). The command previously shelled out to `clojure -M:cli:db` which requires `boundary.cli` — a monorepo-only namespace never included in published libraries. Replaced with `clojure -M:user-cli` which calls `boundary.user.shell.cli-entry/run-cli!` directly via `-e` eval, requiring no unpublished code.
- **`boundary-cli`**: Generated `deps.edn` now includes a `:user-cli` alias with all four JDBC drivers (SQLite, PostgreSQL, H2, MySQL) so `bb create-admin` works regardless of which database adapter the project is configured to use.
- **`boundary-cli`**: Generated `config.clj` now defines `user-validation-config`, which `boundary.user.shell.cli-entry` resolves at runtime via `requiring-resolve`.
- **`boundary-tools`**: `bb create-admin` passes the target environment via `BND_ENV` environment variable instead of `-J-Denv=`, matching how `boundary.config/load-config` actually reads the active profile.

## [1.0.1-alpha-20] - 2026-05-01

### Fixed

- **`boundary-cli`**: `boundary new` now generates a full `boundary-tools` task suite in `bb.edn` instead of a minimal 3-task config. The old template used broken `(clojure ["-M:repl-clj"])` syntax that caused `FileNotFoundException: [-M:repl-clj]` on `bb repl`.
- **`boundary-cli`**: Generated `deps.edn` now uses `:repl` alias (consistent with generated-project convention; monorepo uses `:repl-clj`).

### Changed

- All 23 libraries bumped to `1.0.1-alpha-20` to re-align lockstep versioning.

## [1.0.1-alpha-14] - 2026-04-25

### Fixed

- **`boundary-tools`**: `bb scaffold generate` now works in projects created from `boundary-starter` — scaffolder is injected via `-Sdeps` instead of requiring it on the classpath.
- **`boundary-tools`**: `bb smoke-check` no longer fails in generated projects — removed monorepo-only `:docs-lint` alias from required checks.
- **`boundary-tools`**: `bb check` linting no longer includes `libs/*/src libs/*/test` paths when not in the monorepo.
- **`boundary-tools`**: `bb install-hooks` gives a friendly message instead of a Java exception when run outside a git repository.
- **`boundary-tools`**: AI CLI (`bb ai`) falls back to environment variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OLLAMA_URL`) when config has `:provider :no-op` or no AI config is present.
- **`boundary-ai`**: OpenAI-compatible base URLs with a trailing `/v1` suffix no longer produce double `/v1/v1/chat/completions` paths.
- **`boundary-scaffolder`**: Generated `deps.edn` now includes `:clj-kondo` and `:migrate` aliases with all four database drivers (SQLite, PostgreSQL, H2, MySQL).

## [1.0.1-alpha-13] - 2026-04-20

### Added

#### `boundary-devtools` — DX Vision: 6-phase developer experience overhaul
- **Phase 1 — Zero-Friction Onboarding**: Error codes (`BND-*`) with structured messages, ADRs for devtools guidance engine, REPL command center, dev dashboard, error experience, and progressive learning (ADR-024 through ADR-029).
- **Phase 2 — REPL Power**: Introspection tools (`boundary.devtools.core.introspection`), schema exploration (`schema-tools`), documentation lookup (`documentation`), and guidance engine (`guidance`). REPL namespace (`boundary.devtools.shell.repl`) with unified API.
- **Phase 3 — Error Experience**: Error classifier, enricher, and formatter for human-friendly Clojure error messages. Auto-fix suggestions (`boundary.devtools.core.auto_fix`), stacktrace parser, FC/IS checker, HTTP error middleware, and REPL error handler.
- **Phase 4 — Dev Dashboard**: Browser-based dashboard (`localhost:9090`) with pages for system overview, routes, schemas, database, errors, requests, and docs. Hiccup-rendered with custom CSS, served via Ring.
- **Phase 5 — Advanced REPL**: Request/response recording (`boundary.devtools.core.recording`), route testing (`router`), and rapid prototyping (`prototype`). Shell adapters for file-based recording persistence and route simulation.
- **Phase 6 — Dashboard Extensions + AI**: Config editor, security analyzer, jobs dashboard page. AI-powered REPL helpers for code explanation, refactoring suggestions, and documentation generation.

#### `boundary-ai` — REPL AI integration (Phase 6)
- New AI-powered REPL functions: `explain-code`, `suggest-refactor`, `generate-docs` in `boundary.ai.shell.repl`.
- Prompt builders for Phase 6 features in `boundary.ai.core.prompts`.

### Fixed

#### `boundary-cache` — LRU eviction bug (#137)
- **LRU eviction evicting wrong entry when timestamps are identical**: Fixed eviction logic in `boundary.cache.shell.adapters.in_memory` to correctly identify the least-recently-used entry when multiple entries share the same timestamp.

#### `boundary-tools` — BOU-15 deprecated wrapper usage scanner (BOU-15)
- **Detect `:refer`'d deprecated symbols**: `normalize-require-spec` now extracts `:refer [sym ...]` vectors alongside `:as` aliases. A new `extract-referred-symbols` function maps directly referred symbol names to their source namespace. `find-qualified-call-sites` runs a second regex pass for bare `(symbol ...)` call sites, so usage like `(:require [boundary.search.core.index :refer [build-document]])` is no longer silently missed.
- **Detect fully-qualified deprecated calls**: `find-qualified-call-sites` now unconditionally searches for `(namespace/symbol ...)` patterns regardless of whether the file has an alias or `:refer` entry. Calls like `(boundary.search.core.index/build-document ...)` are now correctly reported.

#### `ci` — E2E job disabled
- Disabled the `e2e` CI job with `if: false` to reduce pipeline run time. Tests can be run manually when needed.

### Changed

#### Security — Alpine.js CSP build (#129)
- Migrated to Alpine.js CSP-compatible build to comply with Content Security Policy. Hardened CI pipeline with stricter security checks.

#### Chore — Project data cleanup (#130)
- Moved `boundary-tools` into `libs/tools/` to follow monorepo convention. Removed redundant top-level `boundary-tools/` directory.

## [1.0.1-alpha-12] - 2026-04-06

### Added

#### `boundary-e2e` — Admin UI end-to-end test suite (BOU-10)
- **19 Clojure/spel e2e tests** for the admin Users and Tenants UI — list overviews, detail/edit forms, search with HTMX fragment updates, access control, and soft-delete.
- Shared admin helper namespace (`boundary.e2e.helpers.admin`) with `login-as-admin!`, `login-as-user!`, two-phase HTMX settle waiting (`install-htmx-settle-listener!` / `await-htmx-settle!`), and table/form query utilities.
- Tests build on BOU-9 auth helpers and `with-fresh-seed` fixture for isolated H2 state per test.

### Fixed

#### `platform` — Compile-time PostgreSQL class references
- **Removed compile-time `(:import [org.postgresql.util PGobject])` and `(instance? org.postgresql.util.PSQLException ...)`** from `boundary.user.shell.service`, `boundary.tenant.shell.persistence`, and `boundary.tenant.shell.invite-persistence`. Replaced with runtime class name checks so the REPL starts without the `:db` alias on the classpath.

#### `admin` — Table view UX improvements
- **Single-click row navigation restored**: Clicking any data cell now navigates to the edit form. Previously, editable cells blocked single-click navigation due to an overly broad exclusion in the click handler.
- **Single-click / double-click coexistence**: Added 250ms debounce so single-click navigates to the edit form while double-click triggers inline field editing without conflict.
- **Removed redundant edit action icon**: The per-row edit icon button was removed since single-click navigation covers this. The chevron-right navigation hint is retained on hover.

### Changed

#### `admin` — Compact table view layout
- **Sticky pagination footer**: Pagination bar now sticks to the bottom of the viewport when scrolling long tables, always visible without scrolling down.
- **Compact toolbar**: Replaced the large gradient hero section with a single-row toolbar combining title, record count badge, search, and action buttons — reclaiming ~120px of vertical space.
- **Action buttons right-aligned**: Delete, refresh, and create buttons are now pushed to the right side of the toolbar for visual balance.

#### `admin` — Collapsible sidebar
- **Collapsible sidebar**: Sidebar can now be collapsed to a 64px icon-only mode via toggle button or Ctrl+B keyboard shortcut. State is persisted to localStorage.
- **Hover expand**: Hovering over the collapsed sidebar temporarily expands it; moving away collapses it again. Pin button keeps it expanded.
- **CSP-safe Alpine.js store**: Moved sidebar Alpine.js store initialization from inline script to external `admin-ux.js` to comply with Content Security Policy.
- **Script load order**: `admin-ux.js` now loads before `alpine.min.js` so the sidebar store is registered before Alpine initializes.

### Added

#### `boundary-e2e` — end-to-end test suite for login sequence
- **33 Clojure/spel e2e tests** covering `/web/login`, `/web/register`, and `/api/v1/auth/*` — browser automation + API testing via [spel](https://github.com/Blockether/spel) (Playwright Java wrapper). No Node.js/npm/TypeScript introduced.
- New sub-library `libs/e2e/` with `com.blockether/spel` dependency, isolated behind opt-in `:e2e` alias — normal `clojure -M:test` runs are unaffected.
- `bb e2e`: orchestrator task that starts the app in `:test` profile on port 3100, runs the kaocha `:e2e` suite, and tears down the server.
- `bb run-e2e-server`: standalone task for manual debugging against the test-profile server.
- Test-only `POST /test/reset` endpoint (behind `:test/reset-endpoint-enabled?` config flag) that truncates H2 and re-seeds baseline tenant/users via production services. Guarded by startup assertion (throws in prod/acc) and `bb doctor` check.
- Kaocha `:e2e` suite in `tests.e2e.edn` with `^:e2e` metadata filtering.
- CI: new `e2e` job in `.github/workflows/ci.yml` with Playwright browser cache.

### Fixed

#### `boundary-user` — 5 auth/session bugs discovered by e2e tests
- **MFA handlers**: read `[:session :user :id]` instead of `[:user :id]` from the request — all 4 MFA endpoints (`setup`, `enable`, `disable`, `status`) always returned 500. Fixed by reading `(:user request)` directly.
- **Remember-me checkbox**: `login-submit-handler` checked for `"on"` but the `ui/checkbox` component submitted `"true"` — remember-me never activated. Fixed by accepting any truthy form value.
- **Session validation after login redirect**: `string->instant` in `boundary.core.utils.type-conversion` did not handle `java.time.OffsetDateTime` (returned by H2 for `TIMESTAMP WITH TIME ZONE` columns) — caused NPE in `is-session-valid?`, bouncing users back to login after successful authentication. Fixed by adding `OffsetDateTime` handling.
- **Account lockout not enforced**: `should-allow-login-attempt?` and `calculate-failed-login-consequences` existed in the core layer but were never called from the service layer. Fixed by adding a service-level lockout gate that checks the threshold before delegating to `authenticate-user`. Only true lockout (`:retry-after` present) short-circuits — deactivated/deleted accounts fall through to the normal auth flow to preserve their own error semantics.
- **Session tokens break URL paths**: standard base64 encoding produced `+`, `/`, `=` characters that caused Jetty 400 errors on `GET/DELETE /api/v1/sessions/:token`. Fixed by switching `generate-session-token` to URL-safe base64 (`Base64/getUrlEncoder` without padding). **Breaking change**: existing sessions with old-format tokens will fail validation — users must re-login after deploy.

### Changed

#### `boundary-tools` — 4 new developer helper tools

##### `bb doctor` — Config Doctor (rule-based)
- **Rule-based config validation**: 6 checks against `config.edn` and project files — no AI required.
- `env-refs` (error): detects `#env VAR` references in the `:active` section without `#or` fallback that are not set in the environment.
- `providers` (error): validates `:provider` values against known sets (logging, metrics, error-reporting, payments, AI, cache).
- `jwt-secret` (error): verifies `JWT_SECRET` is set when the user module is active.
- `admin-parity` (warn): checks that admin entity EDN files exist in both `dev/admin/` and `test/admin/`.
- `prod-placeholders` (error): flags placeholder values (`company.com`, `example.com`, `TODO`, `CHANGEME`) in prod/acc configs.
- `wiring-requires` (warn): verifies active Integrant modules have their `module-wiring` require in `wiring.clj`.
- CLI: `bb doctor [--env dev|prod|acc|all] [--ci]`. `--ci` exits non-zero on any error for CI pipelines.
- Structured output with pass/warn/error indicators and actionable fix suggestions.
- New namespace: `boundary.tools.doctor`.

##### `bb setup` — Config Setup Wizard (templates + optional AI)
- **Interactive config setup wizard** with three modes: guided prompts, CLI flags (`--database postgresql --payment stripe`), or AI-powered natural language (`bb setup ai "PostgreSQL with Stripe"`).
- Generates `resources/conf/dev/config.edn`, `resources/conf/test/config.edn`, and `.env.example` from template fragments.
- Component templates for all supported databases (PostgreSQL, SQLite, H2, MySQL), AI providers (Ollama, Anthropic, OpenAI), payment providers (Mock, Stripe, Mollie), cache (Redis, in-memory), and email (SMTP).
- Test config always uses H2 in-memory + mock/no-op providers for fast isolated tests.
- AI mode delegates to `boundary.ai.shell.cli-entry setup-parse`; falls back to interactive if no AI provider is available.
- New namespace: `boundary.tools.setup`.

##### `bb scaffold integrate` — Module Integration (rule-based)
- **Automated module wiring** after `bb scaffold generate`: patches `deps.edn` (source/test paths), `tests.edn` (per-library suite), and `wiring.clj` (module-wiring require).
- `--dry-run` mode previews all changes without writing files.
- Detects already-integrated modules (idempotent).
- Generates Integrant config snippet for manual insertion (config.edn uses Aero reader tags that can't be safely modified programmatically).
- Accessible via both `bb scaffold integrate <module>` and `bb scaffold:integrate <module>`.
- New namespace: `boundary.tools.integrate`.

##### `bb ai admin-entity` — Admin Entity Generator (AI-powered)
- **AI-powered admin entity EDN generation** from natural language descriptions (`bb ai admin-entity "products with name, price, status"`).
- Discovers existing admin entities from `resources/conf/dev/admin/` and includes them as examples in the prompt for style consistency.
- Preview + confirm flow; `--yes` flag for non-interactive use.
- Writes to both `resources/conf/dev/admin/<entity>.edn` and `resources/conf/test/admin/<entity>.edn`.
- Prints post-generation instructions (allowlist registration, `#include` directive).
- Babashka wrapper: `boundary.tools.admin_entity`. Clojure-side additions:
  - `boundary.ai.core.prompts`: `admin-entity-messages`, `setup-parse-messages` prompt builders.
  - `boundary.ai.shell.service`: `generate-admin-entity`, `parse-setup-description` orchestration functions.
  - `boundary.ai.shell.cli-entry`: `cmd-admin-entity`, `cmd-setup-parse` subcommands.

##### CI / developer experience
- `bb.edn`: 3 new tasks registered (`doctor`, `setup`, `scaffold:integrate`) + 3 new requires (`boundary.tools.doctor`, `boundary.tools.setup`, `boundary.tools.integrate`).
- `bb ai` help text and dispatch updated with `admin-entity` and `setup-parse` subcommands.
- `bb scaffold` help text and dispatch updated with `integrate` subcommand.
- `AGENTS.md` (root): new tools added to Quick Reference and namespace table.
- `CLAUDE.md`: new commands added to Scripting section.
- `boundary-tools/AGENTS.md`: comprehensive documentation for all 4 tools with examples, tables, and workflow guides.
- `libs/ai/AGENTS.md`: features list updated to 7, service API examples added, 3 new pitfalls documented (#9–#11).
#### `boundary-realtime` — Ring WebSocket handler
- **Ring 1.15 WebSocket upgrade handler** (`boundary.realtime.shell.handlers.ring-websocket`): bridges Ring's map-based `::ring.websocket/listener` response to the existing `IRealtimeService` connect/disconnect lifecycle. JWT authentication via `token` query parameter; on-open creates adapter and registers connection, on-close/on-error triggers disconnect cleanup.
- `websocket-handler` accepts keyword options: `:token-param` (default `"token"`) and `:on-message` for optional client→server bidirectional messaging.
- `ring/ring-core 1.15.3` added to `libs/realtime/deps.edn`.
- 6 integration tests covering: missing token → 400, listener response structure, custom token param, on-open registration, on-close cleanup, on-error cleanup.

#### `boundary-payments` — new library
- **Multi-provider payment abstraction**: `IPaymentProvider` protocol in `boundary.payments.ports` with `create-checkout-session`, `get-payment-status`, `process-webhook`, and `verify-webhook-signature` methods. Implementations: `StripePaymentProvider`, `MolliePaymentProvider`, `MockPaymentProvider` (development/tests).
- **Malli schemas**: `CheckoutRequest`, `CheckoutResult`, `PaymentStatusResult` (`:pending`/`:paid`/`:failed`/`:cancelled`), `WebhookResult` (`:payment.paid`/`:payment.failed`/`:payment.cancelled`/`:payment.authorized`).
- **Pure core layer** (`boundary.payments.core.provider`): `cents->euro`, `normalize-event-type`, `mollie-status->event-type`, `mollie-status->payment-status`, `stripe-event->event-type`.
- **Stripe adapter**: Checkout Session creation with `payment_intent_data[metadata][checkout_id]` for webhook correlation, HMAC-SHA256 signature verification with constant-time comparison, 300s timestamp tolerance, graceful handling of malformed `Stripe-Signature` headers.
- **Mollie adapter**: Payment creation via Mollie API v2, status polling via `get-payment-status`, form-POST webhook processing with payment fetch-back verification.
- **Integrant component**: `:boundary/payment-provider` with `:provider` (`:mock`/`:mollie`/`:stripe`), `:api-key`, `:webhook-secret`, `:webhook-base-url`.
- **Application wiring**: `payments-module-config` in `boundary.config`, `boundary.payments.shell.module-wiring` loaded via platform wiring, `boundary/payments` dependency added to `libs/platform/deps.edn`.
- 19 tests, 111 assertions, 0 failures (`^:unit` + `^:integration`).
- `libs/payments/deps.edn`: standalone library with `clj-http`, `cheshire`, `malli`, `integrant`, `tools.logging`.

#### `boundary-ai` — new library (Phase 19 of Boundary Roadmap)
- **Multi-provider AI abstraction**: `IAIProvider` protocol in `boundary.ai.ports` with `complete`, `complete-json`, and `provider-name` methods. Implementations: `OllamaProvider` (offline-first, no API key), `AnthropicProvider`, `OpenAIProvider`, `NoOpProvider` (test stub).
- **Automatic provider fallback**: configure a `:fallback` provider in `:boundary/ai-service`; if the primary fails, the fallback is used transparently.
- **Feature 1 — NL Scaffolding** (`bb scaffold ai "<description>" [--yes]`): parses a natural language module description into a validated `ModuleGenerationRequest` spec and delegates to the existing scaffolder pipeline. Preview + confirm by default; use `--yes` for non-interactive generation.
- **Feature 2 — Error Explainer** (`bb ai explain`, `(ai/explain *e)`): reads a Clojure/Boundary stack trace, extracts referenced source files, and returns a structured root-cause + fix-suggestion using framework-specific system prompts.
- **Feature 3 — Test Generator** (`bb ai gen-tests <file>`): reads a source file, detects test type (`:unit` for `core/`, `:contract` for `adapters/`, `:integration` otherwise), and generates a complete Kaocha-compatible test namespace.
- **Feature 4 — SQL Copilot** (`bb ai sql "<description>"`, `(ai/sql "...")`): translates a natural language query description into HoneySQL map + explanation + raw SQL preview. Auto-discovers schema context from `schema.clj` files.
- **Feature 5 — Documentation Wizard** (`bb ai docs --module <path> --type agents|openapi|readme`): generates AGENTS.md developer guides, OpenAPI 3.x YAML, or README.md from source files.
- **REPL helpers** (`boundary.ai.shell.repl`): `(ai/explain *e)`, `(ai/sql "...")`, `(ai/gen-tests "path/to/file.clj")` — bind service once with `(ai/set-service! system-service)`.
- **Integrant component**: `:boundary/ai-service` with `:provider`, `:model`, `:base-url`/`:api-key`, and optional `:fallback` sub-config.
- **Malli schemas**: `Message`, `AIRequest`, `AIResponse`, `ProviderConfig`, `AIConfig`.
- **Pure core layer** (`boundary.ai.core.*`): `prompts.clj` (system + user prompt builders for all 5 features), `context.clj` (module name extraction, stack trace parsing, function signature discovery, schema context), `parsing.clj` (JSON response parser, module spec → CLI args converter, SQL + test code extractors).
- 26 tests, 88 assertions, 0 failures (`^:unit` + `^:integration`).
- `libs/ai/AGENTS.md`: 7-section developer guide covering provider setup, REPL usage, CLI reference, common pitfalls (8 patterns), testing commands.
- `libs/ai/deps.edn`: standalone library with `clj-http`, `cheshire`, `malli`, `integrant`, `tools.logging`.

#### CI / developer experience
- `.github/workflows/ci.yml`: `test-ai` job added (`needs: lint`); `libs/ai/src` added to the lint step; `test-ai` wired into `test-summary`.
- `.github/workflows/publish.yml`: `boundary-ai` added to Layer 4 (standalone, no inter-library dependencies); updated release body and step summary.
- `scripts/ai.clj`: new Babashka script — `bb ai explain`, `bb ai gen-tests`, `bb ai sql`, `bb ai docs`.
- `scripts/scaffold.clj`: `bb scaffold ai "<description>"` subcommand added.
- `bb.edn`: `ai` task added.
- `AGENTS.md` and `CLAUDE.md`: `ai` added to library listing, test command reference, Babashka commands, and Library-Specific Guides table. Version bumped to 3.5.0.
- `resources/conf/dev/config.edn`: `:boundary/ai-service` added (Ollama primary, Anthropic fallback).
- `resources/conf/test/config.edn`: `:boundary/ai-service {:provider :no-op}` for test isolation.

#### `boundary-calendar` — new library (Phase 2 / Q3 2026 roadmap)
- **`defevent` macro** and in-process registry (atom-backed, same pattern as `defreport` in `boundary-reports`): register named event type schemas at load time; `get-event-type`, `list-event-types`, `clear-registry!`.
- **`boundary.calendar.schema`**: Malli schemas — `EventData`, `EventDef`, `OccurrenceResult`, `ConflictResult`; helpers `valid-event?`, `explain-event`, `valid-event-def?`.
- **`boundary.calendar.core.event`**: pure helpers — `duration`, `all-day?`, `within-range?`.
- **`boundary.calendar.core.recurrence`**: DST-aware RRULE expansion via ical4j 4.x `Recur` with `ZonedDateTime` seeds; `recurring?`, `occurrences`, `next-occurrence`, `expand-event`.
- **`boundary.calendar.core.conflict`**: pairwise conflict detection — `overlaps?`, `conflicts?`, `find-conflicts` (returns `ConflictResult` maps with `:overlap-start`/`:overlap-end`).
- **`boundary.calendar.core.ui`**: pure Hiccup calendar views — `event-badge`, `day-cell`, `month-view`, `week-view`, `mini-calendar`.
- **`boundary.calendar.ports`**: `CalendarAdapterProtocol` (`export-ical`, `import-ical`).
- **`boundary.calendar.shell.adapters.ical`**: `ICalAdapter` backed by `org.mnode.ical4j/ical4j 4.0.3`; TZID extracted via regex from property text (ical4j 4.x creates synthetic zone IDs internally).
- **`boundary.calendar.shell.service`**: public API — `export-ical`, `import-ical`, `ical-feed-response` (returns Ring response with `Content-Type: text/calendar; charset=utf-8`).
- 30 tests, 87 assertions, 0 failures (`^:unit` + `^:integration` round-trip).
- `libs/calendar/AGENTS.md`: 11-section developer guide covering DST pitfalls, RRULE examples, ical4j 4.x API notes, registry pollution warning, REPL smoke check.
- `docs-site/content/guides/calendar.adoc` (weight 68): user-facing how-to guide.
- `docs-site/content/api/calendar.adoc` (weight 50): complete function API reference.
- `dev-docs/adr/ADR-011-calendar-library.adoc`: architecture decision record (7 decisions, alternatives considered).

#### `boundary-reports` — added to CI (was missing)
- `test-reports` job added to `.github/workflows/ci.yml`; `libs/reports/src` added to the lint step.

#### CI / developer experience
- `.github/workflows/ci.yml`: `test-calendar` and `test-reports` jobs added (both `needs: lint`; standalone, no inter-library dependencies). Both wired into `test-summary`.
- `AGENTS.md` and `CLAUDE.md`: `reports` and `calendar` added to library listing, test command reference, and Library-Specific Guides table. New **"Adding a New Library to CI"** checklist section in `AGENTS.md`.

#### `boundary-workflow` — new library (Phase 2 / Q3 2026 roadmap)
- **`defworkflow` macro** and in-process registry: declare state machine definitions as data; `get-workflow`, `list-workflows`, `clear-registry!`.
- **`boundary.workflow.schema`**: Malli schemas — `WorkflowDefinition`, `WorkflowInstance`, `TransitionDef`, `AuditEntry`; state/transition validation at definition time.
- **`boundary.workflow.core.machine`**: pure state machine logic — `can-transition?`, `find-transition`, permission checks against `:required-permissions`, guard evaluation.
- **`boundary.workflow.core.transitions`**: `available-transitions-with-status` — returns all candidate transitions with `:enabled?`, `:label`, `:reason` for a given state and actor-roles.
- **`boundary.workflow.core.audit`**: pure audit entry constructors.
- **`boundary.workflow.ports`**: `IWorkflowStore`, `IWorkflowEngine`, `IWorkflowRegistry` protocols.
- **`boundary.workflow.shell.persistence`**: DB persistence via next.jdbc + HoneySQL (`IWorkflowStore` implementation).
- **`boundary.workflow.shell.service`**: orchestration — load → validate → persist → side-effects; `create-workflow-service` factory accepts optional `job-queue` and `guard-registry`.
- **Guard registry**: functions registered at service creation time; receive transition `:context` map; return boolean.
- **Side effects**: job-type keywords on `TransitionDef` (`:side-effects [:notify-user]`); enqueued via `boundary-jobs` after successful transition; silently skipped if no job queue configured.
- **`boundary.workflow.shell.http`**: REST API — `POST /workflow/instances` (start), `POST /workflow/instances/:id/transition`, `GET /workflow/instances/:id` (state + `availableTransitions`), `GET /workflow/instances/:id/audit`.
- **`boundary.workflow.shell.module-wiring`**: Integrant `:boundary/workflow` key; depends on `:boundary/database-context` (required) and `:boundary/job-queue` (optional).
- `libs/workflow/AGENTS.md`: developer guide covering `defworkflow` syntax, guards, side effects, auto-transitions, hooks, and Integrant wiring.
- `docs-site/content/guides/workflow.adoc`: user-facing how-to guide.

#### `boundary-workflow` — lifecycle hooks, auto-transitions, available-transitions
- `:hooks` map on `WorkflowDefinition`: supports `:on-enter-<state>`, `:on-exit-<state>`, and `:on-any-transition` keys. Hooks receive the updated `WorkflowInstance` and fire synchronously after each successful transition (after the audit entry is saved). Exceptions are caught and logged; they do not roll back the transition.
- `:auto? true` on `TransitionDef`: marks a transition as system-initiated. `process-auto-transitions!` port method fires all eligible auto-transitions for a given workflow; uses `[:system]` actor-roles (no user permission check). Returns `{:attempted :processed :failed}` counts.
- `available-transitions` port method: returns candidate transitions with `:enabled?`, `:label`, and `:reason` fields for the current state and actor-roles. Exposed on the `GET /api/workflow/instances/:id` HTTP response as `availableTransitions`.
- `:label` on `TransitionDef` and `:state-config` map on `WorkflowDefinition` for human-readable display names.
- `available-transitions-with-status` pure function in `boundary.workflow.core.transitions`.

#### `boundary-search` — filter support
- `:filters` key on `SearchDefinition`: declares filterable keyword dimensions (e.g. `[:tenant-id :category-id]`).
- `:filter-values` opt in `index-document!` and `build-document`: stores filter data as compact JSON in a new `filters TEXT` column.
- Filter SQL at search time: PostgreSQL uses `d.filters::jsonb->>'key' = ?`; H2/SQLite uses `INSTR(filters, '"key":"val"') > 0` (H2 2.4.x has no JDBC JSON function support).
- `filter-key->json-key` utility in `boundary.search.core.index` (kebab → snake conversion for JSON storage).
- Migration: `resources/migrations/20260312000000-search-filters.{up,down}.sql`.

#### `boundary-admin` — Admin UI Frontend Redesign ("Refined Editorial")

##### Typography & Self-Hosted Fonts
- **DM Sans** (display/body) + **JetBrains Mono** (code/monospace) replace generic system fonts.
- Self-hosted woff2 files under `/fonts/` for CSP compliance (`font-src 'self'`); no external CDN dependency.
- New `fonts.css` with variable-weight `@font-face` declarations (DM Sans 300–700, JetBrains Mono 400–600).
- Font stack tokens updated in `boundary-tokens.css`: `--font-sans`, `--font-display`, `--font-mono`.

##### Design Token Refinements
- **Shadows**: Multi-layered, softer box-shadows (`--shadow-sm` through `--shadow-2xl`) for modern depth.
- **Border radii**: Slightly rounder (`--radius-sm` 6px, `--radius-md` 8px, `--radius-lg` 12px, `--radius-xl` 16px).
- **Transitions**: Spring-like easing curves (`--transition-fast`, `--transition-normal`, `--transition-slow`, `--transition-bounce`).
- **Surface colors**: Warmer light-mode palette (stone tones instead of blue-tinted slate).
- New tokens: `--shadow-card-hover`, `--shadow-inner-glow`, `--tracking-tight`, `--tracking-tighter`, `--topbar-backdrop`.

##### Admin Shell & Sidebar Polish
- Sidebar: subtle gradient background, smooth left-border accent indicator on active/hover nav items.
- Topbar: frosted glass effect with `backdrop-filter: blur(12px) saturate(180%)`.
- Dark mode variants for both sidebar and topbar.

##### Dashboard Redesign
- Hero section with gradient background, decorative radial glow, tighter heading typography.
- Entity cards with hover elevation (`translateY(-2px)`), icon color inversion on hover.
- New `.entity-card-link`, `.entity-card-icon`, `.entity-card-title`, `.entity-card-count`, `.entity-card-description` classes.

##### Entity List Page Polish
- Table wrapper with border-radius, subtle shadow, and overflow hidden.
- Header row: uppercase, letter-spaced, muted color for clean data-grid appearance.
- Row hover with primary-faint background; subtle zebra striping; last-row border removal.
- Search toolbar: unified container with focus ring animation.
- Pagination: pill-style buttons with hover highlight.

##### Form Styling Enhancements
- Form cards with clean borders and radius.
- Input fields: smooth focus transition with 3px primary-faint ring.
- Error state with red border and error-bg ring.
- Labels, help text, and required indicators refined.
- Primary buttons with subtle shadow and hover lift (`translateY(-1px)`).

##### Micro-Interactions & Animation
- `fadeInUp` keyframe for page content entry with staggered delays.
- `tableRowReveal` keyframe for HTMX-loaded table rows (staggered first 10 rows).
- Sidebar nav: background slide animation via `::after` pseudo-element with `scaleX` transform.
- Entity card grid: staggered reveal (50ms intervals, up to 8 cards).
- `@media (prefers-reduced-motion: reduce)` disables all animations.

##### Dark Mode Refinement
- Warmer dark surfaces with blue undertone (`#0c0f17` base).
- Softer borders using `rgba(255,255,255,0.08)`.
- Colored shadow glow on card hover in dark mode.
- Table row hover uses primary color at 6% opacity.

##### Badge & Status Component Polish
- Dot indicator (6px circle) before status text for success/error states.
- Pill-shaped badges with `border-radius: var(--radius-full)`.

#### `boundary-admin` — UX Enhancements (6 features)

##### 1. HTMX Loading Progress Bar
- Fixed top-of-page progress bar appears during all HTMX requests.
- Animated gradient stripe with smooth show/hide transitions.
- Wired to `htmx:beforeRequest` / `htmx:afterRequest` / `htmx:responseError` events.

##### 2. Toast Notification System
- Slide-in toast notifications with 4 variants: success, error, warning, info.
- Each variant has a distinct SVG icon and color scheme.
- Auto-dismiss after configurable duration (default 4s) with progress bar indicator.
- Triggered via HTMX `HX-Trigger: {"showToast": {...}}` response header or `window.AdminUX.showToast()` JS API.
- Server-rendered flash messages (`.alert-success`, `.alert-error`, etc.) auto-converted to toasts on page load.
- `escapeHtml()` prevents XSS in toast title/message content.

##### 3. Clickable Table Rows
- Entity list table rows navigate to detail page on click (`data-href` attribute).
- Smart exclusion: clicks on `a`, `button`, `input`, `select`, `textarea`, `.actions-cell`, `.checkbox-cell`, and `td.editable` are ignored (preserves inline editing).
- Visual hover feedback with chevron navigation hint.

##### 4. Skeleton Loading Screens
- Table bodies replaced with shimmer-animated skeleton rows during HTMX fetch.
- Column count auto-detected from `<thead>`.
- Original table content saved and **restored on HTMX error** (no permanent skeleton state).
- 6 width variants (`w-full`, `w-3-4`, `w-1-2`, `w-1-3`, `w-1-4`) for visual variety.

##### 5. Styled Delete Confirmation Modal
- Custom modal replaces native `window.confirm()` for all delete operations.
- Intercepts `htmx:confirm` event on elements with `hx-delete` or `.danger` class.
- Danger icon, title, message, Cancel/Delete buttons with focus trap.
- Close on Escape key, backdrop click, or Cancel button.
- Translated labels read from `data-confirm-title`, `data-confirm-cancel`, `data-confirm-label` attributes (server-rendered via `[:t ...]` i18n markers); falls back to English.

##### 6. Accessibility — Reduced Motion Support
- `removeAfterAnimation()` helper checks `prefers-reduced-motion: reduce` and removes DOM elements immediately instead of waiting for `animationend` (which never fires when `animation: none`).
- Applied to toast dismissal and modal close.
- All CSS animations gated behind `@media (prefers-reduced-motion: reduce)`.

#### `boundary-admin` — Delete Flow Improvements
- Delete handler now uses `HX-Redirect` instead of empty response with `HX-Trigger`.
- `return_to` query parameter preserved through the delete flow for context-aware redirect.
- **Open redirect prevention**: `return_to` validated to start with `/web/admin/`; invalid values fall back to entity list.

#### `boundary-admin` — Pagination Enhancements
- `page-window` algorithm improved: single-page gaps show the actual page number instead of an ellipsis (e.g. `1 2 3 ... 8` instead of `1 ... 3 ... 8` when page 2 is the only gap).
- All pagination labels internationalized with `[:t ...]` markers (8 new i18n keys).

#### `boundary-i18n` — New Translation Keys
- 12 new keys added to both `en.edn` and `nl.edn`:
  - Pagination: `:admin/pagination-showing`, `:admin/pagination-of`, `:admin/pagination-label`, `:admin/pagination-first-page`, `:admin/pagination-previous-page`, `:admin/pagination-next-page`, `:admin/pagination-last-page`, `:admin/pagination-page`.
  - Modal buttons: `:admin/modal-button-cancel`, `:admin/modal-button-delete`.

#### `boundary-admin` — tenant entity + dashboard stats
- **Tenant admin entity config**: `resources/conf/{dev,test}/admin/tenants.edn` — list/search fields, status enum filter (active/suspended/deleted), field groups (Identity, State, Settings), readonly system fields.
- **Dashboard entity counts**: `admin-home-handler` now calls `count-entities` for each registered entity and passes the stats map to `admin-home`, so entity tiles show real counts instead of always displaying "0".
- Tenants registered in dev and test config allowlists (`#{:users :tenants}`).

#### `boundary-tenant` — convenience functions and protocol extension
- `tenant-provisioned?` public function in `boundary.tenant.shell.provisioning`: checks if a tenant's schema exists in PostgreSQL; returns `false` for non-PostgreSQL databases; throws on missing `:schema-name`.
- `list-tenant-schemas` public function in `boundary.tenant.shell.provisioning`: lists all `tenant_*` schemas in PostgreSQL; returns empty vector for non-PostgreSQL databases.
- `ITenantSchemaProvider` protocol extended with `tenant-provisioned?` and `list-tenant-schemas` methods; `TenantSchemaProvider` record updated to implement both.
- `dev-docs/adr/ADR-020-tenant-database-scope.adoc`: decision to keep tenant provisioning PostgreSQL-only; MySQL/SQLite version promises removed from README.

### Changed

- `boundary-admin` UI theme evolved from "Cyberpunk Professionalism" (Geist + Indigo/Lime) to **"Refined Editorial"** (DM Sans + JetBrains Mono, warmer surfaces, layered shadows, spring-eased transitions). Dark mode refined with blue-tinted surfaces and colored shadow glows.
- `boundary-admin` entity card markup restructured: icon standalone on its own line, then title, description, and count as metadata.
- `boundary-admin` delete handler: returns `HX-Redirect` header instead of empty body with `HX-Trigger: entityDeleted`.
- `boundary-admin` event listeners: all 7 `document.body.addEventListener` calls changed to `document.addEventListener` to survive HTMX body swaps (`hx-target="body" hx-swap="outerHTML"`).
- `boundary-ui-style` CSS bundle: `fonts.css` added as first entry in `admin-pilot-css`; `admin-ux.js` added to `admin-pilot-js`.
- `boundary-ui-style` keyboard.js: confirm modal escape handling added; debug mode disabled.
- `boundary-tenant` promoted from "Active" to "Stable" in `PROJECT_STATUS.adoc`. All convenience functions documented in README are now implemented; 70 tests, 474 assertions, 0 failures.
- `boundary-tenant` README: fixed middleware naming (`wrap-tenant-resolver` → `wrap-tenant-resolution`), removed non-existent `wrap-require-tenant` (use `:require-tenant? true` option instead), clarified middleware locations (platform lib vs tenant lib), replaced MySQL/SQLite roadmap promises with ADR-020 reference.
- `boundary-tenant` integration tests: removed stale "DEFERRED" comment — tests pass with mock observability services and H2 in-memory DB.
- `boundary-external` promoted from "In Development" to "Active" (Twilio, SMTP/IMAP adapters production-capable). Stripe moved to `boundary-payments`.
- Root `AGENTS.md` updated: `workflow` and `search` added to library structure, test commands, and Library-Specific Guides table. Version bumped to 3.3.0.
- `libs/workflow/AGENTS.md` and `libs/search/AGENTS.md` updated to document all new features.
- `docs-site/content/guides/workflow.adoc` and `docs-site/content/guides/search.adoc` updated with new API examples, filter DDL, migration notes, and hook/auto-transition reference.

### New Files

- `libs/ui-style/resources/public/js/admin-ux.js` — Central JS for all 5 UX features (~340 lines).
- `libs/ui-style/resources/public/css/fonts.css` — Self-hosted `@font-face` declarations.
- `libs/ui-style/resources/public/fonts/dm-sans-latin.woff2` (63 KB).
- `libs/ui-style/resources/public/fonts/dm-sans-italic-latin.woff2` (76 KB).
- `libs/ui-style/resources/public/fonts/jetbrains-mono-latin.woff2` (31 KB).

### Tests

- 3044 tests, 15742 assertions, 0 failures across all libraries (`clojure -M:test:db/h2`).
- 113 admin tests, 823 assertions, 0 failures (`clojure -M:test:db/h2 :admin`).
- New test namespaces: `workflow.core.transitions-test` (available-transitions-with-status), `workflow.shell.service-test` (hooks, auto-transitions), `search.core.query-test` (filter SQL), `search.shell.persistence-test` (filter round-trip).

---

## [1.0.0-alpha] - 2026-02-14

### 🎉 Initial Release

The first production-ready release of the Boundary Framework - a batteries-included web framework for Clojure that brings Django's productivity and Rails' conventions with functional programming rigor.

### Architecture

#### Functional Core / Imperative Shell (FC/IS)
- **Pure business logic** in `core/` namespaces (no side effects)
- **I/O and side effects** in `shell/` namespaces
- **Protocol definitions** in `ports.clj` for dependency injection
- **Consistent module structure** across all libraries

#### Library Organization (Monorepo)
- **13 independently publishable libraries** via Clojars
- **Modular design** - use only what you need
- **Zero lock-in** - each library is a standard deps.edn dependency

### Core Libraries

#### `boundary-core` (0.1.0)
Foundation library with essential utilities:
- **Validation**: Malli-based schema validation with human-readable error messages
- **Interceptors**: Declarative cross-cutting concerns
- **Utilities**: Case conversion (kebab-case ↔ snake_case), type conversion, PII redaction
- **Feature flags**: Runtime feature toggles

#### `boundary-observability` (0.1.0)
Multi-provider observability infrastructure:
- **Logging**: Structured logging with Datadog and stdout adapters
- **Metrics**: Counter, gauge, histogram, summary (Datadog StatsD protocol)
- **Error reporting**: Sentry integration with PII redaction
- **Audit logging**: Security and compliance event tracking
- **Interceptor pattern**: Automatic breadcrumbs, logging, metrics (50% code reduction)

#### `boundary-platform` (0.1.0)
HTTP and database infrastructure:
- **HTTP server**: Jetty-based with Integrant lifecycle
- **Routing**: Reitit with normalized route format
- **Database**: HikariCP connection pooling, next.jdbc integration
- **Migrations**: Flyway-based schema migrations
- **CLI**: Command-line interface utilities
- **HTTP interceptors**: Auth, rate limiting, audit (declarative)

#### `boundary-user` (0.1.0)
Authentication and authorization:
- **JWT authentication**: Secure token-based auth
- **Password security**: bcrypt hashing with configurable rounds
- **Multi-Factor Authentication (MFA)**: TOTP-based 2FA (production-ready)
- **Role-based access control (RBAC)**: Fine-grained permissions
- **User management**: CRUD operations with soft delete
- **Account security**: Login attempt tracking, account lockout (5 failures = 15min lockout)

#### `boundary-admin` (0.1.0)
Auto-generated CRUD admin interface (Django Admin for Clojure):
- **Schema introspection**: Auto-detect entity config from database schema
- **Zero-config CRUD**: Create, read, update, delete with no boilerplate
- **Search and filtering**: Full-text search across configurable fields
- **Pagination**: Offset-based with page size control
- **Sorting**: Multi-column sorting (client-side)
- **Field widgets**: Auto-inferred form inputs (text, email, checkbox, select, textarea, date, datetime)
- **Field grouping**: Organize forms with collapsible sections
- **Soft delete support**: Respect `deleted_at` columns
- **Permissions**: Role-based access (admin-only by default, Week 2+ entity-level permissions)
- **HTMX-powered**: Server-side rendering with progressive enhancement
- **Cyberpunk Professionalism UI**: Indigo (#4f46e5) + Lime (#65a30d), Geist fonts, dark mode

#### `boundary-storage` (0.1.0)
File storage abstraction:
- **Local storage**: File system-based storage
- **S3 storage**: Amazon S3 integration (not included in 1.0.0)
- **Validation**: File size, content type, extension validation
- **Security**: Filename sanitization, path traversal prevention
- **Signed URLs**: Temporary access links

#### `boundary-scaffolder` (0.1.0)
Production-ready module generator:
- **Complete module generation**: 9 source files (core, shell, ports, schema, wiring)
- **Test generation**: 3 test files (unit, integration, contract)
- **Migration generation**: 1 Flyway migration file
- **FC/IS architecture**: Zero linting errors, follows all conventions
- **Entity support**: Multi-field entities with types (string, integer, decimal, boolean, text, date, datetime, uuid, json)
- **Field constraints**: Required, unique, indexed

#### `boundary-cache` (0.1.0)
Distributed caching:
- **Redis adapter**: Production-ready caching
- **In-memory adapter**: Development and testing
- **TTL support**: Automatic expiration
- **Tenant scoping**: Multi-tenant cache isolation
- **Atomic operations**: Thread-safe cache access

#### `boundary-jobs` (0.1.0)
Background job processing:
- **In-memory queue**: Development and testing (Redis adapter planned)
- **Job lifecycle**: Enqueue, dequeue, retry, dead letter queue
- **Tenant context**: Multi-tenant job isolation
- **Priority queues**: High, normal, low priority
- **Scheduled jobs**: Future execution with `run-at` timestamp
- **Worker pool**: Parallel job processing with configurable concurrency
- **Retry logic**: Exponential backoff (1s, 2s, 4s, 8s, 16s)

#### `boundary-realtime` (0.1.0)
WebSocket-based real-time communication:
- **JWT authentication**: Secure WebSocket connections via boundary/user
- **Point-to-point messaging**: Send to specific user across all devices
- **Broadcast messaging**: Send to all connections
- **Role-based routing**: Send to users with specific role
- **Topic-based pub/sub**: Subscribe to arbitrary topics
- **Connection registry**: Track active WebSocket connections
- **Production-ready**: Phoenix Channels for Clojure

#### `boundary-tenant` (0.1.0)
Multi-tenancy infrastructure:
- **Tenant management**: CRUD operations for tenant entities
- **PostgreSQL schema isolation**: Per-tenant database schemas
- **Tenant context**: Thread-local tenant resolution
- **Job integration**: Tenant-scoped background jobs
- **Cache integration**: Tenant-scoped caching
- **Lifecycle**: Create, provision, suspend, activate, delete

#### `boundary-email` (0.1.0)
Email infrastructure:
- **SMTP adapter**: Production-ready email sending
- **Email preparation**: Validation, header formatting, recipient normalization
- **Async support**: Non-blocking email delivery
- **Attachment support**: File attachments via multipart/mixed

#### `boundary-external` (0.1.0) - **In Development**
External service adapters:
- **Skeleton implementation**: Not production-ready
- **Week 2+ roadmap**: HTTP client, API adapters, webhooks

### Features

#### Auto-CRUD Admin Interface
- **Django Admin for Clojure**: Auto-generated CRUD UIs from database schema
- **Zero boilerplate**: No manual form definitions required
- **Schema introspection**: Automatically detects entity structure, primary keys, soft delete
- **Customizable**: Override auto-detected config with manual settings
- **Field ordering**: Control form field display order via `:field-order`
- **Field grouping**: Organize forms into collapsible sections via `:field-groups`
- **Widget inference**: Smart form inputs based on field names and types
- **Relationship detection (Week 2+)**: Foreign key relationships, belongs-to, has-many

#### Multi-Factor Authentication (MFA)
- **TOTP-based**: RFC 6238 compliant Time-based One-Time Passwords
- **QR code generation**: Easy mobile app pairing
- **Backup codes**: 10 single-use recovery codes per user
- **Grace period**: 7-day enrollment window after setup
- **Login flow**: Email/password + TOTP code
- **API endpoints**: `/api/auth/mfa/setup`, `/api/auth/mfa/enable`, `/api/auth/mfa/verify`
- **Status**: ✅ Production Ready

#### HTTP Interceptors
- **Declarative pattern**: Auth, rate limiting, audit as route metadata
- **Three phases**: `:enter` (request), `:leave` (response), `:error` (exception)
- **Built-in interceptors**: Request logging, metrics, error reporting, correlation IDs
- **Composable**: Stack multiple interceptors per route
- **Example**:
  ```clojure
  {:path "/api/admin"
   :methods {:post {:handler 'handlers/create-resource
                    :interceptors ['auth/require-admin 'audit/log-action]
                    :summary "Create admin resource"}}}
  ```

#### Observability Interceptor Pattern
- **Multi-layer**: Service layer + persistence layer
- **Automatic**: Logging, metrics, error reporting, breadcrumbs
- **50% code reduction**: Eliminates boilerplate in 31/31 methods (user module)
- **Consistent error handling**: Standardized across all operations
- **Example**:
  ```clojure
  (defn create-user [this user-data]
    (service-interceptors/execute-service-operation
     :create-user
     {:user-data user-data}
     (fn [{:keys [params]}]
       ;; Business logic here - observability automatic
       (let [user (user-core/prepare-user (:user-data params))]
         (.create-user repository user)))))
  ```

#### API Pagination
- **Offset-based**: `limit` and `offset` parameters
- **RFC 5988 Link headers**: `first`, `prev`, `next`, `last` relations
- **Cursor-based (Week 2+)**: Planned for large datasets

#### Configuration Management
- **Aero-based**: Environment-specific profiles (`dev`, `test`, `prod`)
- **`#include` support**: Modular config files per module
- **Environment variables**: Override via `BND_ENV`
- **Example**: Admin entity configs in `resources/conf/{env}/admin/{module}.edn`

#### Database Support
- **Development**: SQLite (zero-config)
- **Testing**: H2 in-memory (via `:test` alias)
- **Production**: PostgreSQL (with schema isolation for multi-tenancy)
- **Migrations**: Flyway-based with `clojure -M:migrate up`

### Documentation

#### Comprehensive Documentation Site
- **Hugo-powered**: Static site generator with AsciiDoc support
- **Content**:
  - **Architecture Decision Records (ADRs)**: 8 documents
  - **Architecture guides**: 18 documents (FC/IS, ports/adapters, module structure)
  - **User guides**: 23 documents (authentication, admin, storage, MFA)
  - **API reference**: Complete API documentation
  - **Examples**: 5 code examples
  - **Getting started**: 6 onboarding guides
- **Deployed**: GitHub Pages at `https://thijs-creemers.github.io/boundary/`
- **Local dev**: `hugo server` in `docs-site/` directory

#### Developer Resources
- **AGENTS.md**: Complete developer guide (commands, patterns, conventions, troubleshooting)
- **Interactive Cheat Sheet**: `docs/cheatsheet.html` with client-side search, copy-to-clipboard
- **README.md**: Elevator pitches for developers (148 words) and management (94 words)
- **Scaffolder README**: Complete module generation workflow

#### Key Documentation Files
- **Architecture guides**: FC/IS patterns, design decisions
- **MFA Setup Guide**: Multi-factor authentication integration
- **API Pagination**: Offset and cursor pagination
- **Observability Integration**: Custom adapters, configuration
- **HTTP Interceptors**: Technical specification (ADR-010)
- **PRD**: Product vision and requirements

### Naming Conventions

#### ✅ ALWAYS Use kebab-case Internally
- **All Clojure code**: `:password-hash`, `:created-at`
- **Database (at boundary only)**: `password_hash`, `created_at`
- **API (at boundary only)**: `passwordHash`, `createdAt`
- **Conversion utilities**: `snake-case->kebab-case-map`, `kebab-case->snake-case-map`

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`. This convention prevents such mismatches.

### Testing

#### Comprehensive Test Suite
- **Test types**:
  - **Unit tests**: Pure functions, no mocks (`:unit` metadata)
  - **Integration tests**: Service with mocked deps (`:integration` metadata)
  - **Contract tests**: Adapters with real DB (`:contract` metadata)
- **Test commands**:
  ```bash
  clojure -M:test:db/h2                    # All tests
  clojure -M:test:db/h2 :core              # Core library
  clojure -M:test:db/h2 --focus-meta :unit # Unit tests only
  clojure -M:test:db/h2 --watch :core      # Watch mode
  ```
- **Coverage**: ~90-95% docstring coverage, comprehensive test coverage

#### Validation Snapshot Testing
- **Graph generation**: Visualize validation rules
- **Coverage reports**: Per-module validation coverage
- **Commands**:
  ```bash
  clojure -M:repl-clj <<'EOF'
  (require '[boundary.shared.tools.validation.repl :as v])
  (spit "build/validation-user.dot" (v/rules->dot {:modules #{:user}}))
  (System/exit 0)
  EOF
  dot -Tpng build/validation-user.dot -o docs/diagrams/validation-user.png
  ```

### Design System

#### Cyberpunk Professionalism
- **Primary color**: Indigo #4f46e5 (5.2:1 contrast on white ✅ WCAG AA)
- **Accent color**: Lime #65a30d (4.6:1 contrast ✅ WCAG AA)
- **Typography**: Geist font family (SIL Open Font License, loaded via jsDelivr CDN)
- **Dark mode**: Gray-12 #030712 base with neon glows
- **Design tokens**: Open Props CSS (`resources/public/css/tokens-openprops.css`)
- **Status colors**: All WCAG AA compliant

#### UI Technologies
- **Hiccup**: Server-side HTML generation (no build step)
- **HTMX**: Progressive enhancement for dynamic interactions
- **Pico CSS**: Base framework
- **Lucide Icons**: Icon system (50+ icons)

### Publishing Infrastructure

#### GitHub Actions Workflow
- **File**: `.github/workflows/publish.yml` (304 lines)
- **Triggers**: Manual dispatch + git tag `v*`
- **Libraries published**: 12 libraries in dependency order
- **Version strategy**: Lockstep versioning (all libraries at 1.0.0)
- **Status**: ✅ Ready (blocked on GitHub Secrets configuration)

#### Clojars Publishing
- **Organization**: `io.github.thijs-creemers`
- **Credentials**: Username `thijs-creemers` (password via GitHub Secrets)
- **Libraries**:
  - `boundary-core` → `io.github.thijs-creemers/boundary-core`
  - `boundary-observability` → `io.github.thijs-creemers/boundary-observability`
  - `boundary-platform` → `io.github.thijs-creemers/boundary-platform`
  - `boundary-user` → `io.github.thijs-creemers/boundary-user`
  - `boundary-admin` → `io.github.thijs-creemers/boundary-admin`
  - `boundary-storage` → `io.github.thijs-creemers/boundary-storage`
  - `boundary-scaffolder` → `io.github.thijs-creemers/boundary-scaffolder`
  - `boundary-cache` → `io.github.thijs-creemers/boundary-cache`
  - `boundary-jobs` → `io.github.thijs-creemers/boundary-jobs`
  - `boundary-tenant` → `io.github.thijs-creemers/boundary-tenant`
  - `boundary-email` → `io.github.thijs-creemers/boundary-email`
  - `boundary-external` → `io.github.thijs-creemers/boundary-external` (skeleton, not production-ready)

### Quick Start

#### Try Boundary (Recommended)
Use the [boundary-starter](https://github.com/thijs-creemers/boundary-starter) template:
```bash
git clone https://github.com/thijs-creemers/boundary-starter
cd boundary-starter
export JWT_SECRET="change-me-dev-secret-min-32-chars"
export BND_ENV="development"
clojure -M:repl-clj
```

In REPL:
```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)  ;; Visit http://localhost:3000
```

**What you get**:
- ✅ SQLite database (zero-config)
- ✅ HTTP server on port 3000
- ✅ Complete Integrant system
- ✅ REPL-driven development
- ✅ Production-ready Dockerfile

#### Using Individual Libraries
```clojure
;; deps.edn
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-platform {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-user {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-admin {:mvn/version "1.0.0"}}}
```

### Deployment

#### Standalone JAR
```bash
clojure -T:build clean && clojure -T:build uber
java -jar target/boundary-*.jar server
```

#### Docker
Use provided `Dockerfile` in boundary-starter template.

#### Environment Variables
```bash
export JWT_SECRET="production-secret-min-32-chars"
export BND_ENV="production"
export DB_PASSWORD="secure_password"
export DATABASE_URL="jdbc:postgresql://localhost:5432/boundary"
```

### Known Issues and Limitations

#### Week 1 Limitations (To be addressed in Week 2+)
- **Admin permissions**: Entity-level and field-level permissions not yet implemented (admin-only)
- **Admin relationships**: Foreign key relationships not auto-detected
- **Composite primary keys**: Not fully supported in admin interface
- **Denylist mode**: Entity discovery only supports allowlist mode
- **Cursor-based pagination**: Not yet implemented (offset-based only)
- **Redis job queue**: In-memory only (Redis adapter planned)
- **External library**: Skeleton implementation, not production-ready

#### Pre-existing LSP Errors (Not Critical)
- **tenant/provisioning.clj**: Unresolved symbol `tx` (15 occurrences)
- **user/user_property_test.clj**: Unresolved test function symbols (17 occurrences)
- **platform/core_test.clj**: Unresolved symbol `tx-ctx` (5 occurrences)

These are false positives from clj-kondo's static analysis and do not affect runtime behavior.

#### Linting Warnings (Non-Critical)
- **Redundant `let` expressions**: 3 warnings in test files (cosmetic issue)

### Migration Guide

#### Not Applicable (First Release)
This is the initial 1.0.0 release. No migration from previous versions.

### Dependencies

#### Key Libraries
- **Clojure**: 1.12.0
- **Integrant**: 0.13.2 (lifecycle management)
- **Aero**: 1.1.6 (configuration)
- **Malli**: 0.16.4 (validation)
- **Reitit**: 0.7.2 (routing)
- **next.jdbc**: 1.3.955 (database)
- **HikariCP**: 6.2.1 (connection pooling)
- **Flyway**: 11.1.0 (migrations)
- **buddy**: 2.0.0 (authentication, JWT)
- **bcrypt**: 0.4.1 (password hashing)

#### Database Drivers
- **H2**: 2.3.232 (testing)
- **PostgreSQL**: 42.7.4 (production)
- **SQLite**: 3.47.2.0 (development)

### Contributors

- **Thijs Creemers** ([@thijs-creemers](https://github.com/thijs-creemers)) - Creator and maintainer

### License

Copyright 2024-2025 Thijs Creemers. All rights reserved.

### Acknowledgments

#### Inspirations
- **Django** (Python): Admin interface, conventions over configuration
- **Ruby on Rails**: Rapid development, batteries-included philosophy
- **Spring Boot** (Java): Production-ready infrastructure
- **Luminus** (Clojure): Web development patterns (not compared, superseded by Boundary)
- **Kit** (Clojure): Module system (not compared, superseded by Boundary)

#### Design Patterns
- **Functional Core / Imperative Shell**: Gary Bernhardt's "Boundaries" talk
- **Ports and Adapters**: Alistair Cockburn's Hexagonal Architecture
- **Problem Details (RFC 7807)**: HTTP API error responses

### Roadmap

#### Week 2+ Features (Post-1.0.0)
- **Admin enhancements**:
  - Entity-level permissions (custom per-entity access rules)
  - Field-level permissions (hide/show fields based on user)
  - Record-level permissions (row-level security)
  - Permission groups (reusable permission sets)
  - Relationship detection (foreign keys, belongs-to, has-many)
  - Composite primary key support
  - Denylist entity discovery mode
- **Pagination**:
  - Cursor-based pagination (for large datasets)
- **Job processing**:
  - Redis queue adapter (distributed job processing)
- **External library**:
  - HTTP client adapter
  - API client framework
  - Webhook handling
- **Database support**:
  - MySQL adapter
  - SQLite adapter improvements
- **Validation**:
  - Validation graph visualization improvements
  - Cross-field validation
- **Testing**:
  - Property-based testing examples
  - Integration test helpers

---

## Version History

- **[1.0.1-alpha-20]** - 2026-05-01: Fix `boundary new` bb.edn template — full boundary-tools task suite, version re-alignment
- **[1.0.1-alpha-14]** - 2026-04-25: Bug fixes — scaffolder in generated projects, AI CLI env fallback, OpenAI double /v1 path, smoke-check / linting in non-monorepo projects
- **[1.0.1-alpha-13]** - 2026-04-20: DX Vision (devtools, dev dashboard, REPL power, error experience, AI integration), LRU cache fix, CSP hardening
- **[1.0.1-alpha-12]** - 2026-04-06: E2E testing, admin UI improvements, auth bug fixes, quality gates, version bump
- **[1.0.0-alpha]** - 2026-02-14: Initial production release

[1.0.1-alpha-20]: https://github.com/thijs-creemers/boundary/releases/tag/1.0.1-alpha-20
[1.0.1-alpha-14]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.1-alpha-14
[1.0.1-alpha-13]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.1-alpha-13
[1.0.1-alpha-12]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.1-alpha-12
[1.0.0-alpha]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.0-alpha
