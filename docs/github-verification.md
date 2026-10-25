# GitHub milestone verification — v0.4.0

Verified locally on 25 October 2026.

- **89 backend tests passed**, with zero failures, errors, or skips: the existing 45 security/lifecycle/worker tests, 7 new RSA/HMAC/HTTP-client tests, and 37 new PostgreSQL-backed GitHub cases.
- App-client coverage validates RS256 JWT signatures/claim lifetime, narrow repository/read permissions, token caching, separate dispatch write scope, source inputs, PKCS#1/PKCS#8 key compatibility, response size bounds, controlled upstream errors, redirect rejection, and GitHub's published HMAC test vector.
- PostgreSQL integration coverage exercises source resolution and durable dispatch, successful run/artifact verification and QUEUED transition, owner-scoped progress, raw Unicode signatures, missing/invalid signatures, payload limits, duplicate/conflicting deliveries, duplicate run attempts, installation mismatch/removal, policy changes during processing, expired job leases, deletion during resolution, stable source pinning across branch movement, known-request binding, network retry behavior, and absence of automatic environment creation.
- Seventeen rejection cases cover forks, wrong workflow/branch/event/attempt/conclusion, modified workflow content, wrong source, image scope/mutable tags, incomplete components, missing scan evidence, altered/missing/duplicate/expired artifacts, and ZIP path manipulation.
- TypeScript and the production Vite build passed. Both GitHub workflows passed actionlint 1.7.12, downloaded from its official release and SHA-256 verified. GitHub setup PowerShell scripts parsed successfully.
- The v0.4.0 API image built and started against the existing local database. V4 is additive; V1/V2/V3 were unchanged. Live readiness, bearer authentication, inventory, operation/build progress, webhook setup/signature gating, portal HTML, and the Vite API proxy passed smoke checks.
- The live local request remains ACTIVE/WAITING_BUILD, with its original intent version and expiry. The GitHub status endpoint reports `configured: false`, no enrolled repositories, and `GITHUB_SETUP_REQUIRED`. The portal shows this pending setup clearly.

The GitHub transport was replaced by controlled fixtures for PostgreSQL integration tests. Its real HTTP/auth implementation was exercised against a local test server and generated test keys. **No real GitHub App registration, installation, Actions image publication, registry scan, or live webhook delivery has been completed yet.** The image workflow is opt-in and cannot be claimed successful until account setup is finished and an actual run passes. Kubernetes provisioning remains unimplemented.

Ignored `.env.github` and `.secrets/github-webhook.secret` were prepared with a random local secret. No private App key was created or requested in chat, and GitHub remains disabled. Finish [the new App setup](github-setup.md), enroll the reviewed repository policy, and observe a real request reach Build verified / Provisioning pending to complete external activation.

Reproduce with `./scripts/verify.ps1 -Integration`, `./scripts/smoke.ps1`, and the installed `.tools/actionlint/actionlint.exe` against `.github/workflows/*.yaml`. See [the build verification contract](github-build-verification.md) for the trust boundary and remaining admission requirements.
