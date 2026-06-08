# BOU-56 CSRF Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the framework's existing custom CSRF protection opt-in (default off) without losing protection in this repo, add an `hx-headers` HTMX emission helper, and correct the two stale Linear tickets — keeping the working BOU-43 implementation (no switch to stock `wrap-anti-forgery`).

**Architecture:** The CSRF impl already exists (`libs/platform/.../core/csrf.clj` + `http-csrf-protection` interceptor). This plan changes two enforcement-default sites from `true` to `false`, compensates this repo's prod/acc configs with explicit `:enabled? true`, adds one pure helper function, and updates tests/docs. No rewrite.

**Tech Stack:** Clojure 1.12.4, buddy (HMAC), cheshire (JSON), Aero (config), Kaocha (tests, H2), clj-kondo (lint), Babashka (`bb doctor`).

**Spec:** `docs/superpowers/specs/2026-06-08-bou-56-csrf-reconciliation-design.md`

---

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `libs/platform/src/boundary/platform/core/csrf.clj` | Modify | Add `hx-headers` helper + cheshire require; update ns docstring |
| `libs/platform/test/boundary/platform/core/csrf_test.clj` | Modify | Unit tests for `hx-headers` |
| `libs/platform/src/boundary/platform/shell/http/interceptors.clj` | Modify | Flip `:or {enabled? true}` → `false`; docstring note |
| `libs/platform/src/boundary/platform/shell/system/wiring.clj` | Modify | Flip merge default `:enabled? true` → `false`; comment |
| `libs/platform/test/boundary/platform/shell/security_test.clj` | Modify | Opt-in default-off interceptor test |
| `resources/conf/prod/config.edn` | Modify | Create `:boundary/http {:security {:csrf …}}` block (explicit enable) |
| `resources/conf/acc/config.edn` | Modify | Same |
| `libs/platform/AGENTS.md` | Modify | CSRF section (opt-in, helpers, config) |
| Linear BOU-56, BOU-57 | Update | Rewrite descriptions to real API |

Task order: Task 1 (helper, isolated) → Task 2 (default flip + test) → Task 3 (config compensation) → Task 4 (docs) → Task 5 (Linear) → Task 6 (final verification). Each task ends in a commit.

---

### Task 1: Add `hx-headers` emission helper

**Files:**
- Modify: `libs/platform/src/boundary/platform/core/csrf.clj` (ns require ~L22-25; new fn after `hidden-field` ~L127)
- Test: `libs/platform/test/boundary/platform/core/csrf_test.clj`

- [ ] **Step 1: Write the failing tests**

Add to the END of `libs/platform/test/boundary/platform/core/csrf_test.clj` (and add `[cheshire.core :as json]` to its `:require`):

```clojure
(deftest ^:unit hx-headers-test
  (testing "1-arity returns a mergeable {:hx-headers <json>} attr map"
    (let [token "nonce123.mac456"
          attrs (csrf/hx-headers token)]
      (is (map? attrs))
      (is (contains? attrs :hx-headers))
      (testing "the json value is {\"x-csrf-token\": <token>}"
        (is (= {"x-csrf-token" token} (json/parse-string (:hx-headers attrs)))))))

  (testing "nil token returns nil (callers can merge unconditionally)"
    (is (nil? (csrf/hx-headers nil))))

  (testing "0-arity reads the request-bound *token*"
    (binding [csrf/*token* "bound.tok"]
      (is (= {"x-csrf-token" "bound.tok"}
             (json/parse-string (:hx-headers (csrf/hx-headers))))))
    (testing "0-arity is nil when no token is bound"
      (is (nil? (csrf/hx-headers))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.platform.core.csrf-test`
Expected: FAIL — `csrf/hx-headers` unresolved (and `json` unresolved if require not added yet).

- [ ] **Step 3: Add the cheshire require**

In `csrf.clj`, change the `:require` (currently L22-25):

```clojure
  (:require [buddy.core.mac :as mac]
            [buddy.core.bytes :as bytes]
            [buddy.core.codecs :as codecs]
            [cheshire.core :as json]
            [clojure.string :as str]))
```

- [ ] **Step 4: Implement `hx-headers`**

Append after `hidden-field` (after current L127) in `csrf.clj`:

```clojure
(defn hx-headers
  "HTMX attribute fragment carrying the CSRF token, for elements that should send
   it without relying on the global <meta>/init.js listener. Merge into an
   element's attribute map (e.g. on <body>) so all inherited hx-* requests include
   the header: [:body (merge attrs (hx-headers)) ...].

   The 0-arity reads the token bound for the current request (*token*); the 1-arity
   takes an explicit token. Returns nil when the token is nil, so callers can merge
   the result unconditionally. The header key uses `header-name` (\"x-csrf-token\");
   Ring lowercases inbound header names, so the interceptor's `extract-token` reads
   it consistently."
  ([] (hx-headers *token*))
  ([token]
   (when token
     {:hx-headers (json/generate-string {header-name token})})))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.platform.core.csrf-test`
Expected: PASS (all existing csrf-test deftests + new `hx-headers-test`).

- [ ] **Step 6: Lint changed files**

Run: `clojure -M:clj-kondo --lint libs/platform/src/boundary/platform/core/csrf.clj libs/platform/test/boundary/platform/core/csrf_test.clj`
Expected: no errors.

- [ ] **Step 7: Verify FC/IS still passes (cheshire in core)**

Run: `bb check:fcis`
Expected: pass — cheshire is pure JSON encoding, already precedented in `core/http/problem_details.clj`.

- [ ] **Step 8: Commit**

```bash
git add libs/platform/src/boundary/platform/core/csrf.clj libs/platform/test/boundary/platform/core/csrf_test.clj
git commit -m "feat(platform): add csrf/hx-headers HTMX emission helper (BOU-56)"
```

---

### Task 2: Flip enforcement default to opt-in (both sites)

**Files:**
- Modify: `libs/platform/src/boundary/platform/shell/http/interceptors.clj:418`
- Modify: `libs/platform/src/boundary/platform/shell/system/wiring.clj:317`
- Test: `libs/platform/test/boundary/platform/shell/security_test.clj`

- [ ] **Step 1: Write the failing test**

Add to `security_test.clj` after `csrf-interceptor-protection-test` (after L134):

```clojure
(deftest ^:security ^:unit csrf-default-is-opt-in-test
  (testing "a :csrf map with a secret but NO :enabled? key does not validate (opt-in, default off)"
    (let [cfg {:secret csrf-secret :exempt-paths []}] ; note: no :enabled? key
      (is (nil? (:response (run-csrf cfg (session-request :post "/web/profile/update"))))
          "missing :enabled? must default to off — no 403"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.platform.shell.security-test`
Expected: FAIL — `csrf-default-is-opt-in-test` gets a 403 (interceptor currently defaults `:or {enabled? true}`).

- [ ] **Step 3: Flip the interceptor default**

In `interceptors.clj`, change the `http-csrf-protection` `:enter` destructure (L417-418):

```clojure
            (let [{:keys [enabled? secret exempt-paths]
                   :or   {enabled? false}} (:csrf system)
```

(was `:or {enabled? true}`)

- [ ] **Step 4: Flip the wiring default + update comment**

In `wiring.clj`, change the csrf-config block (L313-320) to:

```clojure
        ;; CSRF config consumed by http-csrf-protection interceptor.
        ;; Opt-in: disabled by default so a framework upgrade cannot 403 consumers
        ;; that don't yet emit tokens. Each app enables it (after emitting tokens in
        ;; its /web forms) via :boundary/http :security :csrf :enabled? true. Secret
        ;; falls back to JWT_SECRET. Config keys override these defaults.
        csrf-config (merge {:enabled?     false
                            :secret       (System/getenv "JWT_SECRET")
                            :exempt-paths []}
                           (get-in config [:active :boundary/http :security :csrf]))
```

(only `:enabled? true` → `false` and the comment changed; the fail-loud WARN below it stays.)

- [ ] **Step 5: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.platform.shell.security-test`
Expected: PASS — new opt-in test passes AND all existing CSRF tests still pass (they all pass explicit `:enabled? true`, so they're unaffected).

- [ ] **Step 6: Update the interceptor docstring**

In `interceptors.clj`, in the `http-csrf-protection` docstring, append a line to the Config paragraph (near L413-414) so the opt-in default is documented:

```
     {:enabled? bool, :secret <signing-key>, :exempt-paths [\"/api/v1/...\"]}
   Enforcement is opt-in: when :enabled? is absent or false the interceptor is a
   no-op (no validation, no issuance). Apps enable it after emitting tokens."
```

- [ ] **Step 7: Lint + commit**

```bash
clojure -M:clj-kondo --lint libs/platform/src/boundary/platform/shell/http/interceptors.clj libs/platform/src/boundary/platform/shell/system/wiring.clj libs/platform/test/boundary/platform/shell/security_test.clj
git add libs/platform/src/boundary/platform/shell/http/interceptors.clj libs/platform/src/boundary/platform/shell/system/wiring.clj libs/platform/test/boundary/platform/shell/security_test.clj
git commit -m "feat(platform)!: make CSRF enforcement opt-in (default off) (BOU-56)"
```

---

### Task 3: Compensate this repo's prod/acc protection

Flipping the lib default off would silently drop prod/acc from on→off (they have no `:csrf` block today). Add explicit enable. Per the spec, a `:security`-only `:boundary/http` block merges additively (port/host fall back per-key in `src/boundary/config.clj`), so no other HTTP keys are needed.

**Files:**
- Modify: `resources/conf/prod/config.edn`
- Modify: `resources/conf/acc/config.edn`

- [ ] **Step 1: Add the CSRF block to prod**

In `resources/conf/prod/config.edn`, insert before the i18n block. Anchor Edit — replace:

```clojure
  ;; Internationalisation — marker-based i18n with EDN catalogues
  :boundary/i18n
```

with:

```clojure
  ;; HTTP server — CSRF enforcement explicitly ON for prod (lib default is opt-in/off).
  ;; Secret must come from the environment; no literal fallback (fail loud if unset).
  :boundary/http
  {:security {:csrf {:enabled? true
                     :secret   #env "JWT_SECRET"}}}

  ;; Internationalisation — marker-based i18n with EDN catalogues
  :boundary/i18n
```

- [ ] **Step 2: Add the CSRF block to acc**

In `resources/conf/acc/config.edn`, insert before the error-reporting block. Anchor Edit — replace:

```clojure
  ;; Acceptance error reporting configuration (Sentry)
  :boundary/error-reporting
```

with:

```clojure
  ;; HTTP server — CSRF enforcement explicitly ON for acc (lib default is opt-in/off).
  ;; Secret must come from the environment; no literal fallback (fail loud if unset).
  :boundary/http
  {:security {:csrf {:enabled? true
                     :secret   #env "JWT_SECRET"}}}

  ;; Acceptance error reporting configuration (Sentry)
  :boundary/error-reporting
```

- [ ] **Step 3: Verify the CSRF flag resolves to true for prod/acc**

Primary proof — load each config via aero and read the flag:

Run: `clojure -M -e "(require '[aero.core :as aero]) (doseq [env [\"prod\" \"acc\"]] (let [c (aero/read-config (str \"resources/conf/\" env \"/config.edn\") {:profile (keyword env)})] (println env (get-in c [:active :boundary/http :security :csrf :enabled?]))))"`
Expected: prints `prod true` and `acc true` (was `prod nil`/`acc nil` before this task).

NOTE: do NOT use `bb doctor --env all --ci` as a pass/fail gate here — it already exits 1 on
`main` because prod/acc carry many unset bare `#env` refs (POSTGRES_HOST, SENTRY_DSN, …) that
`check-env-refs` flags. That exit code is pre-existing and unrelated to this change; the aero
one-liner above is the real verification. (Optional sanity: `bb doctor --env all` should report
no *new* errors beyond the pre-existing unset-env-ref list.)

- [ ] **Step 4: Commit**

```bash
git add resources/conf/prod/config.edn resources/conf/acc/config.edn
git commit -m "fix(config): enable CSRF explicitly in prod/acc after lib default flip (BOU-56)"
```

---

### Task 4: Documentation (docstrings + AGENTS.md)

**Files:**
- Modify: `libs/platform/src/boundary/platform/core/csrf.clj` (ns docstring)
- Modify: `libs/platform/AGENTS.md`

- [ ] **Step 1: Update the csrf.clj ns docstring**

In the `csrf.clj` ns docstring (L1-21), add a short paragraph before the closing `"` noting enforcement is opt-in and pointing forms→`hidden-field`, HTMX→`hx-headers`/meta:

```
   Enforcement is opt-in at the interceptor level (default off); see
   `boundary.platform.shell.http.interceptors/http-csrf-protection`. Emit the token
   with `hidden-field` (server forms) or `hx-headers` (HTMX elements), or via the
   <meta name=\"csrf-token\"> tag + the ui-style init.js htmx:configRequest listener.
```

- [ ] **Step 2: Correct the EXISTING CSRF section in libs/platform/AGENTS.md**

A `## CSRF Protection` section already exists (~L98-153). Do NOT add a duplicate. Make two
targeted Edits to flip its stale default-on framing to opt-in and add the `hx-headers` path.

**Edit 2a — Configuration code block + paragraph (currently ~L131, L136-138).** Replace:

```clojure
 {:csrf {:enabled?     true                                  ; false only in test/dev
         :secret       #or [#env CSRF_SECRET #env JWT_SECRET] ; defaults to JWT_SECRET
         :exempt-paths ["/api/v1/payments/webhook"]}}}        ; trailing /* = prefix match
```

with:

```clojure
 {:csrf {:enabled?     true                                  ; OPT-IN: lib default is false
         :secret       #or [#env CSRF_SECRET #env JWT_SECRET] ; defaults to JWT_SECRET
         :exempt-paths ["/api/v1/payments/webhook"]}}}        ; trailing /* = prefix match
```

Then replace the paragraph:

> The secret falls back to `JWT_SECRET` even without a config block, so prod/acc are
> protected by default. The test profile ships `:enabled? false` so the broad suite need
> not carry tokens; CSRF-specific tests enable it explicitly.

with:

> **Enforcement is opt-in: the library default is `:enabled? false`** so a framework upgrade
> can't 403 consumers that don't yet emit tokens. An app turns it on with the block above
> (after emitting tokens in its `/web` forms). The secret falls back to `JWT_SECRET`; a
> fail-loud WARN fires at startup if enabled with a blank secret. In this repo, dev/prod/acc
> set `:enabled? true` explicitly; the test profile ships `:enabled? false` so the broad suite
> need not carry tokens (CSRF-specific tests enable it explicitly).

**Edit 2b — Emitting tokens, HTMX bullet (currently ~L146-148).** Replace:

```
- **HTMX** — the shared `page-layout` renders `<meta name="csrf-token">`; the global
  `htmx:configRequest` listener in `init.js` attaches `X-CSRF-Token` to every HTMX
  request. New HTMX actions need nothing.
```

with:

```
- **HTMX** — either (a) merge `(csrf/hx-headers)` (0-arity reads `*token*`) onto an
  element's attrs, e.g. `<body>`, so all inherited `hx-*` requests carry the header; or
  (b) rely on the shared `page-layout`'s `<meta name="csrf-token">` + the global
  `htmx:configRequest` listener in `init.js`, which attaches `X-CSRF-Token` to every HTMX
  request (new HTMX actions then need nothing).
```

- [ ] **Step 3: Lint + commit**

```bash
clojure -M:clj-kondo --lint libs/platform/src/boundary/platform/core/csrf.clj
git add libs/platform/src/boundary/platform/core/csrf.clj libs/platform/AGENTS.md
git commit -m "docs(platform): document opt-in CSRF + emission helpers (BOU-56)"
```

---

### Task 5: Correct the Linear tickets

No code. Use the Linear MCP `save_issue` tool to overwrite each description.

- [ ] **Step 1: Rewrite BOU-56**

Update BOU-56 description to (markdown, real newlines):

> **Reconcile framework CSRF with reality.** BOU-43 (PR #170, on `main`) already replaced the old stub with a real custom HMAC synchronizer-token impl (`libs/platform/.../core/csrf.clj` + `http-csrf-protection` interceptor). This ticket does NOT switch to stock `wrap-anti-forgery` (that would delete working, tested code and can't express the pre-session login binding). Keep the custom impl; reconcile the genuine deltas:
>
> 1. Make enforcement **opt-in** (default off) at both default sites (wiring merge + interceptor `:or`), so a version bump can't 403 consumers that don't yet emit tokens.
> 2. Add a `csrf/hx-headers` HTMX emission helper (forms already have `hidden-field`).
> 3. Compensate this repo's prod/acc configs with explicit `:enabled? true`.
> 4. Already correct (no change): real validation exists, `/web/admin` POSTs protected, API routes excluded.
>
> Out of scope: platform lib-suite version bump (release step); BOU-57 (boundary-license repo). Note: `ring/ring-anti-forgery` was never actually a declared dep — nothing to remove.

- [ ] **Step 2: Rewrite BOU-57**

Update BOU-57 description to:

> **boundary-license: consume the framework CSRF fix + enable enforcement.** Blocked by BOU-56 (needs the lib bump with opt-in default + `hx-headers` helper).
>
> 1. Bump `boundary.platform` to the version shipping the opt-in default + `hx-headers` helper (real validation already shipped in BOU-43).
> 2. **Enable enforcement** — `:boundary/http {:security {:csrf {:enabled? true :secret <≥32 chars>}}}`. Mandatory: the lib default is now OFF, so skipping this = zero CSRF for boundary-license.
> 3. Emit the token in every server-rendered `/web` POST form via `(boundary.platform.core.csrf/hidden-field)` — 15 forms across `web/ui.clj`, `web/api_keys.clj`, `monitoring/shell/http.clj`.
> 4. HTMX: merge `(boundary.platform.core.csrf/hx-headers)` onto `<body>` once so it inherits to all child `hx-*` requests (covers the BOU-53 acknowledge control). Alternative: `<meta name="csrf-token">` + ui-style init.js listener.
> 5. Confirm API routes stay excluded (API-key / JWT auth) — no token expected there.
>
> Tests: each state-changing web POST → 403 without token / success with valid token; HTMX acknowledge POST carries the header and succeeds; API routes unaffected.

- [ ] **Step 3: (No commit — Linear changes are remote.)**

---

### Task 6: Final full verification

- [ ] **Step 1: Full platform test suite**

Run: `clojure -M:test:db/h2 :platform`
Expected: all green (includes csrf-test + security-test).

- [ ] **Step 2: FC/IS + deps checks**

Run: `bb check:fcis && bb check:deps`
Expected: pass.

- [ ] **Step 3: Lint the whole platform lib**

Run: `clojure -M:clj-kondo --lint libs/platform/src libs/platform/test`
Expected: no errors.

- [ ] **Step 4: Confirm clean tree**

Run: `git status --short`
Expected: empty (all work committed).

---

## Follow-ups (NOT in this plan)

- Platform lib-suite **version bump** (BOU-56 #6) — run the boundary-version-bump skill + release checklist after this lands. Remember to update `boundary-tools-version` in generators.clj if the tools version changes.
- BOU-57 implementation lives in the separate **boundary-license** repo.
