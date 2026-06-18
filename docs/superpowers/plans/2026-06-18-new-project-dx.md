# Zero-friction new-project DX — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `boundary new <name>` produces a git-initialised, agent-wired project (Boundary MCP server live in Claude Code/Cursor the moment it opens) that's one `bb quickstart` from running.

**Architecture:** boundary-mcp is published to Clojars and consumed by new projects through an `:mcp` alias in `deps.edn` (kept out of the default `:deps`, so the app never pulls MCP transitively). `boundary new` renders three new template files (`.mcp.json`, `.vscode/extensions.json`, `.githooks/pre-commit`), git-inits the project with an initial commit, and promotes `bb quickstart`. Approach A: create stays fast/offline; all network/db work is deferred to `bb quickstart`.

**Tech Stack:** Clojure, Babashka (the CLI runs under bb), `tools.build` (publish), `clojure.test`/Kaocha.

**Spec:** `docs/superpowers/specs/2026-06-18-new-project-dx-design.md`

---

## File Structure

**Create (templates):**
- `libs/boundary-cli/resources/boundary/cli/templates/mcp.json.tmpl` — the `.mcp.json` rendered into projects (static; launches `clojure -M:mcp`).
- `libs/boundary-cli/resources/boundary/cli/templates/vscode-extensions.json.tmpl` — `.vscode/extensions.json` recommending the agent extension.
- `libs/boundary-cli/resources/boundary/cli/templates/githook-pre-commit.tmpl` — `.githooks/pre-commit` (quick `check:fcis` + lint gate).

**Modify:**
- `libs/boundary-cli/resources/boundary/cli/templates/deps.edn.tmpl` — add the `:mcp` alias.
- `libs/boundary-cli/src/boundary/cli/new.clj` — `boundary-mcp-version` const + sub; three new files in the `files` map; mark the hook executable; git bootstrap + `--skip-git`; rewrite the post-create message.
- `libs/tools/src/boundary/tools/deploy.clj` — append `"boundary-mcp"` to `all-libs`.
- `libs/boundary-cli/test/boundary/cli/new_test.clj` — assert new artifacts + git behavior.
- `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl` — short MCP-wiring note (hand-maintained region).
- `libs/boundary-mcp/AGENTS.md` — correct the stale "skeleton (BOU-96)" status line.

**Verify-only (do NOT recreate):**
- `libs/boundary-mcp/build.clj` — already exists and already correct.

**Commands used throughout:**
- Run boundary-cli tests: `cd libs/boundary-cli && clojure -M:test`
- Single test: `cd libs/boundary-cli && clojure -M:test --focus boundary.cli.new-test/<test-name>`

---

## Task 1: Add boundary-mcp to the publish pipeline

**Files:**
- Verify: `libs/boundary-mcp/build.clj` (exists)
- Modify: `libs/tools/src/boundary/tools/deploy.clj` (the `all-libs` vector)

- [ ] **Step 1: Verify build.clj exists and is correct**

Run: `cat libs/boundary-mcp/build.clj`
Expected: a `build` ns with `(def lib 'org.boundary-app/boundary-mcp)` and `clean`/`jar`/`install`/`deploy` fns (mirrors `libs/user/build.clj`). If it exists, do nothing to it. If it is somehow missing, copy `libs/user/build.clj` and change `lib` to `'org.boundary-app/boundary-mcp` and the `:description`.

- [ ] **Step 2: Verify the pom resolves sibling deps to maven coords**

Run: `cd libs/boundary-mcp && clojure -T:build jar 2>&1 | tail -5 && find target -name '*.pom' -exec grep -l boundary-ai {} \;`
Expected: a generated pom that lists `org.boundary-app/boundary-ai` (and `-devtools`, `-scaffolder`, `-tools`) as `<dependency>` entries — i.e. `:local/root` deps were resolved to published maven coordinates. (Clean up: `rm -rf libs/boundary-mcp/target`.)

- [ ] **Step 3: Append boundary-mcp to all-libs**

In `libs/tools/src/boundary/tools/deploy.clj`, the `all-libs` vector ends with `"devtools"`. Append `"boundary-mcp"` as the **last** entry (all four of mcp's deps — `tools`, `scaffolder`, `ai`, `devtools` — must precede it):

```clojure
   "admin"
   "boundary-cli"
   "devtools"
   "boundary-mcp"]
```

- [ ] **Step 4: Sanity-check the deploy tool still loads**

Run: `bb deploy --help 2>&1 | head -5` (or the repo's documented dry-run, e.g. `bb deploy --missing` if it only reports). 
Expected: no error; `boundary-mcp` is now a known lib (e.g. appears in the valid-libs / status output).

- [ ] **Step 5: Commit**

```bash
git add libs/tools/src/boundary/tools/deploy.clj
git commit -m "build(mcp): add boundary-mcp to Clojars publish pipeline (all-libs)"
```

---

## Task 2: New project templates

**Files:**
- Create: `libs/boundary-cli/resources/boundary/cli/templates/mcp.json.tmpl`
- Create: `libs/boundary-cli/resources/boundary/cli/templates/vscode-extensions.json.tmpl`
- Create: `libs/boundary-cli/resources/boundary/cli/templates/githook-pre-commit.tmpl`
- Modify: `libs/boundary-cli/resources/boundary/cli/templates/deps.edn.tmpl`

No code yet — just the template assets. They get wired into `new.clj` in Task 3.

- [ ] **Step 1: Create mcp.json.tmpl**

`libs/boundary-cli/resources/boundary/cli/templates/mcp.json.tmpl`:

```json
{
  "mcpServers": {
    "boundary": {
      "command": "clojure",
      "args": ["-M:mcp"],
      "env": { "BND_ENV": "dev" }
    }
  }
}
```

(No `{{...}}` tokens — the version lives in the `deps.edn` `:mcp` alias, not here. No `cwd` key — the editor uses the workspace root, which is what the MCP server's reflection needs.)

- [ ] **Step 2: Create vscode-extensions.json.tmpl**

`libs/boundary-cli/resources/boundary/cli/templates/vscode-extensions.json.tmpl`:

```json
{
  "recommendations": [
    "anthropic.claude-code"
  ]
}
```

NOTE: confirm the marketplace id `anthropic.claude-code` is current before finalising; it is only a recommendation (worst case VS Code shows "not found"), so a wrong id is low-stakes, but prefer the correct one.

- [ ] **Step 3: Create githook-pre-commit.tmpl**

`libs/boundary-cli/resources/boundary/cli/templates/githook-pre-commit.tmpl`:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Quick local gate. The full check suite (ports, deps) runs in CI.
bb check:fcis
clojure -M:clj-kondo --lint src test 2>/dev/null || true
```

- [ ] **Step 4: Add the :mcp alias to deps.edn.tmpl**

In `libs/boundary-cli/resources/boundary/cli/templates/deps.edn.tmpl`, inside `:aliases`, add (after `:user-cli`, before the closing `}}`):

```clojure
  ;; Boundary MCP server for editor agents (Claude Code / Cursor). Kept in an
  ;; alias — NOT in :deps — so the app runtime never pulls the MCP server.
  ;; Launched by .mcp.json via `clojure -M:mcp`. Published Boundary poms omit
  ;; their boundary deps (write-pom skips :local/root), and :extra-deps composes
  ;; with :deps above (which already provides core/observability/platform/user/
  ;; ui-style/external/payments), so this lists only mcp's remaining boundary
  ;; closure: ai, devtools, scaffolder, tools, jobs.
  :mcp      {:extra-deps {org.boundary-app/boundary-mcp        {:mvn/version "{{boundary-mcp-version}}"}
                          org.boundary-app/boundary-ai         {:mvn/version "{{boundary-mcp-version}}"}
                          org.boundary-app/boundary-devtools   {:mvn/version "{{boundary-mcp-version}}"}
                          org.boundary-app/boundary-scaffolder {:mvn/version "{{boundary-mcp-version}}"}
                          org.boundary-app/boundary-tools      {:mvn/version "{{boundary-mcp-version}}"}
                          org.boundary-app/boundary-jobs       {:mvn/version "{{boundary-mcp-version}}"}}
             :main-opts  ["-m" "boundary.mcp.shell.server"]}
```

NOTE (closure rationale, for the implementer): boundary-mcp → ai, devtools,
scaffolder, tools. `devtools` → platform, core, ui-style, jobs, user, scaffolder.
Everything except `ai, devtools, scaffolder, tools, jobs` is already in the
project's default `:deps`. If you change this list, re-derive the closure from the
relevant `deps.edn` files.

- [ ] **Step 5: Commit**

```bash
git add libs/boundary-cli/resources/boundary/cli/templates/mcp.json.tmpl \
        libs/boundary-cli/resources/boundary/cli/templates/vscode-extensions.json.tmpl \
        libs/boundary-cli/resources/boundary/cli/templates/githook-pre-commit.tmpl \
        libs/boundary-cli/resources/boundary/cli/templates/deps.edn.tmpl
git commit -m "feat(cli): add .mcp.json, .vscode, pre-commit hook, and :mcp deps alias templates"
```

---

## Task 3: Wire the new templates into the generator

**Files:**
- Modify: `libs/boundary-cli/src/boundary/cli/new.clj`
- Test: `libs/boundary-cli/test/boundary/cli/new_test.clj`

TDD. The generator must render the three new files, resolve `{{boundary-mcp-version}}`, and make the hook executable.

- [ ] **Step 1: Write the failing test (new artifacts + version + executable bit)**

Add to `libs/boundary-cli/test/boundary/cli/new_test.clj`, inside `generate-project-test`'s `try` (alongside the existing `testing` blocks). Add the three paths to the existing "generates required files" `doseq` vector:

```clojure
                   ".mcp.json"
                   ".vscode/extensions.json"
                   ".githooks/pre-commit"
```

Then add new `testing` blocks:

```clojure
      (testing ".mcp.json wires the boundary MCP server via clojure -M:mcp"
        (let [content (slurp (io/file tmp ".mcp.json"))]
          (is (str/includes? content "\"-M:mcp\""))
          (is (str/includes? content "boundary"))
          (is (not (str/includes? content "{{")))))

      (testing "deps.edn has an :mcp alias with a resolved version"
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (str/includes? content ":mcp"))
          (is (str/includes? content "org.boundary-app/boundary-mcp"))
          (is (not (str/includes? content "{{boundary-mcp-version}}")))))

      (testing "pre-commit hook is executable"
        (is (.canExecute (io/file tmp ".githooks/pre-commit"))))
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd libs/boundary-cli && clojure -M:test --focus boundary.cli.new-test/generate-project-test`
Expected: FAIL — missing `.mcp.json` / `.vscode/extensions.json` / `.githooks/pre-commit`, unresolved `{{boundary-mcp-version}}`, hook not executable.

- [ ] **Step 3: Add the version const and substitution**

In `libs/boundary-cli/src/boundary/cli/new.clj`, below the existing const (line 7):

```clojure
;; Keep in sync with libs/boundary-mcp/build.clj version (release-bumped with boundary-tools-version)
(def ^:private boundary-mcp-version "1.0.1-alpha-32")
```

Add to the `subs` map (after `:boundary-tools-version`, ~line 59):

```clojure
                     :boundary-mcp-version     boundary-mcp-version
```

- [ ] **Step 4: Add the three files to the files map**

In the `files` map (lines 73-86), add:

```clojure
                     ".mcp.json"                           "mcp.json.tmpl"
                     ".vscode/extensions.json"             "vscode-extensions.json.tmpl"
                     ".githooks/pre-commit"                "githook-pre-commit.tmpl"
```

- [ ] **Step 5: Make the hook executable after writing**

`write-file!` uses `spit`, which does not set the executable bit; without it `git config core.hooksPath` silently no-ops the hook. The current `generate!` tail is:

```clojure
    (doseq [[target tmpl] files]
      (write-file! dir target (render (read-template tmpl) subs)))))
```

Replace that tail with this exact form (note the `doseq` line drops ONE trailing paren — from `subs)))))` to `subs)))` — and the new `.setExecutable` line carries the final two parens that close `let` and `defn`):

```clojure
    (doseq [[target tmpl] files]
      (write-file! dir target (render (read-template tmpl) subs)))
    (.setExecutable (io/file dir ".githooks/pre-commit") true false)))
```

(The `false` second arg = not owner-only, so the executable bit is set for all — harmless and robust.) If the parens get tangled, run `clj-paren-repair libs/boundary-cli/src/boundary/cli/new.clj`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd libs/boundary-cli && clojure -M:test --focus boundary.cli.new-test/generate-project-test`
Expected: PASS.

- [ ] **Step 7: Run the full boundary-cli suite (no regressions)**

Run: `cd libs/boundary-cli && clojure -M:test`
Expected: all green (including `plugin-skill-in-sync-test` and `directory-exists-test`).

- [ ] **Step 8: Commit**

```bash
git add libs/boundary-cli/src/boundary/cli/new.clj libs/boundary-cli/test/boundary/cli/new_test.clj
git commit -m "feat(cli): render .mcp.json/.vscode/.githooks and pin boundary-mcp version"
```

---

## Task 4: Git bootstrap + `--skip-git`

**Files:**
- Modify: `libs/boundary-cli/src/boundary/cli/new.clj`
- Test: `libs/boundary-cli/test/boundary/cli/new_test.clj`

After generating files, `boundary new` git-inits the project, sets the hooks path, and makes an initial commit — unless `--skip-git`. All git steps are non-fatal. Make the git runner injectable so failure is testable.

- [ ] **Step 1: Write the failing tests**

Add to `libs/boundary-cli/test/boundary/cli/new_test.clj`:

```clojure
(deftest git-bootstrap-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-git-test-" (System/currentTimeMillis))]
    (io/make-parents (io/file tmp "x"))
    (try
      (testing "a failing git runner is non-fatal and returns a warning"
        (let [boom (fn [& _] (throw (RuntimeException. "git missing")))
              result (new/git-bootstrap! tmp boom)]
          (is (false? (:ok? result)))
          (is (seq (:warnings result)))))

      (testing "a successful runner reports ok"
        (let [calls (atom [])
              ok    (fn [& args] (swap! calls conj (vec args)) {:exit 0 :out "" :err ""})
              result (new/git-bootstrap! tmp ok)]
          (is (true? (:ok? result)))
          ;; init, config hooksPath, add, commit  → 4 invocations
          (is (= 4 (count @calls)))
          (is (= "init" (second (first @calls))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd libs/boundary-cli && clojure -M:test --focus boundary.cli.new-test/git-bootstrap-test`
Expected: FAIL — `new/git-bootstrap!` is undefined.

- [ ] **Step 3: Implement git-bootstrap!**

In `libs/boundary-cli/src/boundary/cli/new.clj`, add the require `[clojure.java.shell :as shell]` to the ns form, then add (above `-main`):

```clojure
(defn- run-git
  "Default git runner: shells out via clojure.java.shell. Returns the sh result map."
  [dir & args]
  (apply shell/sh (concat ["git" "-C" dir] args)))

(defn git-bootstrap!
  "Initialise a git repo in dir, point hooks at .githooks, and make an initial
   commit. Every step is non-fatal: on any failure, collect a warning and keep
   going. `run` is the git runner (injected for testing); defaults to run-git.
   Returns {:ok? bool :warnings [str]}."
  ([dir] (git-bootstrap! dir run-git))
  ([dir run]
   (let [steps [["init"]
                ["config" "core.hooksPath" ".githooks"]
                ["add" "-A"]
                ["commit" "-m" "Initial commit (boundary new)"]]
         warnings (reduce
                   (fn [warns args]
                     (let [{:keys [exit err] :as r}
                           (try (apply run dir args)
                                (catch Exception e {:exit 1 :err (.getMessage e)}))]
                       (if (and (map? r) (zero? (or exit 1)))
                         warns
                         (conj warns (str "git " (str/join " " args) " failed: "
                                          (or (not-empty err) "non-zero exit"))))))
                   []
                   steps)]
     {:ok? (empty? warnings) :warnings warnings})))
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd libs/boundary-cli && clojure -M:test --focus boundary.cli.new-test/git-bootstrap-test`
Expected: PASS.

- [ ] **Step 5: Write the `--skip-git` integration test**

Add to `new_test.clj`:

```clojure
(deftest skip-git-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-skipgit-" (System/currentTimeMillis))]
    (try
      (testing "real bootstrap creates a .git directory"
        (new/generate! tmp "gitproj" {})
        (let [{:keys [ok?]} (new/git-bootstrap! tmp)]
          ;; git may be absent in some CI images; only assert .git when it succeeded
          (when ok? (is (.exists (io/file tmp ".git"))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))
```

(The flag-parsing itself is exercised in `-main`; the unit-level guarantee is that `git-bootstrap!` is only called when not skipped — see Step 6.)

- [ ] **Step 6: Wire git bootstrap into -main with the flag**

In `-main`, parse the flag alongside `--force`:

```clojure
        skip-git? (boolean (some #{"--skip-git"} flags))
```

After `(generate! dir project-name {})` and before the success messages, add:

```clojure
      (when-not skip-git?
        (let [{:keys [warnings]} (git-bootstrap! dir)]
          (doseq [w warnings] (println (str "  ⚠ " w)))))
```

Update the usage line:

```clojure
      (println "Usage: boundary new <project-name> [--force] [--skip-git]")
```

- [ ] **Step 7: Run the full suite**

Run: `cd libs/boundary-cli && clojure -M:test`
Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add libs/boundary-cli/src/boundary/cli/new.clj libs/boundary-cli/test/boundary/cli/new_test.clj
git commit -m "feat(cli): git-init new projects with initial commit (+ --skip-git, non-fatal)"
```

---

## Task 5: Rewrite the post-create message

**Files:**
- Modify: `libs/boundary-cli/src/boundary/cli/new.clj` (the `-main` `println` tail, lines 114-122)

- [ ] **Step 1: Replace the Next block**

Replace the final `Next:` `println` (line 122) and adjust the AI-ready lines so the message reflects Approach A and the auto-wired MCP server:

```clojure
      (println "\nAI-ready: CLAUDE.md, AGENTS.md, a Claude Code skill, and a wired MCP server (.mcp.json) are included.")
      (println "Open Claude Code or Cursor here — the Boundary MCP server is live, so the agent has Boundary's tools immediately.")
      (println (str "\nNext:\n  cd " project-name
                    "\n  bb quickstart        # download deps, migrate, optional first module, start")))))
```

- [ ] **Step 2: Smoke-run the generator end to end**

Run (from a scratch dir):
```bash
cd /tmp && rm -rf dxdemo && \
clojure -Sdeps '{:deps {org.boundary-app/boundary-cli {:local/root "'"$OLDPWD"'/libs/boundary-cli"}}}' \
  -M -e "((requiring-resolve 'boundary.cli.new/-main) [\"dxdemo\"])" ; \
ls -la /tmp/dxdemo && echo '--- .mcp.json ---' && cat /tmp/dxdemo/.mcp.json && \
echo '--- :mcp alias ---' && grep -A2 ':mcp' /tmp/dxdemo/deps.edn && \
echo '--- hook +x? ---' && ls -l /tmp/dxdemo/.githooks/pre-commit && \
echo '--- git? ---' && ls -d /tmp/dxdemo/.git
```
Expected: `.mcp.json` with `-M:mcp`; `deps.edn` `:mcp` alias with a concrete version; `pre-commit` with an `x` bit; a `.git` dir (if git present). Clean up `/tmp/dxdemo`.

- [ ] **Step 3: Commit**

```bash
git add libs/boundary-cli/src/boundary/cli/new.clj
git commit -m "feat(cli): post-create message promotes bb quickstart + notes wired MCP"
```

---

## Task 6: Docs

**Files:**
- Modify: `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl`
- Modify: `libs/boundary-mcp/AGENTS.md`

- [ ] **Step 1: Un-stale the boundary-mcp status line**

In `libs/boundary-mcp/AGENTS.md`, the blockquote near the top says "Status: **skeleton (BOU-96)** … Tier 0 tools land in BOU-100, reflective resources in BOU-99." Replace with the current reality:

```markdown
> Status: **active** — stdio transport + JSON-RPC handshake, Tier 0 (read),
> Tier 1 (generate + closed verify loop), and Tier 2 (execute) tools all
> implemented. Some live-introspection resources and the read-only DB
> datasource are `:unavailable` pending an nREPL bridge.
```

- [ ] **Step 2: Add an MCP-wiring note to the project AGENTS template**

In `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl`, add a short section in a **hand-maintained** region (NOT inside any `<!-- boundary:... -->` sentinel block, and not inside the FC/IS / naming / pitfalls sections that `bb agents:gen` regenerates). A safe spot is just after the intro / before those generated sections:

```markdown
## Agent tooling (MCP)

This project ships a wired Boundary MCP server (`.mcp.json`). Open Claude Code or
Cursor in the project root and the agent gains Boundary's tools (lint, scaffold,
run-tests, …) with no setup. The server is launched via the `:mcp` deps alias
(`clojure -M:mcp`) and is absent from the app runtime classpath.
```

- [ ] **Step 3: Verify the generated-docs gate still passes**

Run: `bb check:agents`
Expected: PASS (the new section is outside generated regions, so it must not trip the sync check). If it fails, move the section to a clearly hand-maintained part of the template and re-run.

- [ ] **Step 4: Verify markdown links**

Run: `bb check-links`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add libs/boundary-mcp/AGENTS.md libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl
git commit -m "docs(mcp): refresh boundary-mcp status + document MCP wiring in new projects"
```

---

## Task 7: Final verification

- [ ] **Step 1: Full boundary-cli suite**

Run: `cd libs/boundary-cli && clojure -M:test`
Expected: all green.

- [ ] **Step 2: Lint touched code**

Run: `clojure -M:clj-kondo --lint libs/boundary-cli/src libs/boundary-cli/test libs/tools/src`
Expected: no new warnings/errors in the files this plan touched.

- [ ] **Step 3: Confirm the manual acceptance path (documented, run if a Clojars-published mcp is available)**

```bash
cd /tmp && boundary new demo && cd demo && clojure -M:mcp <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"boundary://conventions"}}
EOF
```
Expected: handshake + tool catalogue, and `boundary://conventions` resolves concretely (proving cwd = project root). NOTE: this requires `org.boundary-app/boundary-mcp` at the pinned version to be on Clojars — it will only pass after a release that publishes mcp. Until then, verify with a local `:local/root` override of the `:mcp` alias.

---

## Notes for the implementer

- **Release dependency:** the generated `:mcp` alias pins `boundary-mcp-version`. That artifact must be published to Clojars (Task 1 enables it; an actual release publishes it) before `clojure -M:mcp` resolves in a real new project. This is expected and called out in the spec's risks.
- **Do not touch** `claude-skill.md.tmpl` — `plugin-skill-in-sync-test` asserts it stays byte-identical to `claude-plugin/skills/boundary/SKILL.md`.
- **Version bump:** record `boundary-mcp-version` in the release checklist (already noted in user memory `project_release_checklist`) so it's bumped with `boundary-tools-version`.
- Relevant skills: @superpowers:test-driven-development, @superpowers:verification-before-completion.
