# Starter — Development Guide

> For general Boundary conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../AGENTS.md).

## Purpose

The starter is a project scaffolding wizard that generates new Boundary projects from templates.
It is the recommended entry point for anyone new to Boundary.

Two entry points exist:

| Entry point | Use case |
|---|---|
| `scripts/bootstrap.sh` | Download the starter from GitHub and extract it locally |
| `bb setup` | Interactive wizard to generate a new project from a template |

## Directory structure

```
starter/
├── bb.edn                  ← Babashka task: `bb setup` → scripts/setup.clj
├── deps.edn                ← Starter's own deps (for running tests)
├── scripts/
│   ├── bootstrap.sh        ← Downloads starter from GitHub tarball
│   ├── setup.clj           ← Interactive wizard entrypoint (Babashka)
│   ├── helpers.clj         ← Validation, template loading, user input
│   ├── file_generators.clj ← Generates deps.edn, config.edn, README, etc.
│   └── library_metadata.clj ← Library dependency metadata and ordering
├── templates/
│   ├── _base.edn           ← Shared foundation (not directly selectable)
│   ├── minimal.edn         ← Bare minimum: HTTP + DB + health check
│   ├── api-only.edn        ← REST API with JWT auth, no admin UI
│   ├── microservice.edn    ← Small footprint for internal services
│   ├── web-app.edn         ← Full web app: auth + admin UI (Hiccup/HTMX)
│   └── saas.edn            ← Production SaaS: + storage, jobs, email, tenant
├── src/boundary/app.clj    ← Minimal Boundary app (used as a live smoke-check)
├── test/                   ← Tests for the wizard itself
│   ├── custom_templates/   ← Tests for custom template creation and editing
│   ├── helpers/            ← Tests for validation helpers
│   └── integration/        ← End-to-end generation tests
└── my-app/                 ← Example generated project (for local testing)
```

## Template system

### Template inheritance

Templates use a `{:extends :parent}` declaration. The resolution order is:

```
_base  ←  minimal  ←  api-only
                   ←  microservice
                   ←  web-app  ←  saas
```

Each template only declares what it adds or overrides. `_base.edn` always applies.

### Template keys

| Key | Description |
|---|---|
| `:meta` | Name, description, version, extends |
| `:dependencies` | Extra Maven deps to add to generated `deps.edn` |
| `:boundary-libs` | Boundary libraries to include (added to `:dependencies`) |
| `:config` | Merged into generated `resources/conf/dev/config.edn` |
| `:env-vars` | `:required` and `:optional` env vars (drives README and `.env.example`) |
| `:migrations` | Migration names to apply after project creation |
| `:integrant-keys` | Integrant system keys to wire into generated config |
| `:routes` | Route namespace strings to register |
| `:readme-sections` | `:features` and `:next-steps` text for the generated README |

### Foundation libraries

`core`, `observability`, and `platform` are **always included** regardless of template.
They cannot be deselected.

### Dependency resolution

When a selected library requires another library (e.g., `calendar` requires `storage`),
the setup wizard auto-adds the dependency with a warning. No manual intervention needed.

## `bb setup` wizard

```bash
# Interactive wizard (recommended)
bb setup

# Non-interactive (all args on command line)
bb setup --template web-app --name my-project --db sqlite

# Preview without writing any files
bb setup --template saas --name my-app --dry-run

# Non-interactive with auto-confirm
bb setup --template minimal --name my-app --yes
```

### Template names

| Template | Boundary libraries included | DB default |
|---|---|---|
| `minimal` | core, observability, platform | SQLite |
| `api-only` | + user | SQLite |
| `microservice` | core, observability, platform | SQLite |
| `web-app` | + user, admin | SQLite |
| `saas` | + user, admin, storage, cache, jobs, email, tenant | PostgreSQL |
| `custom` | Interactive selection | Your choice |

### Database options

| Value | Notes |
|---|---|
| `sqlite` | Zero-config, dev only. Default for all templates except `saas`. |
| `postgres` | Required for multi-tenancy (`tenant` library). |
| `both` | Includes both JDBC drivers; runtime choice via `DATABASE_URL`. |

## Custom templates

The wizard can create, save, and reuse custom templates:

```bash
bb setup
# Select: 6 (custom template)
# Select: 1 (Create new custom template)
# Choose libraries interactively
# Save as: saved-templates/my-template.edn
```

Saved templates live in `saved-templates/` (gitignored by default).

```clojure
;; saved-templates/my-template.edn
{:name        "my-template"
 :description "..."
 :libraries   [:core :observability :platform :user :search]
 :db          :postgres}
```

## Generated project structure

A generated project has this structure:

```
{project-name}/
├── deps.edn                        ← All Boundary libs as git deps
├── bb.edn                          ← `bb scaffold` task wired in
├── build.clj                       ← Uberjar build script
├── src/boundary/{project-name}/
│   └── app.clj                     ← Integrant system entry point
├── test/boundary/{project-name}/
│   └── app_test.clj
├── resources/conf/dev/
│   ├── config.edn                  ← Integrant config
│   └── system.clj                  ← REPL system helper
└── .env.example                    ← Required env vars
```

## Environment variables

| Variable | Required by | Notes |
|---|---|---|
| `JWT_SECRET` | api-only, web-app, saas | Minimum 32 characters |
| `BND_ENV` | all | `development` / `test` / `production` |
| `DATABASE_URL` | postgres templates | JDBC connection string |
| `REDIS_URL` | saas (cache/jobs) | Optional; falls back to in-memory |
| `SMTP_HOST`, `SMTP_PORT`, `EMAIL_FROM` | saas (email) | Required if email enabled |
| `S3_BUCKET`, `S3_REGION` | saas (storage) | Optional; falls back to local |

## Testing the starter itself

The starter has its own test suite for the wizard logic:

```bash
# From starter/ directory
clojure -M:test

# From boundary repo root
clojure -M:test:db/h2 :starter  # if wired into tests.edn
```

Tests cover:
- `test/helpers/` — validation helpers (`valid-project-name?`, template loading)
- `test/custom_templates/` — custom template creation, editing, metadata
- `test/integration/` — end-to-end project generation and smoke tests

## Common pitfalls

### Project name must be kebab-case

```bash
# ❌ WRONG
bb setup --template minimal --name MyProject
bb setup --template minimal --name my_project

# ✅ CORRECT
bb setup --template minimal --name my-project
```

Clojure namespaces use kebab-case. The project name becomes the namespace prefix
(`boundary.my-project.*`).

### JWT_SECRET required at startup

Authentication templates (api-only, web-app, saas) fail on startup without it:

```bash
export JWT_SECRET="dev-secret-minimum-32-characters"
clojure -M:repl-clj
```

### defrecord changes require full restart

After changing a generated `defrecord`, `(ig-repl/reset)` is not enough:

```clojure
(ig-repl/halt)
(ig-repl/go)
```

### SQLite database locked

Occurs when two REPL sessions access the same `dev-database.db`. Close the other session.

### Template dependency auto-resolution

Selecting `calendar` auto-adds `storage`. This is expected behaviour — no action needed.

### Running generated project tests

Tests in the generated app require the full Clojure classpath, not bare `bb -e`:

```bash
# ✅ CORRECT — in generated project directory
clojure -M:test

# ❌ WRONG
bb -e "(load-file \"test/...\")"   ; missing Clojure deps on classpath
```

## Links

- [Root AGENTS Guide](../AGENTS.md)
- [Boundary README](../README.md)
- [Troubleshooting Guide](TROUBLESHOOTING.md)
- [Usage Examples](USAGE_EXAMPLES.md)
