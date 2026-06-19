# Zero-friction new-project DX — Design

**Date:** 2026-06-18
**Status:** Approved (design), pending implementation plan
**Area:** `libs/boundary-cli` (project generator + templates), `libs/boundary-mcp` (Clojars publish), `libs/tools/.../deploy.clj` (release pipeline)

## Goal

`boundary new <name>` should produce a project that is, with no manual wiring:

- a git repository with an initial commit and project-local pre-commit hook,
- **agent-wired** — the Boundary MCP server is live the moment the developer
  opens Claude Code or Cursor in the project,
- one obvious command (`bb quickstart`) away from a running app.

Today a new project ships the full `bb` task suite, `CLAUDE.md`, `AGENTS.md`, and
a Claude Code skill, but: it is not a git repo, has no MCP wiring, and the
post-create message points at a multi-step manual path. boundary-mcp's Tier 0/1/2
tools are now implemented (the `libs/boundary-mcp/AGENTS.md` "skeleton" note is
stale), so the server is ready to wire into new projects.

## Approach

**A — thin create, fat quickstart.** Everything fast and offline happens at create
time; everything network-bound, database-bound, or otherwise fallible is deferred
to the already-existing `bb quickstart` task (check env → configure → migrate →
optional first module → start), which the post-create message promotes.

Rationale: create stays fast and deterministic (seconds, no network), so the first
impression cannot half-fail on a slow maven resolve or a missing `JWT_SECRET`.
The slow/fallible steps live behind one promoted command.

Rejected alternatives:
- **B — fat create** (migrate + start inside `boundary new`): slow first run,
  needs `JWT_SECRET` sourced, can leave a half-initialised project on
  network/db failure — failure at first impression.
- **C — interactive create** (prompt for git/migrate/start): adds friction,
  breaks non-interactive/CI use, grows the `--yes`/`--minimal` flag matrix.

## MCP delivery decision

A new project must consume boundary-mcp the same way it consumes every other
Boundary library: as a **Clojars artifact resolved through `deps.edn`**. A
project must not reach into the install-time monorepo clone — that would couple
the project to CLI install internals. boundary-mcp is therefore **published to
Clojars** and launched from a dedicated `deps.edn` alias.

- boundary-mcp is added to the publish pipeline as
  `org.boundary-app/boundary-mcp` (see Unit 1).
- The project's `deps.edn` gains an **`:mcp` alias** (not a `:deps` entry). It must
  enumerate boundary-mcp's boundary closure that is NOT already in the project's
  default `:deps`, because published Boundary poms omit their boundary deps (see
  "Pom caveat" below). The default `:deps` already covers `core, observability,
  platform, user, ui-style, external, payments`; the closure additions are
  `ai, devtools, scaffolder, tools, jobs`:
  ```clojure
  :mcp {:extra-deps {org.boundary-app/boundary-mcp        {:mvn/version "<suite-version>"}
                     org.boundary-app/boundary-ai         {:mvn/version "<suite-version>"}
                     org.boundary-app/boundary-devtools   {:mvn/version "<suite-version>"}
                     org.boundary-app/boundary-scaffolder {:mvn/version "<suite-version>"}
                     org.boundary-app/boundary-tools      {:mvn/version "<suite-version>"}
                     org.boundary-app/boundary-jobs       {:mvn/version "<suite-version>"}}
        :main-opts  ["-m" "boundary.mcp.shell.server"]}
  ```
  `:extra-deps` composes with the project's `:deps`, so the libs already there
  satisfy the rest of the closure. All additions share the suite version.
- `.mcp.json` (committed, portable) runs `clojure -M:mcp` with cwd inherited
  from the editor (the project root).

Why an alias, not a `:deps` entry: the monorepo rule "applications never pull an
MCP server transitively" is preserved. The MCP server is absent from the default
classpath — `clojure -M:repl`, `-M:test`, and the app runtime never see it. Only
the explicit `clojure -M:mcp` (i.e. the editor launching the server) resolves it.
The version is pinned in `deps.edn` alongside the other Boundary libs and fetched
from Clojars into `~/.m2` exactly like them — no clone dependency, no
machine-specific path.

**Pom caveat (discovered during implementation):** `tools.build/write-pom`
skips `:local/root` coordinates, so every published Boundary lib's pom omits its
boundary dependencies. The framework already relies on each project's `deps.edn`
listing all app libs explicitly; transitive boundary-dep resolution via poms does
**not** work. Consequently the `:mcp` alias must enumerate mcp's boundary closure
itself (above), and this list is brittle — it must be re-derived if mcp's or its
deps' boundary dependencies change. A post-publish smoke test (run `clojure
-M:mcp` against a published artifact and confirm the server boots) is the guard
against drift. Fixing poms framework-wide was considered and rejected as
out-of-scope.

Trade-off accepted: boundary-mcp (an RCE-surface server, though env-gated to
`:read-only` outside local dev) becomes a public Clojars artifact and joins the
release pipeline. This is the deliberate cost of consuming it the framework's
standard way.

## Units of work

### Unit 1 — publish boundary-mcp to Clojars

boundary-mcp currently uses `:local/root` sibling deps (`../ai`, `../devtools`,
`../scaffolder`, `../tools`) — exactly the pattern `libs/user/deps.edn` already
uses for `boundary/platform`, which publishes fine via `tools.build`'s
`write-pom` (it resolves each `:local/root` dep to its published maven
coordinate in the generated pom). All four of mcp's sibling deps are already in
`all-libs` and published, so nothing blocks publishing mcp.

- **`libs/boundary-mcp/build.clj` already exists** and already mirrors
  `libs/user/build.clj` (coord `org.boundary-app/boundary-mcp`, version
  `1.0.1-alpha-32`, `clean`/`jar`/`install`/`deploy` via `b/write-pom` +
  `deps-deploy`); the `:build` alias is wired. This sub-step is **verify-only** —
  do not recreate it. Confirm `clojure -T:build jar` emits a pom with maven
  coords for the sibling deps.
- **Add `"boundary-mcp"` to `all-libs`** in
  `libs/tools/src/boundary/tools/deploy.clj`. `devtools` is currently the **last**
  entry; mcp's deps (`tools`, `scaffolder`, `ai`, `devtools`) must all precede it,
  so **append `"boundary-mcp"` at the very end of the vector** (after `devtools`).
- **Version sourcing for the template — mirror `boundary-tools-version`, NOT the
  catalogue.** boundary-mcp is dev tooling, not an addable app module; it must
  **not** go in `modules-catalogue.edn` (that would list it under
  `boundary add`/`boundary list modules` and fail `catalogue/validate-catalogue!`
  required-field checks). Instead, in `libs/boundary-cli/src/boundary/cli/new.clj`,
  add a private const next to the existing one
  (`(def ^:private boundary-mcp-version "1.0.1-alpha-32")`, new.clj:7) and a
  `:boundary-mcp-version boundary-mcp-version` entry in the `subs` map (new.clj:56).
  Bump it at release alongside `boundary-tools-version`.
- No code change to the server itself; it is launched via `clojure -M:mcp`
  (Unit 2), inheriting the editor's cwd (the project root) — exactly what the
  reflective resources need.

### Unit 2 — new project templates

`boundary new` renders these in addition to today's 14 files. New `.tmpl` files
under `libs/boundary-cli/resources/boundary/cli/templates/`, wired into the
generation map in `libs/boundary-cli/src/boundary/cli/new.clj`:

- **`.mcp.json`** at project root:
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
  Read natively by both Claude Code and Cursor. No `cwd` key → the client uses
  the workspace root (correct for reflection). Single root file, no
  `.cursor/mcp.json` duplicate.
- **`deps.edn.tmpl` — add the `:mcp` alias** (this is the one `deps.edn` change;
  the alias keeps mcp out of the default `:deps`, preserving "no transitive
  MCP"):
  ```clojure
  :mcp {:extra-deps {org.boundary-app/boundary-mcp {:mvn/version "{{boundary-mcp-version}}"}}
        :main-opts  ["-m" "boundary.mcp.shell.server"]}
  ```
- **`.vscode/extensions.json`** — recommends the relevant agent extension; a
  nudge, not configuration. No secrets, no settings.
- **`.githooks/pre-commit`** — project-local, runs the project's own tasks:
  ```bash
  #!/usr/bin/env bash
  set -euo pipefail
  bb check:fcis
  clojure -M:clj-kondo --lint src test 2>/dev/null || true
  ```
  Quick gate only — not `check:ports` / `check:deps` (noisier on a fresh
  project; the full suite belongs in CI).
- Confirm `gitignore.tmpl` ignores `.env` while leaving `.mcp.json`,
  `.vscode/`, and `.githooks/` committed. (Current `gitignore.tmpl` ignores only
  `.env`/`target/`/`.cpcache/` — no functional change needed, just confirmation.)
- **Executable bit:** `.githooks/pre-commit` must be written with `+x`. The
  current `new.clj` file writer uses plain `spit`, which does not set the
  executable bit; without it `git config core.hooksPath` silently no-ops the
  hook. The generator must `chmod +x` the rendered hook (or the bootstrap step
  in Unit 3 must set it after writing).

The MCP server stays out of the application runtime: it lives only in the `:mcp`
alias, so `clojure -M:repl` / `-M:test` and the built app never resolve it.

### Unit 3 — create-time bootstrap in `new.clj`

After files are rendered, unless `--skip-git` is passed:

1. `git init`
2. configure the project's hooks path to `.githooks` (inline the
   `git config core.hooksPath .githooks` step rather than calling the project's
   `bb install-hooks`, which would require the project's bb deps to resolve
   first).
3. `git add -A` and commit `"Initial commit (boundary new)"`.

- New flag: **`--skip-git`**.
- All git steps are **non-fatal**: if git is missing or any step fails, print a
  warning and continue. A created project must never be left half-broken because
  git was unavailable.

### Unit 4 — post-create message rewrite

Replace the `Next:` block in `new.clj` with the approach-A flow:

```
Next:
  cd <project-name>
  bb quickstart        # download deps, migrate, optional first module, start

Open Claude Code or Cursor here — the Boundary MCP server is already wired
(.mcp.json), so the agent has Boundary's tools the moment you start.
```

Keep the existing "optional modules" and "AI-ready" lines.

### Unit 5 — (removed)

The Clojars-alias delivery (Units 1–2) needs no change to `install.sh` or the
`boundary` wrapper. `clojure -M:mcp` resolves boundary-mcp from Clojars/`~/.m2`
independently of the CLI's cached clone. Nothing to do here.

### Unit 6 — docs

- Add a short "MCP / agent wiring" note to the new-project `AGENTS.md.tmpl`
  (manual prose section; the FC/IS / naming / pitfalls sections of that template
  are generated by `bb agents:gen` and must not be hand-edited).
- The `libs/boundary-mcp/AGENTS.md` "skeleton (BOU-96)" status note is stale and
  should be corrected to reflect Tier 0/1/2 shipped (small, in-scope cleanup).

## Testing

- **Publish (Unit 1):** `clojure -T:build jar` in `libs/boundary-mcp` produces a
  pom whose dependencies list the published `org.boundary-app/boundary-*`
  coordinates (not `:local/root`) — confirm by inspecting the generated pom.
  Verify `bb deploy --missing` (or a dry-run) includes `boundary-mcp` after its
  deps.
- **`new.clj`** (extend existing generation tests): assert `.mcp.json` (with the
  `clojure -M:mcp` command), the `:mcp` alias in the rendered `deps.edn`,
  `.githooks/pre-commit`, and `.vscode/extensions.json` are rendered with
  substitutions applied (`{{boundary-mcp-version}}` resolved); assert
  `--skip-git` skips git; assert a git failure is non-fatal (stub/force-fail
  git, expect project still created + warning).
- **Manual acceptance:** `boundary new demo && cd demo`; `clojure -M:mcp` boots
  the server (resolving mcp from `~/.m2`); open Claude Code; confirm a
  `tools/list` round-trip returns the Boundary tool catalogue and
  `boundary://conventions` resolves concretely (proving cwd = project root).

## Risks & mitigations

- **Release ordering:** boundary-mcp must publish *after* its deps
  (`ai`/`devtools`/`scaffolder`/`tools`) and a project's pinned
  `{{boundary-mcp-version}}` must exist on Clojars before `-M:mcp` will resolve.
  Mitigation: append-at-end ordering in `all-libs` (Unit 1); the
  `boundary-mcp-version` const is release-bumped alongside `boundary-tools-version`
  so the template pins a published version.
- **cwd contract:** if an editor launched the server with cwd ≠ project root, the
  reflective resources return `:unavailable`. `.mcp.json` omits `cwd`, so the
  client uses the workspace root — correct. Documented in the template note.
- **`-M:mcp` / `bb quickstart` first run is slow** (maven resolve): acceptable
  and expected; documented.

## Out of scope (YAGNI)

- Per-project MCP version pinning beyond the suite version (mcp tracks the
  suite release like the other libs).
- Auto-migrate or auto-start at `boundary new` time.
- Interactive prompts in `boundary new`.
- `.vscode/settings.json` with project settings or secrets.
- `check:ports` / `check:deps` in the project pre-commit hook.
