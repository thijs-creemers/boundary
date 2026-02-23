# Scaffolder Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Generates new Boundary modules with FC/IS structure, tests, and migrations to accelerate consistent feature delivery.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.scaffolder.shell.cli-entry` | CLI entrypoint for generation commands |
| `boundary.scaffolder.core.*` | Pure generation logic and naming/field transforms |
| `boundary.scaffolder.shell.templates.*` | File templates and output orchestration |

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

## Gotchas

- Regenerated files can drift if templates are updated without corresponding test fixture updates.
- Keep generated field names kebab-case internally; convert only at HTTP/DB boundaries in generated code.

## Testing

```bash
clojure -M:test:db/h2 :scaffolder
```

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
