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

The script `scripts/install.sh` is committed to the monorepo and served via GitHub Pages at `get.boundary.dev`. Until that domain is configured, the canonical fallback URL is:

```bash
curl -fsSL https://raw.githubusercontent.com/thijs-creemers/boundary/main/scripts/install.sh | sh
```

Both URLs must serve identical content. DNS/CDN setup for `get.boundary.dev` is a separate infrastructure task outside this spec.

**Install steps (idempotent — skips already-satisfied steps):**

1. Detect OS — macOS, Debian/Ubuntu, Arch; exit with message if unsupported
2. **JVM** — check `java -version`; if missing:
   - Linux: bootstrap sdkman if absent, then `sdk install java`
   - macOS: `brew install --cask temurin`
3. **Clojure CLI** — check `clojure --version`; if missing:
   - Linux: run official Clojure install script
   - macOS: `brew install clojure`
4. **bbin** — check `bbin --version`; if missing, run official bbin installer
5. **boundary CLI** — install from the tagged GitHub release using the bbin git pattern (same as existing `clj-nrepl-eval` / `clj-paren-repair` tools):
   ```bash
   bbin install https://github.com/thijs-creemers/boundary \
     --tag <latest-version> \
     --main-opts '["-m" "boundary.cli.main"]' \
     --as boundary
   ```
   Note: `bbin install <clojars-coord>` is not used here because bbin's primary install path is Git URLs, not Maven coordinates. The git URL approach is already proven in this project.
6. **PATH** — append `~/.babashka/bbin/bin` to `~/.zshrc` (macOS) or `~/.bashrc` (Linux) if not already present. Print:
   ```
   ✓ Added ~/.babashka/bbin/bin to PATH in ~/.zshrc
     Run: source ~/.zshrc   (or open a new terminal)
     Then: boundary new <your-app-name>
   ```
   If the shell profile cannot be detected, print the `export PATH` line explicitly and instruct the user to add it manually.

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
 :docs-url       "https://github.com/thijs-creemers/boundary/blob/main/libs/payments/AGENTS.md"}
```

Note: `:docs-url` is a GitHub URL, not a local path. Generated projects do not contain the monorepo, so local `libs/*/AGENTS.md` paths would be dead. The URL is rendered as a clickable link in `boundary add` output and in the generated `AGENTS.md`.

**Core modules** (always included, not shown in hint table): `core`, `observability`, `platform`, `user`.

**Optional modules in the catalogue** (all modules except dev-only and internal tooling):

| Module     | Notes                                      |
|------------|--------------------------------------------|
| `payments` | Stripe, Mollie, Mock PSP                   |
| `storage`  | Local/S3, image processing                 |
| `jobs`     | Background jobs, retry logic               |
| `email`    | SMTP, async/queued modes                   |
| `cache`    | Redis or in-memory                         |
| `search`   | Full-text search                           |
| `realtime` | WebSocket pub/sub                          |
| `tenant`   | Multi-tenancy, schema-per-tenant           |
| `ai`       | Ollama, Anthropic, OpenAI                  |
| `external` | Twilio, SMTP, IMAP adapters                |
| `workflow` | Workflow orchestration                     |
| `reports`  | PDF/CSV export, scheduling                 |
| `calendar` | RRULE recurrence, iCal, conflict detection |
| `geo`      | Geocoding, Haversine distance              |
| `i18n`     | Marker-based i18n, translation catalogues  |
| `admin`    | Admin UI, entity config                    |
| `ui-style` | Shared CSS/JS style bundles                |

**Excluded from catalogue** (not user-installable via `boundary add`):
- `tools` — monorepo dev tooling, not a user-facing lib
- `scaffolder` — monorepo internal, invoked via `bb scaffold`
- `devtools` — dev-only, wired automatically in dev profile
- `platform` — core module, always included

---

## Section 3: `boundary new <project-name>`

Creates a standalone project directory. Does not require or clone the monorepo.

### Project name validation

The name must match `[a-z][a-z0-9-]*` (kebab-case). The CLI validates this before generating any files and exits 1 with a clear message if invalid. The name is used as:
- Directory name: `my-app/`
- Namespace prefix (hyphens → underscores): `my_app`
- Config project name: `my-app`

### Existing directory handling

| Situation | Behaviour |
|-----------|-----------|
| Directory does not exist | Create and populate |
| Directory exists and is empty | Populate (with confirmation) |
| Directory exists and is non-empty | Exit 1 with error: "Directory my-app/ already exists and is not empty. Use a different name or remove the directory first." |

`--force` flag overrides the non-empty check and overwrites without confirmation (for scripted use).

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

Sentinel comments delimit the module tables so `boundary add` can locate and update them reliably without fragile full-document parsing.

```markdown
## Boundary Modules

Core modules are pre-installed. Add optional modules with:

  boundary add <module>
  boundary list modules --json    ← machine-readable catalogue for AI tools

<!-- boundary:available-modules -->
| Module     | Description                        | Command                   |
|------------|------------------------------------|---------------------------|
| payments   | Stripe/Mollie/Mock PSP abstraction | boundary add payments     |
| storage    | File storage, local/S3, images     | boundary add storage      |
| jobs       | Background jobs, retry logic       | boundary add jobs         |
| email      | SMTP sending, async/queued         | boundary add email        |
| cache      | Redis or in-memory caching         | boundary add cache        |
| search     | Full-text search                   | boundary add search       |
| realtime   | WebSocket pub/sub                  | boundary add realtime     |
| tenant     | Multi-tenancy, schema-per-tenant   | boundary add tenant       |
| ai         | Multi-provider AI (Ollama/Claude)  | boundary add ai           |
| external   | Twilio, SMTP, IMAP adapters        | boundary add external     |
| workflow   | Workflow orchestration             | boundary add workflow     |
| reports    | PDF/CSV export, scheduling         | boundary add reports      |
| calendar   | iCal, RRULE, conflict detection    | boundary add calendar     |
| geo        | Geocoding, Haversine distance      | boundary add geo          |
| i18n       | Marker-based i18n, translations    | boundary add i18n         |
| admin      | Admin UI, entity config            | boundary add admin        |
| ui-style   | Shared CSS/JS style bundles        | boundary add ui-style     |
<!-- /boundary:available-modules -->

<!-- boundary:installed-modules -->
## Installed Modules

- core, observability, platform, user  (always present)
<!-- /boundary:installed-modules -->
```

`boundary add` removes the row from the `available-modules` block and appends the module name + docs URL to the `installed-modules` block. If the user has deleted the sentinel comments, `boundary add` still patches `deps.edn` and `config.edn` but skips the AGENTS.md update and prints a warning.

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

**Boundary project detection:** a directory is recognised as a boundary project if its `deps.edn` contains at least one `org.boundary-app/boundary-*` coordinate. The CLI checks this before doing anything and exits 1 with a clear message if not in a boundary project.

**Steps:**

1. Read `modules-catalogue.edn` to find the module entry
2. Patch `deps.edn` — add the Clojars coordinate + version
3. Patch `resources/conf/dev/config.edn` — inject config snippet into `:active` section (brace-walking, same pattern as `quickstart.clj`)
4. Patch `resources/conf/test/config.edn` — inject test-safe variant (mock/no-op)
5. Update `AGENTS.md` — remove row from `<!-- boundary:available-modules -->` block, append to `<!-- boundary:installed-modules -->` block with docs URL. Skip with warning if sentinels are absent.
6. Print next steps — env vars to set, link to module docs URL

**Error cases:**

| Situation | Behaviour |
|-----------|-----------|
| Module already installed | Print message, exit 0 |
| Unknown module name | Print available modules, exit 1 |
| Not inside a boundary project | Clear error: "No boundary project found. Run boundary new <name> first.", exit 1 |
| AGENTS.md sentinels missing | Patch deps + config, print warning about AGENTS.md, exit 0 |

**Version conflicts:** `boundary add` always uses the version from the bundled catalogue. If a conflicting version of the same lib is already in `deps.edn`, the command prints a warning showing both versions and does not overwrite — the user must resolve manually.

---

## Section 5: `boundary list modules --json`

### JSON output schema

```json
{
  "cli-version": "1.0.0",
  "catalogue-version": "1.0.1-alpha-14",
  "modules": [
    {
      "name": "payments",
      "description": "PSP abstraction — Mollie, Stripe, Mock checkout flow and webhook verification",
      "clojars": "org.boundary-app/boundary-payments",
      "version": "1.0.1-alpha-14",
      "category": "optional",
      "add-command": "boundary add payments",
      "docs-url": "https://github.com/thijs-creemers/boundary/blob/main/libs/payments/AGENTS.md"
    }
  ]
}
```

`category` is either `"core"` or `"optional"`. Core modules are included in the output so AI tools have a complete picture.

**Human-readable output** (`boundary list modules`, no `--json`): a formatted table identical to the AGENTS.md table, printed to stdout.

---

## Section 6: Version Bump Integration

**Trigger:** after a successful `bb deploy <lib>` for library `X` at version `V`.

**Action:** the deploy script patches `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn`, updating the `:version` field of the matching entry to `V`. If the deploy fails, the catalogue is not patched.

**Canonical deploy script:** `libs/tools/src/boundary/tools/deploy.clj` is the canonical source (used by `bb deploy`). `scripts/deploy.clj` mirrors this logic. Both `all-libs` vectors must include `boundary-cli`. The implementer must reconcile the existing discrepancy between the two files' `all-libs` vectors as part of this work.

**Publishing the CLI:** after patching the catalogue, `boundary-cli` itself is redeployed so the updated catalogue is baked into the new artifact. This second deploy is triggered manually by the developer as part of the release process — it is not automated in CI. A future CI commit-back workflow is out of scope.

**AGENTS.md divergence:** a generated project's `AGENTS.md` is a snapshot at creation time. Module versions listed there will drift as new releases land. This is a known limitation. A `boundary sync` command to refresh the snapshot is out of scope for this spec.

---

## Out of Scope

- Windows support (install script targets macOS and Linux only)
- `boundary remove <module>` — not planned; removal is manual
- Remote catalogue fetching — the catalogue is always bundled, never fetched at runtime
- `boundary sync` — refreshing a generated project's AGENTS.md from the latest catalogue
- Automated CI commit-back for catalogue patches

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
| `libs/tools/src/boundary/tools/deploy.clj` | Add `boundary-cli` to `all-libs`; patch catalogue after successful deploy |
| `scripts/deploy.clj` | Same — reconcile `all-libs` with tools version; add catalogue patch |
| `bb.edn` | Add `boundary-cli` to task references where relevant |
