# GitHub milestone verification — v0.4.0

Verified locally on 25 October 2026.

- The current combined backend suite contains **101 passing tests**, with zero failures, errors, or skips. GitHub coverage still includes RSA/HMAC/HTTP-client tests and PostgreSQL-backed dispatch, verification and rejection cases.
- App-client coverage validates RS256 JWT signatures/claim lifetime, narrow repository/read permissions, token caching, separate dispatch write scope, source inputs, PKCS#1/PKCS#8 key compatibility, response size bounds, controlled upstream errors, redirect rejection, and GitHub's published HMAC test vector.
- PostgreSQL integration coverage exercises source resolution and durable dispatch, successful run/artifact verification and QUEUED transition, owner-scoped progress, raw Unicode signatures, missing/invalid signatures, payload limits, duplicate/conflicting deliveries, duplicate run attempts, installation mismatch/removal, policy changes during processing, expired job leases, deletion during resolution, stable source pinning across branch movement, known-request binding, network retry behavior, and absence of automatic environment creation.
- Seventeen rejection cases cover forks, wrong workflow/branch/event/attempt/conclusion, modified workflow content, wrong source, image scope/mutable tags, incomplete components, missing scan evidence, altered/missing/duplicate/expired artifacts, and ZIP path manipulation.
- TypeScript and the production Vite build passed. Both GitHub workflows passed actionlint 1.7.12, downloaded from its official release and SHA-256 verified. GitHub setup PowerShell scripts parsed successfully.
- The v0.4.0 API image built and started against the existing local database. V4 is additive; V1/V2/V3 were unchanged. Live readiness, bearer authentication, inventory, operation/build progress, webhook setup/signature gating, portal HTML, and the Vite API proxy passed smoke checks.
- A private GitHub App was registered and installed only on `MarwanFo/kubespawn`. The reviewed repository policy is enrolled, webhook authentication is active, and real workflow-dispatch and push runs produced verified API/web digests.

Controlled fixtures remain the repeatable automated test boundary. External activation was also completed: build run `40211456233` passed for the v0.5 milestone, including image publication and the runtime-image vulnerability gates. Run `40211500667` independently verified the previously pinned request under repository policy version 2. The GitHub dispatch client accepts GitHub's current `200` response with a positive `workflow_run_id` and the legacy `204` response; webhook verification remains an independent trust step.

The App key, webhook secret and local configuration remain ignored outside Git. The webhook currently uses a temporary local tunnel and must be replaced by a stable private endpoint before any shared pilot. See [GitHub setup](github-setup.md) for rotation and re-enrollment rules.

Reproduce with `./scripts/verify.ps1 -Integration`, `./scripts/smoke.ps1`, and the installed `.tools/actionlint/actionlint.exe` against `.github/workflows/*.yaml`. See [the build verification contract](github-build-verification.md) for the trust boundary and remaining admission requirements.
