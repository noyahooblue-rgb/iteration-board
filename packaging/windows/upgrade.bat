@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - upgrade (design 10.4)
rem  Run this script from the NEW package folder, as admin:
rem      upgrade.bat [existing-install-folder] [--yes] [--no-pause]
rem  Default target is this folder itself (in-place overlay).
rem  Flow: full backup -> stop services -> keep old jar ->
rem  replace program files -> start services (Flyway migrates
rem  the database automatically on startup).
rem  config\ and data\ of the target are NEVER touched.
rem  If the new version fails to start, the old jar is put
rem  back automatically (database migrations already applied
rem  by Flyway are backward compatible within a release line;
rem  for a full rollback use restore.bat with the backup).
rem ============================================================
set "NEWROOT=%~dp0"
if "%NEWROOT:~-1%"=="\" set "NEWROOT=%NEWROOT:~0,-1%"
set "TARGET="
set "YES=0"
set "NOPAUSE=0"
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--yes" set "YES=1"
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i not "%~1"=="--yes" if /i not "%~1"=="--no-pause" set "TARGET=%~1"
shift
goto :parse_args
:args_done
if "%TARGET%"=="" set "TARGET=%NEWROOT%"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)
if not exist "%TARGET%\app\iteration-board.jar" (
  echo [ERROR] Target install not found: %TARGET%
  call :ib_pause
  exit /b 1
)
if not exist "%NEWROOT%\app\iteration-board.jar" (
  echo [ERROR] New package jar not found in %NEWROOT%\app
  call :ib_pause
  exit /b 1
)

if "%YES%"=="0" (
  choice /C YN /M "Upgrade %TARGET% now"
  if errorlevel 2 exit /b 0
)

rem Back up BEFORE stopping anything: pg_dump needs PostgreSQL
rem running, and since 1.0.3 backup.bat skips (exit 0) when the
rem service is stopped - stopping first would silently skip the
rem safety backup this step exists to take.
echo [INFO] Backing up target - database, uploads and config ...
pushd "%TARGET%"
call "%TARGET%\backup.bat" auto
set "IBRC=%ERRORLEVEL%"
popd
if not "%IBRC%"=="0" (
  echo [ERROR] Backup failed. Upgrade aborted, nothing changed.
  call :ib_pause
  exit /b 1
)

echo [INFO] Stopping services on target ...
net stop IterationBoardApp >nul 2>&1
net stop IterationBoardPG >nul 2>&1

echo [INFO] Saving current program files ...
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
mkdir "%TARGET%\backup\program-%TS%" >nul 2>&1
copy /Y "%TARGET%\app\iteration-board.jar" "%TARGET%\backup\program-%TS%\iteration-board.jar" >nul
if exist "%TARGET%\config\prompts" robocopy "%TARGET%\config\prompts" "%TARGET%\backup\program-%TS%\prompts" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul

echo [INFO] Replacing program files - config\ and data\ untouched ...
copy /Y "%NEWROOT%\app\iteration-board.jar" "%TARGET%\app\iteration-board.jar" >nul
if errorlevel 1 (
  echo [ERROR] Jar copy failed.
  goto :rollback
)
if exist "%NEWROOT%\config\prompts" robocopy "%NEWROOT%\config\prompts" "%TARGET%\config\prompts" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul

echo [INFO] Starting services - database migration runs automatically ...
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL failed to start.
  goto :rollback
)
timeout /T 3 /NOBREAK >nul
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Application failed to start.
  goto :rollback
)
rem --- Health check: read the app port from the target config ---
rem (config\application.yml, server.port; default 18080 if
rem  unreadable. The port is NOT hardcoded - the installed
rem  config is the source of truth.) Poll for up to 90 seconds:
rem  a single attempt right after the service start is not a
rem  real health check, the application needs its boot time.
set "APP_PORT=18080"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
echo [INFO] Health check on http://127.0.0.1:%APP_PORT%/ - port read from config\application.yml, polling up to 90 seconds ...
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(90); $ok = $false; while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 5; $ok = $true; break } catch { if ($_.Exception.Response) { $ok = $true; break } else { Start-Sleep -Seconds 3 } } }; if ($ok) { exit 0 } else { exit 1 }"
if errorlevel 1 (
  echo [WARN] Application service started but the HTTP check did not respond.
  echo [WARN] Check %TARGET%\logs and the app port in config\application.yml,
  echo [WARN] then decide: keep waiting, or roll back by copying back
  echo [WARN] backup\program-%TS%\iteration-board.jar manually.
  call :ib_pause
  exit /b 1
)
echo [INFO] Upgrade complete. Old program kept in backup\program-%TS%\
call :ib_pause
exit /b 0

:rollback
echo [INFO] Rolling back program files ...
net stop IterationBoardApp >nul 2>&1
copy /Y "%TARGET%\backup\program-%TS%\iteration-board.jar" "%TARGET%\app\iteration-board.jar" >nul
net start IterationBoardPG >nul 2>&1
net start IterationBoardApp >nul 2>&1
echo [ERROR] Upgrade failed and program files were rolled back.
echo         Database: if the new version already migrated it, restore
echo         the pre-upgrade backup with restore.bat.
call :ib_pause
exit /b 1

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
