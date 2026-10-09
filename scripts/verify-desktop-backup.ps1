param([Parameter(Mandatory)][string]$MavenPath,[Parameter(Mandatory)][string]$JavaPath)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent));$parent=[IO.Path]::GetFullPath((Join-Path $repo 'backend\.test-tmp'))
$fixture=[IO.Path]::GetFullPath((Join-Path $parent ('r35-upgrade-crash-'+[guid]::NewGuid().ToString('N'))))
if(-not $fixture.StartsWith($parent+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Probe path escapes workspace'}
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$env:JAVA_HOME=Split-Path (Split-Path $JavaPath -Parent) -Parent;$classpathFile=Join-Path $fixture 'classpath.txt'
Push-Location (Join-Path $repo 'backend')
try{& $MavenPath -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile";if($LASTEXITCODE -ne 0){throw 'Probe compilation failed'}}finally{Pop-Location}
$classpath=(Join-Path $repo 'backend\target\test-classes')+';'+(Join-Path $repo 'backend\target\classes')+';'+(Get-Content -LiteralPath $classpathFile -Raw).Trim()
foreach($phase in @('crash','verify')){
    $output=Join-Path $fixture ($phase+'.out.log');$errors=Join-Path $fixture ($phase+'.err.log')
    $process=Start-Process -FilePath $JavaPath -ArgumentList @('-Dspring.devtools.restart.enabled=false','-cp',('"'+$classpath+'"'),'com.coffer.desktop.UpgradeRollbackCrashProbeMain',$phase,('"'+$fixture+'"')) -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $output -RedirectStandardError $errors
    $expected=if($phase -eq 'crash'){73}else{0};if($process.ExitCode -ne $expected){throw ('R35 '+$phase+' failed; inspect '+$fixture)}
}
Get-Content (Join-Path $fixture 'verify.out.log') | Select-String 'R35_NEW_JVM_ROLLBACK_PASS'
Write-Output ('Preserved verification artifacts: '+$fixture)
