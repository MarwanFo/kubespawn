# Preview workload starter

This workload-only chart renders API/web Deployments and ClusterIP Services. The optional `kubespawn-demo` runtime profile adds an isolated ephemeral PostgreSQL Deployment/Service and an Nginx ConfigMap for the reviewed KubeSpawn repository. The default values intentionally fail schema validation until an environment identity and verified image digests are supplied. The fixture contains non-deployable digests and is for rendering only.

```powershell
./.tools/helm/windows-amd64/helm.exe lint templates/preview-small -f templates/preview-small/fixtures/render-only.yaml --strict
./.tools/helm/windows-amd64/helm.exe template preview templates/preview-small -f templates/preview-small/fixtures/render-only.yaml --namespace ks-example-g0001
```

The controller must first create the namespace, restricted `preview-workload` service account, quota, network policy, pull identity, and constrained Argo Application/AppProject. Routing remains controller-owned. Services are named `<release>-api` and `<release>-web`; baseline allow rules must match their ports and pod labels.

API/web images must support non-root UID/GID 10001, a read-only root filesystem with writable `/tmp`, ports >=1024, and the configured health endpoints. Use a distinct liveness path when application readiness includes dependencies. The demo database uses UID 70, an isolated random password from the controller-owned `preview-runtime` Secret, and ephemeral storage; it has no production durability contract. The demo API disables its nested worker/expiry scanner. Runtime credentials and namespace/security resources remain controller-owned. Production database/migration/seed contracts remain future work.
