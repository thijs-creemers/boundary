# Playwright E2E Test Suite — Login Sequence

**Date:** 2026-04-05
**Branch:** `feat/playwright-test-login-sequence`
**Status:** Design approved, pending spec review

## Goal

A Playwright end-to-end test suite that covers the full Boundary platform login
sequence — both the HTML form flows (`/tenants/login`, `/tenants/activate`,
`/portal/accept`) and the underlying `boundary-user` library API endpoints
(`/api/auth/*`). Tests must run isolated with an H2 in-memory database and a
clean state per test, and must pass in CI.

## Scope

### HTML flows (Hiccup + HTMX, `form.form-card`)

| Route | Scenarios |
|---|---|
| `GET/POST /tenants/login` | form render, `remembered-email` cookie prefill, happy path + `session-token` cookie, `return-to` redirect, invalid credentials, empty fields |
| `GET/POST /tenants/activate` | form render, happy path (create + auto-login + `notify=activation-success`), unknown tenant slug, weak password per-field errors, already-activated account |
| `GET/POST /portal/accept?token=...` | valid token prefill, invalid/expired token (error, no form), happy path + `notify=portal-accepted`, password mismatch, weak password |

### API endpoints (`boundary-user`)

| Endpoint | Scenarios |
|---|---|
| `POST /api/auth/login` | happy, wrong password (401), unknown email (401, no user enumeration), lockout after repeat failures, MFA flows (see below) |
| `POST /api/auth/register` | happy, duplicate email (409), weak password (400 with policy details) |
| `POST /api/auth/mfa/setup` | returns TOTP secret |
| `POST /api/auth/mfa/enable` | correct code activates MFA; wrong code rejected |
| `POST /api/auth/mfa/disable` | correct code disables MFA |
| `GET /api/auth/sessions` | lists active sessions |
| `DELETE /api/auth/sessions/:id` | subsequent requests with revoked token get 401 |
| cross-cutting | protected endpoint without token returns 401; `password-hash` never appears in any API response |

**Total scenarios: ~33 tests** across 7 spec files.

## Architecture

### Directory layout

```
e2e/
├── package.json                  # @playwright/test, otplib, typescript
├── playwright.config.ts          # baseURL, webServer, serial workers
├── tsconfig.json
├── fixtures/
│   ├── app.ts                    # custom test() with resetDb + api + auto seed fixture
│   ├── seed.ts                   # baseline seed definition (shared with Clojure via JSON contract)
│   └── totp.ts                   # otplib-based TOTP helper
├── helpers/
│   ├── reset.ts                  # POST /test/reset wrapper
│   ├── users.ts                  # createUserViaApi / enableMfaForUser / loginViaApi
│   └── cookies.ts                # session-token cookie assertions
└── tests/
    ├── html/
    │   ├── tenants-login.spec.ts
    │   ├── tenants-activate.spec.ts
    │   └── portal-accept.spec.ts
    └── api/
        ├── auth-login.spec.ts
        ├── auth-register.spec.ts
        ├── auth-mfa.spec.ts
        └── auth-sessions.spec.ts
```

### Clojure-side additions

- **`bb e2e`** (new in `bb.edn`): thin orchestrator that runs `npx playwright
  test` inside `e2e/`. Playwright's own `webServer` config starts the app, so
  `bb e2e` is essentially `cd e2e && npx playwright test`. Added for
  consistency with existing `bb scaffold` / `bb doctor` / `bb check:*`
  commands.
- **`bb run-e2e-server`** (new): starts the app in `:test` profile on a fixed
  port (`3100`) with H2 in-memory, reset endpoint enabled. This is what
  `playwright.config.ts` `webServer.command` invokes.
- **Test-only HTTP endpoint** `POST /test/reset` (details below).
- **Test-support library source**: `libs/test-support/` (or `src/boundary/test_support/`
  if we keep it inside the main app to avoid publishable library) containing
  `core.clj` (pure seed specs) and `shell/reset.clj` + `shell/handler.clj`.
- **CI job** in `.github/workflows/ci.yml` running `bb e2e` with Playwright
  browser cache between runs.

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

### Baseline seed

What `/test/reset` default-installs:

| Entity | Details | Used by |
|---|---|---|
| Tenant | `slug=acme`, `name=Acme Test` | all flows |
| Admin user | `admin@acme.test` / `Test-Pass-1234!`, activated, no MFA | `/tenants/login` happy, API login |
| Pending admin | `pending@acme.test`, not activated | `/tenants/activate` |
| Contractor invite | `invitee@acme.test` + token | `/portal/accept` |

MFA-user, lockout-user, and duplicate-registration state is **not** in
baseline — tests build it via helpers (hybrid approach). Keeps baseline small
and per-test code readable.

### Playwright fixture

Custom `test` exported from `e2e/fixtures/app.ts`:

```ts
export const test = base.extend<{
  resetDb: (seed?: 'baseline' | 'empty') => Promise<SeedResult>;
  api: ApiClient;
  seed: SeedResult;  // auto-fixture: runs resetDb('baseline') before every test
}>({ ... });
```

- `seed` is an **auto-fixture** — runs `POST /test/reset` before every test
  and yields the generated fixture values. This is the "clean state per test"
  guarantee required by acceptance criteria.
- `api` wraps `request.newContext()` with `baseURL`, JSON headers, and
  optional session-token cookie injection.
- Tests needing an empty DB override explicitly:
  `await resetDb('empty')` in the test body.

### Helpers

| Helper | Purpose |
|---|---|
| `createUserViaApi(api, {email, password})` | `POST /api/auth/register`, returns `{user, sessionToken}` |
| `enableMfaForUser(api, sessionToken)` | setup → enable with current TOTP code, returns secret |
| `currentTotp(secret)` | pure `otplib.authenticator.generate(secret)` |
| `loginViaApi(api, {email, password, mfaCode?})` | shared login for test setup phases |
| `expectSessionCookie(response)` | asserts `Set-Cookie: session-token=...; HttpOnly`, returns token value |
| `expectNoSessionCookie(response)` | asserts no session-token is set |

### HTML selectors

- Forms: `page.locator('form.form-card')` (per Boundary UI convention stated
  in the task).
- Fields: `getByLabel(...)` for accessibility + stability.
- Error messages: `form.getByRole('alert')` with text assertions. Exact
  error strings are pulled from the existing `libs/user` / `libs/admin` i18n
  catalogue during implementation — one source of truth, robust to copy
  changes.

### Cookie assertions

Always via `response.headers()['set-cookie']` or `context.cookies()` — never
`document.cookie`, because `session-token` is `HttpOnly`. Same for
`remembered-email`.

### Parallelism

`fullyParallel: false`, `workers: 1`. A single shared app instance means
parallel tests would step on each other's lockout counters and sessions.
Serial execution is the honest trade-off. Estimated wall time: < 2 minutes.

## Components & data flow

```
┌─────────────────────────┐        ┌────────────────────────────┐
│ playwright.config.ts    │  spawn │ bb run-e2e-server          │
│   webServer.command ────┼───────▶│   :test profile + H2       │
│   baseURL http://...    │  wait  │   :test/reset-enabled? true│
└────────┬────────────────┘        └──────────┬─────────────────┘
         │                                    │
         │ test run (workers=1, serial)       │
         ▼                                    │
┌─────────────────────────┐                   │
│ fixtures/app.ts         │  POST /test/reset │
│   seed auto-fixture ────┼──────────────────▶│──▶ boundary.test-support
└────────┬────────────────┘                   │    .shell.reset/reset!
         │ seed result                        │       truncate → seed
         ▼                                    │
┌─────────────────────────┐                   │
│ test body               │  HTTP (HTML+API)  │
│   page.goto / api.post ─┼──────────────────▶│──▶ reitit router
│   assertions            │◀──────────────────┼──  /tenants/*, /portal/*,
└─────────────────────────┘                   │    /api/auth/*
                                              │
                                              ▼
                                         H2 in-memory
```

## Error handling

- **App crash during reset**: `reset-handler` catches exceptions, returns
  `500` with error info, test fails with clear message. No silent partial
  state.
- **Reset endpoint unreachable in CI**: Playwright `webServer.timeout = 60000`
  + `/health` probe. If the server never comes up, the suite fails at
  setup time, not with confusing test failures.
- **Flaky TOTP timing** (MFA test running across 30-second window boundary):
  helper generates code with `otplib.authenticator.generate`, retries once if
  the window is about to roll over. (Edge case — acceptable to leave as a
  known limitation if this proves rare.)
- **Reset endpoint enabled in prod config**: startup assertion crashes the
  app; `bb doctor` catches it earlier in CI.

## Testing (of the test infrastructure)

- `boundary.test-support.core` gets unit tests (`:unit` metadata) for its
  pure seed-spec functions.
- `boundary.test-support.shell.reset` gets a contract test (`:contract`) that
  runs the full reset against H2 and verifies the seeded rows exist via
  direct queries. This is the safety net — if prod code paths that the seed
  uses break, this test catches it immediately.
- Playwright suite itself is the integration/e2e layer — it is the test.

## CI integration

New job in `.github/workflows/ci.yml`:

```yaml
e2e:
  runs-on: ubuntu-latest
  steps:
    - checkout
    - setup-java + setup-clojure + setup-babashka
    - setup-node
    - cache ~/.cache/ms-playwright
    - cd e2e && npm ci
    - cd e2e && npx playwright install --with-deps chromium
    - bb e2e
    - upload playwright-report on failure
```

Runs on PRs and pushes to `main`. Chromium-only (Firefox/WebKit add ~2x CI
time for marginal coverage on server-rendered HTML).

## Non-goals

- Multi-browser matrix (chromium only).
- Visual regression / screenshot diffing.
- Performance benchmarking.
- Load testing (separate concern).
- Covering non-login flows (dashboards, admin CRUD, etc.) — follow-up work.

## Acceptance criteria

- [ ] All ~33 scenarios implemented as Playwright tests, passing locally and in CI.
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
  a library consumers depend on.
