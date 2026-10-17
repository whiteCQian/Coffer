param(
    [string]$ResourceRoot,
    [string]$OutputRoot,
    [Parameter(Mandatory)][string]$JavaPath
)

# Backport the import boundary, duplicate-toast and model connection diagnostics fixes to V44.
# The original application, database, library, credentials and model configuration are never modified.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $ResourceRoot) { $ResourceRoot = Join-Path $repo 'desktop\dist\win-unpacked\resources\coffer' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('.test-tmp\desktop-inbox-fix-' + [guid]::NewGuid().ToString('N') + '\resources') }
$ResourceRoot = [IO.Path]::GetFullPath($ResourceRoot)
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$prefix = [IO.Path]::GetFullPath((Join-Path $repo '.test-tmp')).TrimEnd('\') + '\'
if (-not $OutputRoot.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Repair output must stay inside repository .test-tmp' }
foreach ($target in @($ResourceRoot, $OutputRoot)) {
    for ($cursor = $target; $cursor; $cursor = [IO.Path]::GetDirectoryName($cursor)) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked repair paths are refused' }
    }
}
if (Test-Path -LiteralPath $OutputRoot) { throw 'Repair output already exists; select a fresh directory' }
$manifest = Get-Content -LiteralPath (Join-Path $ResourceRoot 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.schemaVersion -ne 44) { throw 'This backport is only for the existing V44 desktop bundle' }
& node.exe -e 'require(process.argv[1]).verifyResources(process.argv[2]);' (Join-Path $repo 'desktop\src\resources.cjs') $ResourceRoot
if ($LASTEXITCODE -ne 0) { throw 'Source resources failed verification' }
$javac = Join-Path (Split-Path $JavaPath -Parent) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'A JDK 17+ is required' }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
Get-ChildItem -LiteralPath $ResourceRoot -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $OutputRoot -Recurse }

$work = Join-Path (Split-Path $OutputRoot -Parent) ('compile-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$jarPath = Join-Path $OutputRoot 'backend\coffer-backend.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    foreach ($entry in $archive.Entries) {
        $destination = [IO.Path]::GetFullPath((Join-Path $work $entry.FullName))
        if (-not $destination.StartsWith($work.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe JAR entry' }
    }
} finally { $archive.Dispose() }
[IO.Compression.ZipFile]::ExtractToDirectory($jarPath, $work)
$classes = Join-Path $work 'repair-classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$classpath = (Join-Path $work 'BOOT-INF\classes') + ';' + (Join-Path $work 'BOOT-INF\lib\*')
& $javac --release 17 -proc:none -encoding UTF-8 -classpath $classpath -d $classes (Join-Path $PSScriptRoot 'repairs\desktop-v44\DesktopImportModelErrorAdvice.java')
if ($LASTEXITCODE -ne 0) { throw 'V44 import advice compilation failed' }
$testerSource = [IO.File]::ReadAllText((Join-Path $repo 'backend\src\main\java\com\coffer\model\runtime\ModelRuntimeConnectivityTester.java'))
$testerField = 'private final com.coffer.config.EmbeddingProperties embeddingProperties;'
foreach ($expected in @('import lombok.RequiredArgsConstructor;', '@RequiredArgsConstructor', $testerField)) {
    if ([regex]::Matches($testerSource, [regex]::Escape($expected)).Count -ne 1) { throw 'Unexpected connectivity tester source; repair refused' }
}
# The installed runtime excludes Lombok. Replace only its constructor annotation for this isolated compilation.
$testerSource = $testerSource.Replace('import lombok.RequiredArgsConstructor;', '').Replace('@RequiredArgsConstructor', '')
$testerSource = $testerSource.Replace($testerField, $testerField + "`n    public ModelRuntimeConnectivityTester(com.coffer.config.EmbeddingProperties embeddingProperties) { this.embeddingProperties = embeddingProperties; }")
$testerSourcePath = Join-Path $work 'ModelRuntimeConnectivityTester.java'
[IO.File]::WriteAllText($testerSourcePath, $testerSource, [Text.UTF8Encoding]::new($false))
& $javac --release 17 -proc:none -encoding UTF-8 -classpath $classpath -d $classes $testerSourcePath
if ($LASTEXITCODE -ne 0) { throw 'V44 connectivity tester compilation failed' }
& $javac --release 17 -proc:none -encoding UTF-8 -classpath $classpath -d $classes (Join-Path $repo 'backend\src\main\java\com\coffer\config\DeadlineHttpClient.java') (Join-Path $repo 'backend\src\main\java\com\coffer\config\SafeModelHttpClient.java')
if ($LASTEXITCODE -ne 0) { throw 'V44 model response deadline compilation failed' }
$archive = [IO.Compression.ZipFile]::Open($jarPath, [IO.Compression.ZipArchiveMode]::Update)
try {
    if ($archive.GetEntry('BOOT-INF/classes/com/coffer/desktop/DesktopImportModelErrorAdvice.class') -and $manifest.inboxImportRepair -ne 'model-mode-error-and-single-toast-v44-2026-10-15') { throw 'Unexpected existing import advice' }
    foreach ($compiled in Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class') {
        $entryName = 'BOOT-INF/classes/' + $compiled.FullName.Substring($classes.Length + 1).Replace('\', '/')
        $prior = $archive.GetEntry($entryName)
        if ($prior) { $prior.Delete() }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $compiled.FullName, $entryName) | Out-Null
    }
} finally { $archive.Dispose() }

$scripts = @(Get-ChildItem -LiteralPath (Join-Path $OutputRoot 'web\assets') -Filter '*.js' | Where-Object {
    [IO.File]::ReadAllText($_.FullName).Contains('网络异常，请稍后重试')
})
if ($scripts.Count -ne 1) { throw 'Unexpected V44 HTTP client bundle' }
$scriptPath = $scripts[0].FullName
$scriptText = [IO.File]::ReadAllText($scriptPath)
$boundary = 'pt.interceptors.response.use('
$oldBody = 'e.config.quietError||$t.error(t.msg||"请求失败"),Promise.reject(new Error(t.msg||"请求失败"))'
$newBody = '(()=>{const n=new Error(t.msg||"请求失败");return e.config.quietError||cofferImportNotifyV44(n,n.message),Promise.reject(n)})()'
$oldHttp = '(c=e.config)!=null&&c.quietError||$t.error(a),Promise.reject(e)'
$newHttp = '(c=e.config)!=null&&c.quietError||cofferImportNotifyV44(e,a),Promise.reject(e)'
$helper = 'const cofferImportNotifiedV44=new WeakSet;function cofferImportNotifyV44(e,t){if(e&&typeof e==="object"){if(cofferImportNotifiedV44.has(e))return;cofferImportNotifiedV44.add(e)}$t.error(t)}'
if (-not $scriptText.Contains($helper)) {
    foreach ($expected in @($boundary, $oldBody, $oldHttp)) {
        if ([regex]::Matches($scriptText, [regex]::Escape($expected)).Count -ne 1) { throw 'Unexpected HTTP interceptor; repair refused' }
    }
    $scriptText = $scriptText.Replace($boundary, $helper + $boundary).Replace($oldBody, $newBody).Replace($oldHttp, $newHttp)
}
foreach ($replacement in @(
    @('pt.post("/settings/runtime/config/test",e)', 'pt.post("/settings/runtime/config/test",e,{timeout:9e4})'),
    @('pt.post("/settings/runtime/validate",{mode:e})', 'pt.post("/settings/runtime/validate",{mode:e},{timeout:21e4})')
)) {
    if ($scriptText.Contains($replacement[1])) { continue }
    if ([regex]::Matches($scriptText, [regex]::Escape($replacement[0])).Count -ne 1) { throw 'Unexpected model test request; timeout repair refused' }
    $scriptText = $scriptText.Replace($replacement[0], $replacement[1])
}
[IO.File]::WriteAllText($scriptPath, $scriptText, [Text.UTF8Encoding]::new($false))
& node.exe --check $scriptPath
if ($LASTEXITCODE -ne 0) { throw 'Patched HTTP client syntax check failed' }
foreach ($file in @($jarPath, $scriptPath)) {
    $relative = $file.Substring($OutputRoot.Length + 1).Replace('\', '/')
    $entries = @($manifest.files | Where-Object { $_.path -eq $relative })
    if ($entries.Count -ne 1) { throw 'Unexpected resource manifest entry' }
    $entries[0].size = (Get-Item -LiteralPath $file).Length
    $entries[0].sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest | Add-Member -NotePropertyName inboxImportRepair -NotePropertyValue 'model-mode-error-and-single-toast-v44-2026-10-15' -Force
$manifest | Add-Member -NotePropertyName modelConnectionRepair -NotePropertyValue 'provider-http-error-classification-v44-2026-10-15' -Force
$manifest | Add-Member -NotePropertyName modelDeadlineRepair -NotePropertyValue 'complete-response-deadline-v44-2026-10-15' -Force
[IO.File]::WriteAllText((Join-Path $OutputRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
& node.exe -e 'const m=require(process.argv[1]).verifyResources(process.argv[2]);console.log(JSON.stringify({version:m.version,schemaVersion:m.schemaVersion,repair:m.inboxImportRepair,resourcesVerified:m.files.length}));' (Join-Path $repo 'desktop\src\resources.cjs') $OutputRoot
if ($LASTEXITCODE -ne 0) { throw 'Repaired resources failed verification' }
$sourceAsar = Join-Path (Split-Path $ResourceRoot -Parent) 'app.asar'
if (-not (Test-Path -LiteralPath $sourceAsar -PathType Leaf)) { throw 'Bundled shell archive missing; proxy timeout repair cannot be completed' }
& node.exe (Join-Path $repo 'desktop\scripts\repair-model-proxy.cjs') $sourceAsar (Join-Path (Split-Path $OutputRoot -Parent) 'app.asar')
if ($LASTEXITCODE -ne 0) { throw 'Model-test proxy archive repair failed' }
Write-Output ('Prepared repair: ' + $OutputRoot)
