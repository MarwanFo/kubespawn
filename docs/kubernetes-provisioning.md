# Local Kubernetes provisioning (v0.5)

The local worker now reserves capacity, establishes an owned namespace baseline, creates a constrained Argo AppProject and Application, and observes Argo sync/health. Deletion waits for the Argo finalizer and confirmed namespace/project absence before releasing capacity. Kubernetes provisioning is opt-in and supported only in the local profile.

## Local setup

Use PowerShell 7 and Docker Desktop Linux containers. The single-node local development profile runs on this machine's 8 GiB Docker allocation; the architecture's two-node configuration remains separate in `infra/local/kind.yaml`.

```powershell
cd C:\Users\Marwan\Documents\IDP
./scripts/setup-kubernetes.ps1
```

This uses checksum-verified KinD 0.33.0, Kubernetes 1.35.8 pinned by image digest, Cilium 1.20.4, and namespace-scoped Argo CD 3.5.6. Credentials and the isolated admin kubeconfig are ignored under `.secrets`; the normal host kubeconfig is not changed. The runtime controller uses a separate restricted service account. Local controller and Argo tokens expire after 24 hours; renew with `./scripts/configure-kubernetes.ps1` and restart the API. The controller never loads a default host/admin context.

After configuring the GitHub App and committing/pushing the reviewed workload chart, enroll the repository again. A changed chart requires new policy/build verification; old evidence cannot silently approve a new chart. Build verification remains the reviewed-producer trust model documented in `github-build-verification.md`.

```powershell
./scripts/enroll-github-repository.ps1 -RepositoryId 1432768345 -InstallationId 175153081
. ./scripts/common.ps1
Import-LocalEnvironment
$taskCompose = Get-ProjectComposeArguments
docker compose @taskCompose up -d --build postgres api
./scripts/smoke.ps1
```

Keep an existing Vite terminal running. `scripts/start.ps1` also selects enabled overlays automatically but starts the Docker portal, so stop Vite first when using that full-stack helper.

## What gets provisioned

The KubeSpawn repository has a deliberately local demo contract: two verified API/web images and a separate ephemeral PostgreSQL instance. Its database password and local API bearer token are random per environment and are never copied from the platform. The preview's worker and expiry scanner are disabled. Database data is disposable and is lost if the database pod is replaced. Other repositories wait for an explicit application contract rather than inheriting KubeSpawn-specific configuration.

The controller creates a restricted workload service account, quota, limits, default-deny ingress/egress, cluster DNS access, web-to-API and API-to-database allowances, and deployment RBAC. The chart creates only workloads/services/configmaps. Argo has per-namespace deployment rights and cannot mutate quotas, network policy, service accounts, secrets, RBAC, routes, or cluster resources. Its cluster registration lists only managed destination namespaces.

Every resource carries immutable environment ownership and the environment lease fence. The adapter refuses to adopt unowned resources, uses resource-version comparisons for updates/deletes, and checks current leases, active intent, expiration, repository approval and exact build binding before each active mutation. Admission constrains the controller's namespace/Argo identities; RBAC and Pod Security constrain Argo workloads. This is a local development boundary, not completed tenant authorization or independent provenance admission.

## Progress and observation

`GET /api/v1/kubernetes/status` exposes enabled/capacity/routing status; `GET /api/v1/kubernetes/environment-progress` is owner scoped. The portal shows reservations, Argo identity, observed stages and cleanup. Active jobs keep reconciling so namespace baseline and Argo drift are repaired. Healthy Argo workloads produce `WORKLOADS_HEALTHY`, while the environment remains `PROVISIONING`: private routing, authentication and external endpoint health must be implemented before a `READY` badge or serving revision is recorded.

Two slots are admitted atomically under a PostgreSQL cluster allocation lock. Slots are held through failed provisioning and terminating namespaces. Cleanup jobs continue retrying beyond the normal provisioning failure budget. After `DESTROYED`, cleanup tombstones remain polled to catch late remote effects; newly observed resources reopen cleanup and retain capacity. External effects are at least once. A database fence cannot cancel an already accepted Kubernetes call; this is why cleanup and tombstone reconciliation remain necessary.

For local inspection only, forward the web service on localhost:

```powershell
kubectl --kubeconfig .secrets/kind-admin.yaml -n YOUR_NAMESPACE port-forward --address 127.0.0.1 service/preview-web 9090:8080
```

Open `http://localhost:9090`. There is no public preview route yet. Keep the GitHub webhook tunnel pointed at the platform API, not a preview.

## Verification and limitations

Run `scripts/verify.ps1 -Integration`, Helm lint for generic/demo profiles, the live smoke script, and the live Kubernetes checks recorded in `kubernetes-verification.md`. Test real Argo rollout, delete/finalizer completion, reservation release, forbidden controller/Argo operations, and actual denied network traffic. An accepted NetworkPolicy object alone is not proof of enforcement.

Next: authenticated Gateway routing, endpoint checks, independent image/provenance admission, a production application/database/migration contract, durable storage/provider inventory, full tenant/project capacity budgets, fleet orphan scans and database-restore quarantine. Do not deploy this local profile to a shared or production cluster.

Primary references: [Cilium on KinD](https://docs.cilium.io/en/stable/installation/kind/), [namespace-scoped Argo installation](https://argo-cd.readthedocs.io/en/stable/operator-manual/installation/), [Argo projects](https://argo-cd.readthedocs.io/en/stable/user-guide/projects/), [Argo finalizers](https://argo-cd.readthedocs.io/en/stable/user-guide/app_deletion/).
