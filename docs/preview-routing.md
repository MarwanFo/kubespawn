# Authenticated preview routing (v0.6)

KubeSpawn's local preview edge uses Gateway API with Envoy Gateway 1.8.5. A controller-owned `HTTPRoute` maps `/e/<environment-id>/` to that environment's web service and removes the prefix before forwarding. The reviewed workload chart contains no Gateway resources.

## Local setup

Use PowerShell 7 and the dedicated `kind-kubespawn` cluster:

```powershell
cd C:\Users\Marwan\Documents\IDP
./scripts/setup-kubernetes.ps1
./scripts/start.ps1
```

The setup script installs the Envoy Gateway Helm chart, creates the administrator-owned Gateway, refreshes the restricted controller and Argo credentials, and leaves the normal host kubeconfig unchanged. The Kubernetes Compose overlay starts a small loopback-only proxy on `http://localhost:9080`; it connects to the Gateway's fixed NodePort over Docker's private `kind` network. Existing single-node clusters do not need to be recreated.

## Access contract

The portal shows **Open preview** only after the environment is `READY`. That form posts to the owner-scoped platform endpoint:

```text
POST /api/v1/kubernetes/environments/{id}/preview-session
```

The API reads the environment's random access token from its isolated `preview-runtime` Secret, sets it in an `HttpOnly`, `SameSite=Strict` cookie scoped to `/e/<id>/`, and redirects to the preview URL. The token is absent from JavaScript, URLs, logs, and the preview application's API. Anonymous or incorrectly authenticated requests return `401`.

The preview web container receives only its API token and preview access token; it no longer receives the database password. Its unprotected health endpoint is reachable only inside the cluster. The workload NetworkPolicy permits web ingress from the Envoy Gateway namespace and retains the default deny boundary.

## Readiness and cleanup

Argo health is necessary but does not produce `READY`. On every active reconciliation, the controller verifies all of these observations:

1. Argo reports the exact chart revision `Synced` and workloads `Healthy`.
2. The shared Gateway reports `Programmed=True`.
3. The environment's `HTTPRoute` reports `Accepted=True` and `ResolvedRefs=True`.
4. An anonymous request through the edge returns `401`.
5. The same request with the environment cookie succeeds.

Only then does the fenced database transaction set `phase=READY` and `serving_revision` to the verified source SHA. Losing any observation moves the environment back to `PROVISIONING` and clears the serving revision. Deletion removes the route before Argo workloads and namespace cleanup.

This is a loopback-only local development implementation. Production still requires browser OIDC, tenant authorization, HTTPS with managed DNS/certificates, and an Envoy Gateway `SecurityPolicy` backed by trusted edge authorization.

Primary references: [Envoy Gateway Helm installation](https://gateway.envoyproxy.io/v1.8/install/install-helm/), [EnvoyProxy customization](https://gateway.envoyproxy.io/v1.8/tasks/operations/customize-envoyproxy/), [Gateway API URL rewrites](https://gateway-api.sigs.k8s.io/guides/http-redirect-rewrite/), and [Envoy Gateway SecurityPolicy](https://gateway.envoyproxy.io/docs/concepts/gateway_api_extensions/security-policy/).
