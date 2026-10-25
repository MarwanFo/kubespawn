#requires -Version 7.0
. "$PSScriptRoot/common.ps1"
Import-LocalEnvironment
Push-Location (Join-Path $ProjectRoot 'portal')
try { & npm.cmd run dev; Assert-NativeSuccess 'Portal development server' }
finally { Pop-Location }
