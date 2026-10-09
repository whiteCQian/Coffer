param([Parameter(Mandatory)][string]$MavenPath, [Parameter(Mandatory)][string]$JavaPath)
$ErrorActionPreference = 'Stop'
if (Get-Process WINWORD -ErrorAction SilentlyContinue) { throw 'Close existing Word sessions before this isolated optional fixture.' }
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $repo 'backend\.test-tmp'))
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $tempRoot ('r33-office-' + [guid]::NewGuid().ToString('N'))))
if (-not $fixtureRoot.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture escapes temporary workspace' }
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$word = $null; $document = $null; $wordProcess = $null; $javaProcess = $null
$oldData = $env:COFFER_DESKTOP_USER_DATA_DIR; $oldHome = $env:JAVA_HOME
function Wait-FixtureFile([string]$name) {
    $until = [DateTime]::UtcNow.AddSeconds(90)
    $target = Join-Path $fixtureRoot $name
    while (-not (Test-Path -LiteralPath $target)) {
        if ($javaProcess -and $javaProcess.HasExited) { throw 'Office backend probe exited; inspect fixture logs' }
        if ([DateTime]::UtcNow -gt $until) { throw ('Office fixture timeout: '+$name) }
        Start-Sleep -Milliseconds 100
    }
}
try {
    $env:JAVA_HOME = Split-Path (Split-Path $JavaPath -Parent) -Parent
    $classpathFile = Join-Path $fixtureRoot 'classpath.txt'
    Push-Location (Join-Path $repo 'backend')
    try { & $MavenPath -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"; if ($LASTEXITCODE -ne 0) { throw 'Office fixture compilation failed' } }
    finally { Pop-Location }
    $started = [DateTime]::Now
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false; $word.DisplayAlerts = 0
    $wordProcesses = @(Get-Process WINWORD -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -ge $started.AddSeconds(-2) })
    if ($wordProcesses.Count -ne 1) { throw 'Cannot prove ownership of Word instance' }
    $wordProcess = $wordProcesses[0]
    $document = $word.Documents.Add(); $document.Content.Text = 'R33 real Word original'
    $original = Join-Path $fixtureRoot 'original.docx'; $document.SaveAs2($original, 16); $document.Close(0)
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) | Out-Null; $document = $null
    $classpath = (Join-Path $repo 'backend\target\test-classes') + ';' + (Join-Path $repo 'backend\target\classes') + ';' + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $env:COFFER_DESKTOP_USER_DATA_DIR = (Join-Path $fixtureRoot 'data')
    $javaProcess = Start-Process -FilePath $JavaPath -ArgumentList @('-Dspring.devtools.restart.enabled=false','-cp',('"'+$classpath+'"'),'com.coffer.desktop.WorkCopyOfficeProbeMain',('"'+$fixtureRoot+'"')) -WorkingDirectory (Join-Path $repo 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $fixtureRoot 'probe.out.log') -RedirectStandardError (Join-Path $fixtureRoot 'probe.err.log')
    Wait-FixtureFile 'work-path.txt'
    $workPath = [IO.Path]::GetFullPath((Get-Content -LiteralPath (Join-Path $fixtureRoot 'work-path.txt') -Raw).Trim())
    if (-not $workPath.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $workPath -notmatch '[\\/]work[\\/]') { throw 'Office was not handed a controlled fixture work path' }
    $document = $word.Documents.Open($workPath); $document.Content.Text = 'R33 real Word saved edit'; $document.Save()
    New-Item -ItemType File -Path (Join-Path $fixtureRoot 'busy.flag') | Out-Null; Wait-FixtureFile 'busy-pass.flag'
    $document.Content.Text = 'R33 memory only unsaved'
    # Only this proven, freshly created Word process is killed; no user document or prior process is touched.
    $wordProcess.Kill(); $wordProcess.WaitForExit()
    New-Item -ItemType File -Path (Join-Path $fixtureRoot 'crashed.flag') | Out-Null; Wait-FixtureFile 'crash-pass.flag'
    Get-ChildItem -LiteralPath (Split-Path $workPath -Parent) -File -Force | Where-Object { $_.Name.StartsWith('~$') } | ForEach-Object {
        $lockPath = [IO.Path]::GetFullPath($_.FullName)
        if (-not $lockPath.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Lock cleanup escapes fixture' }
        Remove-Item -LiteralPath $lockPath -Force
    }
    New-Item -ItemType File -Path (Join-Path $fixtureRoot 'closed.flag') | Out-Null
    if (-not $javaProcess.WaitForExit(90000) -or $javaProcess.ExitCode -ne 0) { throw 'Real Office flow failed; inspect fixture logs' }
    Get-Content -LiteralPath (Join-Path $fixtureRoot 'probe.out.log') | Select-String 'R33_OFFICE_PASS'
} finally {
    if ($javaProcess -and -not $javaProcess.HasExited) { $javaProcess.Kill(); $javaProcess.WaitForExit() }
    if ($wordProcess -and -not $wordProcess.HasExited) {
        if ($document) { try { $document.Close(0) } catch {} }
        if ($word) { try { $word.Quit(0) } catch {} }
        if (-not $wordProcess.HasExited) { $wordProcess.Kill(); $wordProcess.WaitForExit() }
    }
    if ($document) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) | Out-Null }
    if ($word) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) | Out-Null }
    $env:COFFER_DESKTOP_USER_DATA_DIR = $oldData; $env:JAVA_HOME = $oldHome
    Write-Output ('Office fixture artifacts: '+$fixtureRoot)
}
