param(
    [Parameter(Mandatory)][ValidateSet('backup','snapshot','verify','restore','upgrade','recover-upgrade')][string]$Action,
    [string]$InstallDirectory,
    [string]$JavaPath,
    [string]$JarPath,
    [string]$DataDirectory,
    [string]$LibraryDirectory,
    [string]$DestinationDirectory,
    [string]$Archive,
    [string]$TargetDirectory,
    [int]$TargetSchema=41
)
# Whole-instance offline maintenance. Close Coffer and external editors first. Never pass a passphrase on argv.
$ErrorActionPreference='Stop'
if($InstallDirectory){$JavaPath=Join-Path $InstallDirectory 'resources\coffer\runtime\bin\java.exe';$JarPath=Join-Path $InstallDirectory 'resources\coffer\backend\coffer-backend.jar'}
if(-not $JavaPath -or -not $JarPath -or -not(Test-Path -LiteralPath $JavaPath) -or -not(Test-Path -LiteralPath $JarPath)){throw 'Supply the installed application directory, or existing JavaPath/JarPath'}
if($Action -in @('backup','snapshot','upgrade','recover-upgrade') -and (-not $DataDirectory -or -not(Test-Path -LiteralPath $DataDirectory -PathType Container))){throw 'DataDirectory must be the existing original data directory'}
if($Action -in @('backup','snapshot') -and (-not $DestinationDirectory -or -not(Test-Path -LiteralPath $DestinationDirectory -PathType Container))){throw 'DestinationDirectory must be an existing backup directory'}
if($Action -in @('verify','restore') -and (-not $Archive -or -not(Test-Path -LiteralPath $Archive -PathType Leaf))){throw 'Archive must be an existing encrypted backup package'}
if($Action -eq 'verify' -and (-not $TargetDirectory -or -not(Test-Path -LiteralPath $TargetDirectory -PathType Container))){throw 'For verify, TargetDirectory must be an existing local directory for private temporary validation; existing contents are retained'}
if($Action -eq 'restore' -and -not $TargetDirectory){throw 'Restore requires an empty or absent TargetDirectory with an existing parent directory'}
$request=@{action=$Action;dataDirectory=$DataDirectory;libraryDirectory=$LibraryDirectory;destinationDirectory=$DestinationDirectory;archive=$Archive;targetDirectory=$TargetDirectory;targetSchema=$TargetSchema}
$passwordPointer=[IntPtr]::Zero
try{
    if($Action -ne 'recover-upgrade'){
        $secret=Read-Host '备份口令（至少 12 字符；不会作为命令参数或环境变量保存）' -AsSecureString
        $passwordPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        $request.password=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        if($request.password.Length -lt 12){throw 'Passphrase must contain at least 12 characters'}
    }
    $info=New-Object Diagnostics.ProcessStartInfo;$info.FileName=[IO.Path]::GetFullPath($JavaPath)
    $info.Arguments='-jar "'+[IO.Path]::GetFullPath($JarPath)+'" --coffer-desktop-maintenance'
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $utf8=New-Object Text.UTF8Encoding($false);$info.StandardOutputEncoding=$utf8;$info.StandardErrorEncoding=$utf8
    foreach($variable in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_PROFILES_ACTIVE')){$info.EnvironmentVariables.Remove($variable)}
    $process=New-Object Diagnostics.Process;$process.StartInfo=$info
    if(-not $process.Start()){throw 'Maintenance process did not start'}
    $output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
    # Windows PowerShell may use an ANSI stdin writer; Jackson requires UTF-8, including Chinese paths.
    # Write bytes directly for compatibility with both Windows PowerShell 5.1 and PowerShell 7.
    $payload=$utf8.GetBytes(($request | ConvertTo-Json -Depth 6 -Compress))
    try{$process.StandardInput.BaseStream.Write($payload,0,$payload.Length);$process.StandardInput.BaseStream.Flush()}finally{[Array]::Clear($payload,0,$payload.Length);$process.StandardInput.Close();$request.Remove('password')}
    $process.WaitForExit();$text=$output.GetAwaiter().GetResult();$null=$errors.GetAwaiter().GetResult()
    $line=($text -split '\r?\n' | Where-Object {$_ -like '{"code":*'} | Select-Object -Last 1)
    if(-not $line){throw 'Maintenance did not return a verified result; data and journals are retained'}
    $result=$line | ConvertFrom-Json
    if($process.ExitCode -ne 0 -or $result.code -ne 0){throw ('Maintenance refused: '+$result.error)}
    $result.data | ConvertTo-Json -Depth 6
}finally{if($passwordPointer -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)};if($request){$request.Remove('password')}}
