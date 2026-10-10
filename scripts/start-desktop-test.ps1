param(
    [string]$DataDirectory,
    [string]$ShellDirectory,
    [string]$NodePath,
    [string]$NpmPath,
    [string]$MavenPath,
    [string]$JavaPath,
    [switch]$Rebuild,
    [switch]$PrepareOnly,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$desktop = Join-Path $repo 'desktop'
$resources = Join-Path $desktop '.build\resources'
$electron = Join-Path $desktop 'node_modules\electron\dist\electron.exe'
$package = Get-Content -LiteralPath (Join-Path $desktop 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'Coffer-desktop-test' }
if (-not $ShellDirectory) { $ShellDirectory = Join-Path $env:APPDATA 'Coffer-shell-desktop-test' }
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
$ShellDirectory = [IO.Path]::GetFullPath($ShellDirectory)

function Assert-TestDirectory([string]$directory) {
    if ($directory -match '[;\r\n\x00"]') { throw 'Test directory contains unsupported characters' }
    $installPrefix = $desktop.TrimEnd('\') + '\'
    if ($directory -eq $desktop -or $directory.StartsWith($installPrefix, [StringComparison]::OrdinalIgnoreCase) -or $desktop.StartsWith($directory.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Test data and shell directories must be separate from the desktop program directory' }
    for ($cursor = $directory; $cursor; $cursor = [IO.Path]::GetDirectoryName($cursor)) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw ('Linked test directory refused: ' + $cursor) }
    }
}
function Resolve-Tool([string]$provided, [string]$command, [string[]]$candidates) {
    if ($provided) {
        if (-not (Test-Path -LiteralPath $provided -PathType Leaf)) { throw ('Tool not found: ' + $provided) }
        return (Resolve-Path -LiteralPath $provided).Path
    }
    $onPath = Get-Command $command -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return $onPath.Source }
    foreach ($candidate in $candidates) { if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return (Resolve-Path -LiteralPath $candidate).Path } }
    return $null
}
function Quote-WindowsArgument([string]$value) {
    # ProcessStartInfo on Windows PowerShell 5.1 has no ArgumentList collection.
    return '"' + [regex]::Replace([regex]::Replace($value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

try {
    Assert-TestDirectory $DataDirectory; Assert-TestDirectory $ShellDirectory
    if ($DataDirectory -eq $ShellDirectory -or $DataDirectory.StartsWith($ShellDirectory.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or $ShellDirectory.StartsWith($DataDirectory.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Test data and shell cache directories must be separate' }
    $schema = (Get-ChildItem -LiteralPath (Join-Path $repo 'backend\src\main\resources\db\migration\h2') -File | ForEach-Object { if ($_.Name -match '^V(\d+)__') { [int]$Matches[1] } } | Measure-Object -Maximum).Maximum
    $reasons = @()
    $manifestPath = Join-Path $resources 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { $reasons += 'Bundled resources missing' }
    else {
        try {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($manifest.version -ne $package.version -or $manifest.schemaVersion -ne $schema) { $reasons += 'Bundled version/schema is older than current source' }
            $sourceRoots = @((Join-Path $repo 'backend\src\main'), (Join-Path $repo 'frontend\src'))
            $newer = @($sourceRoots | ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -File } | Where-Object { $_.LastWriteTimeUtc -gt (Get-Item -LiteralPath $manifestPath).LastWriteTimeUtc })
            foreach ($sourceFile in @('backend\pom.xml','frontend\package.json','frontend\package-lock.json','frontend\index.html','frontend\vite.config.ts','desktop\runtime.lock.json')) {
                $sourcePath = Join-Path $repo $sourceFile
                if ((Test-Path -LiteralPath $sourcePath) -and (Get-Item -LiteralPath $sourcePath).LastWriteTimeUtc -gt (Get-Item -LiteralPath $manifestPath).LastWriteTimeUtc) { $newer += $sourcePath }
            }
            if ($newer.Count -gt 0) { $reasons += 'Source changed after bundled resources were built' }
        } catch { $reasons += 'Bundled resource manifest is invalid' }
    }
    if (-not (Test-Path -LiteralPath $electron -PathType Leaf)) { $reasons += 'Electron binary missing' }
    if ($Rebuild) { $reasons += 'Rebuild requested' }
    Write-Host ('Desktop test version: ' + $package.version + ', schema V' + $schema)
    Write-Host ('Test data: ' + $DataDirectory)
    Write-Host ('Test shell profile: ' + $ShellDirectory)
    if ($CheckOnly) {
        [pscustomobject]@{ Version=$package.version; SchemaVersion=$schema; NeedsBuild=($reasons.Count -gt 0); Reasons=$reasons; DataDirectory=$DataDirectory; ShellDirectory=$ShellDirectory; Electron=$electron } | ConvertTo-Json -Depth 4
        exit 0
    }
    if ($reasons.Count -gt 0) {
        Write-Host ('Preparing current desktop resources: ' + ($reasons -join '; '))
        $NodePath = Resolve-Tool $NodePath 'node.exe' @('C:\Program Files\nodejs\node.exe')
        $NpmPath = Resolve-Tool $NpmPath 'npm.cmd' @((Join-Path $desktop '.build\test-launcher-tools\npm.cmd'), 'C:\Program Files\nodejs\npm.cmd')
        $MavenPath = Resolve-Tool $MavenPath 'mvn.cmd' @((Join-Path $repo 'backend\mvnw.cmd'))
        if (-not $MavenPath) {
            $mavenCache = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
            if (Test-Path -LiteralPath $mavenCache) { $cachedMaven = Get-ChildItem -LiteralPath $mavenCache -Recurse -Filter mvn.cmd | Select-Object -First 1; if ($cachedMaven) { $MavenPath = $cachedMaven.FullName } }
        }
        $javaCandidates = @()
        foreach ($javaHomeCandidate in @($env:COFFER_JAVA_HOME,$env:JAVA_HOME)) { if ($javaHomeCandidate) { $javaCandidates += Join-Path $javaHomeCandidate 'bin\java.exe' } }
        foreach ($javaBase in @('C:\Program Files\Java','C:\Program Files\Eclipse Adoptium')) { if (Test-Path -LiteralPath $javaBase) { $javaCandidates += Get-ChildItem -LiteralPath $javaBase -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } } }
        $JavaPath = Resolve-Tool $JavaPath 'java.exe' $javaCandidates
        if (-not $NodePath -or -not $NpmPath -or -not $MavenPath -or -not $JavaPath) { throw 'Building current desktop resources requires Node.js 22.12+, npm, Maven and a JDK 17+. Install them or pass -NodePath/-NpmPath/-MavenPath/-JavaPath.' }
        if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $JavaPath -Parent) 'javac.exe'))) { throw 'A JDK (including javac.exe), not only a JRE, is required for the initial build' }
        $oldProcessPath = [Environment]::GetEnvironmentVariable('PATH','Process')
        [Environment]::SetEnvironmentVariable('PATH', ((Split-Path $NodePath -Parent) + ';' + (Split-Path $NpmPath -Parent) + ';' + $oldProcessPath), 'Process')
        try {
            foreach ($project in @('frontend','desktop')) {
                if (-not (Test-Path -LiteralPath (Join-Path $repo ($project + '\node_modules')))) { & $NpmPath ci --prefix (Join-Path $repo $project); if ($LASTEXITCODE -ne 0) { throw ('Locked dependency installation failed: ' + $project) } }
            }
            & (Join-Path $PSScriptRoot 'build-desktop.ps1') -MavenPath $MavenPath -JavaPath $JavaPath -Unpacked
        } finally { [Environment]::SetEnvironmentVariable('PATH',$oldProcessPath,'Process') }
    }
    if (-not (Test-Path -LiteralPath $electron -PathType Leaf)) { throw 'Electron binary preparation did not complete' }
    $currentManifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($currentManifest.version -ne $package.version -or $currentManifest.schemaVersion -ne $schema) { throw 'Current desktop resources were not produced; launch refused' }
    if ($PrepareOnly) { Write-Host 'Desktop test resources prepared; no application was started.'; exit 0 }
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $electron; $startInfo.WorkingDirectory = $desktop; $startInfo.UseShellExecute = $false
    $startInfo.EnvironmentVariables.Remove('ELECTRON_RUN_AS_NODE')
    $startInfo.Arguments = (@($desktop,('--coffer-data-directory=' + $DataDirectory),('--coffer-shell-directory=' + $ShellDirectory)) | ForEach-Object { Quote-WindowsArgument $_ }) -join ' '
    Write-Host 'Opening the desktop window. Confirm first initialization in the application; existing test data is retained.'
    $application = [Diagnostics.Process]::Start($startInfo)
    $application.WaitForExit()
    exit $application.ExitCode
} catch {
    Write-Host ('Desktop test startup failed: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
