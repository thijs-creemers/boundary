# Decisions - Boundary Roadmap

## [2026-01-26T19:27] Session Start
Architectural choices and key decisions logged here.

## ADR-002: Boundary New Command
- Decided to extend existing scaffolder CLI instead of creating a standalone tool.
- Feasibility is high due to clean dispatch pattern in `cli.clj`.
- Implementation will include fixing doc drift for `--output-dir` and `--force`.
