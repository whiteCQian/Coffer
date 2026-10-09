param([string]$MavenPath, [string]$JavaPath, [switch]$KeepArtifacts)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify-file-crash-recovery.ps1') -MavenPath $MavenPath -JavaPath $JavaPath -KeepArtifacts:$KeepArtifacts -Steps @(
    'work-prepared', 'work-published', 'work-recorded', 'work-committed',
    'copy-opened', 'copy-published', 'copy-saveas-published', 'copy-discard-intent', 'copy-save-committed')
