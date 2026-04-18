# Phase 3: Error Experience — Design Spec

**Date:** 2026-04-18
**Status:** Approved
**Author:** Thijs Creemers + Claude
**Parent:** `docs/superpowers/specs/2026-04-18-mindblowing-dx-design.md` (Phase 3)

## Context

Phase 1 (Foundation) and Phase 2 (REPL Power) established the devtools infrastructure: error code catalog (BND-1xx through BND-6xx), rich error formatter with Unicode borders, "Did you mean?" engine, AI error explanation, REPL helpers, and the guidance system.

Phase 3 turns errors from dead ends into conversations. The goal: every error explains why it happened, suggests a fix, and where possible offers automatic recovery.

## Decisions

- **Error pipeline architecture:** Layered pipeline (`classify → enrich → format → output`), all pure functions in core with shell executing side effects. Chosen over registry pattern (over-engineered for 6 fix types) and direct composition (fights the project's pipeline philosophy).
- **`fix!` safety model:** Safe fixes auto-apply, risky fixes always confirm (Option B), with guidance level controlling *visibility* of fix suggestions in error output — not overriding the safety gate. `deps.edn` modifications always require confirmation regardless of guidance level.
- **Stack trace filtering:** Reorder + filter (Option B from brainstorm). User code (`boundary.*` under `libs/`) moves to the top, framework/JVM frames collapse into a summary. Expandable via `(explain *e :verbose)`.
- **FC/IS violation detection:** Post-reset namespace scan (Option B from brainstorm). Runs after `(go)` and `(reset)`, checks loaded `boundary.*.core.*` namespaces for shell imports. No invasive load hooks.
- **Wiring strategy:** Both REPL exception handler and HTTP dev middleware (Option C from brainstorm). Shared formatter, different output targets.

## Architecture

### Error Pipeline

```
Exception
    │
    ▼
┌─────────────────────────────────────────────┐
│ classify (core/error_classifier.clj)        │
│ Exception → {:code "BND-xxx" :category ...} │
│ Pattern match: ex-data → type → message     │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│ enrich (core/error_enricher.clj)            │
│ Adds: filtered stacktrace, suggestions,     │
│ fix descriptor, dashboard/docs URLs         │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│ format (core/error_formatter.clj)           │
│ Produces rich string with BND code, trace,  │
│ suggestions, fix hint                       │
└──────────────────┬──────────────────────────┘
                   │
              ┌────┴─────┐
              ▼          ▼
         REPL output   HTTP response
         (shell)       (:dev-info field)
```

### File Structure

All new files live in `libs/devtools/`:

```
libs/devtools/src/boundary/devtools/
├── core/
│   ├── error_classifier.clj    # NEW — exception → BND code classification
│   ├── error_enricher.clj      # NEW — adds stacktrace, suggestions, fix info
│   ├── error_codes.clj         # EXISTS — error catalog (no changes)
│   ├── error_formatter.clj     # EXISTS — expand to handle new fields
│   ├── stacktrace.clj          # NEW — stack trace filtering/reordering
│   └── auto_fix.clj            # NEW — fix descriptor registry (pure)
├── shell/
│   ├── error_handler.clj       # NEW — REPL handler + HTTP middleware + FC/IS checker
│   └── auto_fix.clj            # NEW — fix executor (side effects)
```

**Modified files:**
- `dev/repl/user.clj` — wire `fix!`, install REPL error handler, hook FC/IS check into `go`/`reset`

## Component Details

### 1. Error Classifier (`core/error_classifier.clj`)

Pure function. Takes an exception, returns a classified error map.

```clojure
(classify exception)
;; => {:code      "BND-301"
;;     :category  :persistence
;;     :exception ex
;;     :data      {:table "invoices" :migration "20260418_add_invoices_table.sql"}
;;     :source    :ex-data}
```

**Classification strategy (ordered, first match wins):**

1. **ex-data with `:boundary/error-code`** — Direct BND code in the exception. Preferred path for Boundary-internal errors.
2. **ex-data pattern matching** — Match on keys like `:type`, `:schema`, `:malli/error` to infer code (e.g., Malli validation → BND-201).
3. **Exception type** — `SQLException` → BND-3xx, `ConnectException` → BND-303.
4. **Message pattern** — Regex on `.getMessage()` for known patterns (e.g., `"relation .* does not exist"` → BND-301).
5. **Unclassified** — Returns `nil` code. Formatter falls back to standard trace with hint: `"Try (explain *e) for AI analysis"`.

### 2. Stack Trace Filtering (`core/stacktrace.clj`)

Pure functions. Takes an exception, produces filtered/reordered trace data.

```clojure
(filter-stacktrace exception)
;; => {:user-frames      [{:ns "boundary.product.core.validation"
;;                         :fn "validate-product"
;;                         :file "validation.clj" :line 42} ...]
;;     :framework-frames [...]
;;     :jvm-frames       [...]
;;     :total-hidden     24}

(format-stacktrace filtered-trace)
;; => "── Your code ──────────────────────────────
;;     boundary.product.core.validation/validate-product (validation.clj:42)
;;     boundary.product.shell.persistence/save! (persistence.clj:18)
;;
;;     ── Framework (12 frames) ──────────────────
;;     boundary.platform.shell.interceptors... (expand with (explain *e :verbose))"
```

**Namespace classification:**
- **User code:** `boundary.*` namespaces under `libs/` — excluding `boundary.platform.*`, `boundary.devtools.*`, `boundary.observability.*`
- **Framework:** `boundary.platform.*`, `boundary.observability.*`, `ring.*`, `reitit.*`, `integrant.*`
- **JVM internals:** `java.*`, `clojure.lang.*`, `clojure.core`

First user-code frame always appears at the top.

### 3. Error Enricher (`core/error_enricher.clj`)

Pure function. Takes a classified error, adds context from multiple sources.

```clojure
(enrich classified-error)
;; => classified-error merged with:
;;    {:stacktrace    <filtered via stacktrace.clj>
;;     :suggestions   ["Did you mean: :admin?" ...]
;;     :fix           {:fix-id :apply-migration :safe? true ...} or nil
;;     :dashboard-url "http://localhost:9999/dashboard/errors"
;;     :docs-url      "https://boundary.dev/errors/BND-301"}
```

Calls into:
- `stacktrace/filter-stacktrace` for trace filtering
- `messages.clj` (libs/core) for "Did you mean?" suggestions
- `auto_fix/match-fix` for fix availability

### 4. Error Formatter (`core/error_formatter.clj` — expanded)

Extend the existing formatter to handle new enriched error fields:
- Stack trace section (filtered, with expand hint)
- Auto-fix suggestion line: `"Auto-fix: (fix! *e)  — Apply pending migration"`
- Unclassified error fallback format

No changes to the existing `format-error`, `format-config-error`, `format-fcis-violation` signatures — new function `format-enriched-error` that wraps them with the additional sections.

### 5. Auto-Fix Registry (`core/auto_fix.clj` — pure)

Maps error codes to fix descriptors. No side effects.

```clojure
(match-fix classified-error)
;; => {:fix-id    :apply-migration
;;     :label     "Apply pending migration: 20260418_add_invoices_table.sql"
;;     :safe?     true
;;     :action    :migrate-up
;;     :params    {:file "20260418_add_invoices_table.sql"}}
;;  or nil if no fix available
```

**Fix catalog:**

| Error Code | Fix ID | Action | Safe? |
|---|---|---|---|
| BND-301 (missing migration) | `:apply-migration` | Runs `migrate up` | yes |
| BND-101 (missing env var) | `:set-env-var` | Sets env var for current JVM session | yes |
| BND-103 (missing JWT secret) | `:set-jwt-secret` | Generates and sets dev JWT_SECRET | yes |
| Missing module wiring | `:integrate-module` | Runs `scaffold integrate` | yes |
| Invalid config value | `:suggest-config` | Prints correct value, applies if unambiguous | yes |
| Missing dependency | `:add-dependency` | Suggests `deps.edn` edit | **no — always confirms** |
| BND-601/602 (FC/IS) | `:refactor-fcis` | Shows steps + offers `(ai/refactor-fcis ...)` | **no — always confirms** |

### 6. Auto-Fix Executor (`shell/auto_fix.clj` — side effects)

Executes fix descriptors. Handles the safety/confirmation logic.

```clojure
(execute-fix! fix-descriptor opts)
;; opts: {:guidance-level :full
;;        :confirm-fn     (fn [prompt] <interactive y/N>)}
```

**Behavior by guidance level:**
- `:full` — safe fixes apply with a brief `"Applying: ..."` message, risky fixes prompt for confirmation
- `:minimal` — safe fixes apply silently, risky fixes prompt for confirmation
- `:off` — `(fix! *e)` still works when called explicitly, no suggestions in error output

The safety gate (`safe? false` → always confirm) is **never overridden** by guidance level.

### 7. Error Handler Wiring (`shell/error_handler.clj`)

**REPL exception handler:**

```clojure
(defn install-repl-error-handler!
  "Install rich error formatting for the REPL. Called from user.clj on load."
  [opts]
  ;; Configures nREPL middleware to intercept exception rendering
  ;; Runs full pipeline: classify → enrich → format → println
  ;; Falls back to standard trace for unclassified errors + hint
  )
```

**HTTP dev middleware:**

```clojure
(defn wrap-dev-error-enrichment
  "Ring middleware that enriches error responses in dev mode.
   Wraps around existing error handling — doesn't replace it."
  [handler]
  ;; Catches exceptions after existing middleware
  ;; Adds :dev-info field to RFC 7807 Problem Details response
  ;; Only active when environment = :dev
  )
```

Wraps *around* the existing `wrap-enhanced-exception-handling` from observability. Adds rich dev output as `:dev-info` in the response, preserving the existing error flow.

**FC/IS post-reset checker:**

```clojure
(defn check-fcis-violations!
  "Scan loaded namespaces for FC/IS violations after system start."
  []
  ;; Scans all loaded boundary.*.core.* namespaces
  ;; Checks their :requires for boundary.*.shell.* imports
  ;; Prints warnings using error_formatter/format-fcis-violation
  )
```

### 8. Changes to `user.clj`

```clojure
;; New require
[boundary.devtools.shell.error-handler :as error-handler]
[boundary.devtools.shell.auto-fix :as auto-fix-shell]
[boundary.devtools.core.error-classifier :as classifier]
[boundary.devtools.core.error-enricher :as enricher]
[boundary.devtools.core.auto-fix :as auto-fix]

;; Install on load
(error-handler/install-repl-error-handler! {})

;; New top-level function
(defn fix!
  "Auto-fix the last error if a fix is available.
   (fix!)     ; fix last error
   (fix! *e)  ; fix specific exception"
  ([] (fix! *e))
  ([exception]
   (let [classified (classifier/classify exception)
         fix-desc   (auto-fix/match-fix classified)]
     (if fix-desc
       (auto-fix-shell/execute-fix! fix-desc
         {:guidance-level (guidance)
          :confirm-fn     #(do (print (str % " [y/N] "))
                               (flush)
                               (= "y" (read-line)))})
       (println "No auto-fix available for this error. Try (explain *e) for AI analysis.")))))

;; Modified go/reset — add FC/IS check
(defn go []
  (let [result (ig-repl/go)]
    (print-startup-dashboard)
    (error-handler/check-fcis-violations!)
    (maybe-show-tip :start)
    result))

(defn reset []
  (let [result (ig-repl/reset)]
    (error-handler/check-fcis-violations!)
    result))
```

## Testing Strategy

**Unit tests (pure core functions):**
- `error_classifier_test.clj` — Feed known exception types, verify correct BND codes. Test all 5 classification strategies. Test unclassified fallback.
- `stacktrace_test.clj` — Verify reordering (user code first), correct namespace classification, frame collapsing, format output.
- `error_enricher_test.clj` — Verify enrichment adds all expected fields, handles missing classifier output gracefully.
- `auto_fix_test.clj` — Verify fix matching for each error code, verify `nil` for unknown errors.

**Integration tests (shell, against running system):**
- `error_handler_test.clj` — Trigger real errors (missing table, validation failure), verify rich output appears. Test HTTP middleware adds `:dev-info`.
- `auto_fix_shell_test.clj` — Test safe fix execution (e.g., set env var), verify confirmation is requested for risky fixes.
- `fcis_check_test.clj` — Load a namespace with a known violation, verify warning output.

**Test metadata:** All tests tagged `^:unit` or `^:integration` per convention. Added to `:devtools` suite in `tests.edn`.

## Verification Criteria

From the parent spec:

- [ ] Intentionally trigger each BND-xxx error type → verify rich output with code, explanation, and fix suggestion
- [ ] `(fix! *e)` resolves: missing migration, missing env var, missing module wiring
- [ ] `(fix! *e)` for "missing dependency" always asks for confirmation before modifying `deps.edn`
- [ ] Stack traces in dev mode show user code first, framework frames dimmed/collapsed
- [ ] Unclassified errors fall back gracefully with AI analysis hint
- [ ] FC/IS violations detected and warned after `(go)` and `(reset)`
- [ ] HTTP error responses in dev mode include `:dev-info` with rich formatting
- [ ] Guidance level controls suggestion visibility but never overrides safety gates
- [ ] All pure core functions have unit tests
- [ ] `clojure -M:test:db/h2 :devtools` passes
