# Cloud infrastructure boundary

No cloud resources are created by the project setup. Before adding provider resources, select the cloud/account/region, remote state and locking, preview-cluster isolation, network/metadata restrictions, identity model, and cost budget in an ADR. Keep provider credentials and state out of Git.

The first local milestone must pass before cloud provisioning. Terraform will own cloud/network/DNS/IAM primitives and platform bootstrap; it must not also manage controller-generated preview namespaces or Argo Applications.
