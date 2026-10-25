# Durable lifecycle worker — v0.3.0

The local worker consumes the jobs created by lifecycle transactions. It currently checks prerequisites: trusted build verification for reconciliation and verified resource removal for teardown. Neither integration exists yet, so it records `WAITING` with `BUILD_VERIFICATION_REQUIRED` or `CLEANUP_VERIFICATION_REQUIRED`. A ref or a populated commit SHA alone does not authorize deployment. Environment readiness and destruction are never inferred from job processing.

## Queue and serialization

The scheduler runs only under the `local` profile and polls once every two seconds. Each poll recovers up to 50 expired jobs, then claims one due job. Claiming uses short PostgreSQL transactions and `FOR UPDATE SKIP LOCKED`; teardown jobs have priority. An atomic conditional upsert acquires an environment lease, incrementing a persistent fencing token. A second job for that environment cannot acquire a simultaneous valid lease. Different environments can be processed independently by multiple instances.

Each claim also increments the job's lease token and attempt count. Both the job lease and environment lease identify the owning worker. Processing happens outside the claim transaction; a separate heartbeat renews both leases. Renewal and result persistence require matching ownership, both tokens, and unexpired leases. Lease time comes from PostgreSQL, not worker clocks. Released environment lease rows retain their fencing token permanently.

The worker locks and checks the current environment intent before invoking the processor and again before saving its result or failure. An older version or incompatible desired state becomes `SUPERSEDED`; its result cannot overwrite a newer deletion or extension. The worker does not modify environment readiness or desired state.

## States and failures

| State | Meaning |
|---|---|
| `PENDING` | Queued initially, or awaiting a scheduled retry after a processing failure |
| `RUNNING` | Claimed under renewable job and environment leases |
| `WAITING` | Expected prerequisite missing; check again after the prerequisite delay |
| `SUPERSEDED` | A newer environment intent replaced this job; terminal |
| `FAILED` | Unsupported job type or exhausted failure budget; terminal, requires operator attention |
| `COMPLETE` | Reserved for future verified effects; the current gated processor never sets it |

`attempts` counts claims, including prerequisite checks. `failureCount` counts processing errors, timeouts, and lease-expiry recoveries. Expected waits do not spend the failure budget. Retry delay uses exponential backoff with equal jitter, bounded by the configured maximum. Recovery of an obsolete job supersedes it without consuming the budget. Exceptions are mapped to controlled messages/codes; arbitrary exception text is not persisted or exposed to the browser.

Expired workers cannot renew or acknowledge work. Expired jobs are requeued with backoff, or failed at the budget. Recovery releases an environment lease only if its old job, worker, and token still match; it cannot release a replacement worker's lease. Timeouts cancel the processor and schedule a fenced retry. Interrupted shutdown leaves durable work recoverable after lease expiry.

This provides fenced database results and at-least-once processing. Cancellation cannot guarantee that an external request has stopped. Future GitHub/Kubernetes adapters must use idempotent effects, deterministic ownership, intent rechecks, and provider-side preconditions/fencing. No external effects are implemented in this milestone, and exactly-once delivery is not claimed. Outbox publishing remains a separate integration.

## Configuration

| Property under `kubespawn.worker` | Default |
|---|---|
| `enabled` | `true` in the local profile; no scheduler in OIDC mode |
| `poll-interval` | `PT2S` |
| `lease` | `PT30S` |
| `heartbeat` | `PT5S` |
| `timeout` | `PT20S` |
| `prerequisite-delay` | `PT30S` |
| `retry-base` | `PT1S` |
| `retry-max` | `PT1M` |
| `max-failures` | `5` |

Durations must be positive, heartbeat less than half the lease, retry base no greater than retry maximum, and the failure budget positive. Disable the scheduler with `KUBESPAWN_WORKER_ENABLED=false` for manual inspection; due work stays durable. Tests disable both worker and expiry schedulers and invoke batches explicitly. A failed job currently needs diagnosis and a future administrative retry workflow; no public retry/reset endpoint is provided.

## Progress API and portal

Local bearer/owner checks protect `GET /api/v1/operations/{id}` and `GET /api/v1/operations`. The latter returns the latest intent's operation for up to 100 owned environments in one request. Responses include state, reason, safe message, attempts, failure count, update time, next availability, heartbeat, lease expiry, and completion time. Internal worker identities and tokens are not exposed.

The portal polls progress every five seconds and displays only progress matching the environment's current intent version. Mutation completion invalidates progress and inventory queries. The configured worker flag is separate from the Kubernetes controller flag and is not a liveness guarantee. A healthy API also does not prove an external worker integration is available.

## Verification

Run `./scripts/verify.ps1 -Integration` with Docker Desktop running. Worker tests use disposable PostgreSQL containers and cover concurrent claims, environment serialization, independent environments, lease renewal, stale ownership, crash retry limits, prerequisite waits, superseded results, deletion during processing, sanitized failures, unsupported types, timeout cancellation/heartbeats, and owner-scoped progress. Existing lifecycle and authentication tests remain part of the suite.

The V3 migration adds worker metadata and a lease table without modifying V1/V2. Existing incomplete `RUNNING` rows are returned to `PENDING` during this one-time upgrade; deploy migrations with any older experimental workers stopped.

Sources: [PostgreSQL locking and SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html), [conditional ON CONFLICT updates](https://www.postgresql.org/docs/17/sql-insert.html), [row-lock behavior](https://www.postgresql.org/docs/17/explicit-locking.html).
