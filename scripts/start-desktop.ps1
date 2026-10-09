param(
    [string]$JarPath,
    [string]$JavaPath,
    [string]$UserDataDir,
    [string]$LibraryDir,
    [switch]$Initialize,
    [switch]$RebindLibrary,
    [ValidateRange(0, 65535)][int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $JarPath) { $JarPath = Join-Path $repo 'backend\target\desktop\coffer-backend-0.0.1-SNAPSHOT.jar' }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw 'Desktop JAR not found. Build with: mvn -f backend/pom.xml -Pdesktop package, or pass -JarPath.'
}

if (-not $JavaPath) {
    $candidates = @((Join-Path (Split-Path $JarPath -Parent) 'runtime\bin\java.exe'))
    foreach ($javaHomeCandidate in @($env:COFFER_JAVA_HOME, $env:JAVA_HOME)) {
        if ($javaHomeCandidate) { $candidates += Join-Path $javaHomeCandidate 'bin\java.exe' }
    }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $version = (& $candidate -version 2>&1 | Out-String)
        $match = [regex]::Match($version, '(?:version\s+"|openjdk\s+)(\d+)')
        if ($match.Success -and [int]$match.Groups[1].Value -ge 17) { $JavaPath = $candidate; break }
    }
}
if (-not $JavaPath -or -not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw 'A Java 17+ runtime is required; pass -JavaPath or supply the packaged runtime.'
}

# The Java bootstrap owns initialization, locking, keys and binding checks. No infrastructure is launched here.
$arguments = @('-jar', $JarPath, '--spring.profiles.active=desktop', "--server.port=$Port")
if ($UserDataDir) { $arguments += "--coffer.desktop.data-directory=$UserDataDir" }
if ($LibraryDir) { $arguments += "--coffer.desktop.library-directory=$LibraryDir" }
if ($Initialize) { $arguments += '--coffer.desktop.initialize=true' }
if ($RebindLibrary) { $arguments += '--coffer.desktop.rebind-library=true' }
& $JavaPath @arguments
exit $LASTEXITCODE
