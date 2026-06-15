# BOU-95 — First-class root AGENTS.md + MCP knowledge base

**Ticket:** [BOU-95](https://linear.app/boundary-app/issue/BOU-95) — Phase 1: Ship first-class AGENTS.md for Boundary itself
**Project:** Boundary MCP Server
**Date:** 2026-06-15
**Status:** Design approved

## Problem & Context

Claude Code / Cursor users should get good results out of the box from the Boundary
repo. A comprehensive root `AGENTS.md` (976 lines) and 26 per-library `AGENTS.md`
files already exist and pass `bb check-links`. A per-module AI generator
(`bb ai docs --type agents`) already exists in the `ai` library.

The gap is not greenfield authoring. It is:

1. **Drift.** Hand-maintained sections (the 26-library registry, FC/IS rules,
   naming conventions, the 11 pitfalls) rot as libraries and conventions change.
   Nothing enforces that a newly added library appears in the root doc.
2. **No deterministic root generator.** Generation exists only per-module and is
   AI-based; there is no repeatable, reviewed command for the root doc.
3. **No machine-consumable knowledge base.** The repo's guardrail knowledge
   (FC/IS contract, naming, pitfalls, module map) lives only as prose, so a future
   MCP guardrails server (Phase 2) has nothing structured to serve.

This phase ships the structured knowledge source + deterministic generator + a CI
drift guardrail, and lays the data contract base for the Phase 2 MCP server. **No
server code ships this phase.**

## Acceptance Criteria (from ticket)

- A maintained `AGENTS.md` at repo root capturing FC/IS contract, naming, module
  layout, and per-lib pointers. *(Already present; this phase makes the
  drift-prone parts generated + enforced.)*
- Generation command documented.

## Approved Decisions

- **MCP base shape:** a structured knowledge source (EDN). Generator renders it to
  root `AGENTS.md` now; a future MCP server serves the same data. No server code.
- **Generator model:** hybrid. The generator owns specific `<!-- gen:SECTION -->`
  marked regions; hand-written prose outside markers is untouched.
- **Sections owned by EDN:** module registry + pointers, FC/IS dependency rules,
  naming conventions, pitfalls catalogue.
- **Generator location:** a Babashka script in `scripts/` (matches
  `scripts/docs_lint.clj` precedent). Pure render functions + thin IO wrapper.
- **Out of scope:** per-module AI generator prompt polish; any MCP server code or
  skeleton.

## Components

### 1. Knowledge source — `resources/agents/knowledge.edn`

Single EDN file; the source of truth and the future MCP data contract.

```clojure
{:fc-is    {:layers   [{:from :shell :to :core  :allowed true}
                       {:from :core  :to :ports :allowed true}
                       {:from :shell :to :ports :allowed true}
                       {:from :core  :to :shell :allowed false
                        :reason "violates FC/IS"}]
            :rules    ["core/ must not import shell/IO/logging/DB"
                       "cross-module calls go through service ports"
                       "web/HTTP layers never require *.shell.persistence directly"]
            :ports-required true}
 :naming   [{:context :clojure :case :kebab :example ":password-hash"}
            {:context :db      :case :snake :example "password_hash"}
            {:context :api     :case :camel :example "passwordHash"}]
 :pitfalls [{:id "P01" :title "snake_case vs kebab-case mixing"
             :symptom "..." :cause "..." :fix "..."}
            ;; ... 11 total, lifted from current AGENTS.md prose
            ]
 :modules  [{:name "core" :purpose "Validation, case conversion, interceptor pipeline, feature flags"
             :agents "libs/core/AGENTS.md"}
            ;; ... 26 total
            ]}
```

- `:fc-is`, `:naming`, `:pitfalls` are hand-authored, lifting content verbatim
  from the current AGENTS.md prose where it already exists.
- `:modules` is a hand-curated one-line purpose per library (purpose is not cleanly
  auto-derivable), but the **set** of module names is validated against `libs/`
  (see drift guardrail).

### 2. Generator — `scripts/agents_gen.clj` (Babashka)

- **Pure render functions** (`knowledge -> markdown`), one per section:
  `render-modules`, `render-fc-is`, `render-naming`, `render-pitfalls`. No IO.
  Unit-testable in isolation.
- **IO wrapper:** read EDN, read `AGENTS.md`, splice each section's rendered
  markdown between its `<!-- gen:SECTION -->` / `<!-- /gen:SECTION -->` markers,
  write back. Idempotent (running twice produces no diff).
- **Modes:**
  - `bb agents:gen` — write the spliced doc.
  - `bb agents:gen --check` — render + diff only; non-zero exit on drift, no write.
- **Comparison is byte-exact.** Render functions emit canonical output (fixed
  spacing/ordering) so `--check` is a plain string equality on the spliced regions;
  no whitespace normalization. The "formatting-equivalent" diff in build step 4
  refers only to the one-time hand-edit → generated transition, after which the
  doc is byte-stable.

### 3. Marked regions in `AGENTS.md`

Wrap four existing sections in begin/end markers (one-time manual insert; generated
thereafter):

- Module registry — current "Library-Specific Guides" section.
- FC/IS dependency rules — current "Dependency Rules" / layer responsibilities.
- Naming conventions — current "ALWAYS Use kebab-case Internally" boundary table.
- Pitfalls catalogue — current "Common Pitfalls" (11 entries).

All other prose (workflows, debugging, build, REPL, git policy, etc.) stays
hand-maintained and outside markers.

### 4. Drift guardrail — `bb check:agents`, wired into `bb check`

`bb check:agents` does two things:

1. Runs `agents:gen --check` (AGENTS.md must match what the EDN renders).
2. **Registry validation** (bidirectional, keyed on presence of an `AGENTS.md`):
   - Every `libs/<lib>/` directory that **has** an `AGENTS.md` must appear in
     `:modules`. Catches "added a documented library, forgot to register it."
   - Every `:modules` entry's `:agents` path must point at an existing file.
   - Lib directories **without** an `AGENTS.md` are not in the registry and are
     not required to be (e.g. a lib that ships no agent guide). The 26 documented
     libs are the validated set today; the rule is presence-of-`AGENTS.md`, not a
     hardcoded count, so it self-adjusts as libs gain/lose guides.

Added to the `bb check` aggregate and CI. This is the repo-level "prevents error"
guardrail.

### 5. Documentation (satisfies "generation command documented")

- `AGENTS.md` "Maintenance Notes" + `CLAUDE.md`: document `bb agents:gen`,
  `bb agents:gen --check`, the location of `knowledge.edn`, and how to add a
  library or a pitfall.
- Reference the existing per-module AI generator (`bb ai docs --type agents`) as
  the per-library path — unchanged.

### 6. MCP base (no server this phase)

The `resources/agents/knowledge.edn` schema **is** the delivered base. Document the
intended MCP tool → EDN-key mapping in a short header comment and/or
`resources/agents/README.md`:

| MCP tool (Phase 2)   | EDN key     |
|----------------------|-------------|
| `list_modules`       | `:modules`  |
| `get_fc_is_rules`    | `:fc-is`    |
| `naming_rule`        | `:naming`   |
| `lookup_pitfall`     | `:pitfalls` |

Phase 2's server reads the same EDN; no schema change required to serve it.

## Testing

- **Unit (render fns):** each `render-*` produces expected markdown shape.
- **Idempotency:** generating twice yields no change.
- **Check-mode:** `--check` detects an injected drift (non-zero exit) and passes on
  a synced doc.
- **Registry validation:** fails when a `libs/*` lib is missing from `:modules`,
  and when `:modules` references a non-existent lib.
- **Regression:** `bb check-links` still passes after marker insertion.

## Out of Scope

- Per-module AI generator prompt polish (`libs/ai` docs wizard) — untouched.
- Any MCP server code or skeleton — Phase 2.

## Build Sequence

1. Insert markers into `AGENTS.md` around the four sections (no content change).
2. Author `resources/agents/knowledge.edn` from the now-marked content.
3. Write `scripts/agents_gen.clj` (pure render + IO splice + `--check`).
4. Run `bb agents:gen`; verify diff is only formatting-equivalent.
5. Add `bb agents:gen` / `agents:gen` and `bb check:agents` tasks to `bb.edn`;
   wire `check:agents` into `bb check` + CI.
6. Add tests.
7. Document commands in `AGENTS.md` + `CLAUDE.md`; add `resources/agents/README.md`
   with the MCP tool→key mapping.
8. Run full quality gates (`bb check`, `bb check-links`).
