param([Parameter(Mandatory)][string]$JavaPath, [string]$JarPath)

$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $JarPath) { $JarPath = Join-Path $repo 'backend\target\desktop\coffer-backend-0.0.1-SNAPSHOT.jar' }
$JarPath = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Build the desktop JAR before verification.' }
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $repo 'backend\.test-tmp'))
$probe = [System.IO.Path]::GetFullPath((Join-Path $tempRoot ('r30-jar-' + [guid]::NewGuid().ToString('N'))))
if (-not $probe.StartsWith($tempRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Probe escapes the workspace temporary directory.' }
New-Item -ItemType Directory -Path $probe -Force | Out-Null
$dataRoot = Join-Path $probe 'data'
$password = 'Probe-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $forbidden = @($archive.Entries | Where-Object {
        $_.FullName -match '^BOOT-INF/lib/minio-' -or
        $_.FullName -match 'com/coffer/(service/MinioStorageService|config/(MinioConfig|BucketInitializer|MinioStorageHealthIndicator|ServerStorageProbe))'
    })
    if ($forbidden.Count) { throw 'Desktop JAR contains server storage dependencies.' }
} finally { $archive.Dispose() }

function Start-Probe([string]$Name, [bool]$Initialize) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    $arguments = @('-jar', ('"' + $JarPath + '"'), '--spring.profiles.active=desktop',
        ('"--coffer.desktop.data-directory=' + $dataRoot + '"'), "--server.port=$port",
        "--coffer.desktop.initialize=$($Initialize.ToString().ToLowerInvariant())",
        '--spring.data.redis.host=127.0.0.1', '--spring.data.redis.port=1',
        '--spring.data.redis.connect-timeout=100ms', '--spring.data.redis.timeout=100ms',
        '--minio.endpoint=http://127.0.0.1:1', '--coffer.embedding.enabled=false', '--coffer.hybrid.enabled=false')
    $out = Join-Path $probe "$Name.out.log"; $err = Join-Path $probe "$Name.err.log"
    $process = Start-Process -FilePath $JavaPath -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -WorkingDirectory $probe -RedirectStandardOutput $out -RedirectStandardError $err
    $processes.Add($process)
    return @{ Process = $process; Url = "http://127.0.0.1:$port"; ErrorLog = $err }
}

function Wait-Ready($Run) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $Run.Process.Refresh()
        if ($Run.Process.HasExited) { throw 'Isolated desktop exited before readiness; inspect the probe logs.' }
        try {
            $health = Invoke-RestMethod ($Run.Url + '/actuator/health/readiness') -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Isolated desktop did not become ready within 60 seconds.'
}

function Stop-Probe($Run) {
    $Run.Process.Refresh()
    if (-not $Run.Process.HasExited) {
        # Only processes returned by this script are terminated. This also exercises crash restart.
        $Run.Process.Kill(); $Run.Process.WaitForExit()
    }
}

function Assert-Rejected([string]$Name, [string]$Reason) {
    $run = Start-Probe $Name $false
    if (-not $run.Process.WaitForExit(30000)) { Stop-Probe $run; throw 'Rejected desktop did not exit.' }
    if ($run.Process.ExitCode -eq 0 -or -not (Select-String -LiteralPath $run.ErrorLog -SimpleMatch -Quiet "COFFER_DESKTOP_$Reason")) {
        throw "Expected fixed startup diagnostic: $Reason"
    }
}

function Post-Json($Run, $Session, [string]$Path, $Body) {
    $null = Invoke-RestMethod ($Run.Url + '/api/auth/csrf') -WebSession $Session -TimeoutSec 5
    # SPA header resolution expects the raw cookie token, not the response's XOR-masked token.
    $token = ($Session.Cookies.GetCookies([Uri]$Run.Url) | Where-Object Name -eq 'XSRF-TOKEN').Value
    $result = Invoke-RestMethod ($Run.Url + $Path) -Method Post -WebSession $Session -TimeoutSec 10 `
        -ContentType 'application/json' -Headers @{ 'X-XSRF-TOKEN' = $token } -Body ($Body | ConvertTo-Json -Compress)
    if ($result.code -ne 0) { throw 'Desktop HTTP operation failed.' }
    return $result.data
}

try {
    # A missing directory is never created by a normal launch, including its log directory.
    Assert-Rejected 'missing-root' 'NOT_INITIALIZED'
    if (Test-Path -LiteralPath $dataRoot) { throw 'Normal startup recreated a missing data directory.' }
    $first = Start-Probe 'initial' $true; Wait-Ready $first
    if (-not (Test-Path -LiteralPath (Join-Path $dataRoot 'logs\coffer-safe.log') -PathType Leaf)) { throw 'Desktop logs escaped the data directory.' }
    $admin = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $token = (Get-Content -LiteralPath (Join-Path $dataRoot 'coffer-initial-admin-token.txt') -Raw).Trim()
    $null = Post-Json $first $admin '/api/auth/setup' @{setupToken=$token; username='probe-admin'; password=$password}
    $null = Post-Json $first $admin '/api/admin/users' @{username='probe-user'; password=$password}
    $user = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $null = Post-Json $first $user '/api/auth/login' @{username='probe-user'; password=$password}
    $files = Invoke-RestMethod ($first.Url + '/api/files') -WebSession $user -TimeoutSec 5
    if ($files.code -ne 0 -or $files.data.totalElements -ne 0) { throw 'New desktop file list failed.' }
    Assert-Rejected 'second-process' 'IN_USE'
    $keyBefore = (Get-FileHash -LiteralPath (Join-Path $dataRoot '.coffer-encryption-key') -Algorithm SHA256).Hash
    Stop-Probe $first

    $second = Start-Probe 'restart' $false; Wait-Ready $second
    $state = Invoke-RestMethod ($second.Url + '/api/auth/status') -TimeoutSec 5
    if ($state.data.setupRequired -ne $false) { throw 'Restart lost the initialized accounts.' }
    $user = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $null = Post-Json $second $user '/api/auth/login' @{username='probe-user'; password=$password}
    $privacy = Invoke-RestMethod ($second.Url + '/api/privacy') -WebSession $user -TimeoutSec 5
    if ($privacy.code -ne 0) { throw 'Private desktop API failed after restart.' }
    if (Test-Path -LiteralPath (Join-Path $dataRoot 'coffer-initial-admin-token.txt')) { throw 'Consumed setup token was retained on restart.' }
    Stop-Probe $second

    # Every mutation below stays within the already-verified isolated probe directory.
    $library = [System.IO.Path]::GetFullPath((Join-Path $dataRoot 'library'))
    $savedLibrary = [System.IO.Path]::GetFullPath((Join-Path $dataRoot 'library-saved'))
    foreach ($target in @($library, $savedLibrary)) {
        if (-not $target.StartsWith($probe + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Library rename escapes the probe.' }
    }
    Rename-Item -LiteralPath $library -NewName 'library-saved'
    try {
        Assert-Rejected 'missing-library' 'LIBRARY_MISSING'
        if (Test-Path -LiteralPath $library) { throw 'Missing library was recreated.' }
    } finally { Rename-Item -LiteralPath $savedLibrary -NewName 'library' }
    $key = Join-Path $dataRoot '.coffer-encryption-key'; $savedKey = Join-Path $dataRoot '.saved-key'
    Rename-Item -LiteralPath $key -NewName '.saved-key'
    try {
        Assert-Rejected 'missing-key' 'KEY_MISSING'
        if (Test-Path -LiteralPath $key) { throw 'Missing key was recreated.' }
    } finally { Rename-Item -LiteralPath $savedKey -NewName '.coffer-encryption-key' }
    if ((Get-FileHash -LiteralPath $key -Algorithm SHA256).Hash -ne $keyBefore) { throw 'Master key unexpectedly changed.' }
    Write-Output 'R30_JAR_VERIFIED: isolated startup/restart, HTTP setup/login, private API, lock, missing root/library/key, no MinIO SDK'
    Write-Output "Probe artifacts: $probe"
} finally {
    foreach ($process in $processes) {
        $process.Refresh()
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}
