#requires -Version 7.0
. "$PSScriptRoot/common.ps1"
Import-LocalEnvironment
$taskApi = "http://127.0.0.1:$env:API_PORT"
$taskReady = $false
for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
    try {
        $taskHealth = Invoke-RestMethod "$taskApi/actuator/health/readiness" -TimeoutSec 3
        if ($taskHealth.status -eq 'UP') { $taskReady = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $taskReady) { throw 'API readiness did not become healthy. Check docker compose logs api.' }
$taskHeaders = @{ Authorization = "Bearer $env:KUBESPAWN_DEV_TOKEN" }
$taskPlatform = Invoke-RestMethod "$taskApi/api/v1/platform" -Headers $taskHeaders
if ($taskPlatform.controllerEnabled) {
    $taskKubernetes = Invoke-RestMethod "$taskApi/api/v1/kubernetes/status" -Headers $taskHeaders
    if (-not $taskKubernetes.enabled) { throw 'Kubernetes controller configuration is inconsistent.' }
    $null = Invoke-RestMethod "$taskApi/api/v1/kubernetes/environment-progress" -Headers $taskHeaders
}
if ($taskPlatform.capabilities -notcontains 'job-processing') { throw 'Local API does not expose worker progress.' }
$taskOperations = Invoke-RestMethod "$taskApi/api/v1/operations" -Headers $taskHeaders
$taskGithubStatus = Invoke-RestMethod "$taskApi/api/v1/github/status" -Headers $taskHeaders
$taskBuilds = Invoke-RestMethod "$taskApi/api/v1/github/environment-builds" -Headers $taskHeaders
if ($taskGithubStatus.configured -ne $taskPlatform.githubConfigured) { throw 'GitHub configuration status is inconsistent.' }
$taskWebhookProbe = Invoke-WebRequest "$taskApi/integrations/github/webhook" -Method Post -ContentType 'application/json' -Body '{}' `
    -Headers @{ Authorization = "Bearer $env:KUBESPAWN_DEV_TOKEN"; 'X-GitHub-Delivery' = [Guid]::NewGuid().ToString(); 'X-GitHub-Event' = 'ping' } -SkipHttpErrorCheck
$taskExpectedWebhookStatus = if ($taskGithubStatus.configured) { 401 } else { 503 }
if ($taskWebhookProbe.StatusCode -ne $taskExpectedWebhookStatus) { throw 'Webhook signature/setup gate did not reject the probe.' }
$taskInventory = Invoke-RestMethod "$taskApi/api/v1/environments" -Headers $taskHeaders
$taskUnauthorized = Invoke-WebRequest "$taskApi/api/v1/environments" -SkipHttpErrorCheck
if ($taskUnauthorized.StatusCode -ne 401) { throw 'Unauthenticated API request was not rejected.' }
$taskPortal = "http://127.0.0.1:$env:PORTAL_PORT"
$taskHtml = Invoke-WebRequest $taskPortal
if ($taskHtml.Content -notmatch 'KubeSpawn') { throw 'Portal HTML was not served.' }
$taskProxy = Invoke-RestMethod "$taskPortal/api/v1/platform"
if ($taskProxy.name -ne 'KubeSpawn') { throw 'Portal-to-API proxy is not working.' }
Write-Host "Smoke checks passed: database readiness, bearer authentication, inventory ($($taskInventory.items.Count)), operation progress ($(@($taskOperations).Count)), build progress ($(@($taskBuilds).Count)), webhook gate, portal, and API proxy."
