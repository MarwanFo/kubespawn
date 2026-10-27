# KubeSpawn

A developer platform for creating, testing, reviewing, and operating disposable application environments.

**Current code: v0.7 infrastructure MVP and partial identity scaffolding.** The local preview lifecycle, persisted identity/tenant tables, profile/admin forms, and OIDC configuration are reusable foundations. Complete Login/Sign-up, account recovery, the requested role model, QA reviews, tenant-authorized lifecycle operations, and production browser-security acceptance are still to be built.

**Target product: architecture v4.** The SaaS will provide branded Login and Sign-up, organization/project onboarding, and three roles: **Developer**, **QA/Reviewer**, and **Portal Admin/DevOps**. Follow [the current architecture](docs/architecture.md), [the role/security contract](docs/security-and-permissions.md), and [the 18-phase delivery plan](docs/saas-delivery-plan.md). Phase 00 defines the product; Phase 01 begins engineering setup. External deployment is the final phase, Phase 17.

## Start locally on Windows

Requirements: PowerShell 7+, Node.js 22.12+, and Docker Desktop with Linux containers. The full Compose stack does not need host Java. Native backend development/tests need Java 21; setup can install a portable, checksum-verified Temurin JDK into the ignored `.tools` directory without changing system Java.

```powershell
./scripts/setup.ps1 -InstallJava
./scripts/start.ps1
./scripts/smoke.ps1
```

Open [the portal](http://localhost:5173). API readiness is at [localhost:8080/actuator/health/readiness](http://localhost:8080/actuator/health/readiness). The first start downloads images and builds both services. Setup generates a private, ignored `.env` with random local credentials and never replaces an existing file.

To connect GitHub, follow [the new App setup guide](docs/github-setup.md). `./scripts/configure-github.ps1` prepares ignored configuration and a random webhook secret without enabling external access. The optional `compose.github.yaml` overlay mounts an operator-supplied private key; the normal Compose stack leaves GitHub disabled.

For the local cluster, follow [Kubernetes provisioning](docs/kubernetes-provisioning.md). `./scripts/setup-kubernetes.ps1` prepares KinD, Cilium and namespace-scoped Argo. `scripts/start.ps1` selects configured GitHub/Kubernetes overlays automatically. Existing Vite users should start only `postgres api` with the selected overlays to avoid a portal port conflict.

Stop with `./scripts/stop.ps1`. It retains PostgreSQL data. PostgreSQL is exposed on localhost port 55432 to avoid common Windows reservations around 5432. Port overrides are in `.env`; changing database credentials after the volume has been initialized requires updating the database role as well. Restart the portal after changing the local API token.

On another OS, generate equivalent `.env` credentials, then use `docker compose up --build -d`. PowerShell helper scripts that select `java.exe` are Windows-specific; the Gradle wrapper and application containers work on Linux/macOS.

## Develop with hot reload

Run `docker compose up -d postgres`, then open two terminals:

```powershell
./scripts/dev-api.ps1
```

```powershell
./scripts/dev-portal.ps1
```

Stop the Compose API/portal first if they already occupy those ports. The Vite server reads the root `.env` and adds the local bearer token only in its server-side proxy. It is not a `VITE_` variable and is not bundled into browser JavaScript.

## Verify

```powershell
./scripts/verify.ps1                  # Backend security tests, JAR, frontend typecheck/build
./scripts/verify.ps1 -Integration     # Also PostgreSQL/Testcontainers tests; Docker required
./scripts/smoke.ps1                   # Against the running local Compose stack
```

Equivalent portable commands:

```sh
cd control-plane
./gradlew test integrationTest bootJar
cd ../portal
npm ci
npm run build
```

Gradle Wrapper 9.8.2 is included with its distribution SHA-256. npm and Gradle dependency lockfiles are included, and container base images are pinned by digest. CI runs security and real PostgreSQL migration/persistence tests plus the portal build; it does not silently skip database tests when Docker is unavailable.

## Repository map

| Path | Purpose |
|---|---|
| `control-plane/` | Java 21, Spring Boot 4.1.2, JPA/Flyway, bearer authentication, health/metrics, Fabric8 dependency |
| `portal/` | React 19, TypeScript, Vite, Tailwind, TanStack Query |
| `compose.yaml` | Local PostgreSQL, API, and portal; host ports bind only to loopback |
| `infra/local/` | Optional KinD configuration and cluster prerequisites |
| `templates/preview-small/` | Reviewed workload-only Helm chart; rendered by Argo only after build, policy and capacity admission |
| `templates/baseline/` | Controller-owned namespace baseline example |
| `scripts/` | Windows setup, start/stop, development, smoke, and verification helpers |
| `docs/` | Delivery scope, architectural decisions, and operating notes |
| `docs/architecture.md` | Current SaaS architecture v4 |
| `docs/saas-delivery-plan.md` | Full-stack delivery phases and exit criteria |
| `docs/security-and-permissions.md` | Developer, QA/Reviewer, and Portal Admin authorization contract |
| `kubespawn_idp_architecture_v3.md` | Retained infrastructure/lifecycle reference; v4 replaces product scope, roles, auth, and phase order |

## API available now

| Endpoint | Authentication | Behavior |
|---|---|---|
| `GET /actuator/health` | Public | Process/dependency health without details |
| `GET /actuator/health/readiness` | Public | Includes the database readiness check |
| `GET /api/v1/platform` | Bearer | Foundation capabilities and controller availability |
| `GET /api/v1/environments?limit=50` | Bearer | Latest recorded environments, maximum 100; no seed data |
| `GET /api/v1/me` | Session or bearer | Current persisted user, platform role, and tenant memberships |
| `PATCH /api/v1/me` | Session or bearer + CSRF | Update the current user's display name and avatar URL |
| `GET /api/v1/admin/overview` | Platform administrator | User, tenant, membership, and environment totals |
| `GET /api/v1/admin/users` | Platform administrator | User directory with tenant memberships |
| `POST /api/v1/admin/users` | Platform administrator + CSRF | Provision an external OIDC identity and tenant membership |
| `PATCH /api/v1/admin/tenants/{tenantId}/members/{userId}` | Platform administrator + CSRF | Change tenant role or membership status |
| `POST /api/v1/environments` | Local bearer | Create a `WAITING_BUILD` request; requires a UUID `Idempotency-Key` |
| `GET /api/v1/environments/{id}` | Local bearer, owner | Current intent with its quoted version in `ETag` |
| `PATCH /api/v1/environments/{id}/expiry` | Local bearer, owner | Extend absolute expiry; requires quoted `If-Match` |
| `DELETE /api/v1/environments/{id}` | Local bearer, owner | Request absence and queue teardown; requires quoted `If-Match` |
| `GET /api/v1/environments/{id}/audit` | Local bearer, owner | Latest 50 audit events |
| `GET /api/v1/operations/{id}` | Local bearer, owner | Operation state, reason, attempts, failures, and timing |
| `GET /api/v1/operations` | Local bearer, owner | Latest operation per environment, maximum 100 |
| `POST /api/v1/operations/{id}/retry` | Local bearer, owner | Retry the current failed ACTIVE/unexpired operation; requires quoted `If-Match` |
| `GET /api/v1/github/status` | Local bearer | GitHub configuration and enrolled repository policies |
| `POST /api/v1/github/repositories` | Local bearer | Validate App access and enroll a reviewed build policy |
| `GET /api/v1/github/environment-builds` | Local bearer, owner | Pinned SHA, build progress, and verified image digests |
| `GET /api/v1/kubernetes/status` | Local bearer | Local controller flag, slot limit, and preview-routing readiness |
| `GET /api/v1/kubernetes/environment-progress` | Local bearer, owner | Capacity, namespace, Argo and cleanup observations |
| `POST /api/v1/kubernetes/environments/{id}/preview-session` | Local bearer, owner | Set a path-scoped HttpOnly preview cookie and redirect to a READY preview |
| `POST /integrations/github/webhook` | Raw-body HMAC | Deduplicated run receipt and installation/removal events |
| `GET /actuator/prometheus` | Bearer | Micrometer metrics; restrict access further before deployment |

Mutations return `202 Accepted` with an environment snapshot and `operationId`. They record intent rather than claiming provisioning or removal. Initial TTL is 1–24 hours; extensions are bounded by 72 hours from creation. The local expiry scanner runs every 30 seconds and requests absence for expired records; the worker reports `DESTROYED` only after the Kubernetes adapter confirms remote absence. Stale versions return 412, missing version preconditions return 428, and deleted/expired requests cannot be reactivated. See [the lifecycle contract](docs/lifecycle-intents.md).

The local worker polls every 2 seconds. Expected prerequisite waits use `WAITING` and are checked again after 30 seconds; processing failures use bounded retries, and obsolete intents become `SUPERSEDED`. `workerEnabled` reports the configured scheduler separately from `controllerEnabled`; the latter becomes true only with the explicit Kubernetes overlay. Neither flag is a liveness probe. See [worker behavior and recovery](docs/job-worker.md).

GitHub delivery metadata is a wakeup rather than build authority. The verifier independently checks current run identity/attempt, approved workflow bytes, successful conclusion, source/request binding, complete image set, exact registry paths, and downloaded artifact SHA-256. A verified build alone cannot create or reactivate an environment. The supplied workflow runs tests/builds/scans without registry write permission and publishes from a separate runner. It is disabled until the repository variable `KUBESPAWN_PUBLISH_IMAGES` is explicitly enabled. See [the verification contract](docs/github-build-verification.md).

The local profile accepts the generated development token and resolves it to a persisted bootstrap administrator in the local tenant. It exists only for development and verification. The `saas` profile contains partial OAuth2/OIDC browser-session configuration; complete provider setup, branded account pages, callback/logout integration, shared sessions, SPA CSRF bootstrap/refresh, and browser acceptance tests are pending. The separate default resource-server mode remains available for scoped machine/API tokens. Current unknown identities fail closed until provisioned; the target architecture replaces that behavior with verified onboarding-only registration and explicit membership activation.

The Compose portal is still a **local development proxy** that inserts the bootstrap token. Browser mutations must have an Origin matching the proxy's forwarded Host; the local Vite and Compose proxies preserve it. Do not expose or reuse that proxy configuration in production. Tenant-filtered reads are implemented, but lifecycle writes remain local-only until the next milestone applies tenant/project roles, repository mappings, and quotas to every mutation. The base stack has no Kubernetes credentials; only the explicit ignored Kubernetes overlay mounts the restricted controller credential.

See [the current SaaS architecture](docs/architecture.md), [the full delivery plan](docs/saas-delivery-plan.md), [security and permissions](docs/security-and-permissions.md), [the v0.7 foundation](docs/saas-foundation.md), [implementation status](docs/implementation-status.md), [preview routing](docs/preview-routing.md), [GitHub setup](docs/github-setup.md), [GitHub verification results](docs/github-verification.md), [worker behavior](docs/job-worker.md), [worker verification](docs/worker-verification.md), [lifecycle verification](docs/lifecycle-verification.md), [initial setup verification](docs/setup-verification.md), [the ownership decision](docs/decisions/0001-lifecycle-ownership.md), and [the retained lifecycle specification](kubespawn_idp_architecture_v3.md).
