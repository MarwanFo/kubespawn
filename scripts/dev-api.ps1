#requires -Version 7.0
. "$PSScriptRoot/common.ps1"
Import-LocalEnvironment
Use-ProjectJava
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:$env:POSTGRES_PORT/$env:POSTGRES_DB"
$env:DATABASE_USER = $env:POSTGRES_USER
$env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:SERVER_PORT = $env:API_PORT
Push-Location (Join-Path $ProjectRoot 'control-plane')
try { & ./gradlew.bat --no-daemon bootRun; Assert-NativeSuccess 'API development server' }
finally { Pop-Location }
