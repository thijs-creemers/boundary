# BOU-95 First-class AGENTS.md Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a single structured knowledge source (`resources/agents/knowledge.edn` + the existing `modules-catalogue.edn`) the source of truth that a deterministic Babashka generator renders into the framework root `AGENTS.md` and the downstream `boundary new` template `AGENTS.md.tmpl`, with a CI drift guardrail, so both the Boundary repo and every project built with Boundary ship correct, current FC/IS / naming / pitfall / module guidance.

**Architecture:** A pure-render + thin-IO Babashka script (`scripts/agents_gen.clj`, on the existing bb classpath) reads guardrail knowledge from EDN and module data from the bundled catalogue, then splices rendered markdown into `<!-- gen:SECTION -->` marked regions of two target files. Per-pitfall `:surfaces` tags let the same source render an 11-pitfall framework doc and a 6-pitfall downstream template. Both `CLAUDE.md` files are reduced to `@AGENTS.md` importer stubs so AGENTS.md is the single source for Claude Code and AGENTS.md-native tools alike. A `bb check:agents` task (run-the-generator-in-`--check`-mode plus module-source validation) is wired into `bb check` + CI.

**Tech Stack:** Babashka (Clojure), `clojure.test` (run under bb), EDN, the existing `boundary.tools.check` subprocess registry.

**Spec:** `docs/superpowers/specs/2026-06-15-bou-95-first-class-agents-md-design.md`

---

## File Structure

- Create: `resources/agents/knowledge.edn` — guardrail knowledge (`:fc-is`, `:naming`, `:pitfalls`, `:module-allowlist`).
- Create: `scripts/agents_gen.clj` — `(ns agents-gen)`: pure render fns + splice + IO + `--check` + module-source validation + `-main`.
- Create: `scripts/agents_gen_test.clj` — `(ns agents-gen-test)`: clojure.test for the pure fns, run under bb.
- Create: `resources/agents/README.md` — MCP tool → source mapping + maintenance notes.
- Modify: `AGENTS.md` — insert `<!-- gen:* -->` markers around 4 sections; content becomes generated.
- Modify: `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl` — insert `<!-- gen:* -->` markers around 3 guardrail sections.
- Modify: `CLAUDE.md` — reduce to `@AGENTS.md` stub + Claude-only notes.
- Modify: `libs/boundary-cli/resources/boundary/cli/templates/CLAUDE.md.tmpl` — reduce to `@AGENTS.md` stub + Claude skill pointer.
- Modify: `bb.edn` — add `agents:gen`, `check:agents`, `test:agents` tasks; add `agents-gen` require.
- Modify: `libs/tools/src/boundary/tools/check.clj` — add `{:id :agents …}` to `all-checks`.

### Conventions used by the generator

- **Marker syntax:** an owned region is delimited by `<!-- gen:SECTION -->` and `<!-- /gen:SECTION -->` on their own lines. The generator replaces everything *between* the markers and leaves the marker lines themselves intact. Sections: `fc-is`, `naming`, `pitfalls`, `modules`.
- **Never touch `boundary:*` markers.** The template's `<!-- boundary:available-modules -->` / `<!-- boundary:installed-modules -->` regions are filled at runtime by `boundary add` (`libs/boundary-cli/src/boundary/cli/add.clj`). The generator only ever operates on `gen:*` markers; it must not write inside `boundary:*` regions.
- **Namespace token:** code examples in `knowledge.edn` use the literal `{{ns}}` sentinel. At render time the framework target replaces it with `myapp`; the `AGENTS.md.tmpl` target replaces it with `{{project-ns}}` (so the CLI's own substitution fills it per project).
- **Byte-exact:** render fns emit canonical output (fixed spacing/column widths/ordering). `--check` compares full target-file strings for exact equality.

---

## Task 1: Insert `gen:*` markers into both AGENTS files (no content change)

**Files:**
- Modify: `AGENTS.md`
- Modify: `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl`

This task only *wraps* existing prose in marker comments. Do not change wording yet — Task 8's first generation will reconcile content.

- [ ] **Step 1: Wrap the framework AGENTS.md sections**

In `AGENTS.md`, add a marker line immediately before and after each of these four existing sections' bodies (heading stays outside the markers so it is not regenerated):
- Naming conventions — the kebab/snake/camel content under "### 1. ALWAYS Use kebab-case Internally" (~line 266).
- FC/IS dependency rules — the "### 3. Dependency Rules" content (~line 302).
- Common Pitfalls — wrap the entire body of the "## Common Pitfalls" section (the 11 `### N` entries, ~line 450 onward).
- Module table — the body of "## Library-Specific Guides" (~line 857).

Each wrap looks like:

```markdown
<!-- gen:naming -->
... existing content, untouched for now ...
<!-- /gen:naming -->
```

Use section ids `naming`, `fc-is`, `pitfalls`, `modules` respectively.

- [ ] **Step 2: Wrap the template AGENTS.md.tmpl sections**

In `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl`, wrap three sections in `gen:*` markers:
- Naming — the "### Case conversion" table.
- FC/IS — the "Hard dependency rules" block under "## Architecture: Functional Core / Imperative Shell".
- Pitfalls — the body of "## Common Pitfalls" (the 6 `### N` entries, ~line 276 onward).

Do **not** add a `modules` marker here — the template's module list keeps using its existing `<!-- boundary:available-modules -->` / `<!-- boundary:installed-modules -->` markers. Leave those untouched.

- [ ] **Step 3: Verify links still pass**

Run: `bb check-links`
Expected: `Broken links: 0` and exit 0.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl
git commit -m "docs(agents): wrap generated sections in gen:* markers (BOU-95)"
```

---

## Task 2: Author `resources/agents/knowledge.edn`

**Files:**
- Create: `resources/agents/knowledge.edn`

- [ ] **Step 1: Write the EDN skeleton with fc-is and naming**

```clojure
{:fc-is
 {:layers   [{:from :shell :to :core  :allowed true}
             {:from :core  :to :ports :allowed true}
             {:from :shell :to :ports :allowed true}
             {:from :core  :to :shell :allowed false :reason "violates FC/IS"}
             {:from :core  :to :io    :allowed false :reason "even logging"}]
  :rules    ["core/ must not import shell/IO/logging/DB"
             "cross-module calls go through service ports"
             "web/HTTP layers never require *.shell.persistence directly"]
  :ports-required true
  ;; Example reproduced verbatim from current AGENTS.md, ns-token sentinel applied:
  :example  "(ns {{ns}}.core.product)\n(defn calculate-total [items] (reduce + (map :price items)))"}

 :naming
 [{:context :clojure :case :kebab :example ":password-hash, :created-at"}
  {:context :db      :case :snake :example "password_hash, created_at"}
  {:context :api     :case :camel :example "passwordHash, createdAt"}]

 ;; Libraries that have a libs/*/AGENTS.md but are NOT installable app modules
 ;; (dev/build tooling, absent from modules-catalogue.edn). The framework root
 ;; module table renders catalogue modules ++ these, so every documented lib keeps
 ;; a pointer. Module-source validation (Task 10) derives its allowlist from the
 ;; :name values here. Descriptions/docs-urls lifted from each lib's AGENTS.md.
 :dev-modules
 [{:name "scaffolder" :description "Module generation / scaffolding"
   :docs-url "https://github.com/thijs-creemers/boundary/blob/main/libs/scaffolder/AGENTS.md"}
  {:name "tools" :description "Developer tooling (bb tasks, checks, doctor)"
   :docs-url "https://github.com/thijs-creemers/boundary/blob/main/libs/tools/AGENTS.md"}
  {:name "devtools" :description "Dev-only utilities"
   :docs-url "https://github.com/thijs-creemers/boundary/blob/main/libs/devtools/AGENTS.md"}]

 :pitfalls
 [;; ── Task 6 fills this; see below ──
  ]}
```

- [ ] **Step 2: Populate `:pitfalls` with all 11, tagged by `:surfaces`**

Lift each pitfall verbatim from the framework `AGENTS.md` "Common Pitfalls" section. Each entry:

```clojure
{:id "P01"
 :title "snake_case vs kebab-case mixing"
 :surfaces #{:framework :downstream}
 :symptom "Using (:password_hash user) instead of (:password-hash user) returns nil."
 :cause   "snake_case key used in Clojure code; DB returns snake_case."
 :fix     "Always use kebab-case internally; convert only at the DB boundary."
 ;; OPTIONAL — a fenced clojure example, reproduced verbatim where the source
 ;; pitfall has one (validation-in-wrong-layer, exception handling, java interop,
 ;; reitit routes, swagger, etc.). Use the {{ns}} sentinel for namespaces.
 :example "(throw (ex-info \"User not found\" {:type :not-found :id id}))"}
```

**Preserve existing code examples.** Several framework pitfalls (validation in
wrong layer, exception handling, Java interop, reitit routes, swagger) carry
before/after code blocks that are the most valuable part of the entry. Capture each
such block in `:example` (verbatim, `{{ns}}` for namespaces) so generation does not
lose it. Entries without a code block simply omit `:example`.

Tag with `:surfaces`:
- `#{:framework :downstream}` (the 6 shared, also present in the template today): snake/kebab mixing, defrecord changes not taking effect, core depending on shell, missing `:type` in ex-info, validation in wrong layer, forward references.
- `#{:framework}` (framework-only): unbalanced parentheses / paren repair, schema-database mismatch, Java interop static vs instance, module API routes (reitit vectors vs normalized maps), Swagger/OpenAPI params.

Keep one canonical wording per pitfall (the downstream wording converges on the framework wording). Where an example references a namespace, use the `{{ns}}` sentinel.

- [ ] **Step 3: Verify the EDN reads**

Run: `bb -e "(clojure.edn/read-string (slurp \"resources/agents/knowledge.edn\")) (println :ok)"`
Expected: prints `:ok` (no reader exception).

- [ ] **Step 4: Commit**

```bash
git add resources/agents/knowledge.edn
git commit -m "feat(agents): structured guardrail knowledge source (BOU-95)"
```

---

## Task 3: Generator scaffold — namespace, loaders, splice (TDD)

**Files:**
- Create: `scripts/agents_gen.clj`
- Create: `scripts/agents_gen_test.clj`

- [ ] **Step 1: Write the failing test for `splice-region`**

In `scripts/agents_gen_test.clj`:

```clojure
(ns agents-gen-test
  (:require [clojure.test :refer [deftest is testing]]
            [agents-gen :as gen]))

(deftest splice-region-replaces-between-markers
  (let [doc "a\n<!-- gen:x -->\nOLD\n<!-- /gen:x -->\nb\n"]
    (is (= "a\n<!-- gen:x -->\nNEW\n<!-- /gen:x -->\nb\n"
           (gen/splice-region doc "x" "NEW")))))

(deftest splice-region-is-idempotent
  (let [doc "a\n<!-- gen:x -->\nOLD\n<!-- /gen:x -->\nb\n"
        once (gen/splice-region doc "x" "NEW")]
    (is (= once (gen/splice-region once "x" "NEW")))))

(deftest splice-region-throws-on-missing-marker
  (is (thrown? clojure.lang.ExceptionInfo
               (gen/splice-region "no markers here" "x" "NEW"))))
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `bb test:agents` (task added in Task 11; until then run inline:)
`bb -e "(require 'agents-gen-test)(clojure.test/run-tests 'agents-gen-test)"`
Expected: FAIL — `agents-gen` namespace / `splice-region` not found.

> **Classpath note:** always run these via plain `bb -e` (NOT `bb -cp scripts -e`).
> `scripts` is already on the bb.edn `:paths`; passing `-cp` *overrides* `:paths`
> and would drop `libs/boundary-cli/resources`, making `(io/resource
> "boundary/cli/modules-catalogue.edn")` return `nil` in later tasks.

- [ ] **Step 3: Implement the scaffold + `splice-region`**

In `scripts/agents_gen.clj`:

```clojure
#!/usr/bin/env bb
;; scripts/agents_gen.clj
;; Deterministic generator for the framework AGENTS.md and the downstream
;; AGENTS.md.tmpl, from resources/agents/knowledge.edn + modules-catalogue.edn.
;; Usage:
;;   bb agents:gen            ; write both targets
;;   bb agents:gen --check    ; verify in sync + module-source valid; non-zero on drift
(ns agents-gen
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn splice-region
  "Replace the content between <!-- gen:SECTION --> and <!-- /gen:SECTION -->
   with body (markers preserved, body placed on its own lines). Throws if a
   marker is missing."
  [content section body]
  (let [open  (str "<!-- gen:" section " -->")
        close (str "<!-- /gen:" section " -->")
        oi    (str/index-of content open)
        ci    (str/index-of content close)]
    (when (or (nil? oi) (nil? ci))
      (throw (ex-info "gen marker not found" {:section section})))
    (str (subs content 0 (+ oi (count open)))
         "\n" body "\n"
         (subs content ci))))
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `bb -e "(require 'agents-gen-test)(clojure.test/run-tests 'agents-gen-test)"`
Expected: 3 tests pass, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add scripts/agents_gen.clj scripts/agents_gen_test.clj
git commit -m "feat(agents): generator scaffold + splice-region (BOU-95)"
```

---

## Task 4: `render-fc-is` (TDD)

**Files:**
- Modify: `scripts/agents_gen.clj`
- Modify: `scripts/agents_gen_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(def sample-knowledge
  {:fc-is {:layers [{:from :shell :to :core :allowed true}
                    {:from :core :to :shell :allowed false :reason "violates FC/IS"}]
           :rules ["core/ must not import shell/IO/logging/DB"]
           :ports-required true
           :example "(ns {{ns}}.core.product)"}})

(deftest render-fc-is-emits-rows-and-substitutes-ns
  (let [out (gen/render-fc-is (:fc-is sample-knowledge) "myapp")]
    (is (str/includes? out "Shell → Core"))
    (is (str/includes? out "❌"))
    (is (str/includes? out "violates FC/IS"))
    (is (str/includes? out "myapp.core.product"))
    (is (not (str/includes? out "{{ns}}")))))

(deftest render-fc-is-template-keeps-project-ns-token
  (let [out (gen/render-fc-is (:fc-is sample-knowledge) "{{project-ns}}")]
    (is (str/includes? out "{{project-ns}}.core.product"))))
```

- [ ] **Step 2: Run, verify fail** (`render-fc-is` undefined).

- [ ] **Step 3: Implement**

```clojure
(defn- sub-ns [s ns-token] (str/replace s "{{ns}}" ns-token))

(defn render-fc-is
  "Render the FC/IS layer rules section as markdown. ns-token replaces {{ns}}."
  [{:keys [layers rules ports-required example]} ns-token]
  (let [arrow (fn [{:keys [from to allowed reason]}]
                (format "| %s → %s | %s |"
                        (str/capitalize (name from))
                        (str/capitalize (name to))
                        (if allowed "✅ allowed"
                            (str "❌ NEVER — " reason))))]
    (str "| Direction | Allowed? |\n"
         "|-----------|----------|\n"
         (str/join "\n" (map arrow layers)) "\n\n"
         (when ports-required "Every module MUST define `ports.clj`.\n\n")
         (str/join "\n" (map #(str "- " %) rules)) "\n\n"
         "```clojure\n" (sub-ns example ns-token) "\n```")))
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit**

```bash
git add scripts/agents_gen.clj scripts/agents_gen_test.clj
git commit -m "feat(agents): render-fc-is (BOU-95)"
```

---

## Task 5: `render-naming` (TDD)

**Files:** Modify `scripts/agents_gen.clj`, `scripts/agents_gen_test.clj`

- [ ] **Step 1: Failing test**

```clojure
(deftest render-naming-emits-table
  (let [out (gen/render-naming [{:context :clojure :case :kebab :example ":password-hash"}
                                {:context :db :case :snake :example "password_hash"}])]
    (is (str/includes? out "| Location | Convention | Example |"))
    (is (str/includes? out "kebab"))
    (is (str/includes? out ":password-hash"))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement**

```clojure
(defn render-naming
  "Render the case-convention table as markdown."
  [rows]
  (let [label {:clojure "All Clojure code" :db "Database boundary only" :api "API/JSON boundary only"}
        row (fn [{:keys [context case example]}]
              (format "| %s | %s | `%s` |" (label context) (name case) example))]
    (str "| Location | Convention | Example |\n"
         "|----------|-----------|---------|\n"
         (str/join "\n" (map row rows)))))
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit** `feat(agents): render-naming (BOU-95)`

---

## Task 6: `render-pitfalls` with surface filter + ns-token (TDD)

**Files:** Modify `scripts/agents_gen.clj`, `scripts/agents_gen_test.clj`

- [ ] **Step 1: Failing test**

```clojure
(def sample-pitfalls
  [{:id "P01" :title "kebab mixing" :surfaces #{:framework :downstream}
    :symptom "nil values" :cause "snake key" :fix "use kebab; convert at boundary {{ns}}"}
   {:id "P11" :title "swagger params" :surfaces #{:framework}
    :symptom "invisible params" :cause "no declaration" :fix "declare explicitly"}])

(deftest render-pitfalls-framework-includes-all
  (let [out (gen/render-pitfalls sample-pitfalls :framework "myapp")]
    (is (str/includes? out "kebab mixing"))
    (is (str/includes? out "swagger params"))
    (is (str/includes? out "myapp"))
    (is (not (str/includes? out "{{ns}}")))))

(deftest render-pitfalls-downstream-filters-to-tagged
  (let [out (gen/render-pitfalls sample-pitfalls :downstream "{{project-ns}}")]
    (is (str/includes? out "kebab mixing"))
    (is (not (str/includes? out "swagger params")))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement**

```clojure
(defn render-pitfalls
  "Render pitfalls whose :surfaces contains `surface`. ns-token replaces {{ns}}.
   Output order follows the input vector (deterministic). An optional :example is
   rendered as a fenced clojure block after the Fix line."
  [pitfalls surface ns-token]
  (->> pitfalls
       (filter #(contains? (:surfaces %) surface))
       (map-indexed
        (fn [i {:keys [title symptom cause fix example]}]
          (sub-ns
           (str (format "### %d. %s\n\n- **Symptom:** %s\n- **Cause:** %s\n- **Fix:** %s"
                        (inc i) title symptom cause fix)
                (when example (str "\n\n```clojure\n" example "\n```")))
           ns-token)))
       (str/join "\n\n")))
```

Add a test asserting an entry WITH `:example` renders a ```` ```clojure ```` block
and one WITHOUT omits it.

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit** `feat(agents): render-pitfalls with surface filter (BOU-95)`

---

## Task 7: `render-modules` from catalogue, deterministic alignment (TDD)

**Files:** Modify `scripts/agents_gen.clj`, `scripts/agents_gen_test.clj`

- [ ] **Step 1: Failing test**

```clojure
(def sample-modules
  [{:name "core" :description "Validation, case conversion" :category :core
    :docs-url "https://github.com/thijs-creemers/boundary/blob/main/libs/core/AGENTS.md"}
   {:name "payments" :description "PSP abstraction" :category :optional
    :docs-url "https://github.com/thijs-creemers/boundary/blob/main/libs/payments/AGENTS.md"}])

(deftest render-modules-emits-aligned-table-with-links
  (let [out (gen/render-modules sample-modules)]
    (is (str/includes? out "| Module"))
    (is (str/includes? out "[core]"))
    (is (str/includes? out "libs/core/AGENTS.md"))
    ;; deterministic: rendering twice is identical
    (is (= out (gen/render-modules sample-modules)))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement** (no version/clojars; name + description + docs link; deterministic column widths)

```clojure
(defn render-modules
  "Render the framework module table from catalogue :modules entries.
   Name links to the lib's AGENTS.md; no version/clojars (avoids version drift)."
  [modules]
  (let [sorted (sort-by :name modules)
        cell-name (fn [{:keys [name docs-url]}] (format "[%s](%s)" name docs-url))
        names (map cell-name sorted)
        descs (map :description sorted)
        w1 (apply max (count "Module") (map count names))
        w2 (apply max (count "Description") (map count descs))
        pad (fn [s w] (str s (apply str (repeat (- w (count s)) " "))))
        row (fn [a b] (format "| %s | %s |" (pad a w1) (pad b w2)))]
    (str (row "Module" "Description") "\n"
         (format "|%s|%s|"
                 (apply str (repeat (+ w1 2) "-"))
                 (apply str (repeat (+ w2 2) "-"))) "\n"
         (str/join "\n" (map #(row (cell-name %) (:description %)) sorted)))))
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit** `feat(agents): render-modules from catalogue (BOU-95)`

---

## Task 8: Loaders, targets table, write mode (`-main`), first generation

**Files:** Modify `scripts/agents_gen.clj`, `scripts/agents_gen_test.clj`

- [ ] **Step 1: Failing test for `render-target`**

```clojure
(deftest render-target-substitutes-and-splices-known-sections
  (let [doc (str "<!-- gen:naming -->\nx\n<!-- /gen:naming -->\n"
                 "<!-- gen:fc-is -->\ny\n<!-- /gen:fc-is -->\n")
        out (gen/render-target doc sample-knowledge sample-modules
                               {:sections [:naming :fc-is] :ns-token "myapp"
                                :pitfall-surface :framework})]
    (is (str/includes? out "| Location | Convention"))
    (is (str/includes? out "Shell → Core"))
    ;; unknown markers untouched / boundary:* never added
    (is (not (str/includes? out "boundary:")))))
```

(Extend `sample-knowledge` with `:naming` and `:pitfalls` keys for this test.)

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement loaders, targets, render-target, write/`-main`**

```clojure
(def knowledge-path "resources/agents/knowledge.edn")
(def catalogue-resource "boundary/cli/modules-catalogue.edn")
(def tmpl-path "libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl")

(defn load-knowledge [] (edn/read-string (slurp knowledge-path)))
(defn load-modules []
  (-> (io/resource catalogue-resource) slurp edn/read-string :modules))

(def targets
  [{:file "AGENTS.md"
    :sections [:naming :fc-is :pitfalls :modules]
    :ns-token "myapp" :pitfall-surface :framework}
   {:file tmpl-path
    :sections [:naming :fc-is :pitfalls]
    :ns-token "{{project-ns}}" :pitfall-surface :downstream}])

(defn render-section [section knowledge modules {:keys [ns-token pitfall-surface]}]
  (case section
    :naming   (render-naming (:naming knowledge))
    :fc-is    (render-fc-is (:fc-is knowledge) ns-token)
    :pitfalls (render-pitfalls (:pitfalls knowledge) pitfall-surface ns-token)
    ;; framework module table = installable catalogue modules ++ dev-tooling libs,
    ;; so every documented lib keeps a pointer (render-modules sorts by :name).
    :modules  (render-modules (concat modules (:dev-modules knowledge)))))

(defn render-target
  "Return the target file content with each owned section spliced in."
  [content knowledge modules {:keys [sections] :as opts}]
  (reduce (fn [doc section]
            (splice-region doc (name section)
                           (render-section section knowledge modules opts)))
          content sections))

(defn- generate-file [knowledge modules {:keys [file] :as target}]
  (let [current  (slurp file)
        rendered (render-target current knowledge modules target)]
    {:file file :current current :rendered rendered}))

(defn -main [& args]
  (let [check?   (some #{"--check"} args)
        knowledge (load-knowledge)
        modules   (load-modules)
        results   (map #(generate-file knowledge modules %) targets)]
    (if check?
      (run-check results modules knowledge)        ; defined in Tasks 9–10
      (do (doseq [{:keys [file rendered]} results] (spit file rendered))
          (println "agents:gen — wrote" (count results) "targets")))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
```

- [ ] **Step 4: Run the new unit test, verify pass.** (`run-check` is referenced only in the `--check` branch; the write path test does not exercise it.)

- [ ] **Step 5: Generate for real**

Run: `bb -e "(require 'agents-gen)(agents-gen/-main)"`
Then: `git diff --stat`
Expected: only `AGENTS.md` and `AGENTS.md.tmpl` change. Read both diffs and confirm:
- `AGENTS.md`: reflow only — no rule/pitfall/module lost; all 11 pitfalls present.
- `AGENTS.md.tmpl`: expect **genuine reword + reorder** of the pitfall prose (the
  template's 6 pitfalls converge onto the canonical framework wording and the
  knowledge.edn ordering) — this is intended, not a regression. Verify the **same
  6** downstream pitfalls remain (none lost) and `{{project-ns}}` is preserved.

- [ ] **Step 6: Verify links + idempotency**

Run: `bb check-links` → `Broken links: 0`.
Run the generator again; `git diff` → no further change (byte-stable).

- [ ] **Step 7: Commit**

```bash
git add scripts/agents_gen.clj scripts/agents_gen_test.clj AGENTS.md \
        libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl
git commit -m "feat(agents): render targets + first generation of both AGENTS files (BOU-95)"
```

---

## Task 9: `--check` drift detection (TDD)

**Files:** Modify `scripts/agents_gen.clj`, `scripts/agents_gen_test.clj`

- [ ] **Step 1: Failing test**

```clojure
(deftest check-results-detects-drift
  (let [in-sync   [{:file "A" :current "x" :rendered "x"}]
        drifted   [{:file "A" :current "x" :rendered "y"}]]
    (is (empty? (gen/drifted-files in-sync)))
    (is (= ["A"] (gen/drifted-files drifted)))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement** `drifted-files` and the check reporter (validation added in Task 10)

```clojure
(defn drifted-files
  "Return the seq of target files whose current content differs from rendered."
  [results]
  (->> results (remove #(= (:current %) (:rendered %))) (map :file)))

(defn run-check
  "Print drift + validation problems; System/exit 1 if any."
  [results modules knowledge]
  (let [drift   (drifted-files results)
        invalid (validate-modules modules knowledge)] ; Task 10
    (doseq [f drift] (println "✗ out of sync (run bb agents:gen):" f))
    (doseq [p invalid] (println "✗" p))
    (if (or (seq drift) (seq invalid))
      (System/exit 1)
      (println "✓ AGENTS files in sync; module catalogue valid"))))
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit** `feat(agents): --check drift detection (BOU-95)`

---

## Task 10: Module-source validation (allowlist + docs-url parsing) (TDD)

**Files:** Modify `scripts/agents_gen.clj`, `scripts/agents_gen_test.clj`

- [ ] **Step 1: Failing test**

```clojure
(deftest validate-modules-flags-missing-and-dead-links
  (with-redefs [gen/libs-with-agents (constantly #{"core" "user" "newlib" "tools"})]
    (let [modules [{:name "core" :docs-url "x/libs/core/AGENTS.md"}
                   {:name "user" :docs-url "x/libs/user/AGENTS.md"}
                   {:name "ghost" :docs-url "x/libs/ghost/AGENTS.md"}]
          knowledge {:dev-modules [{:name "tools"}]}   ; allowlist derived from :name
          problems (gen/validate-modules modules knowledge)]
      ;; newlib has AGENTS.md, not allowlisted, not in catalogue -> flagged
      (is (some #(str/includes? % "newlib") problems))
      ;; tools is allowlisted -> not flagged
      (is (not-any? #(str/includes? % "tools") problems))
      ;; ghost in catalogue but no libs/ghost dir -> dead docs link flagged
      (is (some #(str/includes? % "ghost") problems)))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement**

```clojure
(defn libs-with-agents
  "Set of lib names under libs/ that contain an AGENTS.md."
  []
  (->> (.listFiles (io/file "libs"))
       (filter #(.isDirectory %))
       (filter #(.exists (io/file % "AGENTS.md")))
       (map #(.getName %))
       set))

(defn- docs-url->lib
  "Parse the libs/<lib>/AGENTS.md suffix out of a GitHub docs URL."
  [url]
  (some-> (re-find #"libs/([^/]+)/AGENTS\.md" (str url)) second))

(defn validate-modules
  "Return a seq of human-readable problems. Empty seq = valid.
   1) Every libs/<lib> with an AGENTS.md (minus dev-modules) must be in the catalogue.
   2) Every catalogue :docs-url must resolve to an existing libs/<lib>/AGENTS.md."
  [modules {:keys [dev-modules]}]
  (let [allowlist  (set (map :name dev-modules))
        cat-names  (set (map :name modules))
        documented (libs-with-agents)
        missing    (remove allowlist (remove cat-names documented))
        dead       (for [m modules
                         :let [lib (docs-url->lib (:docs-url m))]
                         :when (and lib (not (.exists (io/file "libs" lib "AGENTS.md"))))]
                     (str "catalogue entry '" (:name m) "' docs-url points at missing libs/" lib "/AGENTS.md"))]
    (concat
     (map #(str "lib '" % "' has AGENTS.md but no modules-catalogue.edn entry (add it or allowlist it)") missing)
     dead)))
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Run check against the real repo**

Run: `bb -e "(require 'agents-gen)(agents-gen/-main \"--check\")"`
Expected: `✓ AGENTS files in sync; module catalogue valid`, exit 0. (If a real lib is unexpectedly flagged, either add it to the catalogue or to `:module-allowlist` in `knowledge.edn` — do not weaken the check.)

- [ ] **Step 6: Commit** `feat(agents): module-source validation (BOU-95)`

---

## Task 11: Wire bb tasks + `bb check` aggregate

**Files:**
- Modify: `bb.edn`
- Modify: `libs/tools/src/boundary/tools/check.clj`

- [ ] **Step 1: Add the `agents-gen` require + three tasks to `bb.edn`**

In the `:tasks` `:requires` vector, add:
```clojure
             [agents-gen :as agents-gen]
```
Add these tasks (near `check-links`):
```clojure
  agents:gen    {:doc "Generate AGENTS.md + AGENTS.md.tmpl from resources/agents/knowledge.edn (bb agents:gen [--check])"
                 :task (apply agents-gen/-main *command-line-args*)}
  check:agents  {:doc "Verify AGENTS files are in sync with knowledge.edn and the module catalogue is valid (bb check:agents)"
                 :task (agents-gen/-main "--check")}
  test:agents   {:doc "Run agents generator unit tests (bb test:agents)"
                 :task (do (require 'agents-gen-test)
                           (let [s (clojure.test/run-tests 'agents-gen-test)]
                             (when (pos? (+ (:fail s) (:error s))) (System/exit 1))))}
```

- [ ] **Step 2: Verify tasks resolve**

Run: `bb test:agents`
Expected: all generator tests pass, exit 0.
Run: `bb check:agents`
Expected: `✓ AGENTS files in sync; module catalogue valid`.

- [ ] **Step 3: Add `:agents` to the `all-checks` registry**

In `libs/tools/src/boundary/tools/check.clj`, add to the `all-checks` vector (after `:placeholder-tests`):
```clojure
   {:id    :agents
    :label "AGENTS.md drift"
    :cmd   ["bb" "check:agents"]}
```

- [ ] **Step 4: Verify the aggregate runs the new check**

Run: `bb check`
Expected: an "AGENTS.md drift ✓" line appears; overall exit 0.

- [ ] **Step 5: Commit**

```bash
git add bb.edn libs/tools/src/boundary/tools/check.clj
git commit -m "feat(agents): bb agents:gen / check:agents / test:agents + wire into bb check (BOU-95)"
```

---

## Task 12: Reduce both `CLAUDE.md` files to `@AGENTS.md` importer stubs

**Files:**
- Modify: `CLAUDE.md`
- Modify: `libs/boundary-cli/resources/boundary/cli/templates/CLAUDE.md.tmpl`

Claude Code auto-loads only `CLAUDE.md` (not `AGENTS.md`) but supports `@path` imports that expand at launch. Reduce both to stubs so AGENTS.md is the single source.

- [ ] **Step 1: Identify Claude-only content to preserve**

Scan the framework `CLAUDE.md` for content NOT already in `AGENTS.md` (e.g. the custom Kaocha test reporter note, `clj-nrepl-eval`/`clj-paren-repair` install instructions, build commands). For anything genuinely useful and missing from `AGENTS.md`, move it into `AGENTS.md` (outside `gen:*` markers) first, then re-run `bb agents:gen` and `bb check-links`.

- [ ] **Step 2: Rewrite framework `CLAUDE.md` as a stub**

```markdown
# CLAUDE.md

This project uses **AGENTS.md** as the single source of development guidance for
all coding agents (Claude Code, Cursor, etc.). Claude Code loads it via the import
below.

@AGENTS.md

## Claude Code specifics

<!-- Only notes that are Claude-Code-specific and not in AGENTS.md. -->
```

- [ ] **Step 3: Rewrite `CLAUDE.md.tmpl` as a stub**

```markdown
# CLAUDE.md

Built with the Boundary Framework. Development guidance lives in AGENTS.md
(shared by all coding agents); Claude Code loads it via the import below.

@AGENTS.md

## Claude Code specifics

The Boundary scaffolding toolkit skill is in `.claude/skills/boundary/SKILL.md`
— always prefer `bb scaffold` over hand-writing modules.
```

- [ ] **Step 4: Verify**

Run: `bb check-links` → `Broken links: 0`.
Run: `bb check:agents` → still in sync (CLAUDE.md is not a gen target, but confirm no accidental marker removal elsewhere).
Confirm neither stub still contains duplicated FC/IS rules tables or the kebab/snake/camel table.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md libs/boundary-cli/resources/boundary/cli/templates/CLAUDE.md.tmpl AGENTS.md
git commit -m "docs(agents): reduce CLAUDE.md files to @AGENTS.md importer stubs (BOU-95)"
```

---

## Task 13: Documentation — maintenance section, README, release note

**Files:**
- Modify: `AGENTS.md` (outside `gen:*` markers)
- Create: `resources/agents/README.md`

- [ ] **Step 1: Document the generation workflow in `AGENTS.md`**

Under "## Maintenance Notes" (or a new "## AGENTS.md generation" section, outside any `gen:*` marker), add:

```markdown
## AGENTS.md generation

FC/IS rules, naming conventions, pitfalls, and the module table in this file —
and the FC/IS / naming / pitfalls sections of the downstream
`libs/boundary-cli/.../AGENTS.md.tmpl` — are generated from
`resources/agents/knowledge.edn` (+ `modules-catalogue.edn` for modules).

- Regenerate:  `bb agents:gen`
- Verify sync: `bb check:agents`  (also part of `bb check` + CI)
- Add a pitfall / naming rule / FC/IS rule: edit `resources/agents/knowledge.edn`,
  then `bb agents:gen`.
- Add a library: add it to `modules-catalogue.edn` (or `:module-allowlist` in
  `knowledge.edn` for dev-only tooling), then `bb agents:gen`.
- **Regenerate before publishing `boundary-cli`** so downstream `boundary new`
  projects ship the current template.

The per-module AI doc generator (`bb ai docs --module libs/<x> --type agents`) is
separate and unchanged.
```

- [ ] **Step 2: Create `resources/agents/README.md`**

```markdown
# Agents knowledge source

`knowledge.edn` is the single structured source for Boundary's agent guardrails.
A deterministic generator (`scripts/agents_gen.clj`, `bb agents:gen`) renders it
into the framework root `AGENTS.md` and the downstream `AGENTS.md.tmpl`.

## Keys
- `:fc-is`    — layer/dependency rules (Functional Core / Imperative Shell)
- `:naming`   — kebab/snake/camel boundary conventions
- `:pitfalls` — common mistakes; each tagged `:surfaces #{:framework :downstream}`
- `:module-allowlist` — libs with an AGENTS.md that are NOT installable app modules

Module data comes from `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn`.

## Phase 2 — MCP server data contract
A future Boundary MCP guardrails server serves this same data, no schema change:

| MCP tool        | Source                            |
|-----------------|-----------------------------------|
| `list_modules`  | `modules-catalogue.edn :modules`  |
| `get_fc_is_rules` | `knowledge.edn :fc-is`          |
| `naming_rule`   | `knowledge.edn :naming`           |
| `lookup_pitfall`| `knowledge.edn :pitfalls`         |
```

- [ ] **Step 3: Verify links**

Run: `bb check-links` → `Broken links: 0`.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md resources/agents/README.md
git commit -m "docs(agents): document generation workflow + MCP data contract (BOU-95)"
```

---

## Task 14: Full verification + downstream smoke render

**Files:** none (verification only)

- [ ] **Step 1: Full quality gates**

Run: `bb check`
Expected: all checks ✓, incl. "AGENTS.md drift". Exit 0.

Run: `clojure -M:clj-kondo --lint scripts`
Expected: no errors in `agents_gen.clj` / `agents_gen_test.clj`.

- [ ] **Step 2: Generator idempotency on a clean tree**

Run: `bb agents:gen && git diff --quiet && echo CLEAN`
Expected: prints `CLEAN` (generation produces no diff on an already-generated tree).

- [ ] **Step 3: Downstream smoke render**

Run `boundary new` into a temp dir (e.g. `bb -cp libs/boundary-cli/src:libs/boundary-cli/resources -e "(require 'boundary.cli.new) ..."` or the documented `boundary new` entrypoint) and confirm:
- the generated project's `AGENTS.md` contains the FC/IS / naming / 6-pitfall content with `{{project-ns}}` correctly substituted to the project namespace;
- the generated `CLAUDE.md` contains `@AGENTS.md` and no duplicated guardrail prose.

Document the exact command used in the PR description.

- [ ] **Step 4: Run the tools test suite (regression)**

Run: `bb test:agents` and `clojure -M:test:db/h2 :tools` (if the tools Kaocha suite is affected) and `bb test:tools`.
Expected: green.

- [ ] **Step 5: Final commit (if any verification fixups)**

```bash
git add -A
git commit -m "test(agents): full verification + downstream smoke render (BOU-95)"
```

---

## Done criteria

- `bb agents:gen` renders both AGENTS files byte-stably from `knowledge.edn` + catalogue.
- `bb check:agents` (in `bb check` + CI) fails on drift or an undocumented library.
- Framework `AGENTS.md` shows all 11 pitfalls; downstream `AGENTS.md.tmpl` shows the 6 `:downstream` pitfalls with `{{project-ns}}`.
- Both `CLAUDE.md` files are `@AGENTS.md` stubs with no duplicated guardrails.
- `resources/agents/README.md` documents the Phase 2 MCP data contract.
- `bb check-links` passes; per-module AI generator untouched.
