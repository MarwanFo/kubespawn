#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

function Import-LocalEnvironment {
    $taskEnvFile = Join-Path $script:ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $taskEnvFile)) { throw 'Run ./scripts/setup.ps1 first.' }
    $taskEnvFiles = @($taskEnvFile)
    $taskGithubEnvFile = Join-Path $script:ProjectRoot '.env.github'
    if (Test-Path -LiteralPath $taskGithubEnvFile) { $taskEnvFiles += $taskGithubEnvFile }
    foreach ($taskLine in Get-Content -LiteralPath $taskEnvFiles) {
        if ($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}

function Use-ProjectJava {
    $taskTools = Join-Path $script:ProjectRoot '.tools'
    $taskJdk = Get-ChildItem -LiteralPath $taskTools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskJdk) { $env:JAVA_HOME = $taskJdk.FullName }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        throw 'Set JAVA_HOME to a Java 21 JDK or run ./scripts/setup.ps1 -InstallJava.'
    }
    $env:GRADLE_USER_HOME = Join-Path $script:ProjectRoot '.cache/gradle'
}

function Assert-NativeSuccess([string] $Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed (exit code $LASTEXITCODE)." }
}
