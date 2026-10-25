#requires -Version 7.0
param([string] $AppId, [string] $PrivateKeyFile)
. "$PSScriptRoot/common.ps1"

$taskGithubEnv = Join-Path $ProjectRoot '.env.github'
$taskSecretDirectory = Join-Path $ProjectRoot '.secrets'
$taskSecretFile = Join-Path $taskSecretDirectory 'github-webhook.secret'
New-Item -ItemType Directory -Force -Path $taskSecretDirectory | Out-Null
$taskSecret = $null
if (Test-Path -LiteralPath $taskGithubEnv) {
    foreach ($taskLine in Get-Content -LiteralPath $taskGithubEnv) {
        if ($taskLine -match '^GITHUB_WEBHOOK_SECRET=(.+)$') { $taskSecret = $Matches[1] }
    }
}
if (-not $taskSecret -and (Test-Path -LiteralPath $taskSecretFile)) { $taskSecret = (Get-Content -LiteralPath $taskSecretFile -Raw).Trim() }
if (-not $taskSecret) { $taskSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
if ($taskSecret.Length -lt 32) { throw 'The existing webhook secret is too short. Review the local configuration.' }
if (-not (Test-Path -LiteralPath $taskSecretFile)) { Set-Content -LiteralPath $taskSecretFile -Value $taskSecret -NoNewline -Encoding utf8NoBOM }

if ($AppId -or $PrivateKeyFile) {
    if ($AppId -notmatch '^[0-9]+$' -or -not $PrivateKeyFile) { throw 'Provide both a numeric AppId and PrivateKeyFile.' }
    $taskKeyPath = (Resolve-Path -LiteralPath $PrivateKeyFile).Path
    $taskPem = Get-Content -LiteralPath $taskKeyPath -Raw
    $taskRsa = [Security.Cryptography.RSA]::Create()
    try { $taskRsa.ImportFromPem($taskPem); if ($taskRsa.KeySize -lt 2048) { throw 'RSA key is too small.' } }
    finally { $taskRsa.Dispose(); $taskPem = $null }
    if ($taskKeyPath.Contains("`n") -or $taskKeyPath.Contains("`r")) { throw 'Invalid key path.' }
    Set-Content -LiteralPath $taskGithubEnv -Encoding utf8NoBOM -Value @(
        'GITHUB_ENABLED=true', "GITHUB_APP_ID=$AppId", "GITHUB_APP_PRIVATE_KEY_FILE=$taskKeyPath", "GITHUB_WEBHOOK_SECRET=$taskSecret"
    )
    Write-Host 'GitHub App configuration saved locally. Restart the API with the GitHub Compose overlay or dev-api.ps1.'
} elseif (-not (Test-Path -LiteralPath $taskGithubEnv)) {
    Set-Content -LiteralPath $taskGithubEnv -Encoding utf8NoBOM -Value @(
        'GITHUB_ENABLED=false', 'GITHUB_APP_ID=', 'GITHUB_APP_PRIVATE_KEY_FILE=', "GITHUB_WEBHOOK_SECRET=$taskSecret"
    )
    Write-Host 'Prepared ignored .env.github and .secrets/github-webhook.secret. GitHub remains disabled until the App is configured.'
} else { Write-Host 'Existing GitHub configuration preserved.' }
Write-Host 'Use the locally saved webhook secret in the GitHub App settings. Secret values are not printed.'
