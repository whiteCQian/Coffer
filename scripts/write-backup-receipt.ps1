param(
    [Parameter(Mandatory=$true)][string]$ArchivePath,
    [Parameter(Mandatory=$true)][string]$ReceiptPath
)
$ErrorActionPreference = 'Stop'
# Run only after the backup job has finished. Never include its path or contents in the receipt.
$archive = Get-Item -LiteralPath $ArchivePath
if ($archive.PSIsContainer -or $archive.Length -le 0 -or ($archive.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'A non-empty completed backup file is required.'
}
$resolvedReceipt = [IO.Path]::GetFullPath($ReceiptPath)
if ($resolvedReceipt -ieq $archive.FullName) { throw 'Receipt must not overwrite the backup.' }
$parent = Split-Path -Parent $resolvedReceipt
if (-not (Test-Path -LiteralPath $parent -PathType Container)) { throw 'Receipt directory must already exist.' }
$stream = [IO.File]::Open($archive.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
$digest = [Security.Cryptography.SHA256]::Create()
try {
    $size = $stream.Length
    $hash = [BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
} finally { $stream.Dispose(); $digest.Dispose() }
$receipt = @{completedAt=([DateTimeOffset]$archive.LastWriteTimeUtc).ToUniversalTime().ToString('O'); verified=$true; sha256=$hash; sizeBytes=$size}
$temporary = Join-Path $parent ('.coffer-receipt-' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    [IO.File]::WriteAllText($temporary, ($receipt | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $resolvedReceipt -Force
    Write-Host 'Backup checksum receipt updated.'
} finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
