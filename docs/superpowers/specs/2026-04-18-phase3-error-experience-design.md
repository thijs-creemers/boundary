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

## Key Constraints & Edge Cases

### nREPL Exception Handling

`*e` is not reliably available in nREPL sessions. The REPL error handler stores the last exception in a `last-exception*` atom in the devtools shell layer. `(fix!)` zero-arity reads from this atom, not from `*e`.

The REPL error handler does **not** attempt to inject nREPL middleware at runtime (which is impossible — middleware is registered at server startup). Instead, it uses a simpler approach: all public REPL functions in `user.clj` that can throw (`go`, `reset`, `simulate`, `query`, etc.) are wrapped with a try/catch that runs the error pipeline and stores the exception. This is explicit, debuggable, and requires no nREPL internals.

### Chained Exceptions

The classifier walks the cause chain via `(.getCause ex)`. It classifies the **root cause** (innermost exception), not the wrapper. This means a `PSQLException` wrapped in an `ex-info` is classified by the `PSQLException`, not the wrapper's ex-data. If the outermost exception carries `:boundary/error-code` in ex-data (strategy 1), that takes precedence over the cause chain.

### Exceptions During System Startup

If `ig-repl/go` or `ig-repl/reset` throws, the error pipeline handles it via the try/catch wrapper in `user.clj`. Integrant lifecycle failures (missing key, component init failure) are currently unclassified — they fall through to the "unclassified" path with the AI analysis hint. Adding BND-7xx codes for lifecycle errors is deferred to a future phase.

### Enricher Self-Protection

The enricher wraps each sub-call (stacktrace filtering, suggestion lookup, fix matching) in individual try/catch blocks. If any sub-call fails, that field is omitted from the enriched error — the pipeline never fails because of enrichment. The formatter handles missing fields gracefully (already does this).

### Inter-Library Dependencies

`libs/devtools` depends on `libs/core` (for `messages.clj` "Did you mean?" engine). This dependency already exists in `libs/devtools/deps.edn` from Phase 1/2 work. The dependency direction is valid: devtools → core (devtools is a leaf, core is foundational).

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
│   ├── repl_error_handler.clj  # NEW — REPL exception capture + formatting
│   ├── http_error_middleware.clj # NEW — dev-mode HTTP error enrichment
│   ├── fcis_checker.clj        # NEW — post-reset namespace scan
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

**Namespace classification (by prefix, no filesystem inspection at runtime):**
- **User code:** `boundary.*` namespaces — excluding the framework prefixes below
- **Framework:** `boundary.platform.*`, `boundary.observability.*`, `boundary.devtools.*`, `boundary.core.*`, `ring.*`, `reitit.*`, `integrant.*`, `malli.*`
- **JVM internals:** `java.*`, `javax.*`, `clojure.lang.*`, `clojure.core.*`

`boundary.core.*` is classified as framework because from the developer's perspective, validation/case-conversion internals are library code, not their business logic. First user-code frame always appears at the top.

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
| BND-601 (core imports shell) | `:refactor-fcis` | Shows refactoring steps + offers `(ai/refactor-fcis ...)` | **no — always confirms** |

BND-602 (core uses I/O) is detected statically by `bb check:fcis` and clj-kondo, not at runtime. It has no `fix!` entry because runtime detection of direct I/O usage in loaded namespaces is not reliable. The FC/IS post-reset checker only detects BND-601 (namespace-level `:require` violations).

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

### 7. REPL Error Handler (`shell/repl_error_handler.clj`)

Stores the last exception in a `last-exception*` atom. Provides a `handle-repl-error!` function that runs the full pipeline and prints the result.

```clojure
(def last-exception* (atom nil))

(defn handle-repl-error!
  "Run the error pipeline on an exception: classify → enrich → format → print.
   Stores exception in last-exception* for (fix!) to access."
  [exception]
  ;; Stores in last-exception*
  ;; Runs pipeline, prints rich output
  ;; Falls back to standard trace + hint for unclassified errors
  )
```

Not injected as nREPL middleware. Instead, `user.clj` wraps public REPL functions (`go`, `reset`, `simulate`, `query`, etc.) with try/catch that calls `handle-repl-error!`. This is explicit, requires no nREPL internals, and works with any REPL transport (nREPL, socket REPL, Conjure).

### 8. HTTP Error Middleware (`shell/http_error_middleware.clj`)

```clojure
(defn wrap-dev-error-enrichment
  "Ring middleware that enriches error responses in dev mode."
  [handler]
  ;; Positioned INSIDE wrap-enhanced-exception-handling (inner middleware)
  ;; Catches exceptions BEFORE they reach the observability layer
  ;; Runs the error pipeline, attaches result to exception's ex-data
  ;; as :boundary/dev-info so the outer middleware can include it
  ;; in the RFC 7807 response
  ;; Only active when environment = :dev
  )
```

**Middleware ordering:** `wrap-dev-error-enrichment` sits *inside* `wrap-enhanced-exception-handling`. It catches exceptions, runs the pipeline, re-throws with `:boundary/dev-info` attached to the ex-data. The outer `wrap-enhanced-exception-handling` then includes `:dev-info` in the RFC 7807 Problem Details response when present.

**`:dev-info` shape:**

```clojure
{:formatted  "━━━ BND-201: Schema Validation ... ━━━"  ;; rich string for terminal/log
 :code       "BND-201"
 :category   :validation
 :fix-available? true
 :fix-label  "Check schema at boundary.user.schema/create-schema"}
```

### 9. FC/IS Checker (`shell/fcis_checker.clj`)

```clojure
(defn check-fcis-violations!
  "Scan loaded namespaces for FC/IS violations after system start.
   Only detects BND-601 (core imports shell via :require).
   BND-602 (core uses I/O) is detected statically by bb check:fcis."
  []
  ;; Scans all loaded boundary.*.core.* namespaces
  ;; Checks their ns :require declarations for boundary.*.shell.* imports
  ;; Prints warnings using error_formatter/format-fcis-violation
  )
```

### 10. Changes to `user.clj`

```clojure
;; New requires
[boundary.devtools.shell.repl-error-handler :as repl-errors]
[boundary.devtools.shell.fcis-checker :as fcis]
[boundary.devtools.shell.auto-fix :as auto-fix-shell]
[boundary.devtools.core.error-classifier :as classifier]
[boundary.devtools.core.auto-fix :as auto-fix]

;; Helper: wrap REPL functions with error pipeline
(defmacro with-error-handling [& body]
  `(try ~@body
     (catch Exception e#
       (repl-errors/handle-repl-error! e#)
       nil)))

;; New top-level function
(defn fix!
  "Auto-fix the last error if a fix is available.
   (fix!)     ; fix last error
   (fix! ex)  ; fix specific exception"
  ([] (fix! @repl-errors/last-exception*))
  ([exception]
   (if (nil? exception)
     (println "No recent error. Trigger an error first, then call (fix!)")
     (let [classified (classifier/classify exception)
           fix-desc   (auto-fix/match-fix classified)]
       (if fix-desc
         (auto-fix-shell/execute-fix! fix-desc
           {:guidance-level (guidance)
            :confirm-fn     #(do (print (str % " [y/N] "))
                                 (flush)
                                 (= "y" (read-line)))})
         (println "No auto-fix available for this error. Try (explain *e) for AI analysis."))))))

;; Modified go — wraps with error handling, FC/IS check in finally
(defn go []
  (try
    (let [result (ig-repl/go)]
      (print-startup-dashboard)
      (fcis/check-fcis-violations!)
      (maybe-show-tip :start)
      result)
    (catch Exception e
      (repl-errors/handle-repl-error! e)
      (fcis/check-fcis-violations!)  ;; still runs even if go fails
      nil)))

;; Modified reset — FC/IS check runs even on failure
(defn reset []
  (try
    (let [result (ig-repl/reset)]
      (fcis/check-fcis-violations!)
      result)
    (catch Exception e
      (repl-errors/handle-repl-error! e)
      (fcis/check-fcis-violations!)
      nil)))

;; Other public REPL functions wrapped:
(defn simulate [method path & [opts]]
  (with-error-handling
    (when-let [handler (get (system) :boundary/http-handler)]
      (devtools-repl/simulate-request handler method path (or opts {})))))

(defn query [table & [opts]]
  (with-error-handling
    (when-let [ctx (db-context)]
      (devtools-repl/run-query ctx table (or opts {})))))
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
- [ ] REPL error handler fires automatically: evaluate a form that throws BND-201 in REPL → rich output appears without calling `explain` or `fix!`
- [ ] `(fix!)` zero-arity retrieves exception from `last-exception*` atom, not `*e`
- [ ] Chained exceptions: wrapper around PSQLException → classified by root cause
- [ ] Enricher failure in one sub-call does not crash the pipeline — field is omitted gracefully
- [ ] All pure core functions have unit tests
- [ ] `clojure -M:test:db/h2 :devtools` passes
