#requires -Version 7.0
# Writes only ignored credentials for the dedicated local development cluster.
param([string] $AdminKubeconfig = "$PSScriptRoot/../.secrets/kind-admin.yaml")
. "$PSScriptRoot/common.ps1"
Import-LocalEnvironment
$taskContext = (& kubectl --kubeconfig $AdminKubeconfig config current-context).Trim()
Assert-NativeSuccess 'Read local Kubernetes context'
if ($taskContext -ne 'kind-kubespawn') { throw 'Use only the dedicated kind-kubespawn development cluster.' }
& kubectl --kubeconfig $AdminKubeconfig apply -f "$ProjectRoot/infra/local/controller-admission.yaml"
Assert-NativeSuccess 'Install controller admission boundary'
& kubectl --kubeconfig $AdminKubeconfig apply -f "$ProjectRoot/infra/local/controller-rbac.yaml"
Assert-NativeSuccess 'Install controller RBAC'
& kubectl --kubeconfig $AdminKubeconfig apply --server-side --field-manager=kubespawn-local -f "$ProjectRoot/infra/local/argocd-config.yaml"
Assert-NativeSuccess 'Configure Argo RBAC-aware resource observation'
$taskControllerToken = (& kubectl --kubeconfig $AdminKubeconfig -n kubespawn-system create token kubespawn-controller --duration=24h).Trim()
Assert-NativeSuccess 'Issue local controller credential'
$taskAdmin = (& kubectl --kubeconfig $AdminKubeconfig config view --raw -o json | ConvertFrom-Json)
Assert-NativeSuccess 'Read local CA configuration'
$taskConfig = @{
    apiVersion='v1';kind='Config';'current-context'='kubespawn-controller'
    clusters=@(@{name='local';cluster=@{server=$taskAdmin.clusters[0].cluster.server;'certificate-authority-data'=$taskAdmin.clusters[0].cluster.'certificate-authority-data'}})
    users=@(@{name='controller';user=@{token=$taskControllerToken}})
    contexts=@(@{name='kubespawn-controller';context=@{cluster='local';user='controller'}})
}
$taskConfigPath=Join-Path $ProjectRoot '.secrets/kubernetes-controller.json'
[IO.File]::WriteAllText($taskConfigPath,($taskConfig | ConvertTo-Json -Depth 10))
[IO.File]::WriteAllLines((Join-Path $ProjectRoot '.env.kubernetes'),@('KUBERNETES_ENABLED=true',"KUBERNETES_KUBECONFIG_FILE=$taskConfigPath",'KUBERNETES_CAPACITY_SLOTS=2'))
# Argo uses its own service account, with deployment rights granted per environment.
$taskArgoToken=(& kubectl --kubeconfig $AdminKubeconfig -n argocd create token argocd-application-controller --duration=24h).Trim()
Assert-NativeSuccess 'Issue local Argo credential'
$taskArgoSecret=@{
    apiVersion='v1';kind='Secret';metadata=@{name='kubespawn-local-cluster';namespace='argocd';labels=@{'argocd.argoproj.io/secret-type'='cluster'}}
    stringData=@{name='kubespawn-local';server='https://kubernetes.default.svc';namespaces='kubespawn-unassigned';clusterResources='false';config=(@{bearerToken=$taskArgoToken;tlsClientConfig=@{insecure=$false;caData=$taskAdmin.clusters[0].cluster.'certificate-authority-data'}}|ConvertTo-Json -Depth 5 -Compress)}
}
$taskExisting=& kubectl --kubeconfig $AdminKubeconfig -n argocd get secret kubespawn-local-cluster --ignore-not-found -o json
Assert-NativeSuccess 'Read Argo cluster registration'
if ($taskExisting) { $taskExistingValue=$taskExisting|ConvertFrom-Json; $taskArgoSecret.stringData.namespaces=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($taskExistingValue.data.namespaces)) }
$taskArgoSecret | ConvertTo-Json -Depth 10 | & kubectl --kubeconfig $AdminKubeconfig apply -f - | Out-Null
Assert-NativeSuccess 'Configure namespace-scoped Argo cluster access'
Write-Host 'Local Kubernetes credentials saved. They expire after 24 hours; rerun this script to renew. No credentials are printed.'
