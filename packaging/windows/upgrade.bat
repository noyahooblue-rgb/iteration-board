@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - upgrade (design 10.4)
rem  Run this script from the NEW package folder, as admin:
rem      upgrade.bat [existing-install-folder]
rem  Default target is this folder itself (in-place overlay).
rem  Flow: stop services -> full backup -> keep old jar ->
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
set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=%NEWROOT%"
net session >nul 2>&1
if errorlevel 1 ( echo [ERROR] Please run as administrator. & pause & exit /b 1 )
if not exist "%TARGET%\app\iteration-board.jar" ( echo [ERROR] Target install not found: %TARGET% & pause & exit /b 1 )
if not exist "%NEWROOT%\app\iteration-board.jar" ( echo [ERROR] New package jar not found in %NEWROOT%\app & pause & exit /b 1 )

choice /C YN /M "Upgrade %TARGET% now"
if errorlevel 2 exit /b 0

echo [INFO] Stopping services on target ...
"%TARGET%\app\IterationBoardApp.exe" stop >nul 2>&1
"%TARGET%\app\IterationBoardPG.exe" stop >nul 2>&1

echo [INFO] Backing up target (database + uploads + config) ...
pushd "%TARGET%"
call "%TARGET%\backup.bat" auto
set "IBRC=%ERRORLEVEL%"
popd
if not "%IBRC%"=="0" ( echo [ERROR] Backup failed. Upgrade aborted, nothing changed. & pause & exit /b 1 )

echo [INFO] Saving current program files ...
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
mkdir "%TARGET%\backup\program-%TS%" >nul 2>&1
copy /Y "%TARGET%\app\iteration-board.jar" "%TARGET%\backup\program-%TS%\iteration-board.jar" >nul
if exist "%TARGET%\config\prompts" robocopy "%TARGET%\config\prompts" "%TARGET%\backup\program-%TS%\prompts" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul

echo [INFO] Replacing program files (config\ and data\ untouched) ...
copy /Y "%NEWROOT%\app\iteration-board.jar" "%TARGET%\app\iteration-board.jar" >nul
if errorlevel 1 ( echo [ERROR] Jar copy failed. & goto :rollback )
if exist "%NEWROOT%\config\prompts" robocopy "%NEWROOT%\config\prompts" "%TARGET%\config\prompts" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul

echo [INFO] Starting services (database migration runs automatically) ...
"%TARGET%\app\IterationBoardPG.exe" start
if errorlevel 1 ( echo [ERROR] PostgreSQL failed to start. & goto :rollback )
timeout /T 3 /NOBREAK >nul
"%TARGET%\app\IterationBoardApp.exe" start
if errorlevel 1 ( echo [ERROR] Application failed to start. & goto :rollback )
timeout /T 10 /NOBREAK >nul
rem --- Health check: read the app port from the target config ---
rem (config\application.yml, server.port; default 8080 if unreadable.
rem  The port is NOT hardcoded - the installed config is the source of truth.)
set "APP_PORT=8080"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
echo [INFO] Health check on http://127.0.0.1:%APP_PORT%/ (port read from config\application.yml) ...
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 10; exit 0 } catch { if ($_.Exception.Response) { exit 0 } else { exit 1 } }"
if errorlevel 1 (
  echo [WARN] Application service started but HTTP check did not respond.
  echo [WARN] Check %TARGET%\logs and the app port in config\application.yml,
  echo [WARN] then decide: keep waiting, or run this script's rollback by
  echo [WARN] copying back backup\program-%TS%\iteration-board.jar manually.
  pause
  exit /b 1
)
echo [INFO] Upgrade complete. Old program kept in backup\program-%TS%\
pause
exit /b 0

:rollback
echo [INFO] Rolling back program files ...
"%TARGET%\app\IterationBoardApp.exe" stop >nul 2>&1
copy /Y "%TARGET%\backup\program-%TS%\iteration-board.jar" "%TARGET%\app\iteration-board.jar" >nul
"%TARGET%\app\IterationBoardPG.exe" start >nul 2>&1
"%TARGET%\app\IterationBoardApp.exe" start >nul 2>&1
echo [ERROR] Upgrade failed and program files were rolled back.
echo         Database: if the new version already migrated it, restore
echo         the pre-upgrade backup with restore.bat.
pause
exit /b 1
