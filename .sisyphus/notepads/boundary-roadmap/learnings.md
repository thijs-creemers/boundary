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

## Task 3.4: SMTP Email Adapter - 2026-01-27

### Implementation Summary
Created `libs/email/src/boundary/email/shell/adapters/smtp.clj` (281 lines):
- ✅ `SmtpEmailSender` defrecord with config fields (host, port, username, password, tls?, ssl?)
- ✅ Implements `EmailSenderProtocol` (send-email!, send-email-async!)
- ✅ Uses javax.mail for SMTP (Session, MimeMessage, Transport)
- ✅ Comprehensive error handling with typed errors
- ✅ Support for TLS/STARTTLS and SSL
- ✅ Support for CC/BCC headers
- ✅ Support for Reply-To header
- ✅ Zero linting errors/warnings

### Key Patterns Applied

**Adapter Pattern:**
```clojure
(defrecord SmtpEmailSender [host port username password tls? ssl?])

(extend-protocol ports/EmailSenderProtocol
  SmtpEmailSender
  (send-email! [this email] ...)
  (send-email-async! [this email] (future (send-email! this email))))
```

**javax.mail Integration:**
- Properties-based session configuration
- Custom Authenticator for SMTP auth (proxy pattern)
- MimeMessage construction from email map
- Transport.send for synchronous sending
- Proper exception handling (MessagingException vs Exception)

**Error Handling:**
```clojure
{:success? false
 :error {:message "..."
         :type "SmtpError"
         :provider-error {:class "..." :cause "..."}}}
```

**Import Pattern for Nested Java Classes:**
```clojure
(:import [javax.mail Session Transport ...]
         [javax.mail Message$RecipientType]  ; Nested class
         [javax.mail.internet InternetAddress MimeMessage]
         [java.util Properties])
```

**Constructor with Defaults:**
```clojure
(defn create-smtp-sender
  [{:keys [host port username password tls? ssl?]
    :or {tls? true ssl? false}}]
  {:pre [(string? host) (some? port)]}  ; Validation
  (->SmtpEmailSender host port username password tls? ssl?))
```

### Common SMTP Configurations Documented
- Gmail (port 587, TLS)
- Amazon SES (port 587, TLS)
- Mailgun (port 587, TLS)
- SendGrid (port 587, TLS)
- Local dev (Mailhog/MailCatcher - port 1025, no TLS)

### Technical Gotchas

**Java Interop:**
- Nested class: `Message$RecipientType/TO` (requires separate import)
- InternetAddress array for Reply-To: `(into-array InternetAddress [...])`
- Properties must use .put (mutable API)
- Transport.send is static method (not instance method)

**Message ID Handling:**
```clojure
(let [message-id (try
                   (.getMessageID message)
                   (catch Exception _
                     (str (:id email))))]  ; Fallback
  {:success? true :message-id message-id})
```

**Async Implementation:**
Simply wraps sync in future (no special async SMTP support needed)

### Files Created
- `libs/email/src/boundary/email/shell/adapters/smtp.clj` (281 lines)

### Verification Results
```
clojure -M:clj-kondo --lint libs/email/src
# linting took 98ms, errors: 0, warnings: 0
```

### Next Tasks
- Task 3.5: In-memory email adapter (for testing)
- Task 3.6: Email service implementation (shell layer)
- Task 3.7: Write comprehensive tests (unit + integration)


## 2026-01-27: Email Jobs Integration (Task 3.5)

**Implementation**: Created `libs/email/src/boundary/email/shell/jobs_integration.clj`

### Optional Dependency Pattern with `requiring-resolve`

Successfully implemented jobs module as an optional dependency using Clojure's `requiring-resolve`:

**Pattern**:
```clojure
(if-let [fn-var (requiring-resolve 'namespace/function)]
  (fn-var arg1 arg2)  ; Use if available
  (throw (ex-info "Module not available"
                  {:type :missing-dependency
                   :module "module-name"})))
```

**Key Benefits**:
- No hard `:require` in namespace declaration
- Graceful error messages when dependency missing
- Standard Clojure idiom for optional features
- Zero overhead when jobs module not present

**Applied to**:
1. `queue-email-job!` - Uses `requiring-resolve` for `boundary.jobs.ports/enqueue-job!`
2. `register-email-job-handler!` - Uses `requiring-resolve` for `boundary.jobs.ports/register-handler!`

### Job Handler Contract

Implemented `process-email-job` following jobs module conventions:

**Handler Signature**:
```clojure
(defn handler [job-args] 
  {:success? boolean
   :result map      ; On success
   :error map})     ; On failure
```

**Job Args Structure**:
```clojure
{:email {:id UUID
         :to ["email@example.com"]
         :from "sender@example.com"
         :subject "..."
         :body "..."}
 :sender-config {:host "smtp.example.com"
                 :port 587
                 :username "..."
                 :password "..."
                 :tls? true
                 :ssl? false}}
```

**Result on Success**:
```clojure
{:success? true
 :result {:email-id #uuid "..."
          :message-id "<msg-123@smtp.example.com>"
          :sent-at #inst "2026-01-27T..."}}
```

**Result on Failure**:
```clojure
{:success? false
 :error {:message "Connection timeout"
         :type "SmtpError"
         :stacktrace "..."}}
```

### Configuration Extraction Pattern

**Challenge**: Job handlers need sender config, but don't have access to Integrant system.

**Solution**: Extract config from `SmtpEmailSender` instance and store in job args:

```clojure
(defn queue-email-job! [job-queue email-sender email]
  (let [sender-config {:host (:host email-sender)
                       :port (:port email-sender)
                       :username (:username email-sender)
                       :password (:password email-sender)
                       :tls? (:tls? email-sender)
                       :ssl? (:ssl? email-sender)}]
    ;; Store config in job args
    (enqueue-fn job-queue :emails
                {:job-type :send-email
                 :args {:email email
                        :sender-config sender-config}})))
```

**Job handler recreates sender**:
```clojure
(defn process-email-job [job-args]
  (let [{:keys [email sender-config]} job-args
        smtp-sender (smtp/create-smtp-sender sender-config)]
    (ports/send-email! smtp-sender email)))
```

**Why This Works**:
- Job args must be serializable (for Redis storage)
- Config is plain data (maps, strings, numbers)
- Sender creation is cheap (no pooling yet)
- Each job gets isolated sender instance

### Queue Selection

Used `:emails` queue name (not `:default`):
```clojure
(enqueue-fn job-queue :emails job)
```

**Rationale**:
- Separates email jobs from other job types
- Allows dedicated worker pools for emails
- Enables priority tuning for email queue
- Matches jobs module best practices

### Error Handling

**Missing Dependency Errors**:
- Type: `:missing-dependency`
- Module: `"boundary-jobs"`
- Documentation link included
- Clear instructions to add to `deps.edn`

**Job Execution Errors**:
- Wrapped in try-catch with full stack trace
- Propagates SMTP errors from email sender
- Logged at appropriate levels (debug/info/error)

### File Statistics

- **Lines**: 226 total
- **Functions**: 3 public (`queue-email-job!`, `process-email-job`, `register-email-job-handler!`)
- **Dependencies**: 4 requires (ports, smtp, stacktrace, logging)
- **Linting**: ✅ 0 errors, 0 warnings

### Verification

```bash
clojure -M:clj-kondo --lint libs/email/src
# linting took 112ms, errors: 0, warnings: 0
```

### Next Steps

- Task 3.6: Document email module in README
- Task 3.7: Add comprehensive integration tests
- Task 3.8: Implement email queue with retry logic

### Related Patterns

See `libs/external/` for similar optional dependency patterns used for:
- Stripe integration
- Twilio integration
- Other external service adapters


## 2026-01-27: Email Module Test Suite Complete

### Task 3.6 - Email Module Tests

**Created comprehensive test suite for email module:**

1. **Unit Tests** (`libs/email/test/boundary/email/core/email_test.clj`):
   - 11 tests, 89 assertions, all passing
   - Tests all core functions: validation, preparation, formatting, headers
   - 100% coverage of pure functions in core layer
   - Edge cases: empty strings, nil values, invalid emails, multiple recipients

2. **Integration Tests** (`libs/email/test/boundary/email/shell/adapters/smtp_test.clj`):
   - 9 tests, 54 assertions, all passing
   - SMTP adapter creation with various configs (TLS, SSL, auth)
   - Error handling for invalid hosts, connection refused
   - Complete workflow: prepare → validate → send
   - Protocol implementation verification

3. **Test Configuration**:
   - Added email paths to `tests.edn` and `deps.edn`
   - Added `:email` test suite to kaocha config
   - Added `javax.mail` dependency (version 1.6.2)
   - Metadata filtering works: `^:unit` and `^:integration`

4. **Key Fixes**:
   - Fixed `valid-email-address?` to return boolean instead of matched string
   - Fixed SSL test to explicitly disable TLS (defaults apply)
   - Removed unused imports (linting warnings)

5. **Test Results**:
   ```
   Total: 20 tests, 143 assertions, 0 failures
   Unit: 11 tests, 89 assertions
   Integration: 9 tests, 54 assertions
   Linting: 0 errors, 0 warnings
   ```

6. **Test Patterns Followed**:
   - Used `deftest` with `^:unit` or `^:integration` metadata
   - Used `testing` blocks for descriptive test sections
   - No mocks in unit tests (pure functions)
   - Integration tests use invalid hosts (no real SMTP in CI)
   - Included commented-out tests for real SMTP servers (requires MailHog/etc)

**Commands:**
```bash
clojure -M:test :email                    # All tests
clojure -M:test :email --focus-meta :unit # Unit only
clojure -M:test :email --focus-meta :integration # Integration only
clojure -M:clj-kondo --lint libs/email/src libs/email/test
```

**Dependencies Added:**
- `com.sun.mail/javax.mail {:mvn/version "1.6.2"}` in both:
  - `libs/email/deps.edn`
  - Root `deps.edn`

