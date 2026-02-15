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
| `boundary.jobs.shell.worker` | Worker implementation: polling, processing, pool management |
| `boundary.jobs.shell.tenant-context` | Multi-tenant job execution with schema switching |

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
