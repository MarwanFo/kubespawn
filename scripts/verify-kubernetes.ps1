#requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^ks-man-[a-f0-9]{32}$')][string]$Namespace)
. "$PSScriptRoot/common.ps1"
$taskAdmin=Join-Path $ProjectRoot '.secrets/kind-admin.yaml'
$taskContext=(& kubectl --kubeconfig $taskAdmin config current-context).Trim()
if($taskContext -ne 'kind-kubespawn') { throw 'Only the dedicated local cluster is supported.' }
$taskDeployments=& kubectl --kubeconfig $taskAdmin -n $Namespace get deployment -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read preview deployments'
if($taskDeployments.items.Count -ne 3) { throw 'Expected the demo API/web/database deployments.' }
foreach($taskDeployment in $taskDeployments.items) {
    if($taskDeployment.status.availableReplicas -ne 1 -or $taskDeployment.status.observedGeneration -lt $taskDeployment.metadata.generation) { throw 'Wait for all three deployments to become available.' }
}
$taskPrincipal='system:serviceaccount:argocd:argocd-application-controller'
foreach($taskResource in @('secrets','networkpolicies','rolebindings')) {
    $taskAllowed=(& kubectl --kubeconfig $taskAdmin auth can-i create $taskResource -n $Namespace --as $taskPrincipal).Trim()
    if($taskAllowed -ne 'no') { throw "Argo unexpectedly has create rights for $taskResource" }
}
$taskAllowed=(& kubectl --kubeconfig $taskAdmin auth can-i create namespaces --as $taskPrincipal).Trim()
if($taskAllowed -ne 'no') { throw 'Argo unexpectedly has namespace creation rights.' }
$taskAllowed=(& kubectl --kubeconfig $taskAdmin auth can-i escalate roles -n $Namespace --as system:serviceaccount:kubespawn-system:kubespawn-controller).Trim()
if($taskAllowed -ne 'no') { throw 'Controller unexpectedly has Role escalation rights.' }
$taskWeb=(& kubectl --kubeconfig $taskAdmin -n $Namespace get pod -l app.kubernetes.io/name=web -o jsonpath='{.items[0].metadata.name}').Trim()
$taskApi=(& kubectl --kubeconfig $taskAdmin -n $Namespace get pod -l app.kubernetes.io/name=api -o jsonpath='{.items[0].metadata.name}').Trim()
$taskDatabaseIp=(& kubectl --kubeconfig $taskAdmin -n $Namespace get service preview-database -o jsonpath='{.spec.clusterIP}').Trim()
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskWeb -- wget -q -T 5 -O - http://preview-api:8080/actuator/health/readiness
Assert-NativeSuccess 'Allowed web-to-API and cluster DNS path'
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskApi -- /bin/sh -c "nc -w 3 $taskDatabaseIp 5432 < /dev/null"
Assert-NativeSuccess 'Allowed API-to-database TCP path'
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskWeb -- /bin/sh -c "nc -w 3 $taskDatabaseIp 5432 < /dev/null"
if($LASTEXITCODE -eq 0) { throw 'Web unexpectedly reached the database.' }
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskApi -- /bin/sh -c 'nc -w 3 10.96.0.1 443 < /dev/null'
if($LASTEXITCODE -eq 0) { throw 'Preview API unexpectedly reached the Kubernetes API.' }
Write-Host 'Live checks passed: three available deployments, restricted Argo/controller rights, allowed web/API/database paths, denied web-to-database and API-to-Kubernetes traffic.'
