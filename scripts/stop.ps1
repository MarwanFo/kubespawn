#requires -Version 7.0
. "$PSScriptRoot/common.ps1"
Push-Location $ProjectRoot
try {
    & docker compose down
    Assert-NativeSuccess 'Local stack shutdown'
    Write-Host 'Stopped containers. PostgreSQL data is retained in the named volume.'
} finally { Pop-Location }
