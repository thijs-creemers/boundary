# boundary-tools

**Artifact:** `org.boundary-app/boundary-tools`
**Version:** `1.0.0-alpha`
**Published to:** [Clojars](https://clojars.org/org.boundary-app/boundary-tools)

Developer tooling for the Boundary framework: scaffolding, AI assistance, i18n management, deployment, and development utilities — all packaged as a single Clojars artifact that any Boundary project can consume.

---

## Getting started (new projects)

Add to your project's `bb.edn`:

```clojure
{:deps {org.boundary-app/boundary-tools {:mvn/version "1.0.0-alpha"}}
 :tasks
 {:requires ([boundary.tools.scaffold :as scaffold]
             [boundary.tools.ai       :as ai]
             [boundary.tools.i18n     :as i18n]
             [boundary.tools.admin    :as admin]
             [boundary.tools.deploy   :as deploy]
             [boundary.tools.dev      :as dev])

  scaffold     {:task (apply scaffold/-main *command-line-args*)}
  ai           {:task (apply ai/-main *command-line-args*)}
  create-admin {:task (apply admin/-main *command-line-args*)}
  deploy       {:task (apply deploy/-main *command-line-args*)}
  check-links  {:task (dev/check-links)}
  smoke-check  {:task (dev/smoke-check)}
  install-hooks {:task (dev/install-hooks)}
  i18n:find    {:task (apply i18n/-main "find" *command-line-args*)}
  i18n:scan    {:task (i18n/-main "scan")}
  i18n:missing {:task (i18n/-main "missing")}
  i18n:unused  {:task (i18n/-main "unused")}}}
```

### Upgrade path

Bump the `:mvn/version` in your `bb.edn`:

```clojure
org.boundary-app/boundary-tools {:mvn/version "1.0.1-alpha"}
```

---

## Command reference

### `bb scaffold` — Interactive scaffolding wizard

```bash
bb scaffold                          # Show help
bb scaffold generate                 # Interactive module generation wizard
bb scaffold new                      # Interactive new project wizard
bb scaffold field                    # Interactive add-field wizard
bb scaffold endpoint                 # Interactive add-endpoint wizard
bb scaffold adapter                  # Interactive add-adapter wizard
bb scaffold ai "<description>"       # AI-powered module from NL description
bb scaffold ai "<description>" --yes # Non-interactive (skip confirmation)

# Non-interactive passthrough (pass args directly to scaffolder CLI):
bb scaffold generate --module-name foo --entity Foo --field name:string:required
bb scaffold field --module-name foo --entity Foo --name price --type decimal
```

### `bb ai` — Framework-aware AI tooling

```bash
bb ai                                        # Show help
bb ai explain                                # Explain error from stdin
bb ai explain --file stacktrace.txt          # Explain error from file
bb ai gen-tests libs/user/src/...clj         # Generate test namespace
bb ai gen-tests libs/user/src/...clj -o out  # Write tests to file
bb ai sql "find active users last 7 days"    # HoneySQL from NL description
bb ai docs --module libs/user                # Generate all docs
bb ai docs --module libs/user --type agents  # Generate AGENTS.md only
```

Provider selection (first matching env var wins):
- `ANTHROPIC_API_KEY` → Anthropic (Claude)
- `OPENAI_API_KEY` → OpenAI (GPT)
- `OLLAMA_URL` → Ollama (local, default `http://localhost:11434`)

### `bb create-admin` — Create first admin user

```bash
bb create-admin                              # Interactive wizard
bb create-admin --env prod                   # Use production config
bb create-admin --email admin@app.com --name "Admin"  # Skip prompts
bb create-admin --dir examples/ecommerce-api # Target a sub-project
```

Run database migrations first: `clojure -M:migrate up`

### `bb deploy` — Deploy to Clojars

```bash
bb deploy --help                    # Show help
bb deploy --all                     # Deploy all 21 artifacts
bb deploy --missing                 # Deploy only unpublished artifacts
bb deploy core platform user        # Deploy specific libraries
bb deploy boundary-tools            # Deploy boundary-tools itself
```

Required environment variables:
- `CLOJARS_USERNAME` — your Clojars username
- `CLOJARS_PASSWORD` — your Clojars deploy token

### `bb check-links` — Validate AGENTS.md links

```bash
bb check-links    # Check root AGENTS.md + all libs/*/AGENTS.md for broken local links
```

Exits non-zero if broken links are found. Skips `http://`, `https://`, `mailto:`, and `#anchor` links.

### `bb smoke-check` — Verify tool entrypoints

```bash
bb smoke-check    # Verify deps.edn aliases, migrate CLI, test runner, docs lint
```

Checks that required aliases (`:migrate`, `:test`, `:repl-clj`, `:docs-lint`) exist in `deps.edn` and that key CLIs respond.

### `bb install-hooks` — Configure git hooks

```bash
bb install-hooks  # Sets git config core.hooksPath to .githooks
```

### `bb i18n:*` — Internationalisation tooling

```bash
bb i18n:find "Sign in"      # Find key by substring in catalogue + grep codebase
bb i18n:find :user/sign-in  # Find by exact keyword
bb i18n:scan                # Scan core/ui.clj files for unexternalised strings (CI gate)
bb i18n:missing             # Report keys in en.edn missing from other locales
bb i18n:unused              # Report catalogue keys not referenced in source
```

Translation files live in `libs/i18n/resources/boundary/i18n/translations/`.

---

## Namespaces

| Namespace | Purpose |
|---|---|
| `boundary.tools.scaffold` | Interactive scaffolding wizards + AI passthrough |
| `boundary.tools.ai` | AI CLI frontend (explain, gen-tests, sql, docs) |
| `boundary.tools.i18n` | i18n catalogue management (find/scan/missing/unused) |
| `boundary.tools.admin` | First admin user creation wizard |
| `boundary.tools.deploy` | Clojars deployment for all 21 Boundary artifacts |
| `boundary.tools.dev` | check-links + smoke-check + install-hooks |

---

## Releasing boundary-tools

```bash
# From the monorepo root:
bb deploy boundary-tools

# Or directly from within boundary-tools/:
cd boundary-tools
clojure -T:build clean
clojure -T:build deploy
```

Bump `version` in `boundary-tools/build.clj` before releasing.
