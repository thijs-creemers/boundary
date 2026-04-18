# Phase 3: Error Experience — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every Boundary error a conversation — rich formatted output with BND codes, filtered stack traces, auto-recovery via `(fix!)`, and automatic error enrichment in both REPL and HTTP flows.

**Architecture:** Layered error pipeline (`classify → enrich → format → output`) with pure functions in `libs/devtools/core/` and side-effecting wiring in `libs/devtools/shell/`. REPL functions wrapped with try/catch (no nREPL middleware injection). HTTP middleware positioned inside `wrap-enhanced-exception-handling`.

**Tech Stack:** Clojure 1.12.4, Malli (validation schemas), clojure.test + Kaocha (testing), Integrant (system lifecycle)

**Spec:** `docs/superpowers/specs/2026-04-18-phase3-error-experience-design.md`

---

## File Map

### New Files (libs/devtools)

| File | Responsibility |
|------|---------------|
| `libs/devtools/src/boundary/devtools/core/stacktrace.clj` | Parse, classify, reorder, and format JVM stack traces |
| `libs/devtools/src/boundary/devtools/core/error_classifier.clj` | Pattern-match exceptions to BND-xxx codes |
| `libs/devtools/src/boundary/devtools/core/auto_fix.clj` | Pure fix descriptor registry — maps error codes to fix descriptors |
| `libs/devtools/src/boundary/devtools/core/error_enricher.clj` | Assemble enriched error map from classifier + stacktrace + suggestions + fix |
| `libs/devtools/src/boundary/devtools/shell/auto_fix.clj` | Execute fix descriptors (side effects: run migrations, set env vars) |
| `libs/devtools/src/boundary/devtools/shell/repl_error_handler.clj` | `last-exception*` atom + `handle-repl-error!` pipeline runner |
| `libs/devtools/src/boundary/devtools/shell/http_error_middleware.clj` | `wrap-dev-error-enrichment` Ring middleware |
| `libs/devtools/src/boundary/devtools/shell/fcis_checker.clj` | Post-reset namespace scan for FC/IS violations |

### New Test Files

| File | Tests |
|------|-------|
| `libs/devtools/test/boundary/devtools/core/stacktrace_test.clj` | Namespace classification, reordering, formatting |
| `libs/devtools/test/boundary/devtools/core/error_classifier_test.clj` | All 5 classification strategies + chained exceptions |
| `libs/devtools/test/boundary/devtools/core/auto_fix_test.clj` | Fix matching for each error code |
| `libs/devtools/test/boundary/devtools/core/error_enricher_test.clj` | Enrichment assembly, self-protection on sub-call failure |
| `libs/devtools/test/boundary/devtools/shell/auto_fix_test.clj` | Safe/risky fix execution, confirmation logic |
| `libs/devtools/test/boundary/devtools/shell/repl_error_handler_test.clj` | Pipeline execution, last-exception* storage |
| `libs/devtools/test/boundary/devtools/shell/http_error_middleware_test.clj` | Middleware behavior, :dev-info shape |
| `libs/devtools/test/boundary/devtools/shell/fcis_checker_test.clj` | Namespace scan, violation detection |

### Modified Files

| File | Change |
|------|--------|
| `libs/devtools/src/boundary/devtools/core/error_formatter.clj` | Add `format-enriched-error` and `format-unclassified-error`, add `stacktrace` require |
| `dev/repl/user.clj` | Add `fix!`, `with-error-handling` macro, wrap `go`/`reset`/`simulate`/`query`, hook FC/IS checker |
| `libs/devtools/deps.edn` | Verify `boundary/core` dependency exists (needed for `messages.clj` suggestions); add if missing |
| `libs/devtools/src/boundary/devtools/core/guidance.clj` | Add `(fix!)` to commands palette |

---

## Task 1: Stack Trace Filtering

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/stacktrace.clj`
- Test: `libs/devtools/test/boundary/devtools/core/stacktrace_test.clj`

- [ ] **Step 1: Write tests for namespace classification**

```clojure
;; libs/devtools/test/boundary/devtools/core/stacktrace_test.clj
(ns boundary.devtools.core.stacktrace-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.core.stacktrace :as st]))

(deftest ^:unit classify-frame-test
  (testing "user code — boundary namespace not in framework list"
    (is (= :user (st/classify-frame "boundary.product.core.validation")))
    (is (= :user (st/classify-frame "boundary.invoice.shell.persistence"))))

  (testing "framework — boundary internal libraries"
    (is (= :framework (st/classify-frame "boundary.platform.shell.interceptors")))
    (is (= :framework (st/classify-frame "boundary.observability.errors.core")))
    (is (= :framework (st/classify-frame "boundary.devtools.core.guidance")))
    (is (= :framework (st/classify-frame "boundary.core.validation.messages"))))

  (testing "framework — third-party libraries"
    (is (= :framework (st/classify-frame "ring.middleware.params")))
    (is (= :framework (st/classify-frame "reitit.ring")))
    (is (= :framework (st/classify-frame "integrant.core")))
    (is (= :framework (st/classify-frame "malli.core"))))

  (testing "jvm — Java and Clojure internals"
    (is (= :jvm (st/classify-frame "java.lang.Thread")))
    (is (= :jvm (st/classify-frame "javax.servlet.http.HttpServlet")))
    (is (= :jvm (st/classify-frame "clojure.lang.AFn")))
    (is (= :jvm (st/classify-frame "clojure.core$map")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.stacktrace-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement `classify-frame`**

```clojure
;; libs/devtools/src/boundary/devtools/core/stacktrace.clj
(ns boundary.devtools.core.stacktrace
  "Stack trace filtering and reordering for development error output.
   Pure functions — no I/O, no side effects."
  (:require [clojure.string :as str]))

(def ^:private framework-prefixes
  "Namespace prefixes classified as framework code."
  #{"boundary.platform." "boundary.observability." "boundary.devtools."
    "boundary.core." "ring." "reitit." "integrant." "malli."})

(def ^:private jvm-prefixes
  "Namespace prefixes classified as JVM internals."
  #{"java." "javax." "clojure.lang." "clojure.core"})

(defn classify-frame
  "Classify a namespace string as :user, :framework, or :jvm."
  [ns-str]
  (cond
    (some #(str/starts-with? ns-str %) jvm-prefixes)       :jvm
    (some #(str/starts-with? ns-str %) framework-prefixes)  :framework
    (str/starts-with? ns-str "boundary.")                   :user
    :else                                                   :framework))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.stacktrace-test`
Expected: PASS

- [ ] **Step 5: Write tests for `filter-stacktrace` and `format-stacktrace`**

```clojure
;; Append to stacktrace_test.clj

(defn- make-exception-with-trace
  "Create an exception with a synthetic stack trace for testing."
  [frames]
  (let [ex (Exception. "test error")
        elements (into-array StackTraceElement
                   (map (fn [{:keys [ns fn file line]}]
                          (StackTraceElement. ns fn file line))
                        frames))]
    (.setStackTrace ex elements)
    ex))

(deftest ^:unit filter-stacktrace-test
  (let [ex (make-exception-with-trace
             [{:ns "clojure.core$map" :fn "invoke" :file "core.clj" :line 100}
              {:ns "boundary.platform.shell.interceptors" :fn "execute" :file "interceptors.clj" :line 42}
              {:ns "boundary.product.core.validation" :fn "validate" :file "validation.clj" :line 15}
              {:ns "boundary.product.shell.persistence" :fn "save!" :file "persistence.clj" :line 30}
              {:ns "java.lang.Thread" :fn "run" :file "Thread.java" :line 829}])
        result (st/filter-stacktrace ex)]

    (testing "user frames extracted and ordered"
      (is (= 2 (count (:user-frames result))))
      (is (= "boundary.product.core.validation" (:ns (first (:user-frames result))))))

    (testing "framework and jvm frames counted"
      (is (= 1 (count (:framework-frames result))))
      (is (= 2 (count (:jvm-frames result)))))

    (testing "total-hidden is framework + jvm count"
      (is (= 3 (:total-hidden result))))))

(deftest ^:unit format-stacktrace-test
  (let [filtered {:user-frames [{:ns "boundary.product.core.validation"
                                  :fn "validate"
                                  :file "validation.clj"
                                  :line 15}]
                  :framework-frames [{:ns "ring.middleware.params" :fn "wrap" :file "params.clj" :line 10}]
                  :jvm-frames [{:ns "java.lang.Thread" :fn "run" :file "Thread.java" :line 829}]
                  :total-hidden 2}
        output (st/format-stacktrace filtered)]

    (testing "output contains user code section"
      (is (str/includes? output "Your code"))
      (is (str/includes? output "boundary.product.core.validation/validate")))

    (testing "output contains hidden frame count"
      (is (str/includes? output "2 frames")))))
```

- [ ] **Step 6: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.stacktrace-test`
Expected: FAIL — functions not defined

- [ ] **Step 7: Implement `filter-stacktrace` and `format-stacktrace`**

```clojure
;; Append to stacktrace.clj

(defn- stack-element->map
  "Convert a StackTraceElement to a map."
  [^StackTraceElement element]
  {:ns   (.getClassName element)
   :fn   (.getMethodName element)
   :file (.getFileName element)
   :line (.getLineNumber element)})

(defn filter-stacktrace
  "Filter and reorder an exception's stack trace.
   Returns {:user-frames [...] :framework-frames [...] :jvm-frames [...] :total-hidden N}"
  [^Throwable exception]
  (let [frames    (map stack-element->map (.getStackTrace exception))
        grouped   (group-by #(classify-frame (:ns %)) frames)
        user      (vec (get grouped :user []))
        framework (vec (get grouped :framework []))
        jvm       (vec (get grouped :jvm []))]
    {:user-frames      user
     :framework-frames framework
     :jvm-frames       jvm
     :total-hidden     (+ (count framework) (count jvm))}))

(defn- format-frame
  "Format a single stack frame as a string."
  [{:keys [ns fn file line]}]
  (str ns "/" fn " (" file ":" line ")"))

(defn format-stacktrace
  "Format a filtered stack trace for display."
  [{:keys [user-frames total-hidden]}]
  (let [user-section (if (seq user-frames)
                       (str "\u2500\u2500 Your code \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                            (str/join "\n" (map #(str "  " (format-frame %)) user-frames)))
                       "No user code frames found")
        hidden-section (when (pos? total-hidden)
                         (str "\n\n\u2500\u2500 Framework (" total-hidden " frames) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"
                              "  (expand with (explain *e :verbose))"))]
    (str user-section hidden-section)))
```

- [ ] **Step 8: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.stacktrace-test`
Expected: PASS

- [ ] **Step 9: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/stacktrace.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/stacktrace_test.clj
git add libs/devtools/src/boundary/devtools/core/stacktrace.clj libs/devtools/test/boundary/devtools/core/stacktrace_test.clj
git commit -m "feat: add stack trace filtering and reordering"
```

---

## Task 2: Error Classifier

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/error_classifier.clj`
- Test: `libs/devtools/test/boundary/devtools/core/error_classifier_test.clj`
- Read: `libs/devtools/src/boundary/devtools/core/error_codes.clj` (for available BND codes)

- [ ] **Step 1: Write tests for all 5 classification strategies**

```clojure
;; libs/devtools/test/boundary/devtools/core/error_classifier_test.clj
(ns boundary.devtools.core.error-classifier-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.core.error-classifier :as classifier]))

(deftest ^:unit classify-strategy-1-explicit-code-test
  (testing "ex-data with :boundary/error-code uses that code directly"
    (let [ex (ex-info "validation failed" {:boundary/error-code "BND-201"
                                           :schema :user/create})
          result (classifier/classify ex)]
      (is (= "BND-201" (:code result)))
      (is (= :validation (:category result)))
      (is (= :ex-data (:source result))))))

(deftest ^:unit classify-strategy-2-ex-data-pattern-test
  (testing "Malli validation error → BND-201"
    (let [ex (ex-info "validation" {:type :malli.core/invalid})
          result (classifier/classify ex)]
      (is (= "BND-201" (:code result)))
      (is (= :ex-data-pattern (:source result)))))

  (testing "ex-data with :type :db/error → BND-303"
    (let [ex (ex-info "db error" {:type :db/error})
          result (classifier/classify ex)]
      (is (= "BND-303" (:code result))))))

(deftest ^:unit classify-strategy-3-exception-type-test
  (testing "SQLException → BND-303"
    (let [ex (java.sql.SQLException. "connection refused")]
      (is (= "BND-303" (:code (classifier/classify ex))))))

  (testing "ConnectException → BND-303"
    (let [ex (java.net.ConnectException. "Connection refused")]
      (is (= "BND-303" (:code (classifier/classify ex)))))))

(deftest ^:unit classify-strategy-4-message-pattern-test
  (testing "relation does not exist → BND-301"
    (let [ex (java.sql.SQLException. "ERROR: relation \"invoices\" does not exist")]
      (is (= "BND-301" (:code (classifier/classify ex))))))

  (testing "table not found → BND-301"
    (let [ex (java.sql.SQLException. "Table \"INVOICES\" not found")]
      (is (= "BND-301" (:code (classifier/classify ex)))))))

(deftest ^:unit classify-strategy-5-unclassified-test
  (testing "generic exception returns nil code"
    (let [ex (Exception. "something went wrong")]
      (is (nil? (:code (classifier/classify ex)))))))

(deftest ^:unit classify-chained-exception-test
  (testing "root cause is classified when wrapper has no :boundary/error-code"
    (let [root (java.sql.SQLException. "ERROR: relation \"users\" does not exist")
          wrapper (ex-info "operation failed" {:operation :save} root)
          result (classifier/classify wrapper)]
      (is (= "BND-301" (:code result)))))

  (testing "wrapper :boundary/error-code takes precedence over root cause"
    (let [root (java.sql.SQLException. "connection refused")
          wrapper (ex-info "known error" {:boundary/error-code "BND-201"} root)
          result (classifier/classify wrapper)]
      (is (= "BND-201" (:code result))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.error-classifier-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement error classifier**

```clojure
;; libs/devtools/src/boundary/devtools/core/error_classifier.clj
(ns boundary.devtools.core.error-classifier
  "Classify exceptions into BND-xxx error codes.
   Pure functions — no I/O, no side effects.
   
   Classification strategy (ordered, first match wins):
   1. ex-data with :boundary/error-code — direct BND code
   2. ex-data pattern matching — infer from :type, :schema, :malli/error
   3. Exception type — SQLException, ConnectException, etc.
   4. Message pattern — regex on .getMessage()
   5. Unclassified — nil code"
  (:require [boundary.devtools.core.error-codes :as codes]
            [clojure.string :as str]))

;; =============================================================================
;; Cause chain walking
;; =============================================================================

(defn- root-cause
  "Walk the cause chain to find the root cause."
  [^Throwable ex]
  (if-let [cause (.getCause ex)]
    (recur cause)
    ex))

;; =============================================================================
;; Strategy 1: Explicit error code in ex-data
;; =============================================================================

(defn- classify-explicit-code
  "Check if exception has :boundary/error-code in ex-data."
  [ex]
  (when-let [code (get (ex-data ex) :boundary/error-code)]
    (when-let [error-def (codes/lookup code)]
      {:code     code
       :category (:category error-def)
       :data     (dissoc (ex-data ex) :boundary/error-code)
       :source   :ex-data})))

;; =============================================================================
;; Strategy 2: ex-data pattern matching
;; =============================================================================

(defn- classify-ex-data-pattern
  "Infer error code from ex-data keys and values."
  [ex]
  (let [data (ex-data ex)]
    (when data
      (cond
        ;; Malli validation error
        (or (contains? data :malli/error)
            (= :malli.core/invalid (:type data)))
        {:code "BND-201" :category :validation :data data :source :ex-data-pattern}

        ;; Database error type
        (= :db/error (:type data))
        {:code "BND-303" :category :persistence :data data :source :ex-data-pattern}

        ;; Auth errors
        (= :auth/required (:type data))
        {:code "BND-401" :category :auth :data data :source :ex-data-pattern}

        (= :auth/forbidden (:type data))
        {:code "BND-402" :category :auth :data data :source :ex-data-pattern}

        :else nil))))

;; =============================================================================
;; Strategy 3: Exception type
;; =============================================================================

(defn- classify-exception-type
  "Classify by Java exception class."
  [ex]
  (cond
    (instance? java.sql.SQLException ex)
    {:code "BND-303" :category :persistence :data {} :source :exception-type}

    (instance? java.net.ConnectException ex)
    {:code "BND-303" :category :persistence :data {} :source :exception-type}

    :else nil))

;; =============================================================================
;; Strategy 4: Message pattern matching
;; =============================================================================

(def ^:private message-patterns
  "Ordered list of [regex code category] for message-based classification."
  [[#"(?i)relation .* does not exist"       "BND-301" :persistence]
   [#"(?i)table .* not found"               "BND-301" :persistence]
   [#"(?i)column .* does not exist"         "BND-301" :persistence]
   [#"(?i)no such table"                    "BND-301" :persistence]
   [#"(?i)pool.*exhaust"                    "BND-302" :persistence]
   [#"(?i)connection.*refused"              "BND-303" :persistence]
   [#"(?i)authentication.*required"         "BND-401" :auth]
   [#"(?i)permission.*denied"              "BND-402" :auth]])

(defn- classify-message-pattern
  "Classify by regex matching on exception message."
  [ex]
  (when-let [msg (.getMessage ^Throwable ex)]
    (some (fn [[pattern code category]]
            (when (re-find pattern msg)
              {:code code :category category :data {} :source :message-pattern}))
          message-patterns)))

;; =============================================================================
;; Main classifier
;; =============================================================================

(defn classify
  "Classify an exception into a BND-xxx error code.
   
   Walks the cause chain: if the outermost exception has :boundary/error-code
   in ex-data (strategy 1), that takes precedence. Otherwise, classifies the
   root cause.
   
   Returns a map with :code, :category, :exception, :data, :source
   or a map with :code nil for unclassified errors."
  [^Throwable exception]
  (when exception
    (let [;; Strategy 1: check outermost exception first for explicit code
          explicit (classify-explicit-code exception)
          ;; For strategies 2-4: use root cause
          root     (root-cause exception)
          result   (or explicit
                       (classify-ex-data-pattern root)
                       (classify-exception-type root)
                       (classify-message-pattern root)
                       {:code nil :category nil :data {} :source :unclassified})]
      (assoc result :exception exception))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.error-classifier-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/error_classifier.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/error_classifier_test.clj
git add libs/devtools/src/boundary/devtools/core/error_classifier.clj libs/devtools/test/boundary/devtools/core/error_classifier_test.clj
git commit -m "feat: add error classifier with 5 classification strategies"
```

---

## Task 3: Auto-Fix Registry (Pure)

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/auto_fix.clj`
- Test: `libs/devtools/test/boundary/devtools/core/auto_fix_test.clj`

- [ ] **Step 1: Write tests for fix matching**

```clojure
;; libs/devtools/test/boundary/devtools/core/auto_fix_test.clj
(ns boundary.devtools.core.auto-fix-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.core.auto-fix :as auto-fix]))

(deftest ^:unit match-fix-known-codes-test
  (testing "BND-301 missing migration → :apply-migration (safe)"
    (let [fix (auto-fix/match-fix {:code "BND-301" :data {}})]
      (is (= :apply-migration (:fix-id fix)))
      (is (= :migrate-up (:action fix)))
      (is (true? (:safe? fix)))))

  (testing "BND-101 missing env var → :set-env-var (safe)"
    (let [fix (auto-fix/match-fix {:code "BND-101" :data {:var-name "DATABASE_URL"}})]
      (is (= :set-env-var (:fix-id fix)))
      (is (true? (:safe? fix)))))

  (testing "BND-103 missing JWT secret → :set-jwt-secret (safe)"
    (let [fix (auto-fix/match-fix {:code "BND-103" :data {}})]
      (is (= :set-jwt-secret (:fix-id fix)))
      (is (true? (:safe? fix)))))

  (testing "BND-601 FC/IS violation → :refactor-fcis (not safe)"
    (let [fix (auto-fix/match-fix {:code "BND-601" :data {}})]
      (is (= :refactor-fcis (:fix-id fix)))
      (is (false? (:safe? fix))))))

(deftest ^:unit match-fix-unknown-code-test
  (testing "unknown error code returns nil"
    (is (nil? (auto-fix/match-fix {:code "BND-999" :data {}}))))

  (testing "nil code returns nil"
    (is (nil? (auto-fix/match-fix {:code nil :data {}})))))

(deftest ^:unit fix-descriptor-shape-test
  (testing "all fix descriptors have required keys"
    (doseq [code ["BND-301" "BND-101" "BND-103" "BND-601"]]
      (let [fix (auto-fix/match-fix {:code code :data {}})]
        (is (contains? fix :fix-id) (str "missing :fix-id for " code))
        (is (contains? fix :action) (str "missing :action for " code))
        (is (contains? fix :safe?) (str "missing :safe? for " code))
        (is (contains? fix :label) (str "missing :label for " code))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.auto-fix-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement auto-fix registry**

```clojure
;; libs/devtools/src/boundary/devtools/core/auto_fix.clj
(ns boundary.devtools.core.auto-fix
  "Fix descriptor registry — maps error codes to auto-fix descriptors.
   Pure functions — no I/O, no side effects.
   
   Fix descriptors describe WHAT to fix, not HOW. The shell layer
   (boundary.devtools.shell.auto-fix) handles execution.")

(def ^:private fix-catalog
  "Map of BND error codes to fix descriptors."
  {"BND-301" {:fix-id :apply-migration
              :label  "Apply pending database migration"
              :safe?  true
              :action :migrate-up}

   "BND-101" {:fix-id :set-env-var
              :label  "Set missing environment variable for current session"
              :safe?  true
              :action :set-env}

   "BND-103" {:fix-id :set-jwt-secret
              :label  "Generate and set dev JWT_SECRET for current session"
              :safe?  true
              :action :set-jwt}

   "BND-601" {:fix-id :refactor-fcis
              :label  "Show FC/IS refactoring steps"
              :safe?  false
              :action :show-refactoring}

   ;; Missing module wiring — detected by state analyzer
   "BND-WIRING" {:fix-id :integrate-module
                  :label  "Wire scaffolded module into the system"
                  :safe?  true
                  :action :integrate-module}

   ;; Missing dependency — always requires confirmation
   "BND-DEP" {:fix-id :add-dependency
              :label  "Add missing dependency to deps.edn"
              :safe?  false
              :action :add-dependency}})

(defn match-fix
  "Find a fix descriptor for a classified error.
   Returns a fix descriptor map or nil if no fix is available.
   
   The :data from the classified error is merged into :params
   so the executor has context about what to fix."
  [{:keys [code data]}]
  (when-let [fix (get fix-catalog code)]
    (assoc fix :params (or data {}))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.auto-fix-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/auto_fix.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/auto_fix_test.clj
git add libs/devtools/src/boundary/devtools/core/auto_fix.clj libs/devtools/test/boundary/devtools/core/auto_fix_test.clj
git commit -m "feat: add auto-fix descriptor registry"
```

---

## Task 4: Error Enricher

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/error_enricher.clj`
- Test: `libs/devtools/test/boundary/devtools/core/error_enricher_test.clj`
- Read: `libs/core/src/boundary/core/validation/messages.clj` (for suggestion functions)
- Read: `libs/devtools/src/boundary/devtools/core/stacktrace.clj` (from Task 1)
- Read: `libs/devtools/src/boundary/devtools/core/auto_fix.clj` (from Task 3)

- [ ] **Step 1: Write tests for enrichment**

```clojure
;; libs/devtools/test/boundary/devtools/core/error_enricher_test.clj
(ns boundary.devtools.core.error-enricher-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.core.error-enricher :as enricher]))

(deftest ^:unit enrich-classified-error-test
  (let [ex (ex-info "validation failed" {:boundary/error-code "BND-201"})
        classified {:code "BND-201"
                    :category :validation
                    :exception ex
                    :data {}
                    :source :ex-data}
        enriched (enricher/enrich classified)]

    (testing "enriched error has stacktrace"
      (is (contains? enriched :stacktrace)))

    (testing "enriched error has fix info when available"
      ;; BND-201 has no fix in catalog, so should be nil
      (is (nil? (:fix enriched))))

    (testing "enriched error has dashboard-url"
      (is (string? (:dashboard-url enriched))))

    (testing "enriched error has docs-url"
      (is (string? (:docs-url enriched))))))

(deftest ^:unit enrich-with-fix-test
  (let [ex (ex-info "migration" {:boundary/error-code "BND-301"})
        classified {:code "BND-301"
                    :category :persistence
                    :exception ex
                    :data {}
                    :source :ex-data}
        enriched (enricher/enrich classified)]

    (testing "enriched error has fix descriptor for BND-301"
      (is (some? (:fix enriched)))
      (is (= :apply-migration (get-in enriched [:fix :fix-id]))))))

(deftest ^:unit enrich-nil-code-test
  (testing "unclassified error (nil code) is enriched gracefully"
    (let [ex (Exception. "unknown")
          classified {:code nil :category nil :exception ex :data {} :source :unclassified}
          enriched (enricher/enrich classified)]
      (is (contains? enriched :stacktrace))
      (is (nil? (:fix enriched)))
      (is (nil? (:docs-url enriched))))))

(deftest ^:unit enrich-self-protection-test
  (testing "enricher survives when stacktrace filtering throws"
    ;; Pass nil as exception — filter-stacktrace will get a nil and may fail
    (let [classified {:code "BND-201" :category :validation :exception nil :data {} :source :ex-data}
          enriched (enricher/enrich classified)]
      ;; Should not throw — field is just omitted
      (is (map? enriched))
      (is (= "BND-201" (:code enriched))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.error-enricher-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement error enricher**

```clojure
;; libs/devtools/src/boundary/devtools/core/error_enricher.clj
(ns boundary.devtools.core.error-enricher
  "Enrich classified errors with stacktrace, suggestions, fix info, and URLs.
   Pure functions — no I/O, no side effects.
   
   Each sub-call is wrapped in try/catch for self-protection:
   if any enrichment step fails, that field is omitted rather than
   crashing the pipeline."
  (:require [boundary.devtools.core.stacktrace :as stacktrace]
            [boundary.devtools.core.auto-fix :as auto-fix]))

(defn- safe-call
  "Call f, returning its result or nil if it throws."
  [f]
  (try (f) (catch Exception _ nil)))

(defn enrich
  "Enrich a classified error map with additional context.
   
   Adds:
   - :stacktrace — filtered/reordered stack trace
   - :suggestions — 'Did you mean?' suggestions (when applicable)
   - :fix — auto-fix descriptor or nil
   - :dashboard-url — link to dev dashboard error page
   - :docs-url — link to error code documentation
   
   Each field is independently protected: if a sub-call fails,
   that field is omitted from the result."
  [{:keys [code exception] :as classified}]
  (let [trace       (safe-call #(when exception (stacktrace/filter-stacktrace exception)))
        fix         (safe-call #(auto-fix/match-fix classified))
        dashboard   (when code "http://localhost:9999/dashboard/errors")
        docs        (when code (str "https://boundary.dev/errors/" code))]
    (cond-> classified
      trace     (assoc :stacktrace trace)
      fix       (assoc :fix fix)
      dashboard (assoc :dashboard-url dashboard)
      docs      (assoc :docs-url docs))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.error-enricher-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/error_enricher.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/error_enricher_test.clj
git add libs/devtools/src/boundary/devtools/core/error_enricher.clj libs/devtools/test/boundary/devtools/core/error_enricher_test.clj
git commit -m "feat: add error enricher with self-protection"
```

---

## Task 5: Expand Error Formatter

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/core/error_formatter.clj`
- Test: `libs/devtools/test/boundary/devtools/core/error_formatter_test.clj` (add to existing if present, otherwise check `error_codes_test.clj`)

- [ ] **Step 1: Write tests for new formatter functions**

Check if `libs/devtools/test/boundary/devtools/core/error_formatter_test.clj` exists. If not, create it. Add tests for `format-enriched-error` and `format-unclassified-error`.

```clojure
;; libs/devtools/test/boundary/devtools/core/error_formatter_test.clj
(ns boundary.devtools.core.error-formatter-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [boundary.devtools.core.error-formatter :as formatter]))

(deftest ^:unit format-enriched-error-test
  (testing "enriched error with all fields"
    (let [enriched {:code "BND-201"
                    :category :validation
                    :data {:schema :user/create}
                    :stacktrace {:user-frames [{:ns "boundary.user.core.validation"
                                                 :fn "validate"
                                                 :file "validation.clj"
                                                 :line 42}]
                                 :framework-frames []
                                 :jvm-frames []
                                 :total-hidden 5}
                    :fix {:fix-id :apply-migration
                          :label "Apply pending migration"
                          :safe? true}
                    :dashboard-url "http://localhost:9999/dashboard/errors"
                    :docs-url "https://boundary.dev/errors/BND-201"}
          output (formatter/format-enriched-error enriched)]
      (is (str/includes? output "BND-201"))
      (is (str/includes? output "Your code"))
      (is (str/includes? output "(fix!)"))
      (is (str/includes? output "localhost:9999"))))

  (testing "enriched error without fix"
    (let [enriched {:code "BND-402"
                    :category :auth
                    :data {}
                    :stacktrace {:user-frames [] :framework-frames [] :jvm-frames [] :total-hidden 3}
                    :dashboard-url "http://localhost:9999/dashboard/errors"
                    :docs-url "https://boundary.dev/errors/BND-402"}
          output (formatter/format-enriched-error enriched)]
      (is (str/includes? output "BND-402"))
      (is (not (str/includes? output "(fix!)"))))))

(deftest ^:unit format-unclassified-error-test
  (let [ex (Exception. "something broke")
        output (formatter/format-unclassified-error ex)]
    (testing "shows exception message"
      (is (str/includes? output "something broke")))
    (testing "suggests AI analysis"
      (is (str/includes? output "explain")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.error-formatter-test`
Expected: FAIL — functions not defined

- [ ] **Step 3: Add `format-enriched-error` and `format-unclassified-error` to `error_formatter.clj`**

Read the existing file first, then append the new functions after the existing ones. Do NOT modify existing `format-error`, `format-config-error`, or `format-fcis-violation`.

First, update the `ns` form in `error_formatter.clj` to add the `stacktrace` require:

```clojure
;; In the ns form, add to :require:
[boundary.devtools.core.stacktrace :as stacktrace]
```

Then append the new functions:

```clojure
;; Append to libs/devtools/src/boundary/devtools/core/error_formatter.clj

(defn format-enriched-error
  "Format a fully enriched error map for rich development output.
   Combines the BND code header, stack trace, and auto-fix suggestion.
   
   `enriched` is the output of error-enricher/enrich:
     :code, :category, :data, :stacktrace, :fix, :dashboard-url, :docs-url"
  [{:keys [code category data stacktrace fix dashboard-url docs-url]}]
  (let [error-def  (codes/lookup code)
        title      (or (:title error-def) "Error")
        lines      (cond-> [(separator code title)]
                     ;; Error description
                     (:description error-def)
                     (conj (:description error-def))

                     true (conj "")

                     ;; Stack trace
                     stacktrace
                     (conj (stacktrace/format-stacktrace stacktrace))

                     stacktrace (conj "")

                     ;; Fix suggestion from catalog
                     (:fix error-def)
                     (conj (str "Fix: " (:fix error-def)))

                     ;; Auto-fix available
                     fix
                     (conj (str "\nAuto-fix: (fix!)  \u2014 " (:label fix)))

                     true (conj "")

                     ;; Links
                     dashboard-url (conj (str "Dashboard: " dashboard-url))
                     docs-url      (conj (str "Docs: " docs-url))

                     true (conj (apply str (repeat 65 "\u2501"))))]
    (str/join "\n" (remove nil? lines))))

(defn format-unclassified-error
  "Format an unclassified error with a fallback message and AI hint."
  [^Throwable exception]
  (let [msg (or (.getMessage exception) "Unknown error")]
    (str "\u2501\u2501\u2501 Unclassified Error \u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
         msg "\n"
         "\n"
         "This error is not recognized by Boundary's error catalog.\n"
         "Try: (explain *e) for AI-powered analysis\n"
         (apply str (repeat 65 "\u2501")))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.error-formatter-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/error_formatter.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/error_formatter_test.clj
git add libs/devtools/src/boundary/devtools/core/error_formatter.clj libs/devtools/test/boundary/devtools/core/error_formatter_test.clj
git commit -m "feat: add format-enriched-error and format-unclassified-error"
```

---

## Task 6: REPL Error Handler (Shell)

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/repl_error_handler.clj`
- Test: `libs/devtools/test/boundary/devtools/shell/repl_error_handler_test.clj`

- [ ] **Step 1: Write tests for REPL error handler**

```clojure
;; libs/devtools/test/boundary/devtools/shell/repl_error_handler_test.clj
(ns boundary.devtools.shell.repl-error-handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.shell.repl-error-handler :as handler]))

(deftest ^:integration handle-repl-error-stores-exception-test
  (testing "handle-repl-error! stores exception in last-exception* atom"
    (reset! handler/last-exception* nil)
    (let [ex (ex-info "test error" {:boundary/error-code "BND-201"})]
      (with-out-str (handler/handle-repl-error! ex))
      (is (= ex @handler/last-exception*)))))

(deftest ^:integration handle-repl-error-prints-output-test
  (testing "handle-repl-error! prints formatted output for classified error"
    (let [ex (ex-info "validation failed" {:boundary/error-code "BND-201"})
          output (with-out-str (handler/handle-repl-error! ex))]
      (is (clojure.string/includes? output "BND-201"))))

  (testing "handle-repl-error! prints fallback for unclassified error"
    (let [ex (Exception. "mystery error")
          output (with-out-str (handler/handle-repl-error! ex))]
      (is (clojure.string/includes? output "mystery error"))
      (is (clojure.string/includes? output "explain")))))

(deftest ^:integration handle-repl-error-nil-safe-test
  (testing "handle-repl-error! handles nil gracefully"
    (is (= "" (with-out-str (handler/handle-repl-error! nil))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.repl-error-handler-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement REPL error handler**

```clojure
;; libs/devtools/src/boundary/devtools/shell/repl_error_handler.clj
(ns boundary.devtools.shell.repl-error-handler
  "REPL error handler — runs the error pipeline and stores the last exception.
   
   This is a shell namespace: it performs I/O (printing).
   
   Usage from user.clj:
     Wrap public REPL functions with try/catch that calls handle-repl-error!
     The zero-arity (fix!) reads from last-exception*."
  (:require [boundary.devtools.core.error-classifier :as classifier]
            [boundary.devtools.core.error-enricher :as enricher]
            [boundary.devtools.core.error-formatter :as formatter]))

(defonce last-exception*
  (atom nil))

(defn handle-repl-error!
  "Run the full error pipeline on an exception and print the result.
   Stores the exception in last-exception* for (fix!) to access.
   
   Pipeline: classify → enrich → format → print
   Falls back to standard output + AI hint for unclassified errors."
  [^Throwable exception]
  (when exception
    (reset! last-exception* exception)
    (let [classified (classifier/classify exception)]
      (if (:code classified)
        (let [enriched  (enricher/enrich classified)
              formatted (formatter/format-enriched-error enriched)]
          (println formatted))
        (println (formatter/format-unclassified-error exception))))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.repl-error-handler-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/repl_error_handler.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/repl_error_handler_test.clj
git add libs/devtools/src/boundary/devtools/shell/repl_error_handler.clj libs/devtools/test/boundary/devtools/shell/repl_error_handler_test.clj
git commit -m "feat: add REPL error handler with last-exception* atom"
```

---

## Task 7: Auto-Fix Executor (Shell)

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/auto_fix.clj`
- Test: `libs/devtools/test/boundary/devtools/shell/auto_fix_test.clj`

- [ ] **Step 1: Write tests for fix execution**

```clojure
;; libs/devtools/test/boundary/devtools/shell/auto_fix_test.clj
(ns boundary.devtools.shell.auto-fix-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.shell.auto-fix :as executor]))

(deftest ^:integration execute-safe-fix-test
  (testing "safe fix executes without confirmation at :full guidance"
    (let [fix {:fix-id :set-env-var
               :label "Set DATABASE_URL"
               :safe? true
               :action :set-env
               :params {:var-name "TEST_AUTO_FIX_VAR" :value "test-value"}}
          output (with-out-str
                   (executor/execute-fix! fix
                     {:guidance-level :full
                      :confirm-fn (fn [_] (throw (ex-info "should not confirm" {})))}))]
      (is (clojure.string/includes? output "Applying"))))

  (testing "safe fix executes silently at :minimal guidance"
    (let [fix {:fix-id :set-env-var
               :label "Set var"
               :safe? true
               :action :set-env
               :params {:var-name "TEST_AUTO_FIX_SILENT" :value "silent"}}
          output (with-out-str
                   (executor/execute-fix! fix {:guidance-level :minimal}))]
      (is (= "" output)))))

(deftest ^:integration execute-risky-fix-requires-confirmation-test
  (testing "risky fix requires confirmation even at :minimal"
    (let [confirmed? (atom false)
          fix {:fix-id :refactor-fcis
               :label "Show refactoring"
               :safe? false
               :action :show-refactoring
               :params {}}]
      (with-out-str
        (executor/execute-fix! fix
          {:guidance-level :minimal
           :confirm-fn (fn [_] (reset! confirmed? true) true)}))
      (is (true? @confirmed?))))

  (testing "risky fix aborted when user declines"
    (let [fix {:fix-id :refactor-fcis
               :label "Show refactoring"
               :safe? false
               :action :show-refactoring
               :params {}}
          output (with-out-str
                   (executor/execute-fix! fix
                     {:guidance-level :full
                      :confirm-fn (fn [_] false)}))]
      (is (clojure.string/includes? output "Aborted")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.auto-fix-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement auto-fix executor**

```clojure
;; libs/devtools/src/boundary/devtools/shell/auto_fix.clj
(ns boundary.devtools.shell.auto-fix
  "Execute fix descriptors — side-effecting operations.
   
   This is a shell namespace: it runs migrations, sets env vars, etc.
   The safety gate (safe? false → always confirm) is never overridden
   by guidance level."
  (:require [clojure.java.shell :as shell]))

;; =============================================================================
;; Fix action implementations
;; =============================================================================

(defmulti run-action!
  "Execute a specific fix action. Dispatches on :action keyword."
  (fn [action params] action))

(defmethod run-action! :migrate-up
  [_ _params]
  (println "Running: bb migrate up")
  (let [{:keys [exit out err]} (shell/sh "bb" "migrate" "up")]
    (when (seq out) (println out))
    (when (and (seq err) (not (zero? exit))) (println err))
    (zero? exit)))

(defmethod run-action! :set-env
  [_ {:keys [var-name value]}]
  (when (and var-name value)
    (System/setProperty var-name value)
    true))

(defmethod run-action! :set-jwt
  [_ _params]
  (let [secret (str "dev-secret-" (System/currentTimeMillis) "-boundary")]
    (System/setProperty "JWT_SECRET" secret)
    true))

(defmethod run-action! :integrate-module
  [_ {:keys [module-name]}]
  (when module-name
    (println (str "Running: bb scaffold integrate " module-name))
    (let [{:keys [exit out err]} (shell/sh "bb" "scaffold" "integrate" (name module-name))]
      (when (seq out) (println out))
      (when (and (seq err) (not (zero? exit))) (println err))
      (zero? exit))))

(defmethod run-action! :add-dependency
  [_ {:keys [lib version]}]
  (println (str "Suggested addition to deps.edn:"))
  (println (str "  " lib " {:mvn/version \"" (or version "LATEST") "\"}"))
  (println "Please add this manually to the appropriate deps.edn file.")
  true)

(defmethod run-action! :show-refactoring
  [_ {:keys [source-ns requires-ns]}]
  (println "FC/IS Refactoring Steps:")
  (println "  1. Create a protocol in ports.clj for the data you need")
  (println "  2. Move the shell dependency behind the protocol")
  (println "  3. Have the shell namespace implement the protocol")
  (when source-ns
    (println (str "  Source: " source-ns))
    (println (str "  Remove require: " requires-ns)))
  (println "\n  Or try: (ai/refactor-fcis '" source-ns ")")
  true)

(defmethod run-action! :default
  [action _params]
  (println (str "Unknown fix action: " action))
  false)

;; =============================================================================
;; Executor
;; =============================================================================

(defn execute-fix!
  "Execute a fix descriptor with safety/confirmation logic.
   
   opts:
     :guidance-level — :full, :minimal, or :off
     :confirm-fn     — (fn [prompt] => boolean), for risky fixes"
  [{:keys [label safe? action params]} {:keys [guidance-level confirm-fn]}]
  (let [should-confirm? (not safe?)
        should-print?   (not= guidance-level :minimal)]
    (if should-confirm?
      ;; Risky fix: always confirm
      (if (and confirm-fn (confirm-fn (str "Apply fix: " label "?")))
        (do
          (when should-print? (println (str "Applying: " label)))
          (run-action! action params))
        (println "Aborted."))
      ;; Safe fix: apply, optionally print
      (do
        (when should-print? (println (str "Applying: " label)))
        (run-action! action params)))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.auto-fix-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/auto_fix.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/auto_fix_test.clj
git add libs/devtools/src/boundary/devtools/shell/auto_fix.clj libs/devtools/test/boundary/devtools/shell/auto_fix_test.clj
git commit -m "feat: add auto-fix executor with safety/confirmation logic"
```

---

## Task 8: HTTP Error Middleware

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/http_error_middleware.clj`
- Test: `libs/devtools/test/boundary/devtools/shell/http_error_middleware_test.clj`
- Read: `libs/platform/src/boundary/platform/shell/utils/error_handling.clj` (for middleware context)

- [ ] **Step 1: Write tests for HTTP middleware**

```clojure
;; libs/devtools/test/boundary/devtools/shell/http_error_middleware_test.clj
(ns boundary.devtools.shell.http-error-middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.shell.http-error-middleware :as middleware]))

(deftest ^:integration wrap-dev-error-enrichment-test
  (testing "exceptions are re-thrown with :boundary/dev-info in ex-data"
    (let [handler (fn [_req] (throw (ex-info "bad input" {:boundary/error-code "BND-201"})))
          wrapped (middleware/wrap-dev-error-enrichment handler)
          thrown-ex (try (wrapped {:uri "/api/test" :request-method :post})
                        nil
                        (catch Exception e e))]
      (is (some? thrown-ex))
      (let [dev-info (get (ex-data thrown-ex) :boundary/dev-info)]
        (is (some? dev-info) "should have :boundary/dev-info in ex-data")
        (is (= "BND-201" (:code dev-info)))
        (is (string? (:formatted dev-info))))))

  (testing "non-exception responses pass through unchanged"
    (let [handler (fn [_req] {:status 200 :body "ok"})
          wrapped (middleware/wrap-dev-error-enrichment handler)]
      (is (= 200 (:status (wrapped {:uri "/test"}))))))

  (testing "unclassified exceptions still get :boundary/dev-info"
    (let [handler (fn [_req] (throw (Exception. "mystery")))
          wrapped (middleware/wrap-dev-error-enrichment handler)
          thrown-ex (try (wrapped {:uri "/api/test"})
                        nil
                        (catch Exception e e))]
      (is (some? thrown-ex))
      (let [dev-info (get (ex-data thrown-ex) :boundary/dev-info)]
        (is (some? dev-info))
        (is (nil? (:code dev-info)))
        (is (string? (:formatted dev-info)))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.http-error-middleware-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement HTTP error middleware**

```clojure
;; libs/devtools/src/boundary/devtools/shell/http_error_middleware.clj
(ns boundary.devtools.shell.http-error-middleware
  "Dev-mode HTTP error enrichment middleware.
   
   Positioned INSIDE wrap-enhanced-exception-handling. Catches exceptions,
   runs the error pipeline, and re-throws with :boundary/dev-info attached
   to ex-data so the outer middleware can include it in the RFC 7807 response."
  (:require [boundary.devtools.core.error-classifier :as classifier]
            [boundary.devtools.core.error-enricher :as enricher]
            [boundary.devtools.core.error-formatter :as formatter]))

(defn- build-dev-info
  "Build the :dev-info map from an enriched error."
  [{:keys [code category fix] :as enriched}]
  {:formatted      (if code
                     (formatter/format-enriched-error enriched)
                     (formatter/format-unclassified-error (:exception enriched)))
   :code           code
   :category       category
   :fix-available? (boolean fix)
   :fix-label      (:label fix)})

(defn wrap-dev-error-enrichment
  "Ring middleware that enriches exceptions with dev-info in dev mode.
   
   Catches exceptions, runs the error pipeline, attaches result as
   :boundary/dev-info in the exception's ex-data, and re-throws.
   The outer error handling middleware can then include :dev-info
   in its Problem Details response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (let [classified (classifier/classify ex)
              enriched   (enricher/enrich classified)
              dev-info   (build-dev-info enriched)
              ;; Preserve original ex-data and add :boundary/dev-info
              original-data (or (ex-data ex) {})
              enhanced-data (assoc original-data :boundary/dev-info dev-info)]
          ;; Pass original exception as cause to preserve full stack trace
          (throw (ex-info (.getMessage ex)
                          enhanced-data
                          ex)))))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.http-error-middleware-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/http_error_middleware.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/http_error_middleware_test.clj
git add libs/devtools/src/boundary/devtools/shell/http_error_middleware.clj libs/devtools/test/boundary/devtools/shell/http_error_middleware_test.clj
git commit -m "feat: add dev-mode HTTP error enrichment middleware"
```

---

## Task 9: FC/IS Checker

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/fcis_checker.clj`
- Test: `libs/devtools/test/boundary/devtools/shell/fcis_checker_test.clj`

- [ ] **Step 1: Write tests for FC/IS namespace scanning**

```clojure
;; libs/devtools/test/boundary/devtools/shell/fcis_checker_test.clj
(ns boundary.devtools.shell.fcis-checker-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.shell.fcis-checker :as fcis]))

(deftest ^:unit core-ns-and-shell-ns-test
  (testing "is-core-ns? identifies core namespaces"
    (is (true? (fcis/core-ns? "boundary.product.core.validation")))
    (is (true? (fcis/core-ns? "boundary.user.core.service")))
    (is (false? (fcis/core-ns? "boundary.product.shell.persistence")))
    (is (false? (fcis/core-ns? "boundary.platform.core.http"))))

  (testing "is-shell-ns? identifies shell namespaces"
    (is (true? (fcis/shell-ns? "boundary.product.shell.persistence")))
    (is (false? (fcis/shell-ns? "boundary.product.core.validation")))
    (is (false? (fcis/shell-ns? "boundary.platform.shell.interceptors")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.fcis-checker-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement FC/IS checker**

```clojure
;; libs/devtools/src/boundary/devtools/shell/fcis_checker.clj
(ns boundary.devtools.shell.fcis-checker
  "Post-reset namespace scanner for FC/IS violations.
   
   Only detects BND-601: core namespace importing shell namespace via :require.
   BND-602 (core uses I/O) is detected statically by bb check:fcis."
  (:require [boundary.devtools.core.error-formatter :as formatter]
            [clojure.string :as str]))

;; Framework prefixes — excluded from violation scanning
(def ^:private framework-prefixes
  #{"boundary.platform." "boundary.observability." "boundary.devtools." "boundary.core."})

(defn core-ns?
  "Is this a user-level core namespace? (boundary.MODULE.core.*)"
  [ns-str]
  (and (str/includes? ns-str ".core.")
       (str/starts-with? ns-str "boundary.")
       (not (some #(str/starts-with? ns-str %) framework-prefixes))))

(defn shell-ns?
  "Is this a user-level shell namespace? (boundary.MODULE.shell.*)"
  [ns-str]
  (and (str/includes? ns-str ".shell.")
       (str/starts-with? ns-str "boundary.")
       (not (some #(str/starts-with? ns-str %) framework-prefixes))))

(defn- extract-module
  "Extract the module name from a namespace string.
   E.g., 'boundary.product.core.validation' → 'product'"
  [ns-str]
  (let [parts (str/split ns-str #"\.")]
    (when (>= (count parts) 3)
      (nth parts 1))))

(defn- ns-requires
  "Get all required namespaces for a namespace object."
  [ns-obj]
  (map ns-name (vals (ns-aliases ns-obj))))

(defn find-violations
  "Scan loaded namespaces for FC/IS violations.
   Returns a vector of {:source-ns :requires-ns :module} maps."
  []
  (let [loaded-nses (all-ns)]
    (->> loaded-nses
         (filter #(core-ns? (str (ns-name %))))
         (mapcat (fn [ns-obj]
                   (let [ns-str       (str (ns-name ns-obj))
                         all-requires (map str (ns-requires ns-obj))]
                     (->> all-requires
                          (filter shell-ns?)
                          (map (fn [shell-ns]
                                 {:source-ns   ns-str
                                  :requires-ns shell-ns
                                  :module      (extract-module ns-str)}))))))
         vec)))

(defn check-fcis-violations!
  "Scan loaded namespaces and print warnings for FC/IS violations.
   Called after (go) and (reset) in user.clj."
  []
  (let [violations (find-violations)]
    (when (seq violations)
      (println)
      (doseq [v violations]
        (println (formatter/format-fcis-violation v)))
      (println (str "\n" (count violations) " FC/IS violation(s) found. "
                    "Run bb check:fcis for full static analysis.\n")))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.fcis-checker-test`
Expected: PASS

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/fcis_checker.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/fcis_checker_test.clj
git add libs/devtools/src/boundary/devtools/shell/fcis_checker.clj libs/devtools/test/boundary/devtools/shell/fcis_checker_test.clj
git commit -m "feat: add post-reset FC/IS violation checker"
```

---

## Task 10: Wire Everything into user.clj

**Files:**
- Modify: `dev/repl/user.clj`
- No new test file — this is integration wiring tested by the devtools suite

- [ ] **Step 1: Read current `dev/repl/user.clj`**

Read the file to understand the current state (already read during exploration, but re-read for latest).

- [ ] **Step 2: Add new requires to `user.clj`**

Add these requires to the `ns` form in `dev/repl/user.clj`:

```clojure
[boundary.devtools.shell.repl-error-handler :as repl-errors]
[boundary.devtools.shell.fcis-checker :as fcis]
[boundary.devtools.shell.auto-fix :as auto-fix-shell]
[boundary.devtools.core.error-classifier :as classifier]
[boundary.devtools.core.auto-fix :as auto-fix]
```

- [ ] **Step 3: Add `with-error-handling` macro and `fix!` function**

Add after the `;; Contextual Tips` section:

```clojure
;; =============================================================================
;; Phase 3: Error Experience — Error Pipeline Wiring
;; =============================================================================

(defmacro ^:private with-error-handling
  "Wrap body in try/catch that runs the error pipeline on exceptions."
  [& body]
  `(try ~@body
     (catch Exception e#
       (repl-errors/handle-repl-error! e#)
       nil)))

(defn fix!
  "Auto-fix the last error if a fix is available.
   (fix!)     ; fix last error (recommended — nREPL-safe)
   (fix! ex)  ; fix a specific exception directly"
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
```

- [ ] **Step 4: Modify `go` to wrap with error handling and FC/IS check**

Replace the existing `go` function:

```clojure
(defn go
  "Start the system with guidance dashboard."
  []
  (try
    (let [result (ig-repl/go)]
      (print-startup-dashboard)
      (fcis/check-fcis-violations!)
      (maybe-show-tip :start)
      result)
    (catch Exception e
      (repl-errors/handle-repl-error! e)
      (fcis/check-fcis-violations!)
      nil)))
```

- [ ] **Step 5: Modify `reset` with FC/IS check**

Replace the existing `reset` function:

```clojure
(defn reset
  "Reload code and restart the system."
  []
  ;; No startup dashboard on reset — dashboard prints once on go, not every reload.
  (try
    (let [result (ig-repl/reset)]
      (fcis/check-fcis-violations!)
      result)
    (catch Exception e
      (repl-errors/handle-repl-error! e)
      (fcis/check-fcis-violations!)
      nil)))
```

- [ ] **Step 6: Wrap `simulate` and `query` with error handling**

Replace the existing `simulate` and `query` functions:

```clojure
;; Note: when-let returns nil silently when system is not running.
;; This is intentional — (status) already handles "not running" messaging.

(defn simulate
  "Simulate an HTTP request against the running system.
   (simulate :get \"/api/v1/users\")
   (simulate :post \"/api/v1/users\" {:body {:email \"test@example.com\"}})
   (simulate :get \"/api/v1/users\" {:headers {\"authorization\" \"Bearer ...\"}})"
  ([method path]
   (simulate method path {}))
  ([method path opts]
   (with-error-handling
     (when-let [handler (get (system) :boundary/http-handler)]
       (devtools-repl/simulate-request handler method path opts)))))

(defn query
  "Quick query a database table.
   (query :users)
   (query :users {:where [:= :active true] :limit 5})"
  ([table]
   (query table {}))
  ([table opts]
   (with-error-handling
     (when-let [ctx (db-context)]
       (devtools-repl/run-query ctx table opts)))))
```

- [ ] **Step 7: Add `fix!` to the startup box and `commands` output**

In the quick start message at the bottom, add `fix!` to the printed box. Also check if `guidance.clj` `format-commands` needs updating — if so, add fix! to the appropriate group in `libs/devtools/src/boundary/devtools/core/guidance.clj`.

- [ ] **Step 8: Run paren repair and commit**

```bash
clj-paren-repair dev/repl/user.clj
git add dev/repl/user.clj
git commit -m "feat: wire Phase 3 error pipeline into REPL"
```

---

## Task 11: Verify deps.edn and Update AGENTS.md

**Files:**
- Verify: `libs/devtools/deps.edn`
- Create or update: `libs/devtools/AGENTS.md`

- [ ] **Step 1: Verify `libs/devtools/deps.edn` has `boundary/core` dependency**

Read `libs/devtools/deps.edn`. If `boundary/core` is not listed as a dependency, add it:

```clojure
:deps {org.clojure/clojure {:mvn/version "1.12.4"}
       boundary/platform {:local/root "../platform"}
       boundary/core {:local/root "../core"}}
```

This is needed because `error_enricher.clj` calls into `boundary.core.validation.messages` for "Did you mean?" suggestions.

- [ ] **Step 2: Create or update `libs/devtools/AGENTS.md`**

If `libs/devtools/AGENTS.md` does not exist, create it. If it exists from Phase 1/2, add Phase 3 documentation. Include:

- Error pipeline overview (classify → enrich → format → output)
- New REPL command: `(fix!)` — what it does, safety model
- Error code catalog reference (BND-xxx)
- Stack trace filtering behavior
- FC/IS violation detection (post-reset)
- HTTP dev middleware behavior

Keep it concise — reference the spec for full details.

- [ ] **Step 3: Commit**

```bash
git add libs/devtools/deps.edn libs/devtools/AGENTS.md
git commit -m "docs: update devtools deps and AGENTS.md for Phase 3"
```

---

## Task 12: Run Full Test Suite

- [ ] **Step 1: Run all devtools tests**

```bash
clojure -M:test:db/h2 :devtools
```

Expected: All tests pass. If any fail, fix and re-run.

- [ ] **Step 2: Run linter**

```bash
clojure -M:clj-kondo --lint libs/devtools/src libs/devtools/test
```

Expected: No errors. Warnings are acceptable if pre-existing.

- [ ] **Step 3: Run FC/IS enforcement check**

```bash
bb check:fcis
```

Expected: No new violations introduced. All new core files are pure.

- [ ] **Step 4: Run full quality checks**

```bash
bb check
```

Expected: All checks pass.

- [ ] **Step 5: Run paren repair on all new files**

```bash
for f in libs/devtools/src/boundary/devtools/core/stacktrace.clj \
         libs/devtools/src/boundary/devtools/core/error_classifier.clj \
         libs/devtools/src/boundary/devtools/core/auto_fix.clj \
         libs/devtools/src/boundary/devtools/core/error_enricher.clj \
         libs/devtools/src/boundary/devtools/shell/auto_fix.clj \
         libs/devtools/src/boundary/devtools/shell/repl_error_handler.clj \
         libs/devtools/src/boundary/devtools/shell/http_error_middleware.clj \
         libs/devtools/src/boundary/devtools/shell/fcis_checker.clj; do
  clj-paren-repair "$f"
done
```

- [ ] **Step 6: Commit any fixes from quality checks**

```bash
git add -A
git commit -m "fix: address linting and quality check findings"
```

---

## Task 13: Update guidance.clj Commands List

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/core/guidance.clj`

- [ ] **Step 1: Read `guidance.clj` to find the commands list**

Read `libs/devtools/src/boundary/devtools/core/guidance.clj` and find the `format-commands` function.

- [ ] **Step 2: Add `(fix!)` to the Debug group in `format-commands`**

Add `(fix!)` to the Debug commands group alongside `(simulate)`, `(trace)`, `(explain)`.

- [ ] **Step 3: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/guidance.clj
git add libs/devtools/src/boundary/devtools/core/guidance.clj
git commit -m "feat: add (fix!) to REPL commands palette"
```

---

## Task 14: Final Verification

- [ ] **Step 1: Manual REPL verification**

Start the REPL and verify the end-to-end flow:

```bash
clojure -M:repl-clj
```

Then in the REPL:

```clojure
(go)                              ;; Should show dashboard + no FC/IS warnings
(fix!)                            ;; Should print "No recent error"
(simulate :get "/nonexistent")    ;; Should trigger error pipeline
(fix!)                            ;; Should show auto-fix if applicable
(commands)                        ;; Should list (fix!) in Debug group
```

- [ ] **Step 2: Verify all spec verification criteria**

Walk through each criterion from the spec:

1. Trigger a BND-201 (validation) error → rich output with code
2. Trigger a BND-301 (missing table) error → rich output with migration fix
3. `(fix!)` resolves a known error pattern
4. Stack traces show user code first
5. Unclassified error shows AI hint
6. FC/IS check runs after `(go)` and `(reset)`
7. Guidance level controls suggestion visibility

- [ ] **Step 3: Final commit if any adjustments needed**

```bash
git add -A
git commit -m "chore: final Phase 3 verification adjustments"
```
