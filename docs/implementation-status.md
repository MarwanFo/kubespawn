# Implementation status

## Included

- Reproducible Spring Boot/Gradle and React/Vite projects, dependency locks, Dockerfiles, local Compose, and Windows helpers.
- Local bearer authentication and a separate OIDC resource-server configuration that fails closed without its issuer.
- PostgreSQL migrations for source identity, environment generations, jobs, outbox events, and independent audit retention.
- Inventory from PostgreSQL through JPA; readiness probes and authenticated Prometheus metrics.
- Local manual create/extend/delete intents, per-actor create idempotency, version preconditions, owner checks, and transactional job/audit/outbox persistence.
- A local expiry scanner that requests absence once and records suppression; resource cleanup remains unimplemented.
- Portal Spawn form, honest waiting/termination states, bounded TTL extension, deletion confirmation, and activity history.
- A portal showing live API state with honest loading/error/empty states and server-provided mutation errors.
- Authentication and real PostgreSQL tests covering migrations, persistence, idempotent retries, concurrent requests, rollback, ownership, expiry, and version races.
- CI with commit-pinned actions; workload-only Helm and controller-owned baseline starters.

## Next: lifecycle vertical slice

1. GitHub App registration, repository enrollment, webhook HMAC verification, delivery deduplication, and current-source checks.
2. Trusted build-result verification, immutable component digests, and a pinned chart/revision contract.
3. Capacity reservations and durable workers with leases, fencing, and serialization around external effects. Intent-only database mutations already use row locks and version checks; these short transactions must not be extended across Kubernetes calls.
4. Controller-owned namespace baseline and Argo Application/AppProject creation with constrained RBAC.
5. Observed migration/workload/edge readiness and GitHub deployment notifications.
6. TTL suppression and verified deletion, including volumes/credentials and crash recovery.

## Before an internal pilot

Complete OIDC audience checks, project/team authorization and tenancy predicates, browser login and preview authorization, Cilium enforcement tests, wildcard TLS/DNS, secrets, telemetry retention, database restore, and cleanup runbooks. Add the rest of the architecture's build/condition/capacity/usage records through new migrations when their feature contracts are implemented.

The lifecycle worker is not connected, so jobs remain `PENDING`, requests remain `WAITING_BUILD`, and deletion remains `TERMINATING`. The current writes are deliberately local-only until repository/team and tenant authorization are implemented. Do not manually insert production-like environments into the local database to make the dashboard look populated.
