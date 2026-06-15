# BOU-95 — First-class AGENTS.md: single knowledge source for framework + downstream projects

**Ticket:** [BOU-95](https://linear.app/boundary-app/issue/BOU-95) — Phase 1: Ship first-class AGENTS.md for Boundary itself
**Project:** Boundary MCP Server
**Date:** 2026-06-15
**Status:** Design approved

## Problem & Context

Boundary has **two** AGENTS.md surfaces, both important and both currently
hand-maintained as drift-prone prose:

1. **Framework repo root `AGENTS.md`** (976 lines) — guides contributors working on
   the Boundary monorepo itself.
2. **Downstream project template `AGENTS.md.tmpl`** (353 lines, in
   `libs/boundary-cli/resources/boundary/cli/templates/`) — shipped in the CLI jar
   and rendered by `boundary new` into every project built **with** Boundary. This
   is what a developer or agent sees when they start a real application.

The primary concern is **downstream correctness**: when someone runs `boundary new`,
the generated project must ship a correct, current AGENTS.md so the developer/agent
gets a good start. Today the template's FC/IS rules, naming conventions, and
pitfalls are hand-copied from the framework doc. When conventions evolve, the
template silently drifts and downstream projects ship stale guidance.

Some structure already exists and works well:
- 26 per-library `libs/*/AGENTS.md` files; `bb check-links` passes.
- A per-module **AI** generator (`bb ai docs --type agents`) — unchanged by this work.
- `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn` — a rich,
  structured module catalogue (name, description, clojars coord, version, category,
  config snippets, docs-url). It already drives the downstream template's
  `<!-- boundary:available-modules -->` / `<!-- boundary:installed-modules -->`
  marker regions at `boundary new` / `boundary add` runtime.

The gap is a **single structured source for the guardrail knowledge** (FC/IS,
naming, pitfalls) that both surfaces render from, plus a deterministic generator and
a CI drift guardrail. This also lays the machine-consumable data contract for the
Phase 2 MCP guardrails server. **No server code ships this phase.**

## Acceptance Criteria (from ticket)

- A maintained root `AGENTS.md` capturing FC/IS contract, naming, module layout, and
  per-lib pointers. *(Present today; this phase makes the drift-prone parts
  generated + enforced.)*
- Generation command documented.

Extended (per brainstorming): the **downstream** `AGENTS.md.tmpl` guardrail content
is generated from the same source, so `boundary new` projects always start correct.

## Approved Decisions

- **MCP base shape:** structured EDN knowledge source; generator renders it now, a
  future MCP server serves the same data. No server code this phase.
- **Generation model:** hybrid — generator owns `<!-- gen:SECTION -->` marked
  regions; hand-written prose outside markers untouched.
- **Two render targets, one source:** `knowledge.edn` renders into marked regions of
  BOTH the framework root `AGENTS.md` and the shipped `AGENTS.md.tmpl`.
- **Knowledge content:** `knowledge.edn` holds `:fc-is`, `:naming`, `:pitfalls`
  only. It does **not** hold a module list.
- **Module source:** the existing `modules-catalogue.edn` is the single module
  source for both surfaces. The framework root module table is generated from it
  too; the downstream runtime markers keep using it as today.
- **Generator location:** a Babashka script in `scripts/` (matches
  `scripts/docs_lint.clj`). Pure render functions + thin IO wrapper.
- **Out of scope:** per-module AI generator prompt polish; any MCP server code.

## Components

### 1. Knowledge source — `resources/agents/knowledge.edn`

The single source of truth for guardrail knowledge and the future MCP data contract.

```clojure
{:fc-is    {:layers   [{:from :shell :to :core  :allowed true}
                       {:from :core  :to :ports :allowed true}
                       {:from :shell :to :ports :allowed true}
                       {:from :core  :to :shell :allowed false
                        :reason "violates FC/IS"}
                       {:from :core  :to :io    :allowed false
                        :reason "even logging"}]
            :rules    ["core/ must not import shell/IO/logging/DB"
                       "cross-module calls go through service ports"
                       "web/HTTP layers never require *.shell.persistence directly"]
            :ports-required true}
 :naming   [{:context :clojure :case :kebab :example ":password-hash"}
            {:context :db      :case :snake :example "password_hash"}
            {:context :api     :case :camel :example "passwordHash"}]
 :pitfalls [{:id "P01" :title "snake_case vs kebab-case mixing"
             :symptom "..." :cause "..." :fix "..."}
            ;; ... lifted verbatim from current prose
            ]}
```

**Namespace token in examples.** Code examples that reference a namespace store a
sentinel token (e.g. `{{ns}}`) rather than a literal. At render time:
- framework `AGENTS.md` target → a concrete example ns (e.g. `myapp`);
- `AGENTS.md.tmpl` target → the literal `{{project-ns}}` so the CLI's own
  substitution fills it per project.

`:fc-is`, `:naming`, `:pitfalls` are hand-authored, lifting content verbatim from
the two existing docs where it already exists (de-duplicating into one source).

### 2. Generator — `scripts/agents_gen.clj` (Babashka)

- **Pure render functions** (`knowledge -> markdown`), one per section
  (`render-fc-is`, `render-naming`, `render-pitfalls`) plus `render-modules`
  (`catalogue -> markdown`). Each takes a `ns-token` argument so the same function
  serves both targets. No IO. Unit-testable.
- **Targets** — a small table mapping `{file, sections, ns-token}`:
  - `AGENTS.md` — sections: fc-is, naming, pitfalls, modules; `ns-token` = `myapp`.
  - `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl` — sections:
    fc-is, naming, pitfalls; `ns-token` = `{{project-ns}}`.
- **IO wrapper:** for each target, read the file, splice each owned section's
  rendered markdown between its `<!-- gen:SECTION -->` / `<!-- /gen:SECTION -->`
  markers, write back. Idempotent.
- **Modes:** `bb agents:gen` (write all targets); `bb agents:gen --check`
  (render + diff only, non-zero exit on drift, no write).
- **Comparison is byte-exact.** Render functions emit canonical output (fixed
  spacing/ordering); `--check` is plain string equality on the spliced regions. The
  "formatting-equivalent" note in the build sequence refers only to the one-time
  hand-edit → generated transition, after which both files are byte-stable.

**Marker coexistence (critical).** The generator's `<!-- gen:* -->` markers are
distinct from the CLI's runtime `<!-- boundary:available-modules -->` /
`<!-- boundary:installed-modules -->` markers in the template. The generator must
**only** touch `<!-- gen:* -->` regions and must never write inside the
`boundary:*` regions, which are filled per-project at `boundary new` / `boundary
add` runtime. The two marker namespaces never overlap.

### 3. Marked regions

**Framework root `AGENTS.md`** — wrap four existing sections (one-time manual
marker insert; generated thereafter):
- FC/IS dependency rules ("Dependency Rules" / layer responsibilities, line ~302)
- Naming conventions ("ALWAYS Use kebab-case Internally", line ~266)
- Pitfalls catalogue ("Common Pitfalls", 11 entries, line ~450)
- Module table ("Library-Specific Guides", line ~857) — generated from catalogue.

**`AGENTS.md.tmpl`** — wrap the three guardrail sections in `<!-- gen:* -->`
markers: FC/IS, naming, pitfalls. The module marker regions
(`<!-- boundary:* -->`) and all other template prose stay as today.

### 4. Drift guardrail — `bb check:agents`, wired into `bb check`

`bb check:agents` performs:

1. `agents:gen --check` — both `AGENTS.md` and `AGENTS.md.tmpl` must match what the
   sources render.
2. **Module-source validation** (bidirectional, keyed on presence of an `AGENTS.md`):
   - Every `libs/<lib>/` directory that **has** an `AGENTS.md` must have a
     `modules-catalogue.edn` entry, **unless** it is on an explicit framework-only
     allowlist (e.g. `tools`, `devtools`, `scaffolder` — dev/build tooling not
     published as an installable app module). The allowlist is declared in
     `knowledge.edn` (or alongside it) so the rule is data-driven, not hardcoded.
   - Every catalogue entry's `:docs-url` / docs path must resolve to an existing
     `libs/<lib>/AGENTS.md`.
   - The rule keys on presence-of-`AGENTS.md`, not a hardcoded count, so it
     self-adjusts as libraries gain or lose guides.

Wired into the `bb check` aggregate (subprocess registry in
`libs/tools/src/boundary/tools/check.clj`) and CI. This is the repo-level "prevents
error" guardrail — the practical correctness backstop for downstream projects.

### 5. Documentation (satisfies "generation command documented")

- Framework `AGENTS.md` "Maintenance Notes" + `CLAUDE.md`: document `bb agents:gen`,
  `bb agents:gen --check`, where `knowledge.edn` and `modules-catalogue.edn` live,
  the two render targets, and how to add a pitfall / naming rule / module.
- Reference the existing per-module AI generator (`bb ai docs --type agents`) as the
  per-library path — unchanged.

### 6. MCP base (no server this phase)

`resources/agents/knowledge.edn` (guardrails) + `modules-catalogue.edn` (modules)
are the delivered data base. Document the intended MCP tool → source-key mapping in
`resources/agents/README.md`:

| MCP tool (Phase 2)   | Source                          |
|----------------------|---------------------------------|
| `list_modules`       | `modules-catalogue.edn :modules`|
| `get_fc_is_rules`    | `knowledge.edn :fc-is`          |
| `naming_rule`        | `knowledge.edn :naming`         |
| `lookup_pitfall`     | `knowledge.edn :pitfalls`       |

Phase 2's server reads the same data; no schema change required to serve it.

## Testing

- **Unit (render fns):** each `render-*` produces expected markdown shape; verify
  `ns-token` substitution differs correctly between the two targets (`myapp` vs
  `{{project-ns}}`).
- **Idempotency:** generating twice yields no change in either target file.
- **Check-mode:** `--check` detects an injected drift in either file (non-zero
  exit) and passes on synced files.
- **Module-source validation:** fails when a documented `libs/*` lib (not on the
  allowlist) is missing from the catalogue, and when a catalogue docs path is dead.
- **Marker safety:** generating does not alter content inside the template's
  `<!-- boundary:* -->` runtime regions.
- **Regression:** `bb check-links` still passes after marker insertion in both files.

## Out of Scope

- Per-module AI generator prompt polish (`libs/ai` docs wizard) — untouched.
- Any MCP server code or skeleton — Phase 2.
- Changing the CLI's runtime module-marker filling (`add.clj`) — unchanged.

## Build Sequence

1. Insert `<!-- gen:* -->` markers into `AGENTS.md` (4 sections) and
   `AGENTS.md.tmpl` (3 sections) around current content — no content change yet.
2. Author `resources/agents/knowledge.edn` from the now-marked guardrail content,
   de-duplicating the two docs into one source; use the `{{ns}}` sentinel in
   examples.
3. Write `scripts/agents_gen.clj`: pure render fns (incl. `render-modules` from
   catalogue), target table, splice + `--check`, marker-safety for `boundary:*`.
4. Run `bb agents:gen`; verify diffs are only formatting-equivalent in both files.
5. Add `agents:gen` task + `check:agents` task to `bb.edn`; wire `check:agents`
   into the `bb check` aggregate + CI.
6. Add tests (render, idempotency, check-mode, module-source validation, marker
   safety).
7. Document commands in framework `AGENTS.md` + `CLAUDE.md`; add
   `resources/agents/README.md` with the MCP tool→source mapping.
8. Run full quality gates (`bb check`, `bb check-links`); sanity-check a
   `boundary new` render to confirm the downstream AGENTS.md reads correctly.
