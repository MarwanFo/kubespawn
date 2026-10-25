# Local setup verification

Verified on 25 October 2026 on Windows with Docker Desktop Linux containers.

## Passing checks

| Check | Result |
|---|---|
| Local bearer authentication and bounded inventory request tests | 5 passed |
| OIDC scope enforcement and local-token isolation tests | 4 passed |
| Real PostgreSQL migration, generation uniqueness/history, and JPA persistence tests | 3 passed; none skipped |
| Backend JAR compilation/package | Passed on Java 21 |
| Portal TypeScript check and Vite production build | Passed on host and inside its Docker build |
| npm dependency audit during installation | No reported vulnerabilities |
| Helm starter lint with explicit rendering fixture | Passed |
| Chart with missing environment/image identity | Correctly rejected |
| PowerShell helper syntax | Passed |
| Docker Compose configuration and both image builds | Passed |
| Running stack smoke check | Passed: database readiness, authenticated API, unauthenticated rejection, inventory, portal HTML, and portal-to-API proxy |
| Generated local token in Git-visible project files or browser assets | No matches |

## Local endpoints

- Portal: `http://localhost:5173`
- API readiness: `http://localhost:8080/actuator/health/readiness`
- PostgreSQL: `127.0.0.1:55432`; credentials are in the ignored `.env` file.

The standard PostgreSQL host port was unavailable on this Windows machine. The project uses 55432 locally and leaves the container's internal 5432 unchanged. It does not stop or reconfigure unrelated services.

The backend uses a checksum-verified workspace-local Java 21 JDK. System Java was not changed. The repository was initialized on `main`; no commits, remote repository, or cloud resources were created. Dependencies, tool caches, archives, generated credentials, and build outputs are excluded from Git.

## Limits of this verification

This validates the application foundation, not the full IDP. No preview cluster or cloud infrastructure was deployed. Argo reconciliation, webhook delivery/build verification, TTL cleanup, private preview authorization, Cilium enforcement, storage cleanup, and recovery experiments remain future acceptance gates. The Helm starter was rendered/linted, not installed or validated against a live Kubernetes API. GitHub Actions is configured but has not run remotely.

Use `scripts/verify.ps1 -Integration` and `scripts/smoke.ps1` to reproduce the application checks. Docker must be available for integration tests; those tests fail rather than silently skip when the engine is unavailable.
