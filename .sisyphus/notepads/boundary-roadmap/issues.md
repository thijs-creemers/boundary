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

## [2026-01-27] Subagent Failure - Task 3.3

**Problem**: Subagent (session `ses_401e3f5dcffeU2o3GrS0b5AQ7v`) failed to complete task 3.3 twice:
1. First attempt: Claimed completion, "No file changes detected"
2. Second attempt (with explicit failure feedback): Claimed completion again, still "No file changes detected"

**File Status**: `libs/email/src/boundary/email/ports.clj` remained a 5-line stub after both attempts.

**Root Cause**: Unknown - subagent may have:
- Failed to use Write tool
- Encountered internal error without reporting
- Misunderstood the task scope

**Resolution**: Orchestrator completed task manually (96 lines, 0 linting errors).

**Lesson**: When subagent fails twice on simple task, direct implementation is more efficient than third delegation attempt.
