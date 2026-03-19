# UI Style Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Central app-wide styling contract for Boundary:
- style bundles (`:base`, `:pilot`, `:admin-pilot`)
- JavaScript bundles (`:base`, `:pilot`, `:admin-pilot`)
- design tokens and theme overrides
- shared CSS/JS assets used by admin/user modules

## Source Of Truth

- Contract and usage: [`README.md`](README.md)
- Bundle API namespace: `boundary.ui-style`
- Assets root: `libs/ui-style/resources/public/css/`

## Rules

1. Do not hardcode CSS file lists in feature modules.
2. Layout namespaces choose bundle keys from `boundary.ui-style`.
3. Theme changes happen via token files, not one-off hardcoded component colors.
4. For onboarding/conversion steps, follow `Module Migration Checklist` in `libs/ui-style/README.md`.
