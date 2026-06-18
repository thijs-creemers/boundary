# Zero-friction new-project DX — Design

**Date:** 2026-06-18
**Status:** Approved (design), pending implementation plan
**Area:** `libs/boundary-cli` (project generator + CLI), `scripts/install.sh`, `libs/boundary-mcp` (launch path)

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

boundary-mcp is **not** published to Clojars (it is dev tooling, like
`libs/tools`) and a committed `.mcp.json` must not contain a machine-specific
absolute path. Chosen mechanism: a **`boundary mcp` subcommand**.

- `.mcp.json` (committed, portable) runs `boundary mcp`.
- `boundary mcp` resolves the cached-clone root and execs the JVM Clojure MCP
  server out-of-process, inheriting the editor-provided cwd (the project root).
- No Clojars publish; no app-level dependency on the MCP server (the monorepo
  rule "applications never pull an MCP server transitively" is preserved — the
  server is launched as a separate process by the `boundary` wrapper, not added
  to the project's `deps.edn`).

Trade-off accepted: the MCP version tracks the installed CLI (cached clone),
not the project's declared boundary version. Acceptable now; a Clojars publish
with per-project version pinning is the future option if that gap matters.

## Install topology (context)

`scripts/install.sh` clones the monorepo at the latest release tag to
`$HOME/.boundary/releases/<tag>` (`BOUNDARY_CACHE`) and writes a wrapper at
`$HOME/.babashka/bbin/bin/boundary`:

```bash
exec bb --classpath "$BOUNDARY_CACHE/libs/boundary-cli/src:$BOUNDARY_CACHE/libs/boundary-cli/resources" -m boundary.cli.main "$@"
```

The cached clone contains every `libs/*`, so `libs/boundary-mcp` and its sibling
`:local/root` deps (`../ai`, `../devtools`, `../scaffolder`, `../tools`) are all
present and resolvable from there.

## Units of work

### Unit 1 — `boundary mcp` subcommand

- **New file** `libs/boundary-cli/src/boundary/cli/mcp.clj` with a `-main`.
- Wire a `"mcp"` case into `libs/boundary-cli/src/boundary/cli/main.clj`'s
  dispatch and add it to the `usage` text.
- Home resolution, in order:
  1. `BOUNDARY_HOME` env var (set by the install.sh wrapper — see Unit 5).
  2. Fallback: derive the clone root from the CLI's own loaded resource location
     (`io/resource "boundary/cli/templates/…"` → walk up to the clone root).
  3. If neither resolves → print a clear error to **stderr** and exit non-zero.
     (stdout is the MCP protocol stream and must never carry diagnostics.)
- Exec (replacing the process, inheriting cwd + env):
  ```
  clojure -Sdeps '{:deps {boundary/mcp {:local/root "<home>/libs/boundary-mcp"}}}' \
    -M -m boundary.mcp.shell.server
  ```
  Uses the real `clojure` CLI + JVM (the MCP server is JVM Clojure: clj-kondo,
  scaffolder, etc.), not bb. Uses direct `-M -m boundary.mcp.shell.server`
  rather than boundary-mcp's `:run` alias deliberately: `-Sdeps` supplies the
  dep, and the launch must not depend on an alias being present in whatever
  `deps.edn` sits in the cwd (the project root). The arbitrary `boundary/mcp`
  coordinate name is irrelevant — only the `:local/root` path matters.
- cwd is inherited from the launching editor; the MCP client sets it to the
  workspace (project) root, which is exactly what the reflective resources need.

### Unit 2 — new project templates

`boundary new` renders these in addition to today's 14 files. New `.tmpl` files
under `libs/boundary-cli/resources/boundary/cli/templates/`, wired into the
generation map in `libs/boundary-cli/src/boundary/cli/new.clj`:

- **`.mcp.json`** at project root:
  ```json
  {
    "mcpServers": {
      "boundary": {
        "command": "boundary",
        "args": ["mcp"],
        "env": { "BND_ENV": "dev" }
      }
    }
  }
  ```
  Read natively by both Claude Code and Cursor. No `cwd` key → the client uses
  the workspace root (correct for reflection). Single root file, no
  `.cursor/mcp.json` duplicate.
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

No change to `deps.edn.tmpl` — the MCP server is launched out-of-process, never
added as an application dependency.

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

### Unit 5 — install.sh wrapper exports `BOUNDARY_HOME`

In `scripts/install.sh`, the generated `boundary` wrapper gains an export so
Unit 1's primary resolution path works:

```bash
cat > "$BBIN_BIN/boundary" << EOF
#!/usr/bin/env bash
export BOUNDARY_HOME="$BOUNDARY_CACHE"
exec bb --classpath "$BOUNDARY_CACHE/libs/boundary-cli/src:$BOUNDARY_CACHE/libs/boundary-cli/resources" -m boundary.cli.main "\$@"
EOF
```

Because `install.sh` is also synced to boundary-app.org (per the
`boundary-website-release` flow), that sync must pick up this change.

### Unit 6 — docs

- Add a short "MCP / agent wiring" note to the new-project `AGENTS.md.tmpl`
  (manual prose section; the FC/IS / naming / pitfalls sections of that template
  are generated by `bb agents:gen` and must not be hand-edited).
- The `libs/boundary-mcp/AGENTS.md` "skeleton (BOU-96)" status note is stale and
  should be corrected to reflect Tier 0/1/2 shipped (small, in-scope cleanup).

## Testing

- **`boundary.cli.mcp`** (new unit tests): home resolution for env-present,
  env-absent-with-derivable-resource, and fully-unresolvable cases; the
  constructed command vector; exec stubbed.
- **`new.clj`** (extend existing generation tests): assert `.mcp.json`,
  `.githooks/pre-commit`, and `.vscode/extensions.json` are rendered with
  substitutions applied; assert `--skip-git` skips git; assert a git failure is
  non-fatal (stub/force-fail git, expect project still created + warning).
- **Manual acceptance:** `boundary new demo && cd demo`; open Claude Code;
  confirm a `tools/list` round-trip against the auto-wired server returns the
  Boundary tool catalogue, and that `boundary://conventions` resolves concretely
  (proving cwd = project root).

## Risks & mitigations

- **`BOUNDARY_HOME` unset** for users who installed before this change → Unit 1's
  classpath-derivation fallback covers them; document re-running `install.sh` to
  refresh the wrapper.
- **cwd contract:** if an editor launched the server with cwd ≠ project root, the
  reflective resources return `:unavailable`. `.mcp.json` omits `cwd`, so the
  client uses the workspace root — correct. Documented in the template note.
- **`bb quickstart` first run is slow** (maven resolve): acceptable and expected;
  it is the one promoted command and clearly labelled.

## Out of scope (YAGNI)

- Publishing boundary-mcp to Clojars / per-project MCP version pinning.
- Auto-migrate or auto-start at `boundary new` time.
- Interactive prompts in `boundary new`.
- `.vscode/settings.json` with project settings or secrets.
- `check:ports` / `check:deps` in the project pre-commit hook.
