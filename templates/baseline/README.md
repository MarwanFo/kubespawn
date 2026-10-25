# Controller-owned baseline

`namespace-example.yaml` demonstrates ownership and defaults; it is not automatically applied. Its default-deny policy intentionally permits only cluster DNS. Add narrowly scoped Gateway-to-app, API-to-database, and telemetry rules for the selected template before serving traffic. Resolver labels vary by cluster and must be verified.

Generate immutable environment ownership, namespace names, and annotations from validated controller input. Pin the Pod Security Admission policy version to the tested cluster compatibility matrix before deployment. Add protected admission rules, restricted Argo resource permissions, image pull identity, secret provisioning, route/access policy, and the controller's cleanup inventory as part of the lifecycle milestone.

This directory is never included in the workload chart. Preview workloads and Argo must not be allowed to modify the baseline.
