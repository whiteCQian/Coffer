param(
    [string]$MavenPath,
    [string]$JavaPath,
    [string[]]$Steps = @('prepared', 'object', 'metadata', 'rename',
        'work-prepared', 'work-published', 'work-recorded', 'work-committed',
        'delete-enqueued', 'delete-claimed', 'delete-removed'),
    [switch]$KeepArtifacts
)

$ErrorActionPreference = 'Stop'
$allowedSteps = @('prepared', 'object', 'metadata', 'rename',
    'work-prepared', 'work-published', 'work-recorded', 'work-committed',
    'delete-enqueued', 'delete-claimed', 'delete-removed',
    'orphan-discard-pending', 'orphan-discard-removed',
    'archive-ledger', 'archive-source-verified', 'archive-copy-unrecorded',
    'archive-target-recorded', 'archive-metadata', 'archive-source-removed',
    'rollback-ledger', 'rollback-copy-unrecorded', 'rollback-copy-verified',
    'rollback-metadata', 'rollback-target-removed',
    'copy-opened', 'copy-published', 'copy-saveas-published', 'copy-discard-intent', 'copy-save-committed')
if ($Steps.Count -eq 0 -or @($Steps | Where-Object { $_ -notin $allowedSteps }).Count -gt 0) {
    throw 'Steps must name one or more supported crash boundaries.'
}
$repo = [System.IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$backend = [System.IO.Path]::GetFullPath((Join-Path $repo 'backend'))
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $backend '.test-tmp'))
$probeRoot = [System.IO.Path]::GetFullPath((Join-Path $tempRoot ('crash-recovery-' + [guid]::NewGuid().ToString('N'))))
if (-not $probeRoot.StartsWith($tempRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Probe target escapes backend temporary directory' }

if (-not $MavenPath) {
    $found = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($found) { $MavenPath = $found.Source }
}
if (-not $MavenPath -or -not (Test-Path -LiteralPath $MavenPath)) {
    throw 'Pass -MavenPath with an existing mvn.cmd location.'
}
if (-not $JavaPath -and $env:JAVA_HOME) { $JavaPath = Join-Path $env:JAVA_HOME 'bin\java.exe' }
if (-not $JavaPath) {
    $found = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($found) { $JavaPath = $found.Source }
}
if (-not $JavaPath -or -not (Test-Path -LiteralPath $JavaPath)) {
    throw 'Pass -JavaPath with an existing java.exe location.'
}

$oldUserData = $env:COFFER_DESKTOP_USER_DATA_DIR
$oldJavaHome = $env:JAVA_HOME
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
$oldJavaOptions = $env:JAVA_TOOL_OPTIONS
$succeeded = $false
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
try {
    $env:JAVA_HOME = Split-Path (Split-Path $JavaPath -Parent) -Parent
    $env:TEMP = $tempRoot
    $env:TMP = $tempRoot
    $env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $tempRoot
    $classpathFile = Join-Path $probeRoot 'classpath.txt'
    Push-Location $backend
    try {
        & $MavenPath -q '-DskipTests' test-compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
        if ($LASTEXITCODE -ne 0) { throw 'Could not compile the crash probe or resolve its classpath.' }
    } finally { Pop-Location }
    $classpath = (Join-Path $backend 'target\test-classes') + ';' +
            (Join-Path $backend 'target\classes') + ';' +
            (Get-Content -LiteralPath $classpathFile -Raw).Trim()

    foreach ($step in $Steps) {
        $scenario = Join-Path $probeRoot $step
        New-Item -ItemType Directory -Path $scenario -Force | Out-Null
        $env:COFFER_DESKTOP_USER_DATA_DIR = (Join-Path $scenario 'data').Replace('\', '/')
        $probeOptions = @('-Dspring.devtools.restart.enabled=false')
        $probeClass = if ($step.StartsWith('archive-') -or $step.StartsWith('rollback-')) {
            'com.coffer.governance.GovernanceCrashProbeMain'
        } elseif ($step.StartsWith('copy-')) { 'com.coffer.desktop.WorkCopyCrashProbeMain' }
        else { 'com.coffer.file.application.FileCrashProbeMain' }
        if ($step.StartsWith('delete-')) {
            $probeOptions += @('-Dcoffer.storage.deletion-retention-hours=0',
                '-Dcoffer.storage.deletion-lease-seconds=1')
        }
        if ($step.StartsWith('orphan-')) {
            $probeOptions += '-Dcoffer.storage.orphan-discard-lease-seconds=1'
        }
        $firstOut = Join-Path $scenario 'first.out.log'
        $firstErr = Join-Path $scenario 'first.err.log'
        $first = Start-Process -FilePath $JavaPath -ArgumentList ($probeOptions + @(
                '-cp', ('"' + $classpath + '"'),
                $probeClass, 'crash', $step)) `
                -WorkingDirectory $backend -WindowStyle Hidden -Wait -PassThru `
                -RedirectStandardOutput $firstOut -RedirectStandardError $firstErr
        if ($first.ExitCode -ne 73 -or -not (Select-String -LiteralPath $firstOut -Quiet -SimpleMatch "CRASH_PROBE_HALTING=$step")) {
            throw "Crash step $step did not halt after its durable write; inspect $firstOut and $firstErr"
        }

        $secondOut = Join-Path $scenario 'second.out.log'
        $secondErr = Join-Path $scenario 'second.err.log'
        $second = Start-Process -FilePath $JavaPath -ArgumentList ($probeOptions + @(
                '-cp', ('"' + $classpath + '"'),
                $probeClass, 'verify', $step)) `
                -WorkingDirectory $backend -WindowStyle Hidden -Wait -PassThru `
                -RedirectStandardOutput $secondOut -RedirectStandardError $secondErr
        if ($second.ExitCode -ne 0 -or -not (Select-String -LiteralPath $secondOut -Quiet -SimpleMatch "CRASH_PROBE_RECOVERED=$step")) {
            throw "Restart verification failed at $step; inspect $secondOut and $secondErr"
        }
        Write-Output "PASS $step : abrupt JVM halt, new JVM recovery, verified object and ledger state"
    }
    $succeeded = $true
} finally {
    $env:COFFER_DESKTOP_USER_DATA_DIR = $oldUserData
    $env:JAVA_HOME = $oldJavaHome
    $env:TEMP = $oldTemp
    $env:TMP = $oldTmp
    $env:JAVA_TOOL_OPTIONS = $oldJavaOptions
    if (-not $KeepArtifacts -and $succeeded) {
        $resolved = [System.IO.Path]::GetFullPath($probeRoot)
        if (-not $resolved.StartsWith($tempRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove a path outside the probe root' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    } else {
        Write-Output "Probe artifacts: $probeRoot"
    }
}
