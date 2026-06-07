$ErrorActionPreference = "Stop"

$root = "D:\codex\zodiac"
$frontendScript = Join-Path $root "local-static-server.js"
$backendDir = Join-Path $root "zodiac-dewey-backend"
$backendJar = Join-Path $backendDir "target\zodiac-dewey.jar"
$runtimeDir = Join-Path $root ".runtime"
$frontendPidFile = Join-Path $runtimeDir "frontend.pid"
$backendPidFile = Join-Path $runtimeDir "backend.pid"
$frontendOutLog = Join-Path $runtimeDir "frontend.out.log"
$frontendErrLog = Join-Path $runtimeDir "frontend.err.log"
$backendOutLog = Join-Path $runtimeDir "backend.out.log"
$backendErrLog = Join-Path $runtimeDir "backend.err.log"

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

& (Join-Path $root "scripts\stop-zodiac-local.ps1") | Out-Null

function Resolve-CommandPath {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Candidates
    )

    foreach ($candidate in $Candidates) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command -and $command.Source) {
            return $command.Source
        }
    }

    throw "Command not found: $($Candidates -join ', ')"
}

if (Test-Path Env:PATH) {
    $legacyPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
    if ($legacyPath -and -not (Test-Path Env:Path)) {
        [Environment]::SetEnvironmentVariable("Path", $legacyPath, "Process")
    }
    Remove-Item Env:PATH -ErrorAction SilentlyContinue
}

$mavenCommand = Resolve-CommandPath -Candidates @("mvn.cmd", "mvn")
$nodeCommand = Resolve-CommandPath -Candidates @("node.exe", "node")
$javaCommand = Resolve-CommandPath -Candidates @("java.exe", "java")

Write-Host "Building Zodiac backend..."
Push-Location $backendDir
try {
    & $mavenCommand -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Backend build failed."
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $backendJar)) {
    throw "Backend jar not found: $backendJar"
}

Write-Host "Starting Zodiac frontend on port 3000..."
$frontendProcess = Start-Process `
    -FilePath $nodeCommand `
    -ArgumentList @($frontendScript) `
    -WorkingDirectory $root `
    -RedirectStandardOutput $frontendOutLog `
    -RedirectStandardError $frontendErrLog `
    -WindowStyle Hidden `
    -PassThru
$frontendProcess.Id | Set-Content -Path $frontendPidFile -Encoding ascii

Write-Host "Starting Zodiac backend on port 8080..."
$backendProcess = Start-Process `
    -FilePath $javaCommand `
    -ArgumentList @("-jar", $backendJar, "--spring.profiles.active=prod") `
    -WorkingDirectory $backendDir `
    -RedirectStandardOutput $backendOutLog `
    -RedirectStandardError $backendErrLog `
    -WindowStyle Hidden `
    -PassThru
$backendProcess.Id | Set-Content -Path $backendPidFile -Encoding ascii

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            return Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
        } catch {
            Start-Sleep -Milliseconds 700
        }
    }

    throw "Timed out waiting for $Url"
}

$frontendResponse = Wait-HttpReady -Url "http://127.0.0.1:3000/" -TimeoutSeconds 15
$backendResponse = Wait-HttpReady -Url "http://127.0.0.1:8080/api/health" -TimeoutSeconds 40

Write-Host ""
Write-Host "Frontend ready:" $frontendResponse.StatusCode "http://127.0.0.1:3000/"
Write-Host "Backend  ready:" $backendResponse.StatusCode "http://127.0.0.1:8080/api/health"
Write-Host ""
Write-Host "Phone access: use your LAN IP with port 3000 on the same Wi-Fi."
