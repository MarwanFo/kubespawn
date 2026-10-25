# Worker milestone verification — v0.3.0

Verified locally on 25 October 2026.

- **45 backend tests passed**, with zero failures, errors, or skips: 10 security tests, 18 existing PostgreSQL lifecycle/persistence tests, and 17 new PostgreSQL worker tests.
- Worker coverage includes competing claims, one valid environment lease across multiple jobs, parallel environments, dual lease renewal, expired ownership rejection, crash recovery and retry exhaustion, monotonic fencing across replacement workers, safe recovery without releasing a replacement lease, normal prerequisite waits, superseded intents/results/failures, deletion during processing, controlled error messages, unsupported jobs, timeout cancellation and heartbeats, and owner-scoped progress routes.
- TypeScript and the production Vite build passed. The API Docker image built successfully as `0.3.0-SNAPSHOT`.
- Flyway V3 adds worker metadata and environment leases. V1/V2 remain unchanged. The updated API started against the existing local PostgreSQL database.
- Live smoke checks passed for database readiness, bearer rejection/acceptance, inventory, operation progress, portal HTML, and the Vite API proxy.
- The existing browser-created `marwanfo/kubespawn` request, ref `main`, intent version 0, was processed automatically. Its reconciliation job changed from `PENDING` to `WAITING` with `BUILD_VERIFICATION_REQUIRED` and zero failures. The portal displayed the enabled worker, operation identifier, checks/failures, update time, and next check.

The live request's desired state and expiry were not changed for worker verification. It remains `ACTIVE`/`WAITING_BUILD`, with no trusted build or deployed preview. Teardown and error paths were exercised in disposable test databases. The API and PostgreSQL run in Docker; the portal runs through Vite on port 5173, while the Docker portal remains stopped.

Docker Desktop initially failed before tests could start because of stale Windows runtime sockets, matching [Docker's reported issue](https://github.com/docker/desktop-feedback/issues/448). After a force-stop, the affected runtime-only directories were renamed aside and preserved, and Desktop started successfully. The Secrets Engine directory was verified to contain only its socket. Settings, images, containers, and volumes were retained; no factory reset was performed.

Reproduce with `./scripts/verify.ps1 -Integration` and `./scripts/smoke.ps1`. See [worker behavior and recovery](job-worker.md) for configuration, failure handling, and the remaining external integration requirements.
