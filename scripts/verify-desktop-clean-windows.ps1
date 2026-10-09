param(
    [Parameter(Mandatory)][string]$InstallerPath,
    [string]$ChecksumPath,
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\Coffer'),
    [string]$EvidenceDirectory = (Join-Path $env:USERPROFILE 'Desktop\Coffer-acceptance'),
    [switch]$SilentInstall,
    [switch]$TestUninstall
)
# Standalone Windows PowerShell 5.1 script: no Node, Maven, installed Java, MySQL, Redis or MinIO is required.
$ErrorActionPreference = 'Stop'
$InstallerPath=[IO.Path]::GetFullPath($InstallerPath);$InstallDirectory=[IO.Path]::GetFullPath($InstallDirectory)
$EvidenceDirectory=[IO.Path]::GetFullPath($EvidenceDirectory)
if(-not $ChecksumPath){$ChecksumPath=Join-Path (Split-Path $InstallerPath -Parent) 'SHA256SUMS.txt'}
$name=[IO.Path]::GetFileName($InstallerPath);$expected=$null
foreach($line in Get-Content -LiteralPath $ChecksumPath){if($line -match '^([a-f0-9]{64})  (.+)$' -and $Matches[2] -eq $name){$expected=$Matches[1]}}
if(-not $expected -or (Get-FileHash -LiteralPath $InstallerPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'Installer SHA-256 validation failed'}
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$dataDirectory=Join-Path $env:LOCALAPPDATA 'Coffer'
$snapshot=@{date=[DateTime]::UtcNow.ToString('o');computer=$env:COMPUTERNAME;os=[Environment]::OSVersion.VersionString;installerSha256=$expected;installerSignature=(Get-AuthenticodeSignature -LiteralPath $InstallerPath).Status.ToString();developerDependenciesRequired=$false}
$arguments=@();if($SilentInstall){$arguments+='/S'};$arguments+=('/D='+$InstallDirectory)
$installer=Start-Process -FilePath $InstallerPath -ArgumentList $arguments -Wait -PassThru -WindowStyle $(if($SilentInstall){'Hidden'}else{'Normal'})
if($installer.ExitCode -ne 0){throw 'Installation failed or was cancelled'}
$exe=Join-Path $InstallDirectory 'Coffer.exe';$resources=Join-Path $InstallDirectory 'resources\coffer'
if(-not (Test-Path -LiteralPath $exe)){throw 'Coffer.exe is missing; verify the chosen installation directory'}
$manifest=Get-Content -LiteralPath (Join-Path $resources 'manifest.json') -Raw | ConvertFrom-Json
foreach($entry in $manifest.files){
    $file=[IO.Path]::GetFullPath((Join-Path $resources $entry.path))
    if(-not $file.StartsWith([IO.Path]::GetFullPath($resources)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Resource path escapes installation'}
    if((Get-Item -LiteralPath $file).Length -ne $entry.size -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256){throw ('Resource hash mismatch: '+$entry.path)}
}
$snapshot.resourcesVerified=$manifest.files.Count;$snapshot.bundledJavaVersion=$manifest.runtime.version;$snapshot.appVersion=$manifest.version
$sample=Join-Path $EvidenceDirectory '本地验收资料.txt';[IO.File]::WriteAllText($sample,'Public report for Coffer clean Windows verification. No personal information.',[Text.UTF8Encoding]::new($false))
$sampleSha=(Get-FileHash -LiteralPath $sample -Algorithm SHA256).Hash
$started=[DateTime]::UtcNow;$application=Start-Process -FilePath $exe -PassThru
Write-Host '请在应用中明确初始化空目录、创建管理员，再创建两个业务账号并登录第一个。'
Write-Host ('本地验证文件：'+$sample)
Write-Host '选择/拖入该文件，核对收件箱与正式目标路径；如验证 AI 闭环，先配置并验证自己的模型端点。'
Write-Host '完成文件查看、受控工作副本编辑/保存、归档/撤销；第二个业务账号不能查看第一个账号的数据。'
Write-Host '关闭网络后仍应能查看本地已入库文件。强杀主窗口后重新打开，未完成副本/操作应有可见恢复结果。'
$core=Read-Host '完成以上实际 UI 流程后输入 PASS（失败输入 FAIL；未完成不要记为通过）'
$snapshot.manualCorePassed=($core -eq 'PASS')
if($core -ne 'PASS'){$snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'acceptance.json') -Encoding UTF8;throw 'Core UI acceptance did not pass'}
$settingsPath=Join-Path $env:APPDATA 'Coffer-shell\desktop-settings.json'
if(Test-Path -LiteralPath $settingsPath){$dataDirectory=(Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json).dataDirectory}
$snapshot.dataDirectory=$dataDirectory
$runtime=Join-Path $dataDirectory '.coffer-runtime'
$ready=Get-ChildItem -LiteralPath $runtime -Filter 'ready-*.json' | Where-Object {$_.LastWriteTimeUtc -ge $started} | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if(-not $ready){throw 'No fresh backend ownership/port record; check the data directory chosen in the startup page'}
$owned=Get-Content -LiteralPath $ready.FullName -Raw | ConvertFrom-Json
$backend=Get-CimInstance Win32_Process -Filter ('ProcessId='+$owned.pid)
if(-not $backend -or $backend.CommandLine -notlike ('*'+(Join-Path $resources 'backend\coffer-backend.jar')+'*')){throw 'Backend process does not belong to this installation'}
$snapshot.backendPid=$owned.pid;$snapshot.backendPort=$owned.port
try{Invoke-WebRequest -Uri ('http://127.0.0.1:'+$owned.port+'/api/files') -UseBasicParsing -TimeoutSec 5 | Out-Null;$ordinary=200}
catch{if($_.Exception.Response){$ordinary=[int]$_.Exception.Response.StatusCode}else{throw}}
if($ordinary -ne 403){throw 'Ordinary local webpage/backend request was not rejected'}
$snapshot.ordinaryLocalRequestStatus=$ordinary
if((Get-FileHash -LiteralPath $sample -Algorithm SHA256).Hash -ne $sampleSha){throw 'External source file changed during import/edit verification'}
$snapshot.sourceRetained=$true
if($TestUninstall){
    Write-Host '请先正常关闭 Coffer 和外部编辑文档；下面运行卸载程序，默认保留数据。'
    if((Read-Host '确认已关闭后输入 CLOSED') -ne 'CLOSED'){throw 'Uninstall verification cancelled'}
    $key=Join-Path $dataDirectory '.coffer-encryption-key';$database=Join-Path $dataDirectory 'database\coffer.mv.db'
    $beforeKey=(Get-FileHash -LiteralPath $key -Algorithm SHA256).Hash;$beforeDb=(Get-FileHash -LiteralPath $database -Algorithm SHA256).Hash
    $uninstaller=Join-Path $InstallDirectory 'Uninstall Coffer.exe'
    $uninstall=Start-Process -FilePath $uninstaller -Wait -PassThru
    if($uninstall.ExitCode -ne 0){throw 'Uninstall failed or was cancelled'}
    if((Get-FileHash -LiteralPath $key -Algorithm SHA256).Hash -ne $beforeKey -or (Get-FileHash -LiteralPath $database -Algorithm SHA256).Hash -ne $beforeDb){throw 'Uninstall modified retained data or key'}
    $snapshot.uninstallRetainedData=$true
}
$snapshot.cleanMachineDeclaration=Read-Host '记录此环境是否确为全新 Windows/未装开发依赖，以及机器或 VM 基线（如实填写）'
$snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'acceptance.json') -Encoding UTF8
Write-Host ('验收记录：'+(Join-Path $EvidenceDirectory 'acceptance.json'))
