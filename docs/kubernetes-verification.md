# Kubernetes milestone verification

## Automated checks

The backend suite contains 101 passing tests, including PostgreSQL integration tests for provisioning observations, concurrent capacity admission, policy/source binding, deletion/stale observation races, expired leases, cleanup retention/confirmed release, continuing cleanup retry, explicit failed-operation retry, and owner-scoped progress. A local HTTP contract test also verifies that cluster-scoped Namespace reads use the Kubernetes core API path.

The portal typecheck/production build and Helm lint for both generic and demo runtime profiles pass. Runtime credentials remain outside Git. The runtime controller uses its dedicated kubeconfig; it cannot directly create Deployments, create Roles, or escalate RBAC. It can bind only the fixed administrator-owned preview deployer ClusterRole to the namespace-scoped Argo controller.

## Local cluster checks

KinD 0.33.0 / Kubernetes 1.35.8, Cilium 1.20.4 and namespace-scoped Argo CD 3.5.6 are installed in the dedicated local development cluster. A verified GitHub build produced digest-pinned API and web images. Argo reached `Synced` / `Healthy`; all three API, web and isolated PostgreSQL deployments became available, and the worker recorded `WORKLOADS_HEALTHY` while correctly leaving the environment in `PROVISIONING`.

`scripts/verify-kubernetes.ps1` confirmed the allowed web-to-API and API-to-database paths. It also executed real denied connections from web to the database and from the preview API to the Kubernetes API. Impersonation checks confirmed that Argo cannot create Secrets, NetworkPolicies, RoleBindings or Namespaces and that the runtime controller cannot escalate Roles.

Deletion was issued through the owner-scoped lifecycle API. The Argo Application finalizer completed, then the namespace and AppProject disappeared. The worker recorded `CLEANUP_CONFIRMED`, moved the request to `DESTROYED`, and released its slot. A separate retained request remained `Synced` / `Healthy` with exactly one of two local capacity slots reserved.

## Scope

This is local workload provisioning. Authenticated Gateway routing, external endpoint readiness, independent provenance admission and production tenancy remain future work; `READY` is deliberately not set from Argo health alone. The PostgreSQL demo is ephemeral. No cloud resources or public preview ingress are created.
