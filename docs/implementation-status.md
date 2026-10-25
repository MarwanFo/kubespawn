# Foundation delivery scope

## Included

- Reproducible Spring Boot/Gradle and React/Vite projects, dependency locks, Dockerfiles, local Compose, and Windows helpers.
- Local bearer authentication and a separate OIDC resource-server configuration that fails closed without its issuer.
- PostgreSQL migrations for source identity, environment generations, jobs, outbox events, and independent audit retention.
- Read-only inventory from PostgreSQL through JPA; readiness probes and authenticated Prometheus metrics.
- A portal showing live API state with honest loading/error/empty states.
- Authentication tests and real PostgreSQL migration/constraint/persistence integration tests.
- CI with commit-pinned actions; workload-only Helm and controller-owned baseline starters.

## Next: lifecycle vertical slice

1. GitHub App registration, repository enrollment, webhook HMAC verification, delivery deduplication, and current-source checks.
2. Trusted build-result verification, immutable component digests, and a pinned chart/revision contract.
3. Transactional create/delete/expiry intents, audit/outbox writes, capacity reservations, per-environment serialization, and durable workers.
4. Controller-owned namespace baseline and Argo Application/AppProject creation with constrained RBAC.
5. Observed migration/workload/edge readiness and GitHub deployment notifications.
6. TTL suppression and verified deletion, including volumes/credentials and crash recovery.

## Before an internal pilot

Complete OIDC audience checks, project/team authorization and tenancy predicates, browser login and preview authorization, Cilium enforcement tests, wildcard TLS/DNS, secrets, telemetry retention, database restore, and cleanup runbooks. Add the rest of the architecture's build/condition/capacity/usage records through new migrations when their feature contracts are implemented.

The stored phase vocabulary and job tables are schema foundations, not evidence that a reconciler already exists. Do not manually insert production-like environments into the local database to make the dashboard look populated.
