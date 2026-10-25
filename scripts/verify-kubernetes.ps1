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
$taskGateway=(& kubectl --kubeconfig $taskAdmin -n gateway-system get gateway preview-gateway -o json | ConvertFrom-Json)
if(-not ($taskGateway.status.conditions | Where-Object { $_.type -eq 'Programmed' -and $_.status -eq 'True' })) { throw 'Preview Gateway is not programmed.' }
$taskRoute=(& kubectl --kubeconfig $taskAdmin -n $Namespace get httproute preview-route -o json | ConvertFrom-Json)
$taskRouteConditions=@($taskRoute.status.parents.conditions)
if(-not ($taskRouteConditions | Where-Object { $_.type -eq 'Accepted' -and $_.status -eq 'True' })) { throw 'Preview route is not accepted.' }
if(-not ($taskRouteConditions | Where-Object { $_.type -eq 'ResolvedRefs' -and $_.status -eq 'True' })) { throw 'Preview route references are unresolved.' }
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskWeb -- wget -q -T 5 -O - http://preview-api:8080/actuator/health/readiness
Assert-NativeSuccess 'Allowed web-to-API and cluster DNS path'
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskApi -- /bin/sh -c "nc -w 3 $taskDatabaseIp 5432 < /dev/null"
Assert-NativeSuccess 'Allowed API-to-database TCP path'
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskWeb -- /bin/sh -c "nc -w 3 $taskDatabaseIp 5432 < /dev/null"
if($LASTEXITCODE -eq 0) { throw 'Web unexpectedly reached the database.' }
& kubectl --kubeconfig $taskAdmin -n $Namespace exec $taskApi -- /bin/sh -c 'nc -w 3 10.96.0.1 443 < /dev/null'
if($LASTEXITCODE -eq 0) { throw 'Preview API unexpectedly reached the Kubernetes API.' }
$taskEnvironment=(& kubectl --kubeconfig $taskAdmin get namespace $Namespace -o jsonpath='{.metadata.labels.kubespawn\.io/environment-id}').Trim()
$taskPreviewToken=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
    (& kubectl --kubeconfig $taskAdmin -n $Namespace get secret preview-runtime -o jsonpath='{.data.PREVIEW_ACCESS_TOKEN}').Trim()))
$taskPreviewPort=if($env:PREVIEW_PORT){$env:PREVIEW_PORT}else{'9080'}
$taskPreviewUrl="http://127.0.0.1:$taskPreviewPort/e/$taskEnvironment/"
$taskDenied=Invoke-WebRequest $taskPreviewUrl -SkipHttpErrorCheck
if($taskDenied.StatusCode -ne 401) { throw 'Anonymous preview access was not denied.' }
$taskAllowed=Invoke-WebRequest $taskPreviewUrl -Headers @{Cookie="kubespawn_preview=$taskPreviewToken"} -SkipHttpErrorCheck
if($taskAllowed.StatusCode -lt 200 -or $taskAllowed.StatusCode -ge 400) { throw 'Authenticated preview access did not succeed.' }
Write-Host 'Live checks passed: Gateway/route readiness, authenticated preview and anonymous denial, three available deployments, restricted Argo/controller rights, allowed web/API/database paths, denied web-to-database and API-to-Kubernetes traffic.'
