#requires -Version 7.0
param([switch] $Integration)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava
Push-Location (Join-Path $ProjectRoot 'control-plane')
try {
    & ./gradlew.bat --no-daemon test bootJar
    Assert-NativeSuccess 'Backend tests and package'
    if ($Integration) {
        & ./gradlew.bat --no-daemon integrationTest
        Assert-NativeSuccess 'PostgreSQL integration tests'
    }
} finally { Pop-Location }
Push-Location (Join-Path $ProjectRoot 'portal')
try { & npm.cmd run build; Assert-NativeSuccess 'Portal typecheck and build' }
finally { Pop-Location }
Write-Host 'Verification completed.'
