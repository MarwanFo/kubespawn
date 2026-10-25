# Lifecycle milestone verification — v0.2.0

Verified locally on 25 October 2026.

- **28 backend tests passed**, with zero failures or skips: 10 authentication/security tests and 18 real PostgreSQL integration tests.
- Lifecycle coverage includes transactional persistence and fault-induced rollback, sequential/concurrent idempotency, conflicting key reuse, independent actor keys, ownership, quoted version preconditions, stale updates, expiry/lifetime limits, idempotent deletion, absence preservation, extend/delete races, and local proxy Origin protection.
- TypeScript and the production Vite build passed. The browser's Unicode-set form patterns were checked against repository and branch inputs.
- The API image rebuilt successfully. Flyway applied the additive V2 migration to the existing local database; V1 was unchanged.
- Local database readiness, bearer rejection/acceptance, inventory, portal HTML, and the Vite API proxy passed smoke checks.
- A real browser submission for `MarwanFo/kubespawn`, ref `main`, with a 24-hour lifetime appeared in the inventory as `WAITING_BUILD`, version 0. Its Activity panel displayed the saved audit event.

The browser-created local request remains available for inspection. It creates no Kubernetes resources and has no verified commit or preview URL. Its reconciliation operation is pending until the worker and trusted build pipeline are implemented. Extension/deletion and races were exercised against disposable Testcontainers databases; the browser request was left active rather than altered for those tests.

The API and PostgreSQL remain in Docker. The Docker portal stays stopped so the user's existing Vite development server can continue using port 5173.

Reproduce backend/frontend verification with `scripts/verify.ps1 -Integration` and live connectivity with `scripts/smoke.ps1`. See [the lifecycle contract](lifecycle-intents.md) for API semantics and the remaining deployment gates.
