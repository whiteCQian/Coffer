param([string]$MavenPath, [string]$JavaPath, [switch]$SkipBuild, [switch]$Unpacked)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$desktop = Join-Path $repo 'desktop'; $buildRoot = [IO.Path]::GetFullPath((Join-Path $desktop '.build'))
$resources = [IO.Path]::GetFullPath((Join-Path $buildRoot 'resources'))
if (-not $resources.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Resource target escapes build workspace' }
function Assert-NoLinks([string]$target) {
    for($cursor=[IO.Path]::GetFullPath($target);$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if(Test-Path -LiteralPath $cursor) {
            $item=Get-Item -LiteralPath $cursor -Force
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw ('Release path contains a link: '+$cursor) }
        }
    }
}
Assert-NoLinks $buildRoot; New-Item -ItemType Directory -Path (Join-Path $buildRoot 'cache') -Force | Out-Null
$runtime = Get-Content -LiteralPath (Join-Path $desktop 'runtime.lock.json') -Raw | ConvertFrom-Json
$archivePath = Join-Path $buildRoot 'cache\temurin-jre-windows-x64.zip'
Assert-NoLinks $archivePath
if (-not (Test-Path -LiteralPath $archivePath)) { Invoke-WebRequest -Uri $runtime.url -OutFile $archivePath -TimeoutSec 180 }
if ((Get-Item -LiteralPath $archivePath).Length -ne $runtime.size -or (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $runtime.sha256) { throw 'Temurin archive checksum/size mismatch; build refused' }
if (-not $SkipBuild) {
    if (-not $MavenPath) { $MavenPath = (Get-Command mvn.cmd -ErrorAction Stop).Source }
    if (-not $JavaPath) { $JavaPath = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $env:JAVA_HOME = Split-Path (Split-Path $JavaPath -Parent) -Parent
    & $MavenPath --batch-mode --no-transfer-progress -f (Join-Path $repo 'backend\pom.xml') -Pdesktop package
    if ($LASTEXITCODE -ne 0) { throw 'Desktop backend build failed' }
    & npm.cmd --prefix (Join-Path $repo 'frontend') run build
    if ($LASTEXITCODE -ne 0) { throw 'Production frontend build failed' }
}
Assert-NoLinks $resources
if (Test-Path -LiteralPath $resources) { Remove-Item -LiteralPath $resources -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $resources 'backend') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'backend\target\desktop\coffer-backend-0.0.1-SNAPSHOT.jar') -Destination (Join-Path $resources 'backend\coffer-backend.jar')
Copy-Item -LiteralPath (Join-Path $repo 'frontend\dist') -Destination (Join-Path $resources 'web') -Recurse
$extractRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot ('jre-extract-' + [guid]::NewGuid().ToString('N'))))
if (-not $extractRoot.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime extraction escapes build workspace' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    foreach($entry in $zip.Entries) {
        $target = [IO.Path]::GetFullPath((Join-Path $extractRoot $entry.FullName))
        if (-not $target.StartsWith($extractRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid runtime archive path' }
    }
} finally { $zip.Dispose() }
[IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $extractRoot)
$jreDirectories = @(Get-ChildItem -LiteralPath $extractRoot -Directory)
if ($jreDirectories.Count -ne 1) { throw 'Unexpected Temurin archive layout' }
Copy-Item -LiteralPath $jreDirectories[0].FullName -Destination (Join-Path $resources 'runtime') -Recurse
# Only this verified build-local extraction is removed. User data is never a build target.
Assert-NoLinks $extractRoot; Remove-Item -LiteralPath $extractRoot -Recurse -Force
& node.exe (Join-Path $desktop 'scripts\resource-manifest.cjs')
if ($LASTEXITCODE -ne 0) { throw 'Resource manifest verification failed' }
if (-not (Test-Path -LiteralPath (Join-Path $desktop 'node_modules\electron\dist\electron.exe'))) { throw 'Install locked desktop dependencies with npm ci --prefix desktop first' }
Push-Location $desktop
try {
    $signingArgs = @()
    if ($env:CSC_LINK -or $env:WIN_CSC_LINK) { $signingArgs = @('--', '-c.win.signExecutable=true') }
    if ($Unpacked) { & npm.cmd run package:dir @signingArgs } else { & npm.cmd run package @signingArgs }
    if ($LASTEXITCODE -ne 0) { throw 'Electron packaging failed' }
    & node.exe scripts\release-checksums.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Package checksum generation failed' }
    Copy-Item -LiteralPath (Join-Path $repo 'scripts\verify-desktop-clean-windows.ps1') -Destination (Join-Path $desktop 'dist\verify-desktop-clean-windows.ps1')
    Copy-Item -LiteralPath (Join-Path $repo 'scripts\desktop-backup.ps1') -Destination (Join-Path $desktop 'dist\desktop-backup.ps1')
    # Windows PowerShell 5.1 requires a BOM to decode the Chinese prompts in standalone scripts.
    foreach($scriptName in @('verify-desktop-clean-windows.ps1','desktop-backup.ps1')) {
        $releaseScript=Join-Path $desktop ('dist\'+$scriptName)
        [IO.File]::WriteAllText($releaseScript,[IO.File]::ReadAllText($releaseScript),(New-Object Text.UTF8Encoding($true)))
    }
    Copy-Item -LiteralPath (Join-Path $desktop 'README.md') -Destination (Join-Path $desktop 'dist\README-安装验收.md')
} finally { Pop-Location }
