# Preview workload starter

This initial chart renders only API/web Deployments and ClusterIP Services. It is not installed by project setup. The default values intentionally fail schema validation until an environment identity and verified image digests are supplied. The fixture contains non-deployable digests and is for rendering only.

```powershell
./.tools/helm/windows-amd64/helm.exe lint templates/preview-small -f templates/preview-small/fixtures/render-only.yaml --strict
./.tools/helm/windows-amd64/helm.exe template preview templates/preview-small -f templates/preview-small/fixtures/render-only.yaml --namespace ks-example-g0001
```

The controller must first create the namespace, restricted `preview-workload` service account, quota, network policy, pull identity, and constrained Argo Application/AppProject. Routing remains controller-owned. Services are named `<release>-api` and `<release>-web`; baseline allow rules must match their ports and pod labels.

Images must support non-root UID/GID 10001, a read-only root filesystem with writable `/tmp`, ports >=1024, and the configured health endpoints. Use a distinct liveness path when application readiness includes dependencies; never restart healthy processes merely because a dependency is unavailable. Database/cache, migration hooks, seed data, and credentials will be added with the sample-application contract. This starter does not yet provide a full-stack environment.
