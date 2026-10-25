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

The two-node profile maps localhost ports 9080/9443 to NodePorts 30080/30443. The small existing-cluster profile reaches the same HTTP NodePort through the loopback-only `preview-edge` Compose service, so its cluster does not need recreation. `scripts/setup-kubernetes.ps1` installs Envoy Gateway 1.8.5 and applies the administrator-owned Gateway. Local HTTPS remains future work; production requires managed DNS, certificates, and trusted edge identity.

KinD is not automatically created by setup/start, and no existing Kubernetes context is modified by those scripts. See the v3 architecture for local DNS, certificates, webhook connectivity, and production separation.
