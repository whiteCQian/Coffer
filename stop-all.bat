@echo off
setlocal
cd /d "%~dp0"

echo Stopping Coffer services ...
echo   (Redis / MinIO are left running - they are shared local services.)

REM ---------- stop backend (java.exe running coffer-backend-*.jar) ----------
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*coffer-backend*' } | ForEach-Object { Write-Host ('[stop] backend pid ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

REM ---------- stop frontend vite (node.exe under this frontend dir) ----------
set "FE_DIR=%~dp0frontend"
powershell -NoProfile -Command "$d = $env:FE_DIR; Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -like ('*' + $d + '*') } | ForEach-Object { Write-Host ('[stop] frontend pid ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
set "FE_DIR="

echo Done. Close any leftover Coffer-Backend / Coffer-Frontend console windows.
endlocal
