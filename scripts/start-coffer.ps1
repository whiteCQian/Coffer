$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root 'backend'
$frontendDir = Join-Path $root 'frontend'
$runtimeDir = Join-Path $root '.coffer-runtime'
$logDir = Join-Path $root 'logs'
$statePath = Join-Path $runtimeDir 'started-processes.json'
$localEnvPath = Join-Path $root 'coffer-local.cmd'
$encryptionKeyPath = Join-Path $root '.coffer-encryption-key'
$setupTokenPath = Join-Path $root 'coffer-initial-admin-token.txt'
$apiPort = 8080
$frontendPort = 5173
$redisPort = 6379
$minioPort = 9000

# Explorer/terminal environments can contain both PATH and Path keys. Windows
# treats them as one variable, while ProcessStartInfo rejects the duplicate.
$processPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
Remove-Item Env:Path -ErrorAction SilentlyContinue
$env:Path = $processPath

New-Item -ItemType Directory -Force -Path $runtimeDir, $logDir | Out-Null

function Set-ProcessEnvironment([string]$Name, [string]$Value) {
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Get-ProcessEnvironment([string]$Name) {
    [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Import-LocalSettings {
    if (Test-Path -LiteralPath $localEnvPath) {
        # Read the batch environment into memory; never echo local settings or credentials.
        $rows = & $env:ComSpec /d /c "call `"$localEnvPath`" >nul 2>&1 & set"
        foreach ($row in $rows) {
            if ($row -match '^(COFFER_[A-Za-z0-9_]+|MINIO_[A-Za-z0-9_]+|MYSQL_[A-Za-z0-9_]+|REDIS_[A-Za-z0-9_]+)=(.*)$') {
                $name = $Matches[1]
                $value = $Matches[2]
                if ($name -ieq 'COFFER_DB_URL') { $value = $value -replace '\^&', '&' }
                Set-ProcessEnvironment $name $value
            }
        }
    }
    foreach ($name in @('MYSQL_PASSWORD','MYSQL_USERNAME','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD',
            'MINIO_ACCESS_KEY','MINIO_SECRET_KEY','COFFER_SECRET_KEY','COFFER_ADMIN_SETUP_TOKEN')) {
        if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment $name))) {
            $value = [Environment]::GetEnvironmentVariable($name, 'User')
            if ([string]::IsNullOrWhiteSpace($value)) {
                $value = [Environment]::GetEnvironmentVariable($name, 'Machine')
            }
            if (-not [string]::IsNullOrWhiteSpace($value)) { Set-ProcessEnvironment $name $value }
        }
    }
}

function New-HexSecret([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToHexString($bytes)
}

function New-SetupSecret {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function Test-TcpPort([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(600)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

function Wait-TcpPort([int]$Port, [int]$Seconds, [string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-TcpPort $Port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not become available on 127.0.0.1:$Port. See logs for its log."
}

function Wait-Http([string]$Url, [int]$Seconds, [string]$Name, [scriptblock]$Check) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 3
            if (& $Check $response) { return $response }
        } catch { }
        Start-Sleep -Milliseconds 750
    }
    throw "$Name did not become healthy. See logs for its log."
}

function Get-JavaRuntime {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($javaHomeCandidate in @((Get-ProcessEnvironment 'COFFER_JAVA_HOME'), (Get-ProcessEnvironment 'JAVA_HOME'))) {
        if (-not [string]::IsNullOrWhiteSpace($javaHomeCandidate)) { $candidates.Add((Join-Path $javaHomeCandidate 'bin\java.exe')) }
    }
    $pathJava = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pathJava) { $candidates.Add($pathJava.Source) }
    foreach ($base in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft')) {
        if (Test-Path $base) {
            Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object {
                $candidates.Add((Join-Path $_.FullName 'bin\java.exe'))
            }
        }
    }
    foreach ($java in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $java)) { continue }
        try { $versionText = (& $java -version 2>&1 | Out-String) }
        catch { $versionText = $_.Exception.Message }
        $match = [regex]::Match($versionText, '(?:version\s+"|openjdk\s+)(\d+)')
        if (-not $match.Success) { continue }
        $major = [int]$match.Groups[1].Value
        if ($major -eq 1) { $majorMatch = [regex]::Match($versionText, 'version\s+"1\.(\d+)'); if ($majorMatch.Success) { $major = [int]$majorMatch.Groups[1].Value } }
        if ($major -ge 17) { return (Resolve-Path -LiteralPath $java).Path }
    }
    throw 'A JDK 17 or later is required. Set JAVA_HOME or COFFER_JAVA_HOME to a supported JDK.'
}

function Find-Maven([string]$JavaHome) {
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return $onPath.Source }
    $wrapper = Join-Path $backendDir 'mvnw.cmd'
    if (Test-Path $wrapper) { return $wrapper }
    $cached = Join-Path $env:USERPROFILE '.m2\wrapper\dists\apache-maven-3.9.16-bin'
    if (Test-Path $cached) {
        $found = Get-ChildItem $cached -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw 'Maven 3.9 or the backend Maven wrapper is required to rebuild the backend.'
}

function Read-ProcessState {
    if (-not (Test-Path $statePath)) { return [ordered]@{ processes = @() } }
    try {
        $json = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
        $processes = @($json.processes | ForEach-Object {
            @{ role = [string]$_.role; pid = [int]$_.pid; executable = [string]$_.executable; startedUtc = [string]$_.startedUtc }
        })
        return [ordered]@{ processes = $processes }
    } catch { throw 'The Coffer runtime state file is invalid. Inspect .coffer-runtime\started-processes.json before continuing.' }
}

function Save-ProcessState($State) {
    $temp = "$statePath.tmp"
    $State | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temp -Encoding utf8
    Move-Item -LiteralPath $temp -Destination $statePath -Force
}

function Add-ManagedProcess($State, [string]$Role, $Process, [string]$Executable) {
    $State.processes = @($State.processes) + @(@{
        role = $Role
        pid = $Process.Id
        executable = $Executable
        startedUtc = $Process.StartTime.ToUniversalTime().ToString('o')
    })
    Save-ProcessState $State
}

try {
    Import-LocalSettings
    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'COFFER_SECRET_KEY'))) {
        if (Test-Path -LiteralPath $encryptionKeyPath) {
            $key = (Get-Content -Raw -LiteralPath $encryptionKeyPath).Trim()
        } else {
            $key = New-HexSecret 32
            [IO.File]::WriteAllText($encryptionKeyPath, $key + [Environment]::NewLine, [Text.Encoding]::ASCII)
            Write-Host "Created the encryption master key in $encryptionKeyPath; keep a protected backup."
        }
        Set-ProcessEnvironment 'COFFER_SECRET_KEY' $key
    }
    $secretKey = Get-ProcessEnvironment 'COFFER_SECRET_KEY'
    if ($secretKey -notmatch '^[A-Fa-f0-9]{64}$') { throw 'COFFER_SECRET_KEY must contain exactly 64 hexadecimal characters.' }

    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'COFFER_ADMIN_SETUP_TOKEN'))) {
        $setupToken = New-SetupSecret
        Set-ProcessEnvironment 'COFFER_ADMIN_SETUP_TOKEN' $setupToken
    } else { $setupToken = Get-ProcessEnvironment 'COFFER_ADMIN_SETUP_TOKEN' }

    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'MYSQL_PASSWORD'))) { throw 'MYSQL_PASSWORD is not configured in coffer-local.cmd or the user/machine environment.' }
    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'MINIO_ROOT_USER')) -or
        [string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'MINIO_ROOT_PASSWORD'))) {
        throw 'MINIO_ROOT_USER and MINIO_ROOT_PASSWORD must be configured in coffer-local.cmd or the user/machine environment.'
    }
    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'MINIO_ACCESS_KEY'))) {
        Set-ProcessEnvironment 'MINIO_ACCESS_KEY' (Get-ProcessEnvironment 'MINIO_ROOT_USER')
    }
    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'MINIO_SECRET_KEY'))) {
        Set-ProcessEnvironment 'MINIO_SECRET_KEY' (Get-ProcessEnvironment 'MINIO_ROOT_PASSWORD')
    }
    if ([string]::IsNullOrWhiteSpace((Get-ProcessEnvironment 'COFFER_COOKIE_SECURE'))) {
        Set-ProcessEnvironment 'COFFER_COOKIE_SECURE' 'false'
    }

    $state = Read-ProcessState
    $javaExe = Get-JavaRuntime
    $node = Get-Command node.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $node) { throw 'Node.js 20 or later is required for the frontend.' }
    $nodeVersion = (& $node.Source --version 2>&1 | Out-String).Trim()
    if ($nodeVersion -notmatch '^v(\d+)' -or [int]$Matches[1] -lt 20) { throw 'Node.js 20 or later is required for the frontend.' }

    if (-not (Test-TcpPort 3306)) {
        $mysqlService = Get-Service -Name 'MySQL*' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($mysqlService -and $mysqlService.Status -ne 'Running') {
            Write-Host 'Starting the configured MySQL Windows service...'
            Start-Service -Name $mysqlService.Name
        }
        Wait-TcpPort 3306 20 'MySQL'
    }

    if (-not (Test-TcpPort $redisPort)) {
        $redisExe = Get-ProcessEnvironment 'COFFER_REDIS_EXE'
        if ([string]::IsNullOrWhiteSpace($redisExe)) { $redisExe = 'D:\Compiler\Redis\Redis-8.8.0-Windows-x64-msys2\redis-server.exe' }
        if (-not (Test-Path -LiteralPath $redisExe)) { throw 'Redis 8 is not listening and COFFER_REDIS_EXE does not point to redis-server.exe.' }
        $redisData = Join-Path $runtimeDir 'redis-data'
        New-Item -ItemType Directory -Force -Path $redisData | Out-Null
        $redisOut = Join-Path $logDir 'redis.out.log'
        $redisErr = Join-Path $logDir 'redis.err.log'
        Start-Process -FilePath $redisExe -ArgumentList @('--bind', '127.0.0.1', '--port', "$redisPort", '--dir', "`"$redisData`"", '--dbfilename', 'dump.rdb') `
            -WorkingDirectory (Split-Path -Parent $redisExe) -WindowStyle Hidden `
            -RedirectStandardOutput $redisOut -RedirectStandardError $redisErr | Out-Null
        Wait-TcpPort $redisPort 20 'Redis 8'
        Write-Host 'Redis 8 is ready.'
    } else { Write-Host 'Redis 8 is already listening; reusing it.' }

    if (-not (Test-TcpPort $minioPort)) {
        $minioExe = Get-ProcessEnvironment 'COFFER_MINIO_EXE'
        if ([string]::IsNullOrWhiteSpace($minioExe)) { $minioExe = 'D:\Compiler\MinIO\minio.exe' }
        $minioData = Get-ProcessEnvironment 'COFFER_MINIO_DATA'
        if ([string]::IsNullOrWhiteSpace($minioData)) { $minioData = 'D:\Compiler\MinIO\data' }
        $minioConfig = Get-ProcessEnvironment 'COFFER_MINIO_CONFIG_DIR'
        if ([string]::IsNullOrWhiteSpace($minioConfig)) { $minioConfig = 'D:\Compiler\MinIO\config' }
        if (-not (Test-Path -LiteralPath $minioExe) -or -not (Test-Path -LiteralPath $minioData) -or -not (Test-Path -LiteralPath $minioConfig)) {
            throw 'MinIO is not listening. Set COFFER_MINIO_EXE, COFFER_MINIO_DATA, and COFFER_MINIO_CONFIG_DIR to valid paths.'
        }
        $minioOut = Join-Path $logDir 'minio.out.log'
        $minioErr = Join-Path $logDir 'minio.err.log'
        $minioArgs = 'server "{0}" --config-dir "{1}" --address "127.0.0.1:9000" --console-address "127.0.0.1:9001"' -f $minioData, $minioConfig
        Start-Process -FilePath $minioExe -ArgumentList $minioArgs -WorkingDirectory (Split-Path -Parent $minioExe) `
            -WindowStyle Hidden -RedirectStandardOutput $minioOut -RedirectStandardError $minioErr | Out-Null
        Wait-TcpPort $minioPort 30 'MinIO'
        Write-Host 'MinIO is ready.'
    } else { Write-Host 'MinIO is already listening; reusing it.' }

    $jar = Join-Path $backendDir 'target\coffer-backend-0.0.1-SNAPSHOT.jar'
    $sourceRoot = Join-Path $backendDir 'src'
    $needsBuild = -not (Test-Path -LiteralPath $jar)
    if (-not $needsBuild) {
        $jarTime = (Get-Item -LiteralPath $jar).LastWriteTimeUtc
        $newerSource = Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | Where-Object { $_.LastWriteTimeUtc -gt $jarTime } | Select-Object -First 1
        $pom = Get-Item -LiteralPath (Join-Path $backendDir 'pom.xml')
        $needsBuild = $null -ne $newerSource -or $pom.LastWriteTimeUtc -gt $jarTime
    }
    if ($needsBuild) {
        Write-Host 'Building the backend from the current source...'
        $maven = Find-Maven (Split-Path -Parent (Split-Path -Parent $javaExe))
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
        $buildOut = Join-Path $logDir 'backend-build.out.log'
        $buildErr = Join-Path $logDir 'backend-build.err.log'
        $mavenRepo = Join-Path $env:USERPROFILE '.m2\repository'
        Push-Location $backendDir
        try { & $maven "-Dmaven.repo.local=$mavenRepo" package -DskipTests *> $buildOut; $buildExit = $LASTEXITCODE }
        finally { Pop-Location }
        if ($buildExit -ne 0 -or -not (Test-Path -LiteralPath $jar)) { throw "Backend build failed (exit $buildExit). See logs\backend-build.*.log." }
    }

    $backendWasAlreadyListening = Test-TcpPort $apiPort
    if (-not $backendWasAlreadyListening) {
        if (Test-Path $statePath) { $state = Read-ProcessState } else { $state = [ordered]@{ processes = @() } }
        $backendOut = Join-Path $logDir 'backend.out.log'
        $backendErr = Join-Path $logDir 'backend.err.log'
        $backendArgs = '-jar "{0}" --spring.profiles.active=prod --server.address=127.0.0.1' -f $jar
        $backend = Start-Process -FilePath $javaExe -ArgumentList $backendArgs -WorkingDirectory $root -WindowStyle Hidden `
            -PassThru -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr
        Add-ManagedProcess $state 'backend' $backend $javaExe
        Write-Host 'Starting the backend and waiting for its health check...'
    } else { Write-Host 'Port 8080 is already serving a listener; checking that it is Coffer.' }
    Wait-Http 'http://127.0.0.1:8080/actuator/health' 90 'Backend' { param($r) $r.status -eq 'UP' } | Out-Null
    Write-Host 'Backend health is UP.'

    try {
        $authStatus = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/auth/status' -TimeoutSec 5
        if ($authStatus.data.setupRequired -and $authStatus.data.setupAvailable) {
            if ($backendWasAlreadyListening) {
                if (-not (Test-Path -LiteralPath $setupTokenPath)) {
                    throw 'An existing backend needs administrator setup, but its one-time token is unavailable. Stop it and restart with start-all.bat.'
                }
                Write-Host "Administrator setup is still required. The existing one-time token is in: $setupTokenPath"
            } else {
                [IO.File]::WriteAllText($setupTokenPath, $setupToken + [Environment]::NewLine, [Text.Encoding]::ASCII)
                Write-Host "First administrator setup is required. The one-time token is in: $setupTokenPath"
            }
            if ($env:COFFER_SHOW_SETUP_TOKEN -ne '0') { Start-Process notepad.exe -ArgumentList "`"$setupTokenPath`"" | Out-Null }
        } elseif (-not $authStatus.data.setupRequired -and (Test-Path $setupTokenPath)) {
            Remove-Item -LiteralPath $setupTokenPath -Force
            Write-Host 'Administrator setup is already complete.'
        }
    } catch { throw 'Could not read the authentication setup status from the backend.' }

    if (-not (Test-TcpPort $frontendPort)) {
        $vite = Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
        if (-not (Test-Path -LiteralPath $vite)) {
            $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($npm) {
                $installer = Start-Process -FilePath $npm.Source -ArgumentList @('ci') -WorkingDirectory $frontendDir `
                    -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $logDir 'frontend-install.out.log') `
                    -RedirectStandardError (Join-Path $logDir 'frontend-install.err.log')
            } else {
                $pnpm = Get-Command pnpm.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
                if (-not $pnpm) { throw 'Frontend dependencies are missing; install Node.js with npm or pnpm and retry.' }
                $installer = Start-Process -FilePath $pnpm.Source -ArgumentList @('install') -WorkingDirectory $frontendDir `
                    -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $logDir 'frontend-install.out.log') `
                    -RedirectStandardError (Join-Path $logDir 'frontend-install.err.log')
            }
            if ($installer.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $vite)) { throw 'Frontend dependency installation failed. See logs\frontend-install.*.log.' }
        }
        $frontendOut = Join-Path $logDir 'frontend.out.log'
        $frontendErr = Join-Path $logDir 'frontend.err.log'
        $frontendArgs = '"{0}" --host 127.0.0.1 --port {1} --strictPort' -f $vite, $frontendPort
        $frontend = Start-Process -FilePath $node.Source -ArgumentList $frontendArgs -WorkingDirectory $frontendDir -WindowStyle Hidden `
            -PassThru -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr
        Add-ManagedProcess $state 'frontend' $frontend $node.Source
        Write-Host 'Starting the frontend...'
    } else { Write-Host 'Port 5173 is already serving a listener; checking that it is the Coffer frontend.' }
    Wait-Http 'http://127.0.0.1:5173/' 30 'Frontend' { param($r) $null -ne $r } | Out-Null
    Write-Host 'Coffer is ready at http://localhost:5173/ (API: http://localhost:8080/).'
    Write-Host 'Logs are in logs. Run stop-all.bat to stop only processes launched by this workspace.'
    if ($env:COFFER_OPEN_BROWSER -ne '0') { Start-Process 'http://localhost:5173/' | Out-Null }
} catch {
    Write-Error ("Startup failed at script line {0}: {1}" -f $_.InvocationInfo.ScriptLineNumber, $_.Exception.Message)
    exit 1
}
