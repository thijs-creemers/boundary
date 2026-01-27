# Issues - Boundary Roadmap

## [2026-01-26T19:27] Session Start
Problems, gotchas, and blockers tracked here.

## Documentation Drift
- `libs/scaffolder/README.md` documents `--output-dir` and `--force` flags for the `generate` command.
- These flags are missing from the implementation in `libs/scaffolder/src/boundary/scaffolder/cli.clj`.
- This drift should be addressed when implementing the `new` command in Phase 2.

## [2026-01-27] Task 2.4 - Subagent Failure

**Issue**: Subagent completely failed to complete the task correctly.

**What Went Wrong**:
- Modified wrong files: `docs/README.md`, `libs/scaffolder/src/boundary/scaffolder/shell/service.clj`
- Deleted unrelated files: `libs/platform/src/boundary/platform/shell/adapters/filesystem/`
- Never touched the actual target file: `generators.clj`

**Resolution**: Orchestrator reverted changes and completed task manually.

**Lesson**: Always verify subagent work immediately before proceeding. Subagents can confidently report success while having done completely wrong work.
