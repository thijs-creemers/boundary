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
             :surfaces #{:framework :downstream}
             :symptom "..." :cause "..." :fix "..."}
            {:id "P11" :title "Swagger/OpenAPI params invisible without explicit declaration"
             :surfaces #{:framework}
             :symptom "..." :cause "..." :fix "..."}
            ;; ... lifted verbatim from current prose
            ]}
```

**Pitfall audience tagging.** Each pitfall carries `:surfaces`, a set drawn from
`#{:framework :downstream}`. There is one canonical entry per pitfall (single
wording), de-duplicating the two docs. The framework `AGENTS.md` renders every
pitfall; the downstream `AGENTS.md.tmpl` renders only those tagged `:downstream`.
The current downstream subset is the existing six: snake/kebab mixing, defrecord
restart, core→shell, missing `:type` in ex-info, validation in wrong layer, forward
references. Framework-only pitfalls (paren repair, schema-DB mismatch, Java interop,
reitit route format, swagger params) are tagged `#{:framework}`.

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
- **Targets** — a small table mapping `{file, sections, ns-token, pitfall-surface}`:
  - `AGENTS.md` — sections: fc-is, naming, pitfalls (all), modules; `ns-token` =
    `myapp`; pitfall-surface = `:framework`.
  - `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl` — sections:
    fc-is, naming, pitfalls (filtered to `:downstream`); `ns-token` =
    `{{project-ns}}`; pitfall-surface = `:downstream`.
  - The two `CLAUDE.md` files are **not** generated; see Component 3a.
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

### 3a. Reduce `CLAUDE.md` files to AGENTS.md importers

FC/IS + naming are currently duplicated a third and fourth time, in the framework
`CLAUDE.md` and the downstream `CLAUDE.md.tmpl`. **Verified behavior:** Claude Code
auto-loads only `CLAUDE.md`, not `AGENTS.md`, but `CLAUDE.md` supports an `@path`
import that expands the target into context at launch (max import depth 4; the
official docs recommend exactly this pattern for repos using `AGENTS.md`).

Therefore both `CLAUDE.md` files are reduced to thin stubs that import the
sibling `AGENTS.md` plus any genuinely Claude-specific notes:

```markdown
@AGENTS.md

## Claude Code specifics
<!-- only notes that do NOT belong in AGENTS.md -->
```

- Framework `CLAUDE.md` → `@AGENTS.md` + retain only Claude-Code-specific notes
  (e.g. the custom test reporter, clj-nrepl-eval/clj-paren-repair install) that are
  not in AGENTS.md, or move those into AGENTS.md and keep the stub minimal.
- Downstream `CLAUDE.md.tmpl` → `@AGENTS.md` + the existing Claude skill pointer.
  The rendered project's `CLAUDE.md` imports that project's own generated
  `AGENTS.md`.

This makes `AGENTS.md` the single source for both Claude Code and AGENTS.md-native
tools (Cursor/Windsurf), with **no** guardrail content left to drift in `CLAUDE.md`.
The `.claude/skills/boundary/SKILL.md` template (a scaffold command table, minimal
guardrail overlap) is left as-is. Import cost equals today's inline cost (loads at
launch either way).

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
   - Join key is the catalogue entry's `:name`, matched against `libs/<name>/`.
   - Every catalogue entry's `:docs-url` must resolve to an existing
     `libs/<lib>/AGENTS.md`. Note `:docs-url` is a full GitHub URL
     (e.g. `https://github.com/thijs-creemers/boundary/blob/main/libs/i18n/AGENTS.md`),
     so the validator parses the `libs/<lib>/AGENTS.md` suffix out of the URL —
     it does not treat the URL as a filesystem path.
   - The rule keys on presence-of-`AGENTS.md`, not a hardcoded count, so it
     self-adjusts as libraries gain or lose guides. (Current state: catalogue lists
     23 modules; 26 libs have an `AGENTS.md`; the 3-lib gap is exactly the
     framework-only allowlist below.)

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

## Notes & Constraints

- **`docs_lint` scope:** `scripts/docs_lint.clj` scans only `.md`/`.adoc` files, not
  `.tmpl`. The `{{project-ns}}` tokens in `AGENTS.md.tmpl` therefore raise no
  unknown-namespace false positives. No change needed. Confirmed against repo.
- **CLI release cadence:** the generated `AGENTS.md.tmpl` ships to downstream users
  only when `boundary-cli` is next published. `bb check:agents` guarantees the
  committed template is correct at all times; the release pipeline delivers it. One
  line in the docs should state this so maintainers regenerate before a CLI release.
- **No versions in the framework module render:** the framework root module table
  renders name + description + docs link only — no clojars coord / version — so it
  does not re-drift on every suite version bump. (Per-project clojars coords in the
  downstream `installed-modules` region are runtime-filled by `add.clj`, untouched.)
- **Deterministic alignment:** the module table is column-aligned ASCII. `render-modules`
  must compute column widths deterministically so output is byte-stable for
  `--check`.

## Testing

- **Unit (render fns):** each `render-*` produces expected markdown shape; verify
  `ns-token` substitution differs correctly between the two targets — assert the
  framework target never emits a `{{...}}` token and the template target always
  does. Verify pitfall filtering: framework renders all, downstream renders only
  `:downstream`-tagged entries.
- **Idempotency:** generating twice yields no change in either target file.
- **Check-mode:** `--check` detects an injected drift in either file (non-zero
  exit) and passes on synced files.
- **Module-source validation:** fails when a documented `libs/*` lib (not on the
  allowlist) is missing from the catalogue, and when a catalogue docs path is dead.
- **Marker safety:** generating does not alter content inside the template's
  `<!-- boundary:* -->` runtime regions.
- **CLAUDE.md import:** both stub `CLAUDE.md` files contain a valid `@AGENTS.md`
  import line and no leftover duplicated FC/IS / naming prose.
- **Regression:** `bb check-links` still passes after marker insertion in both
  files; a `boundary new` smoke render produces a project whose `CLAUDE.md` imports
  its `AGENTS.md` and whose `AGENTS.md` reads correctly.

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
   catalogue), target table, pitfall surface filtering, splice + `--check`,
   marker-safety for `boundary:*`.
4. Run `bb agents:gen`; verify diffs are only formatting-equivalent in both files.
5. Reduce framework `CLAUDE.md` and downstream `CLAUDE.md.tmpl` to `@AGENTS.md`
   importer stubs (move any Claude-only notes into AGENTS.md or keep minimal).
6. Add `agents:gen` task + `check:agents` task to `bb.edn`; wire `check:agents`
   into the `bb check` aggregate + CI.
7. Add tests (render, idempotency, check-mode, module-source validation, marker
   safety, pitfall filtering, CLAUDE.md import).
8. Document commands in framework `AGENTS.md`; add `resources/agents/README.md`
   with the MCP tool→source mapping; add the regenerate-before-CLI-release note.
9. Run full quality gates (`bb check`, `bb check-links`); sanity-check a
   `boundary new` render to confirm the downstream AGENTS.md reads correctly.
