# BOU-56 — CSRF Reconciliation Design

Date: 2026-06-08
Ticket: [BOU-56](https://linear.app/boundary-app/issue/BOU-56) (parent BOU-54, blocks BOU-57)
Status: approved (brainstorm), pending implementation plan

## Background

BOU-56 was written to "replace a CSRF stub, wire `ring.middleware.anti-forgery/wrap-anti-forgery`,
and make enforcement opt-in." That premise is **stale**.

Commit `8956168` — **BOU-43 (PR #170): "real CSRF protection for session-authenticated
requests"**, already merged to `main` — replaced the stub with a complete, tested custom
implementation:

- `libs/platform/src/boundary/platform/core/csrf.clj` — pure HMAC-SHA256 synchronizer-token
  generation + validation, constant-time compare via buddy `mac/verify`.
- `http-csrf-protection` interceptor (`interceptors.clj` ~L392) — wired into
  `default-http-interceptors`. Validates state-changing requests, auto-issues tokens, binds
  `csrf/*token*` for rendering.
- Session binding for authenticated requests; pre-session cookie binding for unauthenticated
  `/web` flows (login / register / MFA).
- Form + `x-csrf-token` header extraction; `<meta>` tag + `init.js` listener for HTMX.
- Comprehensive tests in `security_test.clj` and `csrf_test.clj`.

The ticket's line references (`valid-csrf-token?` ~L55, `http-csrf-protection` ~L341) do not
match the current tree, confirming it was authored against the pre-BOU-43 state. The branch
`feat/BOU-56-CSRF-validation` has zero commits ahead of `main` — no BOU-56 work started.

**Decision (brainstorm):** keep the BOU-43 custom implementation. Do **not** switch to stock
`wrap-anti-forgery` (it cannot express the pre-session login binding the custom impl provides,
and the switch would delete working, tested code). Reconcile only the genuine remaining deltas.

## Genuine deltas vs `main`

1. **Enforcement default.** The library bakes `enabled? true` in two places, so a version bump
   would 403 any consumer rendering `/web` POST forms without tokens. BOU-56 #3 and the sibling
   BOU-57 ("emit tokens + *enable* enforcement") describe an **opt-in** model: the framework ships
   default-off; each consumer migrates by emitting tokens, then flipping enforcement on.
2. **HTMX emission helper.** BOU-56 #4 asked for an `hx-headers` helper. Only `hidden-field`
   (forms) + the `<meta>`/`init.js` path (HTMX) exist today. A self-contained `hx-headers` helper
   is wanted so consumers needn't depend on the global meta/JS listener.

Already correct — **no code change**, documented for the record:
- Real validation exists (not a stub).
- Admin `/web` POSTs are protected (`web-route?` matches the `/web` prefix, covering `/web/admin`;
  asserted by `security_test`). The ticket's "non-admin only" worry was pre-BOU-43.
- API routes are excluded (token-auth without a session cookie is not validated; plus
  `exempt-paths`).

## Part A — Code changes (this repo)

### A1. Flip enforcement default to opt-in (both sites)

The default lives in two places; both must change or "default off" is only half-true.

- `libs/platform/src/boundary/platform/shell/system/wiring.clj` ~L317: the merge default
  `:enabled? true` → `:enabled? false`. Update the adjacent comment to state the lib ships
  opt-in and consumers must set `:enabled? true` after emitting tokens.
- `libs/platform/src/boundary/platform/shell/http/interceptors.clj` ~L418: the destructure
  `:or {enabled? true}` → `:or {enabled? false}`. This is the fallback when a `:csrf` map omits
  `:enabled?`; it must also default off for true opt-in.

The fail-loud startup WARN (wiring.clj ~L323, fires only when enabled + blank secret) stays valid.

### A2. Compensate this repo's protection

This repo is itself a platform consumer. Flipping the lib default off would silently unprotect
environments that relied on default-on. Make this repo's intent explicit:

- `resources/conf/prod/config.edn` — add
  `:security {:csrf {:enabled? true :secret #env JWT_SECRET}}` under `:boundary/http`
  (no literal fallback — prod must fail loud without a secret).
- `resources/conf/acc/config.edn` — same.
- `resources/conf/dev/config.edn` — already `:enabled? true`; no change.
- `resources/conf/test/config.edn` — stays `:enabled? false`; no change.

(Exact nesting/key placement to match each file's existing `:boundary/http` shape — verified in
the implementation plan.)

### A3. Add `hx-headers` helper

In `libs/platform/src/boundary/platform/core/csrf.clj`, add a helper parallel to `hidden-field`:

```clojure
(defn hx-headers
  "HTMX attribute fragment carrying the CSRF token, for elements that should send
   it without relying on the global <meta>/init.js listener. Merge into an element's
   attribute map (e.g. on <body>) so all inherited hx-* requests include the header.

   0-arity reads the token bound for the current request (*token*); 1-arity takes an
   explicit token. Returns nil when the token is nil, so callers can merge it
   unconditionally."
  ([] (hx-headers *token*))
  ([token]
   (when token
     {:hx-headers (cheshire.core/generate-string {header-name token})})))
```

- Returns a **mergeable attribute map** (`{:hx-headers "..."}`) or `nil` — distinct from
  `hidden-field`, which returns a Hiccup element. Call site: `[:body (merge attrs (csrf/hx-headers)) ...]`.
- Header key uses the existing `header-name` constant (`"x-csrf-token"`); the interceptor's
  `extract-token` already reads that header. (Case: HTTP headers are case-insensitive and Ring
  lowercases them, so `"x-csrf-token"` is consistent with the documented `X-CSRF-Token`.)
- JSON via `cheshire.core/generate-string`. cheshire is a declared platform dep and is already
  required from `core/http/problem_details.clj`, so this respects FC/IS (pure string encoding,
  no I/O). Add the `[cheshire.core :as json]` require to the `csrf.clj` ns form.

### A4. Tests

- `libs/platform/test/boundary/platform/core/csrf_test.clj`:
  - `hx-headers` 1-arity returns `{:hx-headers <json>}` whose JSON parses to
    `{"x-csrf-token" <token>}`.
  - `hx-headers` with `nil` token returns `nil`.
  - `hx-headers` 0-arity reads `*token*` under `binding`.
- `libs/platform/test/boundary/platform/shell/security_test.clj`:
  - **Opt-in default assertion**: a `:csrf` map with `:secret` present but **no `:enabled?` key**
    → a state-changing POST is *not* validated (interceptor no-ops). Proves both default sites are
    off. Existing explicit-`:enabled? true` tests remain unchanged and must still pass.

### A5. Docs

- Update docstrings: the `csrf.clj` ns header and the `http-csrf-protection` docstring to state
  enforcement is opt-in (default off) and how to enable.
- `libs/platform/AGENTS.md`: short CSRF section — opt-in default, `hidden-field` for forms,
  `hx-headers` vs `<meta>`/`init.js` for HTMX, config key path, API-route exclusion.

## Part B — Ticket corrections (Linear)

### BOU-56
Rewrite the description to reflect reality:
- Drop "stub always returns true" / "wire `wrap-anti-forgery`" framing.
- State BOU-43 already shipped real validation; this ticket = make it opt-in + add the
  `hx-headers` helper + correct the admin/API notes (already handled).
- Keep references accurate (`core/csrf.clj`, current interceptor location).

### BOU-57
Rewrite to the real API:
- Point 1: not "version that ships real validation" (already shipped) — the bump pulls in the
  **opt-in default + `hx-headers` helper**.
- Point 2: flipping enforcement on is **mandatory** — lib default is now off, so skipping it means
  zero CSRF for boundary-license.
- Points 3-4: name the real API — `boundary.platform.core.csrf/hidden-field` for forms,
  `boundary.platform.core.csrf/hx-headers` merged onto `<body>` for HTMX (not stock
  `*anti-forgery-token*` / `wrap-anti-forgery`), config path
  `:boundary/http {:security {:csrf {:enabled? true :secret …}}}`.

BOU-57's code (separate boundary-license repo) is **out of scope** for this branch.

## Part C — Consumer integration contract

The stable contract BOU-57 (and any consumer) integrates against:

| Concern | Contract |
|---|---|
| Namespace | `boundary.platform.core.csrf` |
| Enable | config `:boundary/http {:security {:csrf {:enabled? true :secret <≥32 chars>}}}` — lib default is **off**; consumer must set on |
| Forms | splice `(csrf/hidden-field)` into each `/web` POST form (0-arity reads bound `*token*`; nil-safe) |
| HTMX | `(csrf/hx-headers)` merged onto `<body>` attrs (inherits to all `hx-*`); or `<meta name="csrf-token" :content (csrf/current-token)>` + ui-style `init.js` listener |
| Token lifecycle | interceptor auto-issues + binds `*token*` for `/web` (session binding when authed; pre-session cookie for login/register/MFA). No handler threading |
| API routes | untouched — token-auth/JWT, no session cookie → not validated. Do not add tokens |
| Reject rule | state-changing POST/PUT/DELETE/PATCH on `/web` or session-authenticated → 403 without a valid token |

## Out of scope / final steps

- Platform lib-suite **version bump** (BOU-56 #6) — separate release step (boundary-version-bump
  skill + release checklist). Run after code lands.
- Optional: drop the now-unused `ring/ring-anti-forgery` dependency if confirmed unreferenced
  (decision is to not use stock middleware). Verify before removing.
- BOU-57 implementation (boundary-license repo).

## Test plan summary

- `clojure -M:test:db/h2 :platform` green, including new hx-headers + opt-in assertions.
- Existing CSRF security tests unchanged and passing (they pass explicit `:enabled? true`).
- `bb check:fcis` passes (cheshire in core already precedented; helper is pure).
- `clojure -M:clj-kondo --lint` clean on changed files.
