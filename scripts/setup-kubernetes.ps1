#requires -Version 7.0
param()
. "$PSScriptRoot/common.ps1"
$taskKind=Join-Path $ProjectRoot '.tools/kind/kind.exe'
$taskHelm=Join-Path $ProjectRoot '.tools/helm/windows-amd64/helm.exe'
$taskAdmin=Join-Path $ProjectRoot '.secrets/kind-admin.yaml'
if (-not (Test-Path -LiteralPath $taskHelm)) { throw 'Run scripts/setup.ps1 first to install the pinned Helm tool.' }
New-Item -ItemType Directory -Force -Path (Split-Path $taskKind), (Split-Path $taskAdmin) | Out-Null
if (-not (Test-Path -LiteralPath $taskKind)) {
    $taskRelease='https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0'
    Invoke-WebRequest "$taskRelease/kind-windows-amd64" -OutFile $taskKind
    $taskResponse=Invoke-WebRequest "$taskRelease/kind-windows-amd64.sha256sum"
    $taskText=if($taskResponse.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($taskResponse.Content) } else { [string]$taskResponse.Content }
    if ((Get-FileHash -LiteralPath $taskKind -Algorithm SHA256).Hash -ine $taskText.Trim().Split(' ')[0]) { throw 'KinD checksum mismatch.' }
}
$taskClusters=& $taskKind get clusters
Assert-NativeSuccess 'Read local KinD clusters'
if ($taskClusters -notcontains 'kubespawn') {
    & $taskKind create cluster --config "$ProjectRoot/infra/local/kind-provisioning.yaml" --kubeconfig $taskAdmin --wait 0s
    Assert-NativeSuccess 'Create local KinD cluster'
}
if (-not (Test-Path -LiteralPath $taskAdmin)) { throw 'Existing cluster has no task-specific admin kubeconfig. Recover it explicitly before setup.' }
& $taskHelm upgrade --install cilium https://helm.cilium.io/cilium-1.20.4.tgz --kubeconfig $taskAdmin --namespace kube-system `
    --set ipam.mode=kubernetes --set operator.replicas=1 --set kubeProxyReplacement=false --set envoy.enabled=false --wait --timeout 5m
Assert-NativeSuccess 'Install Cilium'
& $taskHelm upgrade --install envoy-gateway oci://docker.io/envoyproxy/gateway-helm --version v1.8.5 `
    --kubeconfig $taskAdmin --namespace envoy-gateway-system --create-namespace --wait --timeout 5m
Assert-NativeSuccess 'Install Envoy Gateway'
& kubectl --kubeconfig $taskAdmin apply -f "$ProjectRoot/infra/local/envoy-gateway.yaml"
Assert-NativeSuccess 'Configure the local preview Gateway'
& kubectl --kubeconfig $taskAdmin wait --timeout=5m -n gateway-system gateway/preview-gateway --for=condition=Programmed
Assert-NativeSuccess 'Wait for the local preview Gateway'
& kubectl --kubeconfig $taskAdmin create namespace argocd --dry-run=client -o json | & kubectl --kubeconfig $taskAdmin apply -f -
Assert-NativeSuccess 'Ensure Argo namespace'
$taskArgo='https://raw.githubusercontent.com/argoproj/argo-cd/v3.5.6/manifests'
foreach ($taskCrd in @('application','appproject','applicationset')) {
    & kubectl --kubeconfig $taskAdmin apply --server-side -f "$taskArgo/crds/$taskCrd-crd.yaml"
    Assert-NativeSuccess 'Install Argo CRDs'
}
& kubectl --kubeconfig $taskAdmin apply --server-side -n argocd -f "$taskArgo/namespace-install.yaml"
Assert-NativeSuccess 'Install namespace-scoped Argo'
& "$PSScriptRoot/configure-kubernetes.ps1" -AdminKubeconfig $taskAdmin
Write-Host 'Local cluster prepared. Start the API with the GitHub and Kubernetes Compose overlays.'
