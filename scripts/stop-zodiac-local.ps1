$ErrorActionPreference = "Stop"

$root = "D:\codex\zodiac"
$runtimeDir = Join-Path $root ".runtime"
$frontendPidFile = Join-Path $runtimeDir "frontend.pid"
$backendPidFile = Join-Path $runtimeDir "backend.pid"

function Stop-TrackedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PidFile,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [int]$Port
    )

    if (-not (Test-Path $PidFile)) {
        Write-Host "${Name}: no pid file, checking port $Port"
    } else {
        $pidLine = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
        $rawPid = if ($null -ne $pidLine) { $pidLine.ToString().Trim() } else { "" }
        if (-not $rawPid) {
            Write-Host "${Name}: empty pid file removed"
        } else {
            try {
                $process = Get-Process -Id ([int]$rawPid) -ErrorAction Stop
                Stop-Process -Id $process.Id -Force -ErrorAction Stop
                Write-Host "$Name stopped (PID $rawPid)"
            } catch {
                Write-Host "$Name already stopped (PID $rawPid)"
            }
        }
    }

    try {
        $listenerPids = @()
        $netstatLines = cmd /c "netstat -ano -p tcp | findstr LISTENING | findstr :$Port"
        foreach ($line in $netstatLines) {
            $parts = ($line -split '\s+') | Where-Object { $_ }
            if ($parts.Count -ge 5) {
                $pidValue = $parts[-1]
                if ($pidValue -match '^\d+$') {
                    $listenerPids += [int]$pidValue
                }
            }
        }
        $listenerPids = $listenerPids | Select-Object -Unique
        foreach ($listenerPid in $listenerPids) {
            if ($listenerPid -and $listenerPid -ne 0) {
                try {
                    Stop-Process -Id $listenerPid -Force -ErrorAction Stop
                    Write-Host "$Name stopped by port cleanup (PID $listenerPid)"
                } catch {
                    Write-Host "$Name port cleanup skipped (PID $listenerPid)"
                }
            }
        }
    } catch {
        Write-Host "${Name}: port cleanup check failed"
    }

    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

Stop-TrackedProcess -PidFile $frontendPidFile -Name "Frontend" -Port 3000
Stop-TrackedProcess -PidFile $backendPidFile -Name "Backend" -Port 8080
