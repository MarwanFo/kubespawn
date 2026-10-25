#requires -Version 7.0
param(
    [Parameter(Mandatory)] [long] $RepositoryId,
    [Parameter(Mandatory)] [long] $InstallationId,
    [string] $Repository = 'MarwanFo/kubespawn',
    [string] $Branch = 'main'
)
. "$PSScriptRoot/common.ps1"
Import-LocalEnvironment
if ($RepositoryId -lt 1 -or $InstallationId -lt 1) { throw 'Positive repository and installation IDs are required.' }
& git -C $ProjectRoot diff --quiet HEAD -- .github/workflows/preview-build.yaml templates/preview-small
if ($LASTEXITCODE -ne 0) { throw 'Commit and push the approved workflow and chart before enrollment.' }
$taskWorkflow = Join-Path $ProjectRoot '.github/workflows/preview-build.yaml'
& git -C $ProjectRoot ls-files --error-unmatch -- $taskWorkflow | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Commit and push the approved workflow before enrollment.' }
$taskWorkflowHash = (Get-FileHash -LiteralPath $taskWorkflow -Algorithm SHA256).Hash.ToLowerInvariant()
$taskRevision = (& git -C $ProjectRoot rev-parse HEAD).Trim()
Assert-NativeSuccess 'Read chart revision'
$taskChartRoot = Join-Path $ProjectRoot 'templates/preview-small'
$taskHash = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
try {
    $taskChartFiles = [Collections.Generic.SortedDictionary[string, byte[]]]::new([StringComparer]::Ordinal)
    foreach ($taskFile in Get-ChildItem -LiteralPath $taskChartRoot -Recurse -File) {
        $taskRelative = [IO.Path]::GetRelativePath($taskChartRoot, $taskFile.FullName).Replace('\','/')
        & git -C $ProjectRoot ls-files --error-unmatch -- $taskFile.FullName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Commit and push all chart files before enrollment.' }
        $taskChartFiles[$taskRelative] = [Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText($taskFile.FullName).Replace("`r`n","`n"))
    }
    foreach ($taskRelative in $taskChartFiles.Keys) {
        $taskHash.AppendData([Text.Encoding]::UTF8.GetBytes($taskRelative + "`0"))
        $taskHash.AppendData($taskChartFiles[$taskRelative])
    }
    $taskTemplateHash = [Convert]::ToHexString($taskHash.GetHashAndReset()).ToLowerInvariant()
} finally { $taskHash.Dispose() }
$taskLowerRepository = $Repository.ToLowerInvariant()
$taskBody = @{
    repositoryId=$RepositoryId; installationId=$InstallationId; repository=$Repository; branch=$Branch
    workflowPath='.github/workflows/preview-build.yaml'; workflowSha256=$taskWorkflowHash
    apiImage="ghcr.io/$taskLowerRepository-preview-api"; webImage="ghcr.io/$taskLowerRepository-preview-web"
    chartRevision=$taskRevision; templateHash=$taskTemplateHash
} | ConvertTo-Json
$taskResponse = Invoke-RestMethod "http://127.0.0.1:$env:API_PORT/api/v1/github/repositories" -Method Post `
    -Headers @{Authorization="Bearer $env:KUBESPAWN_DEV_TOKEN"} -ContentType 'application/json' -Body $taskBody
$taskResponse | Select-Object name,id,installationId,policyVersion,enabled
