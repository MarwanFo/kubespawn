# Implementation status

## Included

- Reproducible Spring Boot/Gradle and React/Vite projects, dependency locks, Dockerfiles, local Compose, and Windows helpers.
- Local bearer authentication, a browser OAuth2/OIDC session profile with CSRF protection, and a separate OIDC resource-server configuration that fails closed without its issuer.
- PostgreSQL migrations for users, external identities, tenants, memberships, source identity, environment generations, jobs, outbox events, and independent audit retention.
- Persisted Profile and Admin experiences for user metadata, platform access, tenant roles, identity provisioning, suspension, and role changes.
- Tenant-filtered inventory from PostgreSQL; readiness probes and authenticated Prometheus metrics.
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
- Envoy Gateway routing with controller-owned HTTPRoutes, owner-scoped HttpOnly preview sessions, anonymous denial, authenticated edge probes, honest READY/serving-revision commits, and route-first cleanup.

## Next: tenant-authorized product lifecycle

1. Add an explicit tenant/project context to repository enrollment and every create, extend, delete, retry, build, preview-session, and audit operation.
2. Enforce `VIEWER`, `DEVELOPER`, `MAINTAINER`, and `TENANT_ADMIN` permissions in the service layer; never rely on portal visibility for authorization.
3. Add tenant/project resource budgets, concurrency quotas, retention policy, and production cluster headroom to capacity admission.
4. Add PR source eligibility/current-state reconciliation and independent provenance/admission checks. Current build verification supports manual requests on an approved branch and trusts its reviewed producer.
5. Add OIDC browser integration tests, invitation/activation flows, selected-tenant behavior, and authenticated preview session exchange.
6. Extend the local baseline/Application/AppProject implementation to a production application contract, then extend cleanup to external volumes, providers, credentials, fleet orphan recovery, and restore quarantine.

## Before an internal pilot

Complete OIDC provider acceptance tests and audience policy, project/team authorization, tenant-aware writes, quotas, invitation recovery, Cilium enforcement tests, wildcard TLS/DNS, secret management, telemetry retention, database restore, billing/usage records, and cleanup runbooks. Deployment begins only after these application and operations gates are demonstrated.

The worker is connected in local mode. Without GitHub setup it reports `GITHUB_SETUP_REQUIRED`; with verified evidence it can move to `QUEUED`. Kubernetes is separately opt-in: enabled requests can enter `PROVISIONING`, authenticated edge readiness permits `READY`, and confirmed absence permits `DESTROYED`. Current lifecycle writes remain local-only until repository/team, project, role, and tenant authorization are implemented. Do not manually insert production-like environments into the local database to make the dashboard look populated.
