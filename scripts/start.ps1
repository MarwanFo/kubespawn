#requires -Version 7.0
. "$PSScriptRoot/common.ps1"
Import-LocalEnvironment
Push-Location $ProjectRoot
try {
    & docker info --format '{{.ServerVersion}}'
    Assert-NativeSuccess 'Docker engine check (start Docker Desktop if needed)'
    & docker compose up --build --detach
    Assert-NativeSuccess 'Local stack startup'
    Write-Host "Portal: http://localhost:$env:PORTAL_PORT"
    Write-Host "Health: http://localhost:$env:API_PORT/actuator/health/readiness"
    Write-Host 'Use ./scripts/smoke.ps1 to wait for readiness and verify authentication.'
} finally { Pop-Location }
