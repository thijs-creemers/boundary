# Learnings - Boundary Roadmap

## [2026-01-26T19:27] Session Start
Initial notepad created for boundary-roadmap execution.

## CLI Extension Patterns
- The scaffolder CLI in `libs/scaffolder/src/boundary/scaffolder/cli.clj` uses a consistent `execute-verb` pattern.
- Adding a new command requires:
  1. Define `verb-options` (CLI flags)
  2. Define `validate-verb-options` (validation)
  3. Define `execute-verb` (orchestration)
  4. Add case to `dispatch-command`

## IDE Setup and REPL Workflow Verification
- **System Lifecycle Management**: The preferred way to manage the system lifecycle in the REPL is using the convenience functions provided in the `user` namespace (defined in `dev/repl/user.clj`).
- **Available Commands**: `(go)`, `(reset)`, `(halt)`, and `(system)` are the standard entry points for REPL-driven development in Boundary.
- **Namespace Consistency**: Outdated documentation often refers to `boundary.system` which has been replaced by `boundary.config` and the `user` namespace wiring. Always verify namespace existence when reviewing documentation.
- **IDE Aliases**: Both `:dev` and `:repl-clj` aliases in `deps.edn` are configured to include the `dev/repl` path, ensuring the `user` namespace is available regardless of the connection method.

## Authentication & Security Implementation (Phase 1.4)
- **Security Model**: Implements a hybrid approach with stateless JWTs (HS256) for API access and stateful database-backed sessions for revocation and activity tracking.
- **Password Hashing**: Uses `bcrypt` combined with SHA-512 for robust salted hashing.
- **MFA Flow**: Implements TOTP (Time-based One-Time Password) with a two-step login process:
  1. Initial authentication verifies password and detects MFA requirement.
  2. Second step requires the `mfa-code` to issue a final session.
- **Revocation**: Logout is implemented by deleting the session record from the database, which invalidates the associated JWT by preventing session-based lookup during interceptor validation.
- **Environment Dependency**: Requires `JWT_SECRET` for token signing; defaults should be avoided in production.
- **Backup Codes**: Generates 10 single-use backup codes (XXXX-XXXX-XXXX format) during MFA setup for account recovery.

## 2026-01-26: Database Configuration Guide (Phase 1.3)

### Core Learnings
- **Database Abstraction**: Boundary uses a unified `:boundary/db-context` Integrant key to manage different database engines (SQLite, PostgreSQL, H2). The adapter-based system allows switching engines via configuration changes without touching business logic.
- **Connection Pooling**: HikariCP is the standard pooling library. Optimal pool sizes vary significantly:
  - SQLite: Small pools (1-5) to avoid "Database is locked" errors.
  - PostgreSQL: Larger pools (10-30+) depending on server `max_connections`.
  - H2: Moderate pools (5-10) for in-memory testing.
- **Migration Workflow**: Migratus provides a robust CLI interface. The command `clojure -M:migrate up` is the primary entry point for schema evolution.
- **Internal Conventions**: The framework strictly enforces kebab-case internally and handles conversion to snake_case at the database boundary. This prevents common "nil lookup" bugs in the service layer.
- **Portability**: HoneySQL abstracts dialect differences effectively, including native UUID and JSONB support in PostgreSQL vs. text-based equivalents in SQLite.

### Recommended Configuration Pattern
Always use Aero's `#env` and `#or` tags for database configuration to ensure a smooth "zero-config" developer experience while remaining production-ready:
```clojure
:boundary/db-context
{:adapter  :postgresql
 :host     #or [#env DB_HOST "localhost"]
 :password #env DB_PASSWORD}
```

## Testing Guide Creation (2026-01-26)

- Successfully created `docs/guides/TESTING.md` with over 500 lines of comprehensive documentation.
- **Three-Tier Strategy**: Documented Unit (Core/Pure), Integration (Shell/Service), and Contract (Boundary/HTTP) layers.
- **Metadata Patterns**: Reinforced use of `:unit`, `:integration`, and `:contract` for efficient test filtering.
- **Snapshot Testing**: Documented the `UPDATE_SNAPSHOTS=true` workflow for stabilizing validation logic.
- **Accessibility Integration**: Included specific patterns for testing ARIA labels and semantic HTML, ensuring high UI quality.
- **HTMX Testing**: Added guidance on testing HTMX-specific response headers and fragment returns.
- **Watch Mode**: Emphasized the use of `--watch` for a tight TDD loop.

## [2026-01-27] Task 2.4 - Fixed Project Generators

**Problem**: Generated projects referenced unpublished libraries and had minimal content.

**Solution**: Updated inline generators to use template file content:
- `generate-project-deps`: Now includes full dependency list (Integrant, Ring, Reitit, SQLite, etc.)
- `generate-project-readme`: Comprehensive guide with REPL workflow and testing instructions  
- `generate-project-config`: Proper Integrant wiring with database, HTTP server, and handler components
- `generate-project-main`: Full Integrant system with database pooling, HTTP server lifecycle

**Key Changes**:
1. Changed output from `core.clj` to `app.clj` to match conventions
2. Config path: `resources/conf/dev/config.edn` (not `resources/config.edn`)
3. All dependencies are published to Maven Central/Clojars
4. Generated projects are self-contained and fully functional

**Verification**: `cd /tmp/testapp3 && clojure -P` succeeds (downloads all deps without errors)
## Documentation Patterns
- When adding a new command to a CLI tool, update the Features table, Quick Start, and CLI Options sections in the README.
- Use tables for flag documentation and code blocks for usage examples.
- Include 'Next Steps' to guide users after they run the command.
