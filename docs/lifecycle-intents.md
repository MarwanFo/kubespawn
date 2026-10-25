# Manual lifecycle intent contract

This milestone accepts local development requests. It performs no GitHub API calls, builds, or Kubernetes effects. A supplied branch/ref is an unverified input; `requestedRevision` remains null until a later trusted resolver pins the source SHA. A pending job is not evidence of a running environment.

## Create

`POST /api/v1/environments` with JSON `{"repository":"owner/repository","ref":"main","ttlHours":24}` requires a bearer token and a UUID `Idempotency-Key`. Initial TTL must be 1–24 hours. The response is 202, with a Location, quoted ETag, generated X-Request-ID, and `{environment, operationId}`.

One PostgreSQL transaction claims `(actor, idempotency key)`, creates the manual source and uniquely named environment, and writes the pending reconciliation job, immutable audit event with its intent snapshot, and outbox event. A repeated key with identical normalized input returns the same environment and original operation; a different payload returns 409. Replays return the environment's current state, not a stale claim that it is still active. Claims are retained throughout the environment's retention window; pruning is a separate design task.

The implementation uses `INSERT ... ON CONFLICT DO NOTHING`, followed by a claim-row `SELECT ... FOR UPDATE` under READ COMMITTED isolation. This serializes simultaneous retries and sees the first transaction's committed result. If any source/environment/job/audit/outbox write fails, the entire claim rolls back, permitting a safe retry.

## Extend and delete

Read an environment's current `intentVersion` or ETag and send it as `If-Match: "0"`. Missing preconditions return 428; malformed versions return 400; stale updates return 412. Each accepted state change increments the version and atomically writes audit/job/outbox records. Owner checks return 404 for another actor's environment or operation.

Expiry extensions must be later than the current expiry and no later than 72 hours after creation. Expired or ABSENT requests cannot be extended. Deletion sets desired state ABSENT, observed phase TERMINATING, and source suppression MANUAL_DELETE, and queues teardown. Repeated deletion returns the existing teardown operation without extra events, even when the caller retries its original numeric version. No path changes this generation back to ACTIVE or sets `destroyed_at`.

The local expiry scanner locks up to 50 due ACTIVE rows with `FOR UPDATE SKIP LOCKED` every 30 seconds. It sets ABSENT/TERMINATING, source suppression TTL_EXPIRED, and durable teardown/audit/outbox records. Multiple API instances cannot reap the same intent twice. Extension and deletion/expiry are serialized by the same environment row lock. Scheduler tests disable automatic execution and invoke the batch explicitly.

Database transactions contain database work only. The [durable worker](job-worker.md) claims jobs under renewable job/environment leases and fences result persistence against both tokens and the latest intent. Processor calls occur outside those transactions. Future external adapters must add provider-side idempotency and fencing; these transaction row locks must not be held while calling Kubernetes, registries, or GitHub.

## Local access and portal behavior

Writes and lifecycle detail routes are enabled only by the explicit local profile. The bearer identifies one trusted local developer, not an enterprise role model. Default OIDC mode rejects mutations even for JWTs with read/write scopes. Repository enrollment, source verification, production audience checks, and project/tenant authorization remain deployment gates.

Local proxies insert the token server-side. Browser mutation Origins must match the forwarded Host; other origins are rejected. CLI clients with an explicit bearer and no Origin are supported. Credentials are not placed in browser JavaScript.

The dialog keeps a stable idempotency key for retries with unchanged input, including interrupted responses. Editing the input creates a different request identity. Inputs cannot change while a save is pending. The UI shows pending build/cleanup honestly, refetches state after mutations/errors, and exposes audit activity. It never generates a preview URL before provisioning or silently marks cleanup complete.

## Sources

- [PostgreSQL READ COMMITTED semantics](https://www.postgresql.org/docs/17/transaction-iso.html)
- [PostgreSQL row locking and SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html)
- [PostgreSQL INSERT and ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html)
