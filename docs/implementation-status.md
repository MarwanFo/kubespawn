# Implementation status

## Included

- Reproducible Spring Boot/Gradle and React/Vite projects, dependency locks, Dockerfiles, local Compose, and Windows helpers.
- Local bearer authentication and a separate OIDC resource-server configuration that fails closed without its issuer.
- PostgreSQL migrations for source identity, environment generations, jobs, outbox events, and independent audit retention.
- Inventory from PostgreSQL through JPA; readiness probes and authenticated Prometheus metrics.
- Local manual create/extend/delete intents, per-actor create idempotency, version preconditions, owner checks, and transactional job/audit/outbox persistence.
- A local expiry scanner that requests absence once and records suppression, plus observed Argo/namespace cleanup and capacity release.
- A local durable job worker with atomic claims, renewable job and environment leases, monotonic fencing tokens, bounded retries, timeout cancellation, crash recovery, and intent checks before processing and result persistence.
- Owner-scoped operation progress routes and portal polling, with expected prerequisite waits distinguished from processing failures.
- Optional GitHub App authentication with repository-scoped installation tokens, reviewed repository/workflow enrollment, bounded HMAC webhook receipt, delivery/run-attempt deduplication, and removal/suspension handling.
- Manual source SHA pinning, durable approved-workflow dispatch, independent GitHub run/artifact verification, immutable API/web image evidence, policy-version checks, and owner-scoped build progress.
- An opt-in image workflow with source verification, OCI SBOM/provenance, a strict HIGH/CRITICAL vulnerability gate, and a separate registry publisher. The configured local GitHub App has completed real verified publication runs.
- Portal Spawn form, honest waiting/termination states, bounded TTL extension, deletion confirmation, and activity history.
- A portal showing live API state with honest loading/error/empty states and server-provided mutation errors.
- Authentication and real PostgreSQL tests covering migrations, persistence, idempotent retries, concurrent requests, rollback, ownership, expiry, and version races.
- CI with commit-pinned actions; workload-only Helm and controller-owned baseline starters.
- Optional local KinD/Cilium and namespace-scoped Argo provisioning, atomic cluster slots, verified chart identity, baseline-before-Application ordering, resource ownership/fencing checks, and owner-scoped progress.
- A local KubeSpawn demo workload with isolated ephemeral PostgreSQL/credentials and read-only unprivileged API/web containers.
- Confirmed Argo/namespace/project cleanup, retained capacity through termination, continuing cleanup retries and tombstone monitoring.

## Next: lifecycle vertical slice

1. GitHub App registration, installation, publication and a real verified run have been exercised locally. Keep local credentials/tunnel current.
2. Add PR source eligibility/current-state reconciliation and independent provenance/admission checks. The current build verification supports manual requests on an approved branch and trusts its reviewed producer.
3. Extend the implemented local cluster capacity slots to tenant/project resource budgets and production cluster headroom.
4. Extend the local baseline/Application/AppProject implementation to a production application contract and independently enforced provenance admission.
5. Observed migration/workload/edge readiness and GitHub deployment notifications.
6. Extend implemented TTL suppression and namespace/Argo cleanup to external volumes, providers, credentials, fleet orphan recovery and restore quarantine.

## Before an internal pilot

Complete OIDC audience checks, project/team authorization and tenancy predicates, browser login and preview authorization, Cilium enforcement tests, wildcard TLS/DNS, secrets, telemetry retention, database restore, and cleanup runbooks. Add the rest of the architecture's build/condition/capacity/usage records through new migrations when their feature contracts are implemented.

The worker is connected in local mode. Without GitHub setup it reports `GITHUB_SETUP_REQUIRED`; with verified evidence it can move to `QUEUED`. Kubernetes is separately opt-in: enabled requests can enter `PROVISIONING`, while confirmed absence permits `DESTROYED`. Argo health alone never sets `READY` or `serving_revision`; private routing and endpoint checks remain required. Current writes remain local-only until repository/team and tenant authorization are implemented. Do not manually insert production-like environments into the local database to make the dashboard look populated.
