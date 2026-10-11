param(
    [string]$DataDirectory,
    [string]$JarPath,
    [string]$JavaPath,
    [string]$MavenPath,
    [ValidateRange(1,65535)][int]$Port = 8080,
    [switch]$Rebuild,
    [switch]$PrepareOnly,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'Coffer-desktop-backend-test' }
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
$defaultJar = Join-Path $repo 'backend\target\desktop\coffer-backend-0.0.1-SNAPSHOT.jar'
$customJar = [bool]$JarPath
if (-not $JarPath) { $JarPath = $defaultJar }
$JarPath = [IO.Path]::GetFullPath($JarPath)

function Assert-DataPath {
    if ($DataDirectory -match '[;\r\n\x00"]') { throw 'Test data directory contains unsupported characters' }
    foreach ($programDirectory in @((Join-Path $repo 'backend\target'),(Join-Path $repo 'desktop'))) {
        if ($DataDirectory -eq $programDirectory -or $DataDirectory.StartsWith($programDirectory.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or $programDirectory.StartsWith($DataDirectory.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Test data must be separate from program/build directories' }
    }
    for ($cursor=$DataDirectory; $cursor; $cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked test data directory refused' }
    }
    if ((Test-Path -LiteralPath $DataDirectory) -and -not (Test-Path -LiteralPath $DataDirectory -PathType Container)) { throw 'Test data path must be a directory' }
}
function Quote-Argument([string]$value) {
    return '"'+[regex]::Replace([regex]::Replace($value,'(\\*)"','$1$1\"'),'(\\+)$','$1$1')+'"'
}
function Find-Java([bool]$RequireJdk) {
    $candidates=@()
    if ($JavaPath) { $candidates += $JavaPath }
    if (-not $RequireJdk) { $candidates += Join-Path $repo 'desktop\.build\resources\runtime\bin\java.exe' }
    foreach ($javaHomeCandidate in @($env:COFFER_JAVA_HOME,$env:JAVA_HOME)) { if ($javaHomeCandidate) { $candidates += Join-Path $javaHomeCandidate 'bin\java.exe' } }
    $onPath=Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($base in @('C:\Program Files\Java','C:\Program Files\Eclipse Adoptium','C:\Program Files\Microsoft')) {
        if (Test-Path -LiteralPath $base) { $candidates += Get-ChildItem -LiteralPath $base -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        if ($RequireJdk -and -not (Test-Path -LiteralPath (Join-Path (Split-Path $candidate -Parent) 'javac.exe'))) { continue }
        $info=New-Object Diagnostics.ProcessStartInfo
        $info.FileName=$candidate; $info.Arguments='-version'; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        $probe=[Diagnostics.Process]::Start($info)
        $version=$probe.StandardOutput.ReadToEnd()+$probe.StandardError.ReadToEnd(); $probe.WaitForExit()
        if ($probe.ExitCode -eq 0 -and $version -match '(?:version\s+"|openjdk\s+)(\d+)' -and [int]$Matches[1] -ge 17) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw 'Java 17+ is required (a JDK for rebuilding); use -JavaPath or install a supported JDK'
}
function Find-Maven {
    if ($MavenPath) { if (Test-Path -LiteralPath $MavenPath -PathType Leaf) { return (Resolve-Path -LiteralPath $MavenPath).Path }; throw 'Specified Maven executable not found' }
    $onPath=Get-Command mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return $onPath.Source }
    $wrapper=Join-Path $repo 'backend\mvnw.cmd'; if (Test-Path -LiteralPath $wrapper) { return $wrapper }
    $cache=Join-Path $env:USERPROFILE '.m2\wrapper\dists'
    if (Test-Path -LiteralPath $cache) { $cached=Get-ChildItem -LiteralPath $cache -Recurse -Filter mvn.cmd | Select-Object -First 1; if ($cached) { return $cached.FullName } }
    throw 'Maven or the backend wrapper is required to build the desktop backend'
}

try {
    Assert-DataPath
    $needsBuild=$Rebuild -or -not (Test-Path -LiteralPath $JarPath -PathType Leaf)
    if (-not $needsBuild -and -not $customJar) {
        $jarTime=(Get-Item -LiteralPath $JarPath).LastWriteTimeUtc
        $inputs=@(Get-ChildItem -LiteralPath (Join-Path $repo 'backend\src\main') -Recurse -File)+@(Get-Item -LiteralPath (Join-Path $repo 'backend\pom.xml'))
        $needsBuild=@($inputs | Where-Object { $_.LastWriteTimeUtc -gt $jarTime }).Count -gt 0
    }
    $initialize=-not (Test-Path -LiteralPath $DataDirectory) -or @(Get-ChildItem -LiteralPath $DataDirectory -Force -ErrorAction SilentlyContinue).Count -eq 0
    if ($CheckOnly) {
        [pscustomobject]@{Profile='desktop';Url=('http://127.0.0.1:'+$Port);Jar=$JarPath;NeedsBuild=[bool]$needsBuild;DataDirectory=$DataDirectory;InitializeEmptyDirectory=$initialize} | ConvertTo-Json
        exit 0
    }
    if ($needsBuild) {
        if ($customJar) { throw 'Custom JAR is missing or -Rebuild was requested; build it explicitly or omit -JarPath' }
        $jdk=Find-Java $true; $maven=Find-Maven
        $oldJavaHome=[Environment]::GetEnvironmentVariable('JAVA_HOME','Process')
        [Environment]::SetEnvironmentVariable('JAVA_HOME',(Split-Path (Split-Path $jdk -Parent) -Parent),'Process')
        try {
            & $maven --batch-mode --no-transfer-progress -f (Join-Path $repo 'backend\pom.xml') -Pdesktop package
            if ($LASTEXITCODE -ne 0) { throw 'Desktop backend build failed' }
        } finally { [Environment]::SetEnvironmentVariable('JAVA_HOME',$oldJavaHome,'Process') }
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        if (-not ($archive.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/com/coffer/desktop/DesktopEnvironmentPostProcessor.class' }) -or ($archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/minio-|com/coffer/service/MinioStorageService.class' })) { throw 'Expected a desktop JAR; server storage dependencies are refused' }
    } finally { $archive.Dispose() }
    $runtime=Find-Java $false
    if ($PrepareOnly) { Write-Host ('Desktop backend prepared: '+$JarPath); exit 0 }
    $socket=New-Object Net.Sockets.TcpClient
    try { $socket.Connect('127.0.0.1',$Port); throw ('Port '+$Port+' is occupied; stop the existing service or use -Port') }
    catch [Net.Sockets.SocketException] { }
    finally { $socket.Dispose() }
    $arguments=@('-jar',$JarPath,'--spring.profiles.active=desktop','--coffer.desktop.shell=false',('--server.port='+$Port),('--coffer.desktop.data-directory='+$DataDirectory),('--coffer.desktop.initialize='+$initialize.ToString().ToLowerInvariant()))
    $start=New-Object Diagnostics.ProcessStartInfo
    $start.FileName=$runtime; $start.WorkingDirectory=$repo; $start.UseShellExecute=$false
    $start.Arguments=($arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    # The test instance loads its own directory key; no Web/NAS credentials or profile are inherited.
    $start.EnvironmentVariables.Clear()
    foreach ($name in @('SystemRoot','WINDIR','COMSPEC','USERPROFILE','LOCALAPPDATA','APPDATA','TEMP','TMP','USERNAME','USERDOMAIN')) { $value=[Environment]::GetEnvironmentVariable($name,'Process'); if ($value) { $start.EnvironmentVariables[$name]=$value } }
    $start.EnvironmentVariables['PATH']=(Split-Path $runtime -Parent)+';'+(Join-Path $env:SystemRoot 'System32')
    Write-Host ('Starting desktop test BACKEND: http://127.0.0.1:'+$Port)
    Write-Host ('Profile: desktop (H2 + local files). Data: '+$DataDirectory)
    Write-Host ('Logs: '+(Join-Path $DataDirectory 'logs'))
    Write-Host ('First-admin setup token file (when setup is required): '+(Join-Path $DataDirectory 'coffer-initial-admin-token.txt'))
    Write-Host 'Keep this console open. Press Ctrl+C to stop the backend; existing test data is retained.'
    $backend=[Diagnostics.Process]::Start($start)
    $backend.WaitForExit()
    if ($backend.ExitCode -in @(0,130,143,-1073741510)) { exit 0 }
    throw ('Backend exited with code '+$backend.ExitCode+'; inspect the console and test logs')
} catch { Write-Host ('Desktop backend test startup failed: '+$_.Exception.Message) -ForegroundColor Red; exit 1 }
