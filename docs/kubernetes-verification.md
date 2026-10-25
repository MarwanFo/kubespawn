# Kubernetes milestone verification

## Automated checks

The backend suite contains 97 passing tests, including eight new PostgreSQL integration tests for provisioning observations, concurrent capacity admission, policy/source binding, deletion/stale observation races, expired leases, cleanup retention/confirmed release, continuing cleanup retry, and owner-scoped progress.

The portal typecheck/production build and Helm lint for both generic and demo runtime profiles pass. Runtime credentials remain outside Git. The runtime controller uses its dedicated kubeconfig; it cannot directly create Deployments.

## Local cluster checks

KinD 0.33.0 / Kubernetes 1.35.8, Cilium 1.20.4 and namespace-scoped Argo CD 3.5.6 are installed in the dedicated local development cluster. Live rollout, network-denial and cleanup results are recorded here after verification.

## Scope

This is local workload provisioning. Authenticated Gateway routing, external endpoint readiness, independent provenance admission and production tenancy remain future work; `READY` is deliberately not set from Argo health alone. The PostgreSQL demo is ephemeral. No cloud resources or public preview ingress are created.
