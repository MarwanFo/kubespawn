# GitHub build verification contract — v0.4.0

This milestone adds a GitHub App client, repository enrollment, signed/deduplicated webhook receipt, durable build dispatch/verification jobs, and an owner-scoped build progress view. It supports existing local manual requests for one approved branch per enrolled non-fork repository. PR environment creation, fork execution, Kubernetes effects, and deployment notifications are not implemented here.

## Trust and identities

An operator enrolls a stable repository ID and installation ID, expected name, allowed branch, numeric workflow ID/path, approved workflow file SHA-256, exact API/web GHCR paths, and approved chart revision/template hash. Enrollment verifies remote repository identity/access, rejects fork repositories, checks workflow bytes/state, and confirms the chart commit exists. Policy versions increase on re-enrollment. Disabling an installation/repository prevents new source binding or build acceptance; addition/unsuspension does not silently restore approval.

Manual refs resolve only if they equal the approved branch. The worker pins the full SHA in a fenced database transaction after resolving it through the installation-scoped API. A later branch movement does not alter the request. Deletion/newer intent, an expired lease, or a policy change can prevent that binding. Source resolution never constitutes build verification.

One build request per `(repository ID, SHA, policy version)` queues an internal dispatch job. The App dispatches the approved workflow on the approved branch with `source_sha` and a random request UUID. If the branch advances before dispatch, the workflow can still build the previously pinned SHA; its authoritative result must match the stored request UUID/SHA/policy. A push result instead must match the run's own head SHA and have no request UUID.

## App authentication and network boundaries

RS256 App JWTs have a short lifetime and clock-skew allowance. Installation tokens are restricted to one repository, contents read, and actions read; dispatch uses a separately cached actions-write token. Keys and tokens never appear in API output or persisted job payloads. The API host is fixed to `api.github.com`; arbitrary enterprise hosts are not supported yet. API requests do not follow redirects with credentials.

Artifact download accepts an HTTPS signed redirect only to GitHub artifact storage hosts and makes the second request without Authorization. JSON and artifact responses are bounded. Downloads are limited to 512 KiB; the ZIP must contain exactly one `build-result.json`, with at most 64 KiB of JSON. Entries are parsed in memory, never extracted to disk. Invalid paths, extra files, altered bytes, expired artifacts, and ambiguous result names are rejected.

## Webhooks and durable execution

`POST /integrations/github/webhook` accepts JSON only, at most 256 KiB. A dedicated security chain permits this route; the receiver requires the configured high-entropy HMAC-SHA256 secret and constant-time signature comparison against the exact raw bytes. The local bearer token cannot replace that signature. The route returns 503 while GitHub is disabled.

The receiver stores delivery UUID, payload SHA-256, event identity, installation/repository/run/attempt metadata, and processing state. Raw payloads and sender information are not retained. A replay with the same ID/content is idempotent; conflicting ID reuse returns 409. Multiple delivery IDs for the same repository/run/attempt/policy share one job. Eligible completed workflow-run deliveries and their processing jobs commit atomically before 202. Unsupported or unenrolled events are recorded as ignored.

Internal repository jobs reuse the worker's renewable job lease and fencing token; they have no environment lease. Network work occurs outside database transactions. Results commit only under the current job lease and an enabled matching repository policy. Environment jobs still use both job and environment leases and recheck intent before binding verified evidence. Verification does not take locks on waiting environment jobs: they discover accepted builds on their next scheduled prerequisite check, avoiding an inverse lock order with policy rows.

Dispatch is at-least-once: a crash after GitHub accepts the request but before acknowledgement can dispatch it again. Its deterministic source/request identity and immutable run-specific publication paths prevent a duplicate build from authorizing a different source. The receiver also verifies the stored request identity. No exactly-once external effects are claimed. A CI build may finish after the environment is deleted, but its result cannot recreate or reactivate that environment.

## Verification checks

The signed delivery is a wakeup, not deployment evidence. The worker independently fetches the run using the scoped App token and requires:

1. Matching run ID/current attempt, repository and head-repository IDs, approved branch, workflow ID/path, and allowed `push` or `workflow_dispatch` event. Fork/mismatched repository runs and PR/target events are rejected.
2. Completed successful run, full workflow head SHA, and workflow content at that SHA matching the approved file hash.
3. Exactly one non-expired `kubespawn-build-result-<attempt>` artifact from that run, with matching origin metadata and an API-supplied SHA-256 archive digest.
4. Downloaded ZIP bytes matching that digest; schema version, repository/run/attempt/source/request binding matching policy and stored dispatch identity.
5. Exactly `api` and `web`, exact approved GHCR paths, and immutable `sha256:<64 hex>` digests for both components. Missing components, mutable tags, wrong registry paths, or missing scan/SBOM/provenance policy declarations are rejected.

Verified evidence records the run/attempt, workflow revision/hash, artifact identity/digest, image digests, chart policy, and verification timestamp. A build result alone never creates an environment. An eligible ACTIVE, unexpired request with that pinned SHA and current policy can bind it and move to `QUEUED`; when the optional local Kubernetes adapter is enabled, separate chart/application admission and capacity checks may then move it to `PROVISIONING`. It never becomes READY or sets a serving revision from build or Argo evidence alone.

## Supplied CI pipeline

The workflow is opt-in via `KUBESPAWN_PUBLISH_IMAGES=true`. It runs for `main` pushes or explicit dispatch and uses full commit pins for third-party actions. Build runners have contents-read permission. They check out the full source SHA, run backend database/security tests or frontend typecheck/build, create single-platform OCI indexes with BuildKit SPDX SBOM/max provenance, and scan the runtime image. The scan uses a Docker archive selected from the OCI index because archive scanners can mishandle multi-manifest attestation indexes; the original OCI index is retained for publication.

The documented scan policy rejects HIGH/CRITICAL findings, including unfixed findings, with controlled empty ignore/config inputs. A separate package-write publisher downloads the run's fixed-name artifacts, never checks out or executes application code, copies all OCI manifests/attestations while preserving digests, and confirms registry-addressed manifests. It creates the authoritative result manifest and uploads the attempt-specific artifact.

The receiver trusts this reviewed producer's digest receipts and policy declarations after independently validating workflow identity, success, and artifact integrity. It does **not** independently verify Sigstore/SLSA signatures or pull image layers. BuildKit provenance is generated and retained, not claimed to be independently cryptographically verified by the receiver. Independent attestation/admission checks and stronger isolated builder controls remain internal-pilot gates. Artifact expiry after acceptance does not retroactively remove the stored verified evidence; retention/revocation policy is separate.

## Routes and remaining limits

| Route | Authentication | Purpose |
|---|---|---|
| `POST /integrations/github/webhook` | Raw-body HMAC | Durable eligible run receipt; installation/repository disable events |
| `GET /api/v1/github/status` | Local bearer | Configuration flag and enrolled policies |
| `POST /api/v1/github/repositories` | Local bearer | Remote policy validation and audited enrollment |
| `GET /api/v1/github/environment-builds` | Local bearer, owner | Latest 100 owned requests with source/build state and verified digests |

The local trust model remains one developer. Enterprise OIDC audience/team/tenant policy, a production worker profile, browser login, private preview routing and independent provenance admission remain required before deployment. API errors use controlled messages; failed queue records retain controlled reason codes. There is no public credential API, image override or unverified-build shortcut. The local owner-scoped retry route can reset only the current failed environment operation under its version precondition.

See [setup](github-setup.md) and [verification results](github-verification.md). Primary references: [GitHub JWT authentication](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app), [workflow runs](https://docs.github.com/en/rest/actions/workflow-runs), [artifact API](https://docs.github.com/en/rest/actions/artifacts), [Skopeo copy behavior](https://github.com/podman-container-tools/skopeo/blob/main/docs/skopeo-copy.1.md).
