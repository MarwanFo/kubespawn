#requires -Version 7.0
param([switch] $InstallJava)
. "$PSScriptRoot/common.ps1"

Push-Location $ProjectRoot
try {
    New-Item -ItemType Directory -Force -Path '.tools', '.cache' | Out-Null
    if (-not (Test-Path -LiteralPath '.env')) {
        $taskPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
        $taskToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
        $taskEnv = (Get-Content -LiteralPath '.env.example' -Raw).
            Replace('replace-with-generated-password', $taskPassword).
            Replace('replace-with-generated-token', $taskToken)
        Set-Content -LiteralPath '.env' -Value $taskEnv -Encoding utf8NoBOM
        Write-Host 'Generated .env with random local credentials. Existing credentials are never replaced.'
    }

    if ($InstallJava -and -not (Get-ChildItem -LiteralPath '.tools' -Directory -Filter 'jdk-21*')) {
        $taskAssets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
        $taskAsset = $taskAssets | Select-Object -First 1
        $taskArchive = Join-Path $ProjectRoot '.tools/jdk21.zip'
        Invoke-WebRequest -Uri $taskAsset.binary.package.link -OutFile $taskArchive -TimeoutSec 300
        if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskAsset.binary.package.checksum) {
            throw 'JDK checksum mismatch; archive was not extracted.'
        }
        Expand-Archive -LiteralPath $taskArchive -DestinationPath '.tools'
        Write-Host 'Installed a checksum-verified Java 21 JDK inside .tools.'
    }

    $taskNodeVersion = [version] ((& node --version).TrimStart('v'))
    if ($taskNodeVersion -lt [version]'22.12.0') { throw 'Node.js 22.12 or newer is required.' }
    Push-Location 'portal'
    try {
        & npm.cmd ci --cache ../.cache/npm --no-fund
        Assert-NativeSuccess 'Portal dependency installation'
    } finally { Pop-Location }
    Write-Host 'Setup complete. Start with: ./scripts/start.ps1'
} finally { Pop-Location }
