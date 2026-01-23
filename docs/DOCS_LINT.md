# Documentation Lint Tool

A drift detection tool for documentation after the library split. Warns about stale paths, broken links, unknown namespaces, and invalid aliases.

## Quick Start

```bash
# Via Clojure CLI (works in CI)
clojure -M:dev -m boundary.tools.docs-lint

# Via Babashka (faster, requires bb installed)
bb scripts/docs_lint.clj

# With options
clojure -M:dev -m boundary.tools.docs-lint --verbose
clojure -M:dev -m boundary.tools.docs-lint --out-dir custom/output
```

## Output

Reports are written to `build/docs-lint/`:
- `report.edn` - structured data for tooling
- `report.txt` - human-readable summary

## What It Checks

### 1. Broken Internal Links
Validates that relative links in markdown and asciidoc files point to existing files.

**Example warning:**
```
[broken-link] AGENTS.md - Broken link: docs/guides/module-scaffolding.md
```

### 2. Pre-split / Stale Path References
Detects references to old paths from before the library split.

**Example warning:**
```
[stale-path] docs/AGENTS_FULL.md:494 - Pre-split path: code moved to libs/*/src/boundary/
```

**Known stale patterns:**
- `src/boundary/` → should be `libs/*/src/boundary/`
- `test/boundary/` → should be `libs/*/test/boundary/`
- `cd libs/<lib> && clojure` → prefer root-level commands

### 3. Unknown Namespace References
Warns when docs reference `boundary.*` namespaces that don't exist in the codebase.

**Example warning:**
```
[unknown-namespace] AGENTS.md:44 - Unknown namespace reference: boundary.core.validation-test
```

**Note:** This is a heuristic check. Some warnings may be for:
- Namespaces in test files (not scanned by default)
- Example namespaces that don't exist yet
- Partial namespace prefixes

### 4. Unknown Alias References
Detects `clojure -M:alias` commands where the alias doesn't exist in `deps.edn`.

**Example warning:**
```
[unknown-alias] AGENTS.md:25 - Unknown deps.edn alias: :db/h2
```

**Special cases handled:**
- `:db/*` aliases (database variants)
- Library keywords (`:core`, `:user`, etc.)

## Scope

### Files Scanned
- `README.md`, `README.adoc` (repo root)
- `AGENTS.md`
- `docs/**`
- `examples/**`
- `libs/*/README.md` (library root READMEs only)

### Files Excluded
- `**/src/**/README*` (module READMEs inside code trees)
- `build/**`, `target/**`, `.cpcache/**`, `.git/**`

## CI Integration

The docs-lint runs automatically in CI as a warn-only job. It:
- Never fails the build (always exits 0)
- Uploads report artifacts for review
- Prints a summary to the logs

See `.github/workflows/ci.yml` for the job definition.

## Fixing Warnings

### Broken Links
1. Check if the target file was moved or renamed
2. Update the link path or remove the link

### Stale Paths
1. Update path references to use `libs/{library}/src/...` or `libs/{library}/test/...`
2. For documentation that should be canonical, move it to boundary-docs repo

### Unknown Namespaces
1. Check if the namespace was renamed during the split
2. Update the namespace reference
3. If it's an example namespace, consider if it should exist

### Unknown Aliases
1. Check `deps.edn` for the correct alias name
2. Update the command example

## Configuration

The tool configuration is in the source file. Key settings:

```clojure
;; Files to scan
(def include-patterns
  ["README.md" "AGENTS.md" "docs" "examples" ...])

;; Patterns to exclude
(def exclude-patterns
  [#".*/src/.*/README.*" #"^build/.*" ...])

;; Stale path detection patterns
(def stale-path-patterns
  [[#"src/boundary/" "Pre-split path: code moved to libs/*/src/boundary/"]
   ...])
```

To tune the linter, edit `scripts/docs_lint.clj` or `dev/boundary/tools/docs_lint.clj`.

## Reducing Warning Noise

The initial run after the library split will produce many warnings. To reduce noise:

1. **Fix high-value docs first**: `README.md`, `AGENTS.md`
2. **Archive or remove outdated docs**: Phase completion docs, old migration notes
3. **Link to boundary-docs**: Move canonical content to boundary-docs repo
4. **Tune patterns**: Adjust `stale-path-patterns` if too noisy
