# KubeSpawn

An internal developer platform for disposable pull-request environments.

**Current milestone: manual lifecycle intents.** The portal can create an environment request, extend its expiry, request deletion, and show audit activity. Requests are stored transactionally with durable jobs and outbox events. They start in `WAITING_BUILD`; Kubernetes provisioning, verified builds, Argo CD installation, and cloud deployment are subsequent milestones. Deletion remains `TERMINATING` until a future worker verifies cleanup.

## Start locally on Windows

Requirements: PowerShell 7+, Node.js 22.12+, and Docker Desktop with Linux containers. The full Compose stack does not need host Java. Native backend development/tests need Java 21; setup can install a portable, checksum-verified Temurin JDK into the ignored `.tools` directory without changing system Java.

```powershell
./scripts/setup.ps1 -InstallJava
./scripts/start.ps1
./scripts/smoke.ps1
```

Open [the portal](http://localhost:5173). API readiness is at [localhost:8080/actuator/health/readiness](http://localhost:8080/actuator/health/readiness). The first start downloads images and builds both services. Setup generates a private, ignored `.env` with random local credentials and never replaces an existing file.

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
| `templates/preview-small/` | Workload-only Helm starter; not automatically installed |
| `templates/baseline/` | Controller-owned namespace baseline example |
| `scripts/` | Windows setup, start/stop, development, smoke, and verification helpers |
| `docs/` | Delivery scope, architectural decisions, and operating notes |
| `kubespawn_idp_architecture_v3.md` | Revised architecture contract |

## API available now

| Endpoint | Authentication | Behavior |
|---|---|---|
| `GET /actuator/health` | Public | Process/dependency health without details |
| `GET /actuator/health/readiness` | Public | Includes the database readiness check |
| `GET /api/v1/platform` | Bearer | Foundation capabilities and controller availability |
| `GET /api/v1/environments?limit=50` | Bearer | Latest recorded environments, maximum 100; no seed data |
| `POST /api/v1/environments` | Local bearer | Create a `WAITING_BUILD` request; requires a UUID `Idempotency-Key` |
| `GET /api/v1/environments/{id}` | Local bearer, owner | Current intent with its quoted version in `ETag` |
| `PATCH /api/v1/environments/{id}/expiry` | Local bearer, owner | Extend absolute expiry; requires quoted `If-Match` |
| `DELETE /api/v1/environments/{id}` | Local bearer, owner | Request absence and queue teardown; requires quoted `If-Match` |
| `GET /api/v1/environments/{id}/audit` | Local bearer, owner | Latest 50 audit events |
| `GET /api/v1/operations/{id}` | Local bearer, owner | Durable operation state; jobs remain pending without a worker |
| `GET /actuator/prometheus` | Bearer | Micrometer metrics; restrict access further before deployment |

Mutations return `202 Accepted` with an environment snapshot and `operationId`. They record intent rather than claiming provisioning or removal. Initial TTL is 1–24 hours; extensions are bounded by 72 hours from creation. The local expiry scanner runs every 30 seconds and requests absence for expired records. It never claims to delete Kubernetes resources. Stale versions return 412, missing version preconditions return 428, and deleted/expired requests cannot be reactivated. See [the lifecycle contract](docs/lifecycle-intents.md).

The local profile accepts the generated development token and represents one trusted local developer. The new lifecycle detail/mutation routes are available only in that profile. Outside it, only the earlier inventory/platform reads are supported; the API requires an OIDC issuer (`OIDC_ISSUER_URI`) and the `environments:read` token scope. JWT read/write scopes cannot enable local mutations. Production audience validation, repository/team authorization, browser OIDC login, private preview authorization, and tenancy enforcement remain required before deployment. The default profile never falls back to the development token.

The Compose portal is a **local development proxy** that inserts that token. Browser mutations must have an Origin matching the proxy's forwarded Host; the local Vite and Compose proxies preserve it. Do not expose or reuse this proxy configuration as a production deployment. Production browser authentication needs its own implementation. The local stack has no Kubernetes credentials and cannot create a preview cluster.

See [implementation status](docs/implementation-status.md), [lifecycle verification](docs/lifecycle-verification.md), [initial setup verification](docs/setup-verification.md), [the ownership decision](docs/decisions/0001-lifecycle-ownership.md), and [the architecture specification](kubespawn_idp_architecture_v3.md).
