# Boundary CLI — Design Spec

**Date:** 2026-04-29  
**Status:** Approved for implementation

---

## Problem Statement

New users (human and AI agents) face three friction points with Boundary today:

1. No single command to create a fresh project — the monorepo must be cloned manually
2. `bb quickstart` is interactive and breaks AI tool workflows
3. AI tools have no reliable way to discover which boundary modules exist and what they do

---

## Goals

- One `curl` command installs everything needed on a fresh machine (JVM, Clojure, bbin, `boundary` CLI)
- `boundary new <project-name>` creates a standalone project without touching the monorepo
- Core modules are always included; optional modules are added with `boundary add <module>`
- AI tools (Claude Code, etc.) can discover available modules via `boundary list modules --json` and a generated `AGENTS.md`
- The module catalogue stays in sync with Clojars releases automatically

---

## Approach

Standalone `boundary` CLI published to Clojars as `org.boundary-app/boundary-cli`. Installed globally via bbin. Follows the same build and deploy pipeline as the existing boundary libraries.

---

## Section 1: Bootstrap & Install

```bash
curl -fsSL https://get.boundary.dev | sh
```

Served from GitHub Pages or a CDN redirect to `scripts/install.sh` in the monorepo.

**Install steps (idempotent — skips already-satisfied steps):**

1. Detect OS — macOS, Debian/Ubuntu, Arch; exit with message if unsupported
2. **JVM** — check `java -version`; if missing:
   - Linux: bootstrap sdkman, then `sdk install java`
   - macOS: `brew install --cask temurin`
3. **Clojure CLI** — check `clojure --version`; if missing:
   - Linux: run official Clojure install script
   - macOS: `brew install clojure`
4. **bbin** — check `bbin --version`; if missing, run official bbin installer
5. **boundary CLI** — `bbin install org.boundary-app/boundary-cli --as boundary`
6. Print: `boundary new <your-app-name>` as next step

Each step prints a clear status line. Failures are loud and actionable.

---

## Section 2: The `boundary` CLI

Lives in `libs/boundary-cli/`. Published and deployed with the same `build.clj` + `bb deploy` pipeline as all other boundary libraries.

### Commands

```
boundary new <project-name>       Phase 1 — create a project
boundary add <module>             Phase 2 — wire in an optional module
boundary list modules             Human-readable module table
boundary list modules --json      Machine-readable JSON (for AI tools)
boundary version                  Show CLI version and catalogue version
```

### Directory layout

```
libs/boundary-cli/
├── build.clj                          # Same pattern as libs/tools/build.clj
├── deps.edn
└── src/boundary/cli/
    ├── main.clj                       # Entrypoint, command dispatch
    ├── new.clj                        # boundary new
    ├── add.clj                        # boundary add
    ├── list_modules.clj               # boundary list modules
    └── catalogue.clj                  # Reads modules-catalogue.edn
└── resources/
    └── boundary/cli/
        ├── modules-catalogue.edn      # Static module registry (source of truth)
        └── templates/                 # Project template files
            ├── deps.edn.tmpl
            ├── config.edn.tmpl
            ├── AGENTS.md.tmpl
            └── CLAUDE.md.tmpl
```

### Module catalogue entry shape (`modules-catalogue.edn`)

```edn
{:name           "payments"
 :description    "PSP abstraction — Mollie, Stripe, Mock checkout flow and webhook verification"
 :clojars        org.boundary-app/boundary-payments
 :version        "1.0.1-alpha-14"
 :category       :optional          ; :core | :optional
 :config-key     :boundary/payment-provider
 :config-snippet "  :boundary/payment-provider\n  {:provider :mock}\n"
 :add-command    "boundary add payments"
 :agents-md      "libs/payments/AGENTS.md"}
```

**Core modules** (always included, not shown in hint table): `core`, `observability`, `platform`, `user`.

---

## Section 3: `boundary new <project-name>`

Creates a standalone project directory. Does not require or clone the monorepo.

### Generated structure

```
my-app/
├── deps.edn                          # Core boundary modules as Clojars deps
├── bb.edn                            # Day-to-day dev tasks (test, migrate, repl, doctor)
├── .env.example                      # Required env vars
├── .gitignore
├── CLAUDE.md                         # Points AI tools to AGENTS.md + boundary list modules --json
├── AGENTS.md                         # Module catalogue, installed modules, dev commands
├── resources/
│   └── conf/
│       ├── dev/config.edn            # Core module config wired
│       └── test/config.edn           # Test config (H2 in-memory)
└── src/
    └── my_app/
        └── system.clj                # Integrant system map, wires core modules
```

### Generated `AGENTS.md` (relevant section)

```markdown
## Boundary Modules

Core modules are pre-installed. Add optional modules with:

  boundary add <module>
  boundary list modules --json    ← machine-readable catalogue for AI tools

| Module   | Description                        | Command                 |
|----------|------------------------------------|-------------------------|
| payments | Stripe/Mollie/Mock PSP abstraction | boundary add payments   |
| storage  | File storage, local/S3, images     | boundary add storage    |
| jobs     | Background jobs, retry logic       | boundary add jobs       |
| email    | SMTP sending, async/queued         | boundary add email      |
| cache    | Redis or in-memory caching         | boundary add cache      |
| search   | Full-text search                   | boundary add search     |
| realtime | WebSocket pub/sub                  | boundary add realtime   |
| tenant   | Multi-tenancy, schema-per-tenant   | boundary add tenant     |
| ai       | Multi-provider AI (Ollama/Claude)  | boundary add ai         |

## Installed Modules

- core, observability, platform, user  (always present)
```

### Generated `CLAUDE.md`

```markdown
# my-app

Built with the Boundary Framework (Clojure, FC/IS architecture).

See AGENTS.md for available modules, dev commands, and architecture conventions.

To discover available boundary modules in machine-readable form:
  boundary list modules --json
```

### CLI output after generation

```
✓ Project created: my-app/

Core modules installed: core, observability, platform, user

Optional modules available — add any with:

  boundary add payments    PSP abstraction (Stripe, Mollie, Mock)
  boundary add storage     File storage, local/S3, image processing
  boundary add jobs        Background job processing, retry logic
  boundary add email       SMTP sending, async/queued modes
  boundary add cache       Redis or in-memory caching
  boundary add ai          Multi-provider AI (Ollama, Claude, OpenAI)
  ... (boundary list modules for full list)

Next:
  cd my-app
  boundary add <module>    (optional)
  clojure -M:repl-clj
```

---

## Section 4: `boundary add <module>`

Fully non-interactive — safe for AI agents to call directly.

**Steps:**

1. Read `modules-catalogue.edn` to find the module entry
2. Patch `deps.edn` — add the Clojars coordinate + version
3. Patch `resources/conf/dev/config.edn` — inject config snippet into `:active` section (brace-walking, same pattern as `quickstart.clj`)
4. Patch `resources/conf/test/config.edn` — inject test-safe variant (mock/no-op)
5. Update `AGENTS.md` — move module from "available" table to "Installed Modules", add pointer to its docs
6. Print next steps — env vars to set, link to module AGENTS.md

**Error cases:**

| Situation | Behaviour |
|-----------|-----------|
| Module already installed | Print message, exit 0 |
| Unknown module name | Print available modules, exit 1 |
| Not inside a boundary project | Clear error message, exit 1 |

---

## Section 5: Version Bump Integration

**Trigger:** after a successful `bb deploy <lib>` for library `X` at version `V`.

**Action:** `scripts/deploy.clj` patches `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn`, updating the `:version` field of the matching entry to `V`. If the deploy fails, the catalogue is not patched.

**Publishing the CLI itself:** deploying `boundary-cli` bakes the updated catalogue into the published artifact. Users who reinstall or update via bbin get the fresh catalogue automatically.

This keeps the catalogue version always reflecting what is live on Clojars, with no manual step required.

---

## Out of Scope

- Windows support (install script targets macOS and Linux only; JVM/Clojure work on Windows but the bootstrap script is out of scope)
- `boundary remove <module>` — not planned; removal is manual
- Remote catalogue fetching — the catalogue is always bundled, never fetched at runtime

---

## Files to Create

| File | Purpose |
|------|---------|
| `scripts/install.sh` | Bootstrap script served at `get.boundary.dev` |
| `libs/boundary-cli/build.clj` | Build and deploy config |
| `libs/boundary-cli/deps.edn` | CLI dependencies |
| `libs/boundary-cli/src/boundary/cli/main.clj` | Command dispatch |
| `libs/boundary-cli/src/boundary/cli/new.clj` | `boundary new` |
| `libs/boundary-cli/src/boundary/cli/add.clj` | `boundary add` |
| `libs/boundary-cli/src/boundary/cli/list_modules.clj` | `boundary list modules` |
| `libs/boundary-cli/src/boundary/cli/catalogue.clj` | Catalogue reader |
| `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn` | Static module registry |
| `libs/boundary-cli/resources/boundary/cli/templates/` | Project template files |

## Files to Modify

| File | Change |
|------|--------|
| `scripts/deploy.clj` | Patch catalogue version after successful deploy |
| `bb.edn` | Add `boundary-cli` to the known libs list |
| `libs/tools/src/boundary/tools/deploy.clj` | Same catalogue patch logic |
