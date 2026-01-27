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

## [2026-01-27] Phase 2 Complete - Starter Templates CLI

**Summary**: Successfully implemented `boundary new` command for generating starter projects.

**Accomplishments**:
1. Implemented `new` command with flags: `--name`, `--output-dir`, `--dry-run`
2. Created production-quality inline generators (deps, README, config, main)
3. Generated projects are self-contained with published dependencies only
4. Full Integrant wiring: database pooling, HTTP server, component lifecycle
5. Comprehensive documentation in scaffolder README
6. All tests passing (20 tests, 103 assertions, 0 failures)

**Key Learnings**:
- Keep inline generator pattern but use high-quality content
- Generated projects use SQLite by default for zero-config quickstart
- Config path: `resources/conf/dev/config.edn` (proper Aero structure)
- Main file: `src/{name}/app.clj` (not `core.clj`)
- Subagent reliability is inconsistent - always verify immediately

**Files Modified**:
- `libs/scaffolder/src/boundary/scaffolder/cli.clj` (+85 lines)
- `libs/scaffolder/src/boundary/scaffolder/core/generators.clj` (updated 4 functions)
- `libs/scaffolder/src/boundary/scaffolder/ports.clj` (+17 lines)
- `libs/scaffolder/src/boundary/scaffolder/shell/service.clj` (+50 lines)
- `libs/scaffolder/test/boundary/scaffolder/shell/service_test.clj` (+5 tests)
- `libs/scaffolder/README.md` (+66 lines documentation)

**Commits**: 4 atomic commits
- `feat(scaffolder): add boundary new command for project creation`
- `test(scaffolder): add tests for boundary new command`
- `fix(scaffolder): update project generators to use production-quality templates`
- `docs(scaffolder): document boundary new command`

## [2026-01-27] Task 3.2 Complete - Email Core Layer

**Summary**: Implemented pure email processing functions in `libs/email/src/boundary/email/core/email.clj`.

**Functions Implemented** (231 lines total):
1. **Email Address Validation**:
   - `valid-email-address?` - Basic RFC 5322 pattern validation
   - `validate-recipients` - Batch validation, returns valid/invalid split
   
2. **Header Formatting**:
   - `format-headers` - Normalize headers to kebab-case keywords
   
3. **Email Preparation**:
   - `normalize-recipients` - Convert string or vector to normalized vector
   - `prepare-email` - Create email map with ID, timestamps, normalized recipients
   
4. **Email Validation**:
   - `validate-email` - Complete email structure validation with error messages
   
5. **Email Utilities**:
   - `email-summary` - Create summary for logging/monitoring
   - `add-reply-to` - Add Reply-To header
   - `add-cc` - Add CC recipients
   - `add-bcc` - Add BCC recipients

**Schemas Defined** (108 lines in `schema.clj`):
- `EmailAddress` - Email address string with pattern validation
- `Attachment` - Attachment with filename, content-type, content, size
- `Email` - Complete email with all fields
- `SendEmailInput` - Input schema (to can be string or vector)
- `EmailValidationResult` - Validation result structure
- `RecipientValidationResult` - Recipient validation result structure
- `EmailSummary` - Summary for logging/monitoring

**Key Design Decisions**:
- All functions are pure (no I/O, no side effects)
- Recipients normalized to vector internally (accept string or vector as input)
- Headers stored as kebab-case keywords (`:reply-to`, `:content-type`)
- Basic RFC 5322 email pattern (not full spec)
- Email IDs generated via `java.util.UUID/randomUUID`
- Timestamps via `java.time.Instant/now`

**Verification**:
- ✅ Line counts: 231 lines (email.clj), 108 lines (schema.clj)
- ✅ Linting: 0 errors, 0 warnings
- ✅ All functions properly documented with docstrings
- ✅ Code quality matches framework standards (kebab-case, comprehensive docstrings)

**Next**: Task 3.3 - Implement email ports (protocols)

## [2026-01-27] Task 3.3 Complete - Email Ports

**Summary**: Implemented protocol definitions in `libs/email/src/boundary/email/ports.clj`.

**Protocols Defined** (96 lines total):
1. **EmailSenderProtocol** - Email sending operations:
   - `send-email!` - Synchronous email sending (blocks until sent)
   - `send-email-async!` - Asynchronous email sending (returns future/promise)

2. **EmailQueueProtocol** - Email queue management:
   - `queue-email!` - Add email to queue for processing
   - `process-queue!` - Process next email in queue
   - `queue-size` - Get number of queued emails
   - `peek-queue` - Peek at next email without removing

**Key Design Decisions**:
- Protocol names use PascalCase: `EmailSenderProtocol`, `EmailQueueProtocol`
- Method names use kebab-case with `!` suffix for side effects
- All methods have comprehensive docstrings with Args and Returns sections
- Return values are maps with `:success?` or similar boolean + details
- Queue operations include error handling (`:error` map on failure)

**Pattern Followed**: Jobs module ports (`libs/jobs/src/boundary/jobs/ports.clj`)

**Subagent Issue**: Subagent failed TWICE to complete this task (claimed completion but no file changes). Orchestrator completed manually.

**Verification**:
- ✅ Line count: 96 lines
- ✅ Linting: 0 errors, 0 warnings
- ✅ All methods properly documented

**Next**: Task 3.4 - Implement SMTP adapter
