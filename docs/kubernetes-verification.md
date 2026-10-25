# Kubernetes milestone verification

## Automated checks

The backend suite contains 102 passing tests, including PostgreSQL integration tests for provisioning observations, concurrent capacity admission, policy/source binding, deletion/stale observation races, expired leases, cleanup retention/confirmed release, continuing cleanup retry, explicit failed-operation retry, owner-scoped progress, honest READY/serving-revision commits, and path-scoped preview sessions. A local HTTP contract test also verifies that cluster-scoped Namespace reads use the Kubernetes core API path.

The portal typecheck/production build and Helm lint for both generic and demo runtime profiles pass. Runtime credentials remain outside Git. The runtime controller uses its dedicated kubeconfig; it cannot directly create Deployments, create Roles, or escalate RBAC. It can bind only the fixed administrator-owned preview deployer ClusterRole to the namespace-scoped Argo controller.

## Local cluster checks

KinD 0.33.0 / Kubernetes 1.35.8, Cilium 1.20.4, Envoy Gateway 1.8.5, and namespace-scoped Argo CD 3.5.6 are installed in the dedicated local development cluster. GitHub foundation run `40226445442` and the main image run `40226445451` passed. Policy-4 run `40226463036` produced the digest-pinned API/web images for environment `a466ee44-ca03-41da-bc9c-497bbe8bb0e3` at source `b2ccab384ca6928107df77437718f5dc9f2ae797` and reviewed chart `31d6131f0166a26bed5c5adcae32cbff685c7e54`.

Argo reached `Synced` / `Healthy`; all three API, web and isolated PostgreSQL deployments became available. The shared Gateway reported `Programmed=True`, and the environment route reported current-generation `Accepted=True` and `ResolvedRefs=True`. The worker then checked anonymous denial plus the authenticated HTML, relative asset and preview API paths before recording `EDGE_READY`, `READY`, and the exact serving revision.

`scripts/verify-kubernetes.ps1` confirmed the allowed web-to-API and API-to-database paths. It also executed real denied connections from web to the database and from the preview API to the Kubernetes API. Impersonation checks confirmed that Argo cannot create Secrets, NetworkPolicies, RoleBindings or Namespaces and that the runtime controller cannot escalate Roles. A browser-style session test posted through the portal proxy, followed the 303 redirect, confirmed the path-scoped HttpOnly cookie, and loaded the preview page, hashed asset, and preview API through `http://localhost:9080/e/a466ee44-ca03-41da-bc9c-497bbe8bb0e3/`.

Deletion was issued through the owner-scoped lifecycle API. The Argo Application finalizer completed, then the namespace and AppProject disappeared. The worker recorded `CLEANUP_CONFIRMED`, moved the request to `DESTROYED`, and released its slot. At the final check, the new READY generation and one retained historical generation occupied the two configured local slots.

## Scope

This is loopback-only local preview routing. Production OIDC/authorization, TLS/DNS, independent signature admission and production tenancy remain future work; `READY` is deliberately not set from Argo health alone. The PostgreSQL demo is ephemeral. No cloud resources or public preview ingress are created.
