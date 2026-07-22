# Jobs Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Background job processing with priority queues, scheduled execution, automatic retries with exponential backoff, and multi-tenant support. In-memory (dev/test) and Redis (production) backends.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.jobs.core.job` | Pure functions: job creation, state transitions, retry logic |
| `boundary.jobs.ports` | Protocols: IJobQueue, IJobStore, IJobStats, IJobWorker, IJobRegistry |
| `boundary.jobs.schema` | Malli schemas: job states, priorities, retry config |
| `boundary.jobs.shell.adapters.in-memory` | In-memory adapter (atoms, for dev/test) |
| `boundary.jobs.shell.adapters.redis` | Redis adapter (sorted sets, lists, hashes) |
| `boundary.jobs.shell.adapters.db` | DB adapter (next.jdbc, H2/PostgreSQL) — durable jobs on your existing SQL database |
| `boundary.jobs.shell.worker` | Worker implementation: polling, processing, pool management |
| `boundary.jobs.shell.tenant-context` | Multi-tenant job execution with schema switching |

## DB-backed adapter (`adapters.db`)

Durable jobs on your existing SQL database (H2 or PostgreSQL) — no Redis
required, survives a Redis outage.

```clojure
(require '[boundary.jobs.shell.adapters.db :as db])
(db/create-jobs-table! ds)                 ; once at startup (or ship as a migration)
(def q (db/create-db-job-queue ds :lease-ms 60000))
```

- Implements the same reliable-dequeue contract as Redis: `dequeue-job!` claims
  a row (status `processing`, `locked_by`/`locked_at`); `ack-job!` removes it;
  `reclaim-abandoned-jobs!` returns rows whose **lease** (`locked_at` older than
  `:lease-ms`) has expired back to `ready` (at-least-once).
- **Portable SQL** (no `SELECT … FOR UPDATE SKIP LOCKED`): claim is a SELECT of
  the best candidate + a conditional `UPDATE … WHERE status='ready'`; the row
  lock serialises concurrent workers (one wins, losers retry). Runs identically
  on H2 and PostgreSQL, so its reliability tests run on the default H2 test DB.
- The authoritative job is the JSON `payload` column (same wire format as the
  Redis adapter); the other columns exist only for ordering/claim/reclaim.
- Transactional enqueue (outbox — enqueue in the caller's business-DB tx) is a
  planned follow-up (BOU-181).

## Job Handler Signature

```clojure
;; Handlers receive args map, return result map
(defn my-handler [args]
  {:success? true
   :result {:processed (:id args)}})

;; Failed handler
(defn failing-handler [args]
  {:success? false
   :error {:message "Processing failed" :type "ProcessingError"}})
```

## Usage Patterns

```clojure
;; Register handler
(require '[boundary.jobs.shell.worker :as worker])
(def registry (worker/create-job-registry))
(ports/register-handler! registry :send-email email-handler)

;; Enqueue job
(require '[boundary.jobs.core.job :as job])
(let [new-job (job/create-job {:job-type :send-email
                                :args {:to "user@example.com"}
                                :priority :high
                                :max-retries 3}
                               (java.util.UUID/randomUUID))]
  (ports/enqueue-job! job-queue :default new-job))

;; Start worker
(def w (worker/create-worker
         {:queue-name :default :poll-interval-ms 1000}
         queue store registry))

;; Or worker pool
(def pool (worker/create-worker-pool
            {:queue-name :default :worker-count 5 :poll-interval-ms 1000}
            queue store registry))
(worker/stop-worker-pool! pool)
```

## Job State Transitions

```
pending → running → completed
                  → failed (after max retries)
running → retrying → pending → running → ...
```

## Retry Logic

- Default max-retries: 3
- Exponential backoff: `delay = initial-delay * 2^retry-count`
- Capped at 60 seconds with random jitter (±10%)
- Dead letter queue after max retries exhausted

## Missing Handlers (multi-instance)

The handler registry is per-process. When a worker dequeues a job whose type it
has no handler for, it does **not** silently dead-letter it — the job is
**re-enqueued** for another instance that registered the handler, and dead-lettered
(with a `NoHandlerError`) only once it has gone unhandled for too long. A worker
created with an **empty** registry logs a loud warning at startup.

Two safeguards make this correct on a heterogeneous, loaded fleet:

- **Delayed re-enqueue** — the job is parked in the scheduled set
  `:requeue-delay-ms` into the future (default 1000), not pushed back onto the
  ready queue. A handlerless worker treats a re-enqueue as "processed", so without
  the delay it would re-dequeue the same job on its very next poll and spin.
  Parking removes it from the ready queue during the delay so the worker backs off
  and peers get a fair poll.
- **Age-based give-up** — the job is dead-lettered only after it has been
  continuously unhandled for `:max-requeue-age-ms` (default 300000 / 5 min),
  tracked via `[:metadata :first-missing-at]`. Give-up is deliberately **not**
  attempt-based: counting re-enqueue attempts would let wrong-worker misses (more
  handlerless workers than the budget, or simply high load) exhaust a budget and
  drop a job a slow handler-owning worker hadn't polled yet. A wall-clock window
  is independent of fleet size and load. `:max-requeues` (default 10000) remains
  only as a runaway backstop, not the primary policy.

Keep handler sets consistent across instances: a job-type that *no* worker
registers still burns its full requeue budget before dead-lettering.

## Scheduled-job Atomic Claim

Promotion of a due scheduled job to an execution queue is an **atomic claim**, so
concurrent workers can't both move (and thus run) the same job:
- **Redis**: `ZREM` on `jobs:scheduled` — only the worker whose `ZREM` returns 1 enqueues.
- **In-memory**: `swap-vals!` on the scheduled set — only the caller that removed the entry enqueues.

## Priority Queue Order

Jobs dequeued by priority: critical > high > normal > low.

## Gotchas

1. **Register handlers BEFORE starting workers** - late registration doesn't affect running workers
2. **Handlers are NOT transactional** - use `process-tenant-job!` for tenant-scoped DB operations
3. **Tenant jobs fail explicitly** if tenant not found (safety-first, not silent fallback)
4. **Scheduled jobs** are stored separately and periodically moved to execution queues (default every 5s)
5. **In-memory adapter** is single-process only - NOT for production
6. **Redis key patterns**: `job:{id}`, `queue:{name}:{priority}`, `jobs:scheduled`, `jobs:failed`

## Testing

```bash
clojure -M:test:db/h2 :jobs
```

Test fixture pattern:
```clojure
(def ^:dynamic *system* nil)
(def ^:dynamic *registry* nil)

(defn with-clean-system [f]
  (let [system (in-memory/create-in-memory-jobs-system)
        registry (worker/create-job-registry)]
    (binding [*system* system *registry* registry]
      (f))))
```

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
