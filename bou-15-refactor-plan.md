# BOU-15 Refactor Plan: Deprecate Non-Deterministic Core APIs

## Objective

Remove runtime-dependent behavior from `core` namespaces so functional-core code stays deterministic and replayable.

Any value derived from:

- current time or date
- random UUID generation
- process or environment inspection
- system-default timezone or locale

must be acquired in the imperative shell and passed into core as explicit input.

This plan covers:

- impact analysis of the current codebase
- deprecation and migration strategy
- tooling needed to complete the transition safely
- final cleanup steps to remove compatibility shims without regressions

## Executive Summary

BOU-15 is already partly implemented on this branch.

Completed first-wave migrations:

- `tenant.core.invite`
- `tenant.core.membership`
- `search.core.index`
- `platform.core.http.problem-details`
- `platform.core.search.ranking`
- `platform.core.pagination.versioning`
- `storage.core.validation`
- `calendar.core.recurrence`
- `user.core.ui`
- `reports.core.report`
- `scaffolder.core.generators`

Current state:

- `bb check:fcis` passes.
- `bb check:fcis` now detects both fully-qualified and imported/simple-form static runtime access such as `java.time.Instant/now` and `(Instant/now)`.
- `bb bou-15:deprecated-usage` now reports deprecated wrapper call sites and currently shows zero production usages.
- Deprecated wrappers and `*`-suffixed pure replacements are already present in most migrated namespaces.
- Shell callers and tests have largely been updated to inject IDs, timestamps, dates, zone IDs, environment, and process context explicitly.
- The residual `user.core.user/analyze-user-activity` violation has been removed by making the reference instant explicit.
- Legacy runtime helpers in `boundary.core.utils.type-conversion` now fail loudly with typed deprecation errors instead of reading time or randomness in core.
- Scaffolder regression tests now assert that generated core namespaces do not emit forbidden runtime APIs.
- AGENTS/docs examples have been updated to point at explicit-input APIs instead of deprecated wrappers.

Remaining work:

1. Define the wrapper-removal window for the deprecated compatibility layer.
2. Remove deprecated wrappers and the corresponding deprecation tests once the breaking-change window is accepted.

## Why This Matters

Non-deterministic core functions violate FC/IS in three ways:

1. They make pure functions depend on ambient runtime state.
2. They force tests to rely on fuzzy assertions instead of exact values.
3. They hide important policy decisions, such as ID generation and time selection, inside the wrong layer.

The correct model is:

- shell acquires facts
- core transforms facts

Examples:

- shell calls `UUID/randomUUID`; core receives `user-id`
- shell calls `Instant/now`; core receives `now`
- shell chooses `ZoneId`; core receives `zone-id`
- shell reads environment or process metadata; core receives a context map

## Current Impact Analysis

### First-wave migration status

The originally identified BOU-15 hotspots have already been refactored to use explicit-input alternatives and deprecated wrappers.

| Area | Core namespace | Migration status | Notes |
|---|---|---|---|
| Tenant invites | `libs/tenant/src/boundary/tenant/core/invite.clj` | migrated | `prepare-invite*` added; wrapper deprecated |
| Tenant memberships | `libs/tenant/src/boundary/tenant/core/membership.clj` | migrated | injected membership IDs |
| Search indexing | `libs/search/src/boundary/search/core/index.clj` | migrated | document ID and timestamp injected |
| Problem details | `libs/platform/src/boundary/platform/core/http/problem_details.clj` | migrated | runtime context moved to shell |
| Search ranking | `libs/platform/src/boundary/platform/core/search/ranking.clj` | migrated | hidden `now` default deprecated |
| API versioning | `libs/platform/src/boundary/platform/core/pagination/versioning.clj` | migrated | current date injected |
| Storage naming | `libs/storage/src/boundary/storage/core/validation.clj` | migrated | deterministic filename builder added |
| Calendar recurrence | `libs/calendar/src/boundary/calendar/core/recurrence.clj` | migrated | reference instant injected |
| User UI formatting | `libs/user/src/boundary/user/core/ui.clj` | migrated | `now` and `zone-id` injected |
| Reports formatting | `libs/reports/src/boundary/reports/core/report.clj` | migrated | formatting context injected |
| Scaffolder templates | `libs/scaffolder/src/boundary/scaffolder/core/generators.clj` | partially migrated | template output changed, but needs ongoing verification |

### Residual risks discovered during review

The migration is not fully closed out yet. One enforcement gap has been closed, but a few completion risks remain.

#### 1. FC/IS checker gap was real and is now closed

`check:fcis` originally only detected fully-qualified symbol usage inside stripped source, for example:

- `java.time.Instant/now`
- `java.util.UUID/randomUUID`

That missed imported-static style calls such as:

```clojure
(:import (java.time Instant))
...
(Instant/now)
```

Concrete residual example that was fixed:

- `libs/user/src/boundary/user/core/user.clj`
  - `analyze-user-activity`
  - previously called `(Instant/now)`

The checker now resolves imported and implicit simple class names and flags calls such as:

- `(Instant/now)`
- `(UUID/randomUUID)`
- `(System/currentTimeMillis)`
- `(ProcessHandle/current)`

This work also exposed two dormant runtime helpers in `libs/core/src/boundary/core/utils/type_conversion.clj`; those symbols now throw typed deprecation errors instead of reading runtime state in core.

#### 2. Scaffolder remains a regression vector, but is now covered

`libs/scaffolder/src/boundary/scaffolder/core/generators.clj` still contains template strings with `Instant/now` and `UUID/randomUUID`.

Even when those occurrences are not runtime calls in the scaffolder itself, they matter because scaffolder output can reintroduce BOU-15 violations into newly generated libraries unless template expectations are kept aligned with the rule.

That risk is now partially controlled by generator regression tests that assert generated core code does not contain the forbidden runtime APIs. The shell template is still expected to own runtime generation.

### Secondary impact areas

The following areas are not blocked today, but they are part of the safe completion path:

- shell callers that still rely on deprecated wrappers
- tests that only assert shapes or regexes instead of exact values
- docs and scaffolding guidance that still imply core may allocate IDs or read current time
- compatibility wrappers retained for migration safety

## Target Architecture

### Rule

Core functions may consume runtime-derived values, but may not obtain them.

### Allowed in core

- `now` passed in as an `Instant`
- `current-date` passed in as a `LocalDate`
- generated IDs passed in explicitly
- environment/process metadata passed in via maps
- zone IDs or formatting context passed in explicitly

### Not allowed in core

- `Instant/now`
- `LocalDate/now`
- `UUID/randomUUID`
- `System/currentTimeMillis`
- `System/getProperty`
- `ProcessHandle/current`
- `ZoneId/systemDefault`
- equivalent ambient runtime access via Clojure helpers or imported Java classes

## Migration Pattern

### API shape

Use explicit-input pure replacements, then deprecate legacy wrappers.

Examples:

```clojure
;; old
(prepare-invite tenant-id email now) ; generates ID implicitly

;; transitional
(prepare-invite* tenant-id email {:invite-id invite-id
                                  :now now})

;; eventual steady state after wrapper removal
(prepare-invite tenant-id email {:invite-id invite-id
                                 :now now})
```

```clojure
;; old
(calculate-document-age-days created-at)

;; transitional
(calculate-document-age-days* created-at now)
```

```clojure
;; old
(request->context request)

;; transitional
(request->context* request {:timestamp now
                            :environment env})
```

### Transitional naming

Use `*`-suffixed replacements during migration because they let us:

- add pure alternatives without a breaking rename
- migrate shell callers incrementally
- keep deprecation localized and explicit

### Deprecation behavior

Legacy wrappers in `core` should:

- carry a clear `Deprecated for BOU-15` docstring
- point to the replacement
- fail loudly via typed exception rather than silently continuing implicit behavior

They should not:

- log
- emit runtime warnings
- perform any shell-like side effects

## Execution Plan

### Phase 1. Finish the audit

Goal: produce a complete list of violations, including ones current tooling misses.

Actions:

1. Extend detection beyond fully-qualified symbols.
2. Scan for imported static calls in all `core` namespaces.
3. Scan scaffolder templates for generated violations.
4. Record deprecated wrapper usage in production code and tests.

Deliverable:

- a trustworthy inventory of remaining BOU-15 work

### Phase 2. Fix residual core violations

Goal: remove the remaining runtime access still present in core.

Current known residual:

- no live runtime-dependent core calls are currently known

Actions:

1. Keep `bb check:fcis` clean as new namespaces are touched.
2. Convert any newly discovered helpers to explicit-input APIs or typed deprecations.
3. Re-run targeted suites and `bb check:fcis`.

Deliverable:

- zero known runtime-dependent calls in core source

### Phase 3. Close the tooling gap

Goal: make the enforcement rule match the architectural rule.

`check:fcis` should detect:

- fully-qualified forbidden calls
- imported static forbidden calls
- core usages of `random-uuid`
- ambient randomness helpers where applicable

Recommended implementation approach:

1. Reuse parsed `ns` form imports.
2. Build an alias/import table for forbidden classes.
3. Flag patterns like `(Instant/now)` when `Instant` is imported from `java.time.Instant`.
4. Keep line-level output so fixes remain actionable.

Minimum forbidden set:

- `java.util.UUID/randomUUID`
- `java.time.Instant/now`
- `java.time.LocalDate/now`
- `java.time.LocalDateTime/now`
- `java.time.OffsetDateTime/now`
- `java.time.ZonedDateTime/now`
- `java.time.ZoneId/systemDefault`
- `java.lang.System/currentTimeMillis`
- `java.lang.System/getProperty`
- `java.lang.ProcessHandle/current`
- `clojure.core/random-uuid`

Deliverable:

- `bb check:fcis` fails on both `java.time.Instant/now` and `(Instant/now)`

### Phase 4. Add migration tooling

Goal: make deprecated-wrapper cleanup measurable and low-risk.

Recommended tooling:

1. `bb bou-15:report`
   - lists remaining runtime-dependent core calls
   - lists deprecated BOU-15 wrappers
   - lists call sites still using them

2. `bb bou-15:deprecated-usage`
  - prints usages of wrappers such as `prepare-invite`, `build-document`, `request->context`, `format-relative-time`, where the non-`*` form is still referenced
  - should fail when production usage is non-zero and allow test-only usage during the deprecation window

3. Optional codemod guidance
   - pattern-based rewrite suggestions for common migrations
   - not mandatory, but useful for repetitive shell/test updates

Deliverable:

- migration progress is queryable instead of manual
- current branch state: zero production wrapper usage

### Phase 5. Freeze legacy usage

Goal: stop creating new debt while compatibility wrappers still exist.

Actions:

1. Forbid new production references to deprecated wrappers.
2. Permit temporary test references only where explicitly justified.
3. Update scaffolder templates so new modules generate only explicit-input core APIs.
4. Update docs and AGENTS guidance to show shell-owned time/ID acquisition.

Deliverable:

- no new code is written against deprecated BOU-15 wrappers

### Phase 6. Remove deprecated wrappers

Goal: finish the migration and restore clean API names.

Removal preconditions:

- no production callers of deprecated wrappers
- test suite updated
- scaffolder templates emit the new pattern only
- `bb check:fcis` catches both fully-qualified and imported static forms

Removal steps:

1. delete deprecated wrappers
2. rename `foo*` back to `foo` where desired
3. update references
4. run focused and broad regression tests

Deliverable:

- stable, explicit, FC/IS-safe core API surface

## Module-Specific Completion Notes

### Tenant

Status: migrated.

Remaining work:

- remove deprecated wrappers after caller usage reaches zero
- keep shell services as the sole ID allocators

### Search

Status: migrated.

Remaining work:

- ensure no shell or tests still depend on legacy wrapper names

### Platform problem details

Status: migrated.

Remaining work:

- keep runtime context construction in shell middleware and CLI utilities only
- remove deprecated wrappers after downstream callers stabilize

### Platform versioning and ranking

Status: migrated.

Remaining work:

- enforce explicit current-date/current-time input everywhere

### Storage

Status: migrated.

Remaining work:

- keep generated suffix and timestamp policy in shell adapters only

### Calendar

Status: migrated.

Remaining work:

- ensure all reference-time decisions are shell-owned

### User UI

Status: migrated.

Remaining work:

- keep presentation context explicit
- remove legacy wrapper usage

### Reports

Status: migrated.

Remaining work:

- keep formatting/timezone policy out of core defaults

### Scaffolder

Status: partially migrated and still sensitive.

Remaining work:

- keep scaffold-output regression coverage in place as templates evolve

### User core

Status: migrated.

Remaining work:

- none in the current branch state

## Testing Strategy

Each migration or cleanup step should move tests toward exact determinism.

Expected changes:

- replace fuzzy regex assertions with exact values where possible
- inject fixed instants, dates, zone IDs, and UUIDs in tests
- add checker tests for imported static calls
- add scaffolder regression tests that inspect generated core files

Recommended verification sequence:

1. focused namespace tests for the touched module
2. affected library suite
3. `bb check:fcis`
4. wider regression sweep before wrapper removal

## Risks and Mitigations

### Risk: false sense of completion

Cause:

- checker passes while imported static calls remain

Mitigation:

- fix checker before declaring BOU-15 complete

### Risk: wrapper removal breaks downstream callers

Cause:

- callers still use deprecated names

Mitigation:

- add deprecated-usage reporting first
- remove wrappers only after usage reaches zero

Current state:

- production usage is zero
- remaining references are limited to deprecation-focused tests

### Risk: scaffolder reintroduces violations

Cause:

- templates drift from policy

Mitigation:

- add scaffold-output regression tests

### Risk: tests still encode hidden runtime behavior

Cause:

- old assumptions survive after migration

Mitigation:

- move tests to explicit fixtures and exact assertions

## Definition of Done

BOU-15 is complete when all of the following are true:

1. No `core` namespace reads time, date, randomness, process, environment, or system-default timezone directly.
2. `bb check:fcis` catches both fully-qualified and imported static forms of the forbidden APIs.
3. No production code calls deprecated BOU-15 wrappers.
4. Scaffolder output is FC/IS-safe by default.
5. Tests inject deterministic values and assert exact behavior.
6. Deprecated wrappers are removed, or a dated removal ticket exists with zero remaining production usage.

## Recommended Immediate Next Steps

1. Decide whether wrapper removal happens in this branch or in the next explicit breaking-change window.
2. If removal is approved, delete deprecated wrappers and replace deprecation tests with direct `*`-API coverage.
3. Keep `bb check:fcis` and `bb bou-15:deprecated-usage` in the standard verification flow until wrappers are gone.
