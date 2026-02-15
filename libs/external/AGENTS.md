# External Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Status

**In Development** - This library is an empty skeleton. Only `deps.edn` and `README.md` exist; `src/`, `test/`, and `resources/` contain only `.gitkeep` files.

## Planned Purpose

Adapters for external third-party services:
- **Payments**: Stripe payment processing
- **Notifications**: Generic notification system

## When Implementing

Follow the standard FC/IS module structure:

```
libs/external/src/boundary/external/
├── core/       # Pure functions (data transformations, validation)
├── shell/      # Adapters for external APIs (HTTP calls, webhooks)
├── ports.clj   # Protocol definitions
└── schema.clj  # Malli validation schemas
```

Dependencies: currently only `boundary/platform` (local).
