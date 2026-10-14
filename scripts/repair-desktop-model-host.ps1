param(
    [string]$ResourceRoot,
    [string]$OutputRoot
)

# Configuration-only repair for the shipped V44 desktop bundle. Keeps its
# personal Embedding settings and schema; does not rebuild newer Web changes.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $ResourceRoot) { $ResourceRoot = Join-Path $repo 'desktop\dist\win-unpacked\resources\coffer' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo '.test-tmp\desktop-model-host-fix\resources' }
$ResourceRoot = [IO.Path]::GetFullPath($ResourceRoot)
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$testPrefix = [IO.Path]::GetFullPath((Join-Path $repo '.test-tmp')).TrimEnd('\') + '\'
if (-not $OutputRoot.StartsWith($testPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Repair output must stay inside the repository .test-tmp directory' }
foreach ($target in @($ResourceRoot, $OutputRoot)) {
    for ($cursor = $target; $cursor; $cursor = [IO.Path]::GetDirectoryName($cursor)) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked resource directories are not supported' }
    }
}
if (Test-Path -LiteralPath $OutputRoot) { throw 'Repair output already exists; use a fresh output directory' }
$manifestPath = Join-Path $ResourceRoot 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.schemaVersion -ne 44) { throw 'This repair is intended for the existing V44 desktop bundle only' }
& node.exe -e 'const {verifyResources}=require(process.argv[1]);verifyResources(process.argv[2]);' (Join-Path $repo 'desktop\src\resources.cjs') $ResourceRoot
if ($LASTEXITCODE -ne 0) { throw 'Source resource verification failed' }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
Get-ChildItem -LiteralPath $ResourceRoot -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $OutputRoot -Recurse }
$jarPath = Join-Path $OutputRoot 'backend\coffer-backend.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::Open($jarPath, [IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = $jar.GetEntry('BOOT-INF/classes/application.yml')
    if (-not $entry) { throw 'Bundled application.yml was not found' }
    $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
    try { $original = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $old = 'allowed-api-hosts: ${COFFER_ALLOWED_MODEL_API_HOSTS:api.deepseek.com,dashscope.aliyuncs.com}'
    $new = 'allowed-api-hosts: ${COFFER_ALLOWED_MODEL_API_HOSTS:api.deepseek.com,dashscope.aliyuncs.com,maas.qianwenaiapi.com}'
    if ([regex]::Matches($original, [regex]::Escape($old)).Count -ne 1) { throw 'Unexpected domain defaults; explicit/custom restrictions will not be overwritten' }
    $updated = $original.Replace($old, $new)
    $entry.Delete()
    $replacement = $jar.CreateEntry('BOOT-INF/classes/application.yml', [IO.Compression.CompressionLevel]::Optimal)
    $writer = [IO.StreamWriter]::new($replacement.Open(), [Text.UTF8Encoding]::new($false))
    try { $writer.Write($updated) } finally { $writer.Dispose() }
} finally { $jar.Dispose() }
$backendEntries = @($manifest.files | Where-Object { $_.path -eq 'backend/coffer-backend.jar' })
if ($backendEntries.Count -ne 1) { throw 'Unexpected backend manifest entry' }
$backendEntries[0].size = (Get-Item -LiteralPath $jarPath).Length
$backendEntries[0].sha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest | Add-Member -NotePropertyName desktopRepair -NotePropertyValue 'qianwen-model-host-v44-2026-10-14'
[IO.File]::WriteAllText((Join-Path $OutputRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
& node.exe -e 'const {verifyResources}=require(process.argv[1]);const m=verifyResources(process.argv[2]);console.log(JSON.stringify({version:m.version,schemaVersion:m.schemaVersion,repair:m.desktopRepair,resourcesVerified:m.files.length}));' (Join-Path $repo 'desktop\src\resources.cjs') $OutputRoot
if ($LASTEXITCODE -ne 0) { throw 'Repaired resource verification failed' }
Write-Output ('Prepared repair: ' + $OutputRoot)
