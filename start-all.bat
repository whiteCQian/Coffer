@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM   Coffer one-click launcher (Windows .bat, ASCII only)
REM   Uses your LOCAL Redis + MinIO. Starts backend + frontend.
REM   Double-click this file. On first run it will run `npm install`
REM   (needs network) inside the frontend folder.
REM ============================================================
cd /d "%~dp0"

REM ---------- Config: adjust to your machine ----------
set "JAVA_EXE=C:\Program Files\Java\jdk-25.0.3\bin\java.exe"
set "REDIS_EXE=D:\Compiler\Redis\Redis-8.8.0-Windows-x64-msys2\redis-server.exe"
set "MINIO_EXE=D:\Compiler\MinIO\minio.exe"
set "MINIO_DATA=D:\Compiler\MinIO\data"
set "MINIO_CONFIG_DIR=D:\Compiler\MinIO\config"
set "BACKEND_JAR=%~dp0backend\target\coffer-backend-0.0.1-SNAPSHOT.jar"
set "FRONTEND_DIR=%~dp0frontend"
set "API_PORT=8080"
set "FE_PORT=5173"
set "LOCAL_ENV=%~dp0coffer-local.cmd"

echo.
echo  ============================================
echo    Coffer one-click launcher
echo  ============================================

REM ---------- load or create local runtime secrets ----------
REM The file is ignored by Git. Keep it backed up: changing this key means
REM previously encrypted model credentials can no longer be decrypted.
if exist "%LOCAL_ENV%" call "%LOCAL_ENV%"
if not defined COFFER_SECRET_KEY (
    echo [INIT] Creating local encryption master key ...
    powershell -NoProfile -Command "$bytes = New-Object byte[] 32; $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider; $rng.GetBytes($bytes); $rng.Dispose(); $key = ([BitConverter]::ToString($bytes)).Replace('-', ''); $content = '@echo off' + [Environment]::NewLine + 'set COFFER_SECRET_KEY=' + $key + [Environment]::NewLine; [System.IO.File]::WriteAllText($env:LOCAL_ENV, $content, (New-Object System.Text.UTF8Encoding($false)))"
    if errorlevel 1 (
        echo [FATAL] Failed to create "%LOCAL_ENV%".
        pause
        exit /b 1
    )
    call "%LOCAL_ENV%"
)
if not defined COFFER_SECRET_KEY (
    echo [FATAL] COFFER_SECRET_KEY could not be loaded from "%LOCAL_ENV%".
    echo         Delete the file and run this script again to generate a new key.
    pause
    exit /b 1
)
call :loadPersistedValue MYSQL_PASSWORD
call :loadPersistedValue MINIO_ROOT_USER
call :loadPersistedValue MINIO_ROOT_PASSWORD
call :loadPersistedValue MINIO_ACCESS_KEY
call :loadPersistedValue MINIO_SECRET_KEY

REM ---------- 0) environment self-check ----------
if not exist "%JAVA_EXE%" (
    echo [FATAL] Java not found: "%JAVA_EXE%"
    echo         Edit JAVA_EXE at the top of start-all.bat
    pause
    exit /b 1
)
if not exist "%BACKEND_JAR%" (
    echo [FATAL] Backend jar not found: "%BACKEND_JAR%"
    echo         Build it first:  cd backend ^&^& mvn package -DskipTests
    pause
    exit /b 1
)
set "BACKEND_STALE=0"
for /f "delims=" %%A in ('powershell -NoProfile -Command "$jar = Get-Item -LiteralPath $env:BACKEND_JAR; $newer = Get-ChildItem -LiteralPath (Join-Path $jar.Directory.Parent.FullName 'src') -Recurse -File ^| Where-Object { $_.LastWriteTimeUtc -gt $jar.LastWriteTimeUtc } ^| Select-Object -First 1; $pom = Get-Item -LiteralPath (Join-Path $jar.Directory.Parent.FullName 'pom.xml'); if ($null -ne $newer -or $pom.LastWriteTimeUtc -gt $jar.LastWriteTimeUtc) { '1' } else { '0' }"') do set "BACKEND_STALE=%%A"
if "!BACKEND_STALE!"=="1" (
    echo [FATAL] Backend source or pom.xml is newer than the packaged jar.
    echo         Rebuild it first:  cd backend ^&^& mvn package -DskipTests
    echo         This check prevents launching stale business logic.
    pause
    exit /b 1
)
if not defined MYSQL_PASSWORD (
    echo [FATAL] MYSQL_PASSWORD is not set.
    pause
    exit /b 1
)
if not defined MINIO_ROOT_USER (
    echo [FATAL] MINIO_ROOT_USER is not set.
    pause
    exit /b 1
)
if not defined MINIO_ROOT_PASSWORD (
    echo [FATAL] MINIO_ROOT_PASSWORD is not set.
    pause
    exit /b 1
)
set "MINIO_ACCESS_KEY=%MINIO_ROOT_USER%"
set "MINIO_SECRET_KEY=%MINIO_ROOT_PASSWORD%"

REM ---------- 1) Redis (6379) ----------
set "PORT_FREE=1"
netstat -ano 2>nul | findstr /R /C:":6379" | findstr /C:"LISTENING" >nul 2>&1 && set "PORT_FREE=0"
if "!PORT_FREE!"=="1" if exist "%REDIS_EXE%" (
    echo [1/5] Starting Redis on 6379 ...
    start "Coffer-Redis" /min "%REDIS_EXE%" --port 6379
    ping -n 3 127.0.0.1 >nul
) else (
    echo [1/5] Redis already listening on 6379 - skip.
)

REM ---------- 2) MinIO (9000/9001) ----------
set "PORT_FREE=1"
netstat -ano 2>nul | findstr /R /C:":9000" | findstr /C:"LISTENING" >nul 2>&1 && set "PORT_FREE=0"
if "!PORT_FREE!"=="1" if exist "%MINIO_EXE%" (
    echo [2/5] Starting MinIO on 9000/9001 ...
    start "Coffer-MinIO" /min cmd /c ""%MINIO_EXE%" server "%MINIO_DATA%" --config-dir "%MINIO_CONFIG_DIR%" --address ":9000" --console-address ":9001""
    ping -n 4 127.0.0.1 >nul
) else (
    echo [2/5] MinIO already listening on 9000 - skip.
)

REM ---------- 3) Backend (8080) ----------
set "PORT_FREE=1"
netstat -ano 2>nul | findstr /R /C:":8080" | findstr /C:"LISTENING" >nul 2>&1 && set "PORT_FREE=0"
if "!PORT_FREE!"=="1" (
    echo [3/5] Starting backend on %API_PORT% ...
    start "Coffer-Backend" "%JAVA_EXE%" -jar "%BACKEND_JAR%" --spring.profiles.active=prod
) else (
    echo [3/5] Backend already listening on %API_PORT% - skip.
)

REM ---------- wait for backend health (only if we started it) ----------
if "!PORT_FREE!"=="0" goto :fe
set "UP=NO"
set /a TRY=0

:health
set /a TRY+=1
ping -n 2 127.0.0.1 >nul
curl -s "http://localhost:%API_PORT%/actuator/health" | findstr /C:"UP" >nul 2>&1 && set "UP=YES"
if "!UP!"=="YES" goto :backend_up
if %TRY% LSS 45 goto :health
echo [WARN] Backend not UP within 45s - check the Coffer-Backend window.
goto :fe

:backend_up
echo [OK] Backend UP: http://localhost:%API_PORT%

:fe
REM ---------- 4) Frontend deps (first run) ----------
if not exist "%FRONTEND_DIR%\node_modules" (
    echo [4/5] Installing frontend dependencies - first run, needs network ...
    pushd "%FRONTEND_DIR%"
    call npm install
    popd
)

REM ---------- 5) Frontend dev server (5173) ----------
echo [5/5] Starting frontend on %FE_PORT% ...
if not exist "%FRONTEND_DIR%\node_modules" (
    echo [FATAL] npm install did not complete; frontend cannot start.
    pause
    exit /b 1
)
pushd "%FRONTEND_DIR%"
start "Coffer-Frontend" cmd /k "npm run dev"
popd

REM ---------- open browser ----------
ping -n 9 127.0.0.1 >nul
echo.
echo  ============================================
echo    Coffer is running:
echo      Frontend : http://localhost:%FE_PORT%/
echo      Backend  : http://localhost:%API_PORT%/  (Knife4j docs /doc.html)
echo    To stop services started here: double-click stop-all.bat
echo  ============================================
start "" "http://localhost:%FE_PORT%/"
endlocal
goto :eof

REM Load variables written to User/Machine scope after Explorer or this terminal started.
REM This avoids requiring a Windows restart after setting an environment variable.
:loadPersistedValue
if defined %~1 exit /b 0
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "$value = [Environment]::GetEnvironmentVariable('%~1', 'User'); if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable('%~1', 'Machine') }; if ($null -ne $value) { [Console]::Write($value) }"`) do set "%~1=%%A"
exit /b 0
