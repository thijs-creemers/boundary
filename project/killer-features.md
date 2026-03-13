# Boundary Framework: Killer Features

**Updated**: 2026-03-13

What makes Boundary different from Kit, Luminus, or rolling your own Clojure stack — and why those differences matter in practice.

---

## Delivered Features

### 1. Auto-Generated Admin UI

The Clojure ecosystem has never had a built-in admin interface. Django has it, Rails has ActiveAdmin, Phoenix doesn't, Kit doesn't, Luminus doesn't. Boundary does.

`boundary-admin` auto-generates CRUD interfaces from your entity configuration. Define your entity once; browse, search, filter, create, edit, and delete from a ready-made web UI. Role-based access, audit logging, and bulk operations are built in. No manual form wiring.

**Why it matters:** Every production application needs an admin panel. Building one by hand typically takes days per entity. With `boundary-admin` it takes minutes.

---

### 2. Enforced Functional Core / Imperative Shell

Boundary is the only Clojure framework that structurally enforces FC/IS across every module. It is not a recommendation — the directory layout (`core/`, `shell/`, `ports.clj`) makes it the path of least resistance.

- Pure business logic lives in `core/`. No I/O, no logging, no side effects. Testable without mocks.
- Side effects live in `shell/`. HTTP handlers, persistence, external calls.
- `ports.clj` defines protocols. Dependencies flow inward, never outward.

clj-kondo rules catch violations at lint time. Every library in the framework follows the same pattern, so any Boundary codebase is immediately familiar.

**Why it matters:** Codebases that start with clean architecture stay clean. Onboarding time drops. Refactoring becomes safe. Tests run fast.

---

### 3. Production-Grade Auth Out of the Box

`boundary-user` ships JWT authentication, TOTP-based multi-factor authentication, session management, and RBAC — all integrated with the FC/IS pattern. The auth logic is pure and testable; the session and token stores are injectable adapters.

No third-party auth service required. No copy-paste from tutorials.

---

### 4. Real-Time Without JavaScript Complexity

`boundary-realtime` provides WebSocket and SSE support with JWT-authenticated channels and a pub/sub model. Push state changes from the server; update the UI via HTMX fragments.

The design follows the Phoenix Channels model but integrated natively into the Boundary lifecycle.

---

### 5. Multi-Tenancy as a First-Class Concern

`boundary-tenant` provides PostgreSQL schema-per-tenant isolation. Provision a new tenant in seconds. Every query runs in the correct schema context automatically. The port abstraction means your business logic is unaware of tenancy — the shell handles it.

---

### 6. Background Jobs with Reliable Retry

`boundary-jobs` handles job queuing, execution, retry with exponential backoff, and dead-letter queues. Redis-backed. Jobs are first-class data: inspect them, replay them, monitor them from the admin UI.

---

### 7. Declarative State Machine Workflows

`boundary-workflow` lets you define entity lifecycles as data using `defworkflow`. Guards, permission-based transitions, lifecycle hooks, auto-transitions, and a full audit trail are built in. The entire state machine is pure core logic — no side effects bleed into the transition logic.

```clojure
(defworkflow order-workflow
  {:initial-state :pending
   :states        #{:pending :paid :shipped :delivered :cancelled}
   :transitions   [{:from :pending :to :paid   :required-permissions [:finance]}
                   {:from :paid    :to :shipped :guard :payment-confirmed}
                   {:from :shipped :to :delivered}]})
```

**Why it matters:** State machines are everywhere in business software. Rolling them manually leads to scattered conditional logic and no audit trail.

---

### 8. Full-Text Search with a Single Config Key

`boundary-search` provides document indexing, ranked full-text search, and autocomplete — backed by PostgreSQL FTS on production and a LIKE fallback on H2/SQLite for tests. No Elasticsearch, no external service.

Define a search index with `defsearch`, index your documents, and query. An admin UI lets you inspect indices and run live searches.

---

### 9. Report Generation from Declarative Definitions

`boundary-reports` generates PDF, Excel, and Word documents using the `defreport` macro. Define the report once; render to any format. Schedule reports via `boundary-jobs`. No per-format boilerplate.

---

### 10. Calendar and Scheduling

`boundary-calendar` handles recurring events with RFC 5545 RRULE support, DST-safe occurrence expansion, conflict detection, iCal export/import, and ready-made Hiccup UI components for month and week views.

---

### 11. Interactive Scaffolder

`bb scaffold` runs an interactive wizard that generates a complete, FC/IS-compliant module: entity schema, persistence, service, HTTP handlers, and tests. The output is production-ready, not a starting template that needs rewriting.

---

### 12. Pluggable Observability Stack

`boundary-observability` provides a unified three-pillar stack — structured logging, metrics, and error reporting — where every backend is swappable via configuration. No logging framework is baked in.

| Pillar | Adapters |
|--------|----------|
| Logging | no-op (tests), stdout (dev), SLF4J/Logback (prod), Datadog APM |
| Metrics | no-op (tests), Datadog (counters, gauges, histograms with tags) |
| Error reporting | no-op (tests), Sentry (with breadcrumbs and context) |

Switch from stdout to Datadog by changing one config key. Tests run with no-op adapters — no external services, no log noise, no mock setup.

The framework ships **multi-layer interceptors** that instrument your code automatically. Wrap a service operation or a persistence call with a single macro and you get structured logs, metrics emission, error capture, and breadcrumbs without writing any telemetry code yourself:

```clojure
;; Service layer — one wrapper, all telemetry automatic
(defn create-user [this user-data]
  (service-interceptors/execute-service-operation
    :create-user {:user-data user-data}
    (fn [{:keys [params]}]
      (user-core/prepare-user (:user-data params)))))

;; Persistence layer — same pattern
(defn find-user-by-email [this email]
  (persistence-interceptors/execute-persistence-operation
    logger error-reporter "find-user-by-email" {:email email}
    (fn [] (db/execute-one! ctx query))))
```

**Why it matters:** Most teams bolt observability on after the fact, inconsistently. Boundary makes it the default path — the instrumented version is shorter than the uninstrumented one.

---

### 13. Declarative Interceptor Pipeline Across All Layers

Boundary's interceptor model — enter / leave / error phases — is not just for HTTP. The same composable pipeline runs at the HTTP layer, the service layer, and the persistence layer. Every cross-cutting concern (auth, rate limiting, audit logging, metrics, error handling, correlation IDs) is a reusable interceptor that composes without modifying business logic.

```clojure
;; HTTP route — interceptors declared alongside the handler
{:path "/api/admin"
 :methods {:post {:handler    'handlers/create-resource
                  :interceptors ['auth/require-admin
                                 'audit/log-action
                                 'rate-limit/admin-limit]}}}
```

Interceptors execute enter-phases in order, leave-phases in reverse — guaranteeing that audit logging and metrics always fire even if the handler throws. Built-in interceptors cover request logging, correlation ID propagation, response metrics, and error normalisation (RFC 7807 Problem Details format).

**Why it matters:** Cross-cutting concerns added once apply everywhere, consistently. No scattered try/catch blocks, no repeated auth checks per handler.

---

### 14. Zero-Config Dev, Production-Grade in Prod — Same Code

`boundary-platform` supports SQLite (dev), PostgreSQL, MySQL, and H2 (tests) behind a single database port. Switch databases by changing the `:adapter` config key. No code changes, no import swaps.

```clojure
;; Development
{:boundary/db-context {:adapter :sqlite  :db "dev.db"}}

;; Production
{:boundary/db-context {:adapter    :postgresql
                       :host       #env DB_HOST
                       :database   #env DB_NAME
                       :pool-size  10}}
```

The same applies to every injectable adapter in the framework: cache backends (in-memory → Redis), observability (stdout → Datadog/Sentry), external services (stub → live). The development experience stays frictionless; production stays robust.

Platform also ships RFC 5988-compliant pagination (offset and cursor), API versioning, and Migratus-based schema migrations out of the box.

---

### 15. Rich Validation with PII Redaction

The validation framework in `boundary-core` goes well beyond schema checking:

- **Malli schemas** with human-readable error messages and error codes — not raw schema paths
- **"Did you mean?" suggestions** for field name typos
- **Cross-field and conditional validation** via behaviour definitions
- **Central validation registry** — all rules across all modules in one queryable place
- **Snapshot testing** — lock the output of a validation rule set; CI fails if it silently drifts
- **Coverage reporting** — see which paths through your validation rules are exercised by tests
- **PII redaction** — `boundary.core.utils.pii-redaction` strips sensitive fields (emails, passwords, tokens) before they reach logs or error reports

The PII redaction runs at the observability boundary. Every log line and every Sentry breadcrumb is sanitised automatically. No audits, no accidental GDPR violations in your error tracker.

---

### 16. Feature Flags Built In

`boundary.core.config.feature-flags` provides runtime feature flag evaluation in the functional core — no side effects, pure data. Flags are defined in configuration, evaluated against a context map (user, tenant, environment), and can gate individual code paths or entire HTTP routes.

No third-party service required. No SDK to integrate. No vendor lock-in.

---

### 17. Distributed Cache with Five Protocols

`boundary-cache` is not just a key-value wrapper. It exposes five distinct cache protocols, each testable independently:

| Protocol | Operations |
|----------|------------|
| `ICache` | get, set, delete, exists?, ttl |
| `IBatchCache` | get-many, set-many, delete-many |
| `IAtomicCache` | increment!, decrement!, set-if-absent! (SETNX) |
| `IPatternCache` | keys-matching, delete-matching |
| `INamespacedCache` | tenant-scoped key prefixing |

The `IAtomicCache` protocol enables distributed locks and counters without a separate lock service. The `INamespacedCache` wraps any cache with tenant-scoped key prefixing — `(create-tenant-cache cache "tenant-a")` ensures one tenant's cache can never collide with another's, and `flush-all!` on a tenant cache only deletes that tenant's keys.

In-memory backend for development and tests; Redis (Jedis, connection-pooled) for production.

---

### 18. External Service Adapters with FC/IS Separation

`boundary-external` ships four production adapters — Stripe, Twilio, SMTP, and IMAP — each following the same FC/IS pattern as every other Boundary library. The HTTP calls and Java mail I/O live in `shell/`; all transformation, parameter building, and webhook verification logic lives in pure `core/` functions you can test without network access.

| Adapter | Capabilities |
|---------|-------------|
| Stripe | Payment intents, webhook signature verification (constant-time HMAC), refunds |
| Twilio | SMS and WhatsApp messaging (including sandbox mode) |
| SMTP | Transactional email via javax.mail |
| IMAP | Mailbox reading, UID-based message tracking, unread filtering |

All four are shipped inactive in configuration and enabled by adding a single key to your `config.edn`. No code changes required.

---

## Competitive Snapshot

| Capability | Boundary | Django | Rails | Phoenix | Kit |
|------------|----------|--------|-------|---------|-----|
| FC/IS enforcement | Built-in | — | — | — | — |
| Auto-admin UI | Built-in | Built-in | Gem | — | — |
| MFA + JWT auth | Built-in | 3rd party | Gem | 3rd party | Buddy |
| Background jobs | Built-in | Celery | Gem | Oban | Module |
| Multi-tenancy | Built-in | 3rd party | Gem | 3rd party | — |
| Real-time | Built-in | Channels | Cable | Channels | Sente |
| Full-text search | Built-in | 3rd party | 3rd party | — | — |
| State machine workflows | Built-in | 3rd party | 3rd party | 3rd party | — |
| Report generation | Built-in | 3rd party | 3rd party | — | — |
| Calendar / scheduling | Built-in | 3rd party | 3rd party | — | — |
| Interactive scaffolding | Built-in | Built-in | Built-in | Built-in | Built-in |
| File storage (S3/local) | Built-in | 3rd party | 3rd party | — | — |
| Pluggable observability | Built-in | Manual | Gem | Manual | Manual |
| Automatic instrumentation | Built-in | Manual | Manual | Manual | Manual |
| Multi-layer interceptors | Built-in | — | — | — | — |
| Multi-database (no code changes) | Built-in | Manual | Manual | Manual | Manual |
| PII redaction in logging | Built-in | Manual | Manual | Manual | Manual |
| Feature flags | Built-in | 3rd party | Gem | 3rd party | — |
| Distributed cache + atomic ops | Built-in | Redis ext. | Redis ext. | Redis ext. | Module |
| Tenant-scoped caching | Built-in | Manual | Manual | Manual | — |
| Stripe + Twilio adapters | Built-in | 3rd party | Gem | 3rd party | — |
| Validation with coverage + snapshots | Built-in | Manual | Manual | Manual | — |

**Legend:** Built-in = ships with the framework. 3rd party = community library or external service. Manual = possible but must be wired by the developer. — = not available or not applicable.

---

## Roadmap

Features that differentiate Boundary and are not yet shipped.

### AI-Powered Developer Experience

Deep framework-aware AI tooling integrated into the CLI and REPL:

- **Natural language scaffolding** — describe a module in plain English; get schema, persistence, routes, and tests generated in seconds.
- **Error explainer** — analyze stack traces with context awareness of FC/IS patterns, ports, and schemas.
- **Test generator** — generate unit tests from function signatures and docstrings.
- **SQL copilot** — natural language → HoneySQL query with explanation.
- **Offline-first** — local models via Ollama by default; no data leaves the machine.

### Independent Module Deployments (Dual-Mode Runtime)

Every Boundary module is designed to run in two modes with zero code changes:

- **Composed mode** (monolith): In-process calls, shared database and cache, single JAR.
- **Standalone mode** (microservice): Own HTTP server, own database connection, own observability.

Toggle via configuration. Start simple, extract hot modules when needed — no rewrites, no architecture migrations.

### Push Notifications (`boundary-push`)

Mobile apps live or die on engagement. `boundary-push` adds multi-platform push notification delivery — Firebase (FCM) and Apple (APNs) — without any third-party service like OneSignal or Pusher.

- **Device token management** — registration, rotation, and cleanup per user
- **Scheduled pushes** — via `boundary-jobs` for reliable async delivery
- **Deep linking** — navigate the receiving app to a specific screen on open
- **Silent notifications** — data-only pushes to trigger background syncs
- **Delivery analytics** — track delivery rates and open rates
- **Multi-language** — notification content per locale

Declared with a `defpush` macro, consistent with `defreport`, `defevent`, and `defworkflow`.

**Typical use cases:** order status updates, appointment reminders, transaction alerts, social notifications, on-demand service tracking.

---

### Audience Segmentation (`boundary-audience`)

Targeted communication is 2–5× more effective than broadcast. `boundary-audience` provides a declarative, reusable audience engine that `boundary-push`, `boundary-email`, and `boundary-forms` can all build on — rather than each library re-implementing its own filter logic.

- **Declarative segment definitions** via `defaudience` macro
- **Built-in filter types** — demographics, location, behaviour, account tenure, feature usage, and more
- **Dynamic evaluation** — segments are evaluated at runtime against live user data
- **Caching layer** — precomputed segments with TTL and scheduled refresh for performance
- **Admin UI** — visual audience builder with live preview of segment size
- **Composable** — combine segments with AND / OR / NOT logic

**Typical use cases:** email campaigns, push targeting, survey distribution, feature announcements, onboarding flows.

---

### Dynamic Forms (`boundary-forms`)

Many applications need user-configurable forms — NPS surveys, onboarding questionnaires, lead capture, employee feedback — where the structure changes without a deployment. `boundary-forms` provides a self-hosted form engine so you never need Typeform, Jotform, or Google Forms.

- **Declarative form definitions** via `defform` macro
- **Question types** — text, number, single/multi choice, rating scale, date, file upload
- **Conditional logic** — show or hide questions based on previous answers
- **Response storage** — answers stored as JSONB; queryable and exportable
- **Mandatory forms** — block application access until a required form is submitted
- **Analytics** — completion rates, time-to-complete, aggregate statistics per question
- **Admin UI** — drag-and-drop form builder, response viewer

**Typical use cases:** employee surveys, customer NPS, lead generation forms, research questionnaires, compliance acknowledgements.

---

### Geocoding (`boundary-geo`)

Location features appear in a surprisingly large share of applications — store locators, delivery address validation, proximity search, map visualisations. `boundary-geo` wraps the messiness of provider APIs behind a clean protocol.

- **Multi-provider geocoding** — Google Maps, OpenStreetMap/Nominatim, Mapbox, Azure Maps
- **Reverse geocoding** — coordinates to human-readable address
- **Caching layer** — avoid redundant API calls; address hashes cached with configurable TTL
- **Rate limiting** — per-provider request throttling out of the box
- **Distance calculations** — Haversine formula for point-to-point distances
- **Provider fallback** — configurable failover if the primary provider is unavailable

Because `boundary-geo` abstracts providers behind a port, switching from OpenStreetMap (free) to Google Maps (paid, higher accuracy) is a configuration change, not a code change.

**Typical use cases:** delivery address validation, store locators, CRM contact mapping, logistics routing, real estate listings.

---

## One-Sentence Summary

> Boundary is the first framework that ships enforced FC/IS architecture, auto-generated admin UI, pluggable observability with automatic instrumentation, declarative multi-layer interceptors, state machine workflows, full-text search, report generation, calendar scheduling, multi-tenancy, real-time, distributed caching, background jobs, PII-safe logging, built-in feature flags, and Stripe/Twilio/IMAP adapters — all in one coherent stack, all following the same patterns, all swappable via configuration.
