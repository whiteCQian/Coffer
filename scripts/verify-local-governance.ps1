param([string]$MavenPath, [string]$JavaPath, [switch]$KeepArtifacts)

# Each boundary uses an isolated H2/library pair, Runtime.halt, and a fresh JVM.
# The probes themselves point Redis/MinIO at an unreachable loopback port.
$steps = @('archive-ledger', 'archive-source-verified', 'archive-copy-unrecorded',
    'archive-target-recorded', 'archive-metadata', 'archive-source-removed',
    'rollback-ledger', 'rollback-copy-unrecorded', 'rollback-copy-verified',
    'rollback-metadata', 'rollback-target-removed',
    'delete-enqueued', 'delete-claimed', 'delete-removed')
& (Join-Path $PSScriptRoot 'verify-file-crash-recovery.ps1') -MavenPath $MavenPath -JavaPath $JavaPath `
    -Steps $steps -KeepArtifacts:$KeepArtifacts
if (-not $?) { exit 1 }
