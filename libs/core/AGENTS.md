# Core Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Foundation library providing shared utilities used by all other Boundary modules. Contains **no shell layer** - everything is pure functions.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.core.validation` | Malli-based validation framework with error codes, messages, and "did you mean?" suggestions |
| `boundary.core.validation.registry` | Central registry for validation rules across modules |
| `boundary.core.validation.behavior` | Validation behavior definitions (cross-field, conditional) |
| `boundary.core.validation.coverage` | Validation coverage reporting and analysis |
| `boundary.core.validation.snapshot` | Snapshot-based validation testing |
| `boundary.core.utils.case-conversion` | kebab-case / snake_case / camelCase conversions |
| `boundary.core.utils.type-conversion` | UUID, Instant, BigDecimal string conversions |
| `boundary.core.utils.pii-redaction` | PII filtering for logs and error reports |
| `boundary.core.utils.validation` | Generic validation helpers (validate-with-transform, validate-cli-args) |
| `boundary.core.interceptor` | Interceptor pipeline runner (enter/leave/error phases) |
| `boundary.core.interceptor-context` | Interceptor context creation and management |
| `boundary.core.config.feature-flags` | Feature flag evaluation logic |

## Case Conversion Utilities

These are the most commonly used functions across the entire monorepo:

```clojure
(require '[boundary.core.utils.case-conversion :as cc])

;; At persistence boundary - DB to Clojure
(cc/snake-case->kebab-case-map db-record)

;; At persistence boundary - Clojure to DB
(cc/kebab-case->snake-case-map entity)

;; At API boundary
(cc/kebab-case->camel-case-map entity)
(cc/camel-case->kebab-case-map api-input)
```

## Validation Framework

```clojure
(require '[boundary.core.utils.validation :as validation])

;; Generic validation with transformation
(validation/validate-with-transform SomeSchema data mt/string-transformer)
;; => {:valid? true :data transformed-data}
;; => {:valid? false :errors validation-errors}

;; CLI argument validation
(validation/validate-cli-args CliSchema args cli-transformer)
```

## Interceptor Pipeline

The interceptor system supports enter/leave/error phases, used by both HTTP and service/persistence layers:

```clojure
(require '[boundary.core.interceptor :as ic])

;; Run pipeline: enter phases forward, leave phases reverse
(ic/run-pipeline initial-ctx [interceptor1 interceptor2 interceptor3])
;; Enter order:  int1 → int2 → int3
;; Leave order:  int3 → int2 → int1
```

## Important Conventions

- **All functions in this library must be pure** - no I/O, no logging, no side effects
- This library has **no ports.clj or shell/** - it's entirely functional core
- Only dependency: `org.clojure/clojure` and `metosin/malli`
- Other libraries depend on core, but core depends on nothing else

## Testing

```bash
clojure -M:test:db/h2 :core
clojure -M:test:db/h2 --focus-meta :unit   # All core tests are :unit
```
