# Login E2E Test Suite (Clojure + spel)

**Date:** 2026-04-05
**Branch:** `feat/playwright-test-login-sequence` (branch name retained; implementation uses Clojure/spel, not TypeScript/Playwright)
**Status:** Design approved, scope narrowed 2026-04-05 after route verification; tech-stack switched to Clojure + spel 2026-04-05 after user feedback

## Scope correction (2026-04-05)

The original brainstorm referenced HTML routes `/tenants/login`,
`/tenants/activate`, and `/portal/accept`. Exploration of the codebase
confirmed these routes do not exist. The real login form lives at
`/web/login` (`libs/user/src/boundary/user/shell/http.clj:495`, template
`libs/user/src/boundary/user/core/ui.clj:659`), and self-service
registration lives at `/web/register`. No admin-activation or contractor
portal HTML flow exists today.

Scope has been narrowed to cover only what exists:

- `/web/login` (GET/POST) — including remember-me, return-to, and the MFA
  second-step form served at the same path
- `/web/register` (GET/POST) — self-service registration
- All `/api/auth/*` scenarios as originally specified (login, register, MFA,
  sessions) — these endpoints all exist and match the original spec

Dropped:

- `/tenants/activate` admin activation flow — does not exist
- `/portal/accept` contractor invite flow — does not exist

Follow-up work (out of scope for this plan): building those HTML flows as
production features would require their own brainstorm and design.

## Tech-stack correction (2026-04-05)

The original design proposed `@playwright/test` + TypeScript under an
`e2e/` directory with its own `package.json`. User feedback flagged this
as introducing an unwanted second ecosystem (Node, npm, TypeScript) into
an otherwise Clojure-first monorepo.

Revised approach: use [spel](https://github.com/Blockether/spel), an
idiomatic Clojure wrapper around Playwright Java, as a sub-library at
`libs/e2e/` with its own `deps.edn` pulling in `com.blockether/spel`.
Tests live under `libs/e2e/test/boundary/e2e/` and run via Kaocha with
`^:e2e` metadata. A new `:e2e` alias in the root `deps.edn` keeps spel
opt-in so normal `clojure -M:test` runs don't download Playwright Java
browsers. The server-side `/test/reset` endpoint, baseline seed, and
profile-guarded wiring are unchanged.

All scenarios (HTML flows and API endpoints) and the coverage goals
remain identical. Only the test client and tooling have changed.

## Goal

An end-to-end test suite that covers the full Boundary platform login
sequence — both the HTML form flows (`/web/login`, `/web/register`) and
the underlying `boundary-user` library API endpoints (`/api/v1/auth/*`).
Tests must run isolated with an H2 in-memory database and a clean state
per test, and must pass in CI. Implementation is in Clojure using spel
(Playwright Java wrapper) — no Node.js, npm, or TypeScript is introduced.

## Scope

### HTML flows (Hiccup + HTMX, `form.form-card.ui-form-shell`)

| Route | Scenarios |
|---|---|
| `GET/POST /web/login` | form render, `remembered-email` cookie prefill, happy path sets `session-token` cookie, `remember=on` sets 30-day `remembered-email` cookie, `return-to` redirect honoured, admin-role default redirect to `/web/admin/users`, invalid credentials re-render with error (400), empty fields validation errors, MFA-required path renders `mfa-login-page` with `mfa-code` field, MFA happy path, MFA wrong code |
| `GET/POST /web/register` | form render, happy path (creates user + redirects + session cookie), duplicate email error, weak password policy errors per-field |

### API endpoints (`boundary-user`, real paths are `/api/v1/...`)

Source-of-truth verified in `libs/user/src/boundary/user/shell/http.clj:338-468`. The
`/api/v1` prefix is applied globally by `boundary.platform.shell.http.versioning/apply-versioning`;
unversioned `/api/...` paths exist only as 307 redirects.

| Endpoint | Scenarios |
|---|---|
| `POST /api/v1/auth/login` | happy, wrong password (401), unknown email (401, no user enumeration), lockout after repeat failures |
| `POST /api/v1/auth/register` | happy, duplicate email (409), weak password (400 with policy details) |
| `POST /api/v1/auth/mfa/setup` (authenticated) | returns `{secret, qrCodeUrl, backupCodes, ...}` |
| `POST /api/v1/auth/mfa/enable` (authenticated) | body `{secret, backupCodes, verificationCode}` — correct code activates MFA; wrong code rejected |
| `POST /api/v1/auth/mfa/disable` (authenticated) | no body — disables MFA for the authenticated user |
| `GET /api/v1/auth/mfa/status` (authenticated) | returns MFA enabled flag |
| `DELETE /api/v1/sessions/:token` | revokes a session; subsequent validation returns 401 |
| `GET /api/v1/sessions/:token` | validates a session token |
| cross-cutting | protected endpoint without token returns 401; `password-hash` never appears in any API response |

**Scope note on MFA login:** the JSON API `/api/v1/auth/login` schema is
`:closed` and does **not** accept an `mfaCode` field. MFA second-step is
implemented only via the HTML `mfa-login-form` on `/web/login`. Therefore
"login with MFA active" scenarios are covered by the HTML suite, not the
API suite.

**Scope note on sessions listing:** there is no `GET /api/v1/sessions`
list endpoint. Session management consists of create (`POST`), validate
(`GET /:token`), and invalidate (`DELETE /:token`). Revocation tests
obtain the token from the login `Set-Cookie` header and `DELETE` it
directly.

**Total scenarios: ~28 tests** across 6 spec files (2 HTML + 4 API).

## Architecture

### Directory layout

```
libs/e2e/                            # New sub-library, opt-in via :e2e alias
├── deps.edn                         # spel + kaocha + one-time + clj-http
├── src/boundary/e2e/README.md       # placeholder; no production code
└── test/boundary/e2e/
    ├── helpers/
    │   ├── reset.clj                # POST /test/reset via clj-http
    │   ├── users.clj                # login/register/MFA via clj-http
    │   ├── cookies.clj              # Set-Cookie parsing + HttpOnly assertions
    │   └── totp.clj                 # one-time wrapper
    ├── fixtures.clj                 # use-fixtures :each with-fresh-seed
    ├── smoke_test.clj               # canary: server reachable + seed fixture works
    ├── api/
    │   ├── auth_login_test.clj
    │   ├── auth_register_test.clj
    │   ├── auth_mfa_test.clj
    │   └── auth_sessions_test.clj
    └── html/
        ├── web_login_test.clj
        └── web_register_test.clj

deps.edn                             # NEW :e2e alias with com.blockether/spel dep
tests.edn                            # NEW :e2e kaocha suite + :e2e focus-meta
```

### Clojure-side additions

- **`bb e2e`** (new in `bb.edn`): orchestrator task that starts the app in
  `:test` profile on port 3100, waits for `/web/login` to respond, runs
  `clojure -M:test:e2e :e2e` (kaocha with the `:e2e` alias active), and
  tears down the server on exit. Single command for local + CI runs.
- **`bb run-e2e-server`** (new): starts the app in `:test` profile on a
  fixed port (`3100`) with H2 in-memory and `:test/reset-endpoint-enabled?`
  true. Called by `bb e2e` and available for manual debugging.
- **Test-only HTTP endpoint** `POST /test/reset` (details below).
- **Test-support library source**: `libs/test-support/` (or `src/boundary/test_support/`
  if we keep it inside the main app to avoid publishable library) containing
  `core.clj` (pure seed specs) and `shell/reset.clj` + `shell/handler.clj`.
- **CI job** in `.github/workflows/ci.yml` running `bb e2e` with caches for
  both Maven deps and the Playwright Java browser bundle that spel manages.

### Test reset endpoint

`POST /test/reset` is mounted in the reitit router **only** when
`(get-in config [:test :reset-endpoint-enabled?])` is true. Flag is set in
`resources/conf/test/config.edn` only, never in `dev`/`acc`/`prod`.

**Defence in depth:**

1. Config flag guards route mounting.
2. Mount code asserts profile is not `:prod` — crashes at startup otherwise.
3. New `bb doctor` check warns if the flag is set to true in any non-test config file.
4. FC/IS boundary: `boundary.test-support.core` is pure and covered by `bb check:fcis`.

**Request/response:**

```http
POST /test/reset
Content-Type: application/json

{"seed": "baseline"}  // or "empty", default "baseline"
```

```json
{
  "ok": true,
  "seeded": {
    "tenant": {"slug": "acme", "id": "..."},
    "admin": {"email": "admin@acme.test", "password": "Test-Pass-1234!", "id": "..."},
    "pendingAdmin": {"email": "pending@acme.test"},
    "inviteToken": "abc123..."
  }
}
```

Tests consume the returned IDs/tokens via the `seed` auto-fixture — no
hardcoding of tokens or ephemeral values.

**Implementation (FC/IS-correct):**

- Core (pure): `boundary.test-support.core/baseline-seed-spec` returns a
  data description of tenants/users/invites.
- Shell: `boundary.test-support.shell.reset/reset!` truncates tables via
  `next.jdbc` + HoneySQL (TRUNCATE CASCADE on H2), then calls existing
  production shell services (`user.shell.service/register`,
  `tenant.shell.service/create-tenant`, etc.) to insert seed rows. This reuses
  production code paths and implicitly validates seed-data consistency.
- Handler: `boundary.test-support.shell.handler/reset-handler` — thin HTTP
  wrapper returning JSON.

**Performance target:** reset < 200 ms. With 33 tests: ≤ 7 s reset overhead
total.

**Performance caveats to resolve during planning:**

- H2 does not support `TRUNCATE ... CASCADE` the way Postgres does. Use
  `SET REFERENTIAL_INTEGRITY FALSE` + `TRUNCATE TABLE` per table (or
  `DELETE FROM`) and re-enable referential integrity after.
- Reusing production `register` / `create-tenant` services routes seed
  password creation through bcrypt/argon2, which can blow the 200 ms budget
  on its own for 3–4 users. Mitigation options (pick one in the plan):
  lower the hash cost factor in `:test` profile config, **or** provide a
  shell helper that inserts the seeded admin rows with a pre-computed hash
  while still exercising production code paths for everything else.

### Baseline seed

What `/test/reset` default-installs:

| Entity | Details | Used by |
|---|---|---|
| Tenant | `slug=acme`, `name=Acme Test` | all flows |
| Admin user | `admin@acme.test` / `Test-Pass-1234!`, activated, role `:admin`, no MFA | `/web/login` admin-redirect test, API login |
| Regular user | `user@acme.test` / `Test-Pass-1234!`, activated, role `:user`, no MFA | `/web/login` happy path (dashboard redirect), sessions tests |

MFA-user, lockout-user, and duplicate-registration state is **not** in
baseline — tests build it via helpers (hybrid approach). Keeps baseline small
and per-test code readable.

### Shared test fixture

A kaocha `use-fixtures :each` function at `libs/e2e/test/boundary/e2e/fixtures.clj`:

```clojure
(def ^:dynamic *seed* nil)

(defn with-fresh-seed [f]
  (let [seed (reset/reset-db!)]
    (binding [*seed* seed]
      (f))))
```

- Every e2e test namespace declares `(use-fixtures :each fixtures/with-fresh-seed)` and reads `fx/*seed*` to get the baseline tenant/admin/user.
- Tests needing an empty DB call `(reset/reset-db! {:seed :empty})` explicitly in the test body.
- The "clean state per test" acceptance criterion is satisfied because `/test/reset` runs before every single test.

### Helpers

| Helper | Purpose |
|---|---|
| `reset/reset-db!` | `POST /test/reset` via clj-http, returns parsed `SeedResult` |
| `users/login` | `POST /api/v1/auth/login` |
| `users/register` | `POST /api/v1/auth/register` |
| `users/enable-mfa!` | setup → enable with current TOTP code; returns `{:secret :backupCodes ...}` |
| `users/disable-mfa!` | `POST /api/v1/auth/mfa/disable` (no body) |
| `users/mfa-status` | `GET /api/v1/auth/mfa/status` |
| `totp/current-code` / `totp/fresh-code` | TOTP via `one-time.core` |
| `cookies/session-token` | parses `Set-Cookie: session-token`, asserts HttpOnly |
| `cookies/no-session-token?` | asserts session-token is NOT set |
| `cookies/remembered-email` | parses and URL-decodes the `remembered-email` cookie |

### HTML selectors

- Forms: `form.form-card[action='/web/login']` (via spel's `page/visible?` / `page/locator`). The `form-card` class is the Boundary UI convention (verified at `libs/user/src/boundary/user/core/ui.clj:676`).
- Fields by `name` attribute: `input[name='email']`, `input[name='password']`, etc. Matches the real form markup.
- Error messages: `.validation-errors` — the class used by the `form-field` Hiccup helper.

### Cookie assertions

Always via response headers (`headersArray` equivalent — `:headers` map on clj-http responses, parsed by `helpers.cookies`) or via spel's `page/cookie`. Never via JS `document.cookie`, because `session-token` is `HttpOnly`. Same for `remembered-email`.

### Parallelism

`workers: 1`. A single shared app instance means parallel tests would step on each other's lockout counters and sessions. Serial execution is the honest trade-off. Kaocha runs tests in a single JVM thread by default, matching this requirement without configuration. Estimated wall time: < 3 minutes.

## Components & data flow

```
┌─────────────────────────┐        ┌────────────────────────────┐
│ bb e2e task             │  spawn │ bb run-e2e-server (bg)     │
│   1) start server       │───────▶│   :test profile + H2       │
│   2) wait on /web/login │  wait  │   :test/reset-enabled? true│
│   3) clojure -M:test:e2e│        └──────────┬─────────────────┘
│   4) teardown on exit   │                   │
└────────┬────────────────┘                   │
         │                                    │
         │ kaocha :e2e suite (single JVM)     │
         ▼                                    │
┌─────────────────────────┐                   │
│ fixtures/with-fresh-seed│  POST /test/reset │
│   (use-fixtures :each) ─┼──────────────────▶│──▶ boundary.test-support
└────────┬────────────────┘                   │    .shell.reset
         │ binds *seed*                       │       truncate + seed
         ▼                                    │
┌─────────────────────────┐                   │
│ test body               │  HTTP (clj-http)  │
│   spel/with-testing-page┼──────────────────▶│──▶ reitit router
│   + clj-http calls      │◀──────────────────┼──  /web/*, /api/v1/*
└─────────────────────────┘                   │
                                              ▼
                                         H2 in-memory
```

## Error handling

- **App crash during reset**: `reset-handler` catches exceptions, returns
  `500` with error info, test fails with clear message. No silent partial
  state.
- **Reset endpoint unreachable in CI**: `bb e2e` polls `GET /web/login` for
  up to 60 seconds and throws `ex-info` if the server never comes up. If the
  server dies mid-run, clj-http will surface connection refused errors
  directly in the failing test, not as confusing assertion failures.
- **Flaky TOTP timing** (MFA test running across 30-second window boundary):
  `helpers.totp/fresh-code` sleeps until the window has at least 2 seconds
  left before returning a code. Cheap and removes the edge case entirely.
- **Reset endpoint enabled in prod config**: startup assertion crashes the
  app; `bb doctor` catches it earlier in CI.

## Testing (of the test infrastructure)

- `boundary.test-support.core` gets unit tests (`:unit` metadata) for its
  pure seed-spec functions.
- `boundary.test-support.shell.reset` gets a contract test (`:contract`) that
  runs the full reset against H2 and verifies the seeded rows exist via
  direct queries. This is the safety net — if prod code paths that the seed
  uses break, this test catches it immediately.
- The spel/kaocha e2e suite at `libs/e2e/test/boundary/e2e/` is the integration/e2e layer — it is the test.

## CI integration

New job in `.github/workflows/ci.yml`:

```yaml
e2e:
  runs-on: ubuntu-latest
  steps:
    - checkout
    - setup-java + setup-clojure (with bb) + cache maven deps
    - cache Playwright browser bundle (~/.cache/ms-playwright)
    - clojure -P -M:test:e2e              # warm deps incl. spel
    - bb e2e                               # run the suite
    - upload target/spel/ + target/test-output/ on failure
```

Runs on PRs and pushes to `main`. Chromium-only (Firefox/WebKit add ~2x CI
time for marginal coverage on server-rendered HTML). No Node.js / npm
steps needed — everything is JVM/Clojure.

## Non-goals

- Multi-browser matrix (chromium only).
- Visual regression / screenshot diffing.
- Performance benchmarking.
- Load testing (separate concern).
- Covering non-login flows (dashboards, admin CRUD, etc.) — follow-up work.

## Acceptance criteria

- [ ] All ~28 scenarios implemented as Clojure/spel e2e tests, passing locally and in CI.
- [ ] No Node.js, npm, or TypeScript introduced to the repo.
- [ ] `com.blockether/spel` is the only new Clojars dependency, isolated under the `:e2e` alias / `libs/e2e` sub-library.
- [ ] HTML form flows and API endpoints both covered.
- [ ] Clean state per test via `/test/reset` + `seed` auto-fixture.
- [ ] H2 in-memory, no external services.
- [ ] `bb e2e` is the single entry point.
- [ ] `:test/reset-endpoint-enabled?` cannot be true in prod (enforced by
      startup assertion + `bb doctor` check).
- [ ] `password-hash` absent from every API response under test.
- [ ] `session-token` and `remembered-email` asserted only via `HttpOnly`-aware
      APIs (cookies jar / Set-Cookie header).

## Open questions (deferred to implementation)

- Exact error-message text lookup in `libs/user`/`libs/admin` i18n — resolved
  during implementation by reading current catalogues.
- Whether `test-support` lives under `libs/` (publishable) or `src/` (app-only).
  Leaning toward `src/boundary/test_support/` because it should never ship as
  a library consumers depend on. **Caveat**: `bb check:fcis` and
  `bb check:deps` are configured per-library — confirm the chosen location is
  covered by those checks before relying on the FC/IS acceptance criterion.
  If not, extend the check config to include the test-support path.
