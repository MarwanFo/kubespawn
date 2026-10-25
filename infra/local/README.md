# Optional preview-cluster foundation

The application foundation uses Compose and does not need Kubernetes. `kind.yaml` prepares a separate cluster for the controller milestone. KinD, Helm, kubectl, Docker, and sufficient memory are required. Pin their tested releases and the KinD node image when enabling cluster CI.

```sh
kind create cluster --config infra/local/kind.yaml
helm repo add cilium https://helm.cilium.io/
helm upgrade --install cilium cilium/cilium --version 1.20.4 --namespace kube-system --set ipam.mode=kubernetes
kubectl -n kube-system rollout status daemonset/cilium --timeout=180s
kubectl wait --for=condition=Ready nodes --all --timeout=180s
```

The configuration disables the default CNI. Nodes will not be ready until Cilium is installed. kube-proxy remains enabled. Prove ingress/egress denial with real traffic before using previews.

The optional mappings reserve localhost ports 9080/9443 for a future Gateway data plane using NodePorts 30080/30443. They do not install Envoy Gateway or create a listener. Gateway, cert-manager/local CA, Argo CD, Kyverno, restricted deployment roles, secret handling, and baseline sequencing must be installed and tested as the next milestone. Do not deploy workloads into an unguarded namespace.

KinD is not automatically created by setup/start, and no existing Kubernetes context is modified by those scripts. See the v3 architecture for local DNS, certificates, webhook connectivity, and production separation.
