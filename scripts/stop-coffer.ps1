$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $root '.coffer-runtime\started-processes.json'

if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Host 'No Coffer backend/frontend processes are recorded for this workspace.'
    Write-Host 'Redis, MySQL, and MinIO are left running as shared local services.'
    exit 0
}

try { $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json }
catch { throw 'The Coffer runtime state file is invalid; no processes were stopped.' }

foreach ($entry in @($state.processes)) {
    try {
        $process = Get-Process -Id ([int]$entry.pid) -ErrorAction Stop
        $actualPath = $process.Path
        if (-not $actualPath -or -not [string]::Equals([IO.Path]::GetFullPath($actualPath), [IO.Path]::GetFullPath([string]$entry.executable), [StringComparison]::OrdinalIgnoreCase)) {
            Write-Host "Leaving PID $($entry.pid) alone because its executable no longer matches the recorded Coffer process."
            continue
        }
        $expectedStart = [DateTime]::Parse([string]$entry.startedUtc).ToUniversalTime()
        if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $expectedStart).TotalSeconds) -gt 2) {
            Write-Host "Leaving PID $($entry.pid) alone because it has been reused since Coffer started."
            continue
        }
        Stop-Process -Id $process.Id -Force
        Write-Host "Stopped Coffer $($entry.role) (PID $($entry.pid))."
    } catch [Microsoft.PowerShell.Commands.ProcessCommandException] {
        Write-Host "Coffer process $($entry.pid) is already stopped."
    } catch {
        Write-Host "Could not stop recorded Coffer process $($entry.pid): $($_.Exception.GetType().Name)"
    }
}
Remove-Item -LiteralPath $statePath -Force
Write-Host 'Redis, MySQL, and MinIO are left running as shared local services.'
