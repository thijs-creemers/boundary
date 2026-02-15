# Scaffolder Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Module Scaffolding

Generate complete production-ready modules:

```bash
# Generate module with entity
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required:unique \
  --field price:decimal:required

# Generates:
# - 9 source files (core, shell, ports, schema, wiring)
# - 3 test files (unit, integration, contract)
# - 1 migration file
# - Zero linting errors, complete FC/IS architecture
```

**Integration Steps**: See [Scaffolder README](README.md)

## Testing

```bash
clojure -M:test:db/h2 :scaffolder
```
