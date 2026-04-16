# Boundary Framework — Developer Summary

A module-centric Clojure framework implementing the Functional Core / Imperative Shell (FC/IS) pattern.
Each domain module owns its full stack: pure business logic in `core/`, side effects in `shell/`, protocol interfaces in `ports.clj`.

This page is a thin entry point. To keep a single source of truth, details live in the docs listed under **Where to look**. Update only the sections below; update the referenced docs for everything else.

---

## At a glance

- **22 domain libraries** under `libs/`, plus `boundary-tools/` (dev tooling, published separately) and `libs/e2e/` (Playwright/spel, opt-in).
- **FC/IS enforced in CI** via six automated quality gates.
- **Primary stack**: Clojure 1.12 · Integrant · Aero · Ring/Reitit · next.jdbc + HoneySQL · Malli · Buddy · Hiccup/HTMX · Kaocha · clj-kondo.

---

## Where to look

| I need… | Go to |
|---------|-------|
| Project intro, installation, quick start | [../README.md](../README.md) |
| Full command reference, conventions, pitfalls, debugging | [../AGENTS.md](../AGENTS.md) |
| Library-specific guidance (one per library) | `libs/{lib}/AGENTS.md` (22 total) |
| Architecture, tutorials, API reference | [../docs-site/](../docs-site/) and [../docs/](../docs/) |
| Internal/dev reference docs (publishing, ADRs, etc.) | [../dev-docs/](../dev-docs/) |
| Current version, module status, CI coverage, roadmap progress | [PROJECT_STATUS.adoc](PROJECT_STATUS.adoc) |
| Release history | [../CHANGELOG.md](../CHANGELOG.md) |
| Claude Code working notes for this repo | [../CLAUDE.md](../CLAUDE.md) |
| Roadmap 2026–2027 | [Boundary-Framework-Roadmap-2026-2027.adoc](Boundary-Framework-Roadmap-2026-2027.adoc) |
| Strategic extensions | [Boundary-Framework-Strategische-Uitbreidingen.adoc](Boundary-Framework-Strategische-Uitbreidingen.adoc), [Addendum](Boundary-Framework-Strategische-Uitbreidingen-Addendum.adoc) |

---

## Architectural invariants

These are load-bearing. Everything else is derivable from the repo or the docs above.

**FC/IS dependency rules** (enforced by `bb check:fcis` and `bb check:deps`):

| Direction | Allowed |
|-----------|---------|
| Shell → Core | Yes |
| Core → Ports | Yes |
| Shell → Adapters | Yes |
| Core → Shell | **Never** |
| Core → Adapters | **Never** |

**Case conventions** — convert with `boundary.shared.core.utils.case-conversion`, never manually:

| Boundary | Convention |
|----------|------------|
| Clojure code | `kebab-case` |
| Database | `snake_case` |
| API (JSON) | `camelCase` |

**Quality gates** (all are CI jobs and `bb` tasks):
`bb check:fcis` · `bb check:placeholder-tests` · `bb check:deps` · `docs-lint` · `agents-links` · `clj-kondo`.
