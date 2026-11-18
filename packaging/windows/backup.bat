@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - backup (design 10.4)
rem  Creates backup\<yyyyMMdd-HHmmss>\ with:
rem    db.dump        pg_dump custom-format dump
rem    uploads\       uploaded image files (if the folder exists)
rem    application.yml  config copy (contains the DB password -
rem                     keep backup folders private!)
rem  Backups older than 30 days are deleted.
rem  Runs unattended from Task Scheduler as SYSTEM, and can
rem  also be run manually (no pause in scheduled mode: pass
rem  the argument "auto").
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
if not exist "%ROOT%\backup" mkdir "%ROOT%\backup"
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"

rem --- the services must be running ----------------------------
rem Since 1.0.3 both services default to MANUAL start, so at
rem 02:00 they may well be stopped. The scheduled run must never
rem start services on its own and must not end with an error
rem code: log a skip line and exit 0. A manual run
rem tells the user to start the services first.
sc query IterationBoardPG >nul 2>&1
if errorlevel 1 goto :svc_skip
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 goto :svc_skip
goto :svc_ok
:svc_skip
echo %DATE% %TIME% [SKIP] PostgreSQL service is not running; backup skipped. >> "%ROOT%\logs\backup.log"
if "%~1"=="auto" exit /b 0
echo [INFO] The PostgreSQL service is not running, so there is nothing to back up.
echo [INFO] Start the services first - run start.bat - then run backup.bat again.
pause
exit /b 0
:svc_ok

rem --- read DB password from config\application.yml -----------
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%ROOT%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" (
  echo [ERROR] Could not read DB password from config\application.yml >> "%ROOT%\logs\backup.log"
  exit /b 1
)

for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "DEST=%ROOT%\backup\%TS%"
mkdir "%DEST%"
set "PGPASSWORD=%DBPWD%"
"%ROOT%\runtime\pgsql\bin\pg_dump.exe" -h 127.0.0.1 -p 15432 -U postgres -Fc -f "%DEST%\db.dump" iteration_board
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [ERROR] pg_dump failed with code %IBRC% >> "%ROOT%\logs\backup.log"
  if not "%~1"=="auto" pause
  exit /b 1
)
if exist "%ROOT%\data\uploads" (
  robocopy "%ROOT%\data\uploads" "%DEST%\uploads" /E /NFL /NDL /NJH /NJS >nul
  if errorlevel 8 (
    echo [ERROR] uploads copy failed. >> "%ROOT%\logs\backup.log"
    exit /b 1
  )
)
copy /Y "%ROOT%\config\application.yml" "%DEST%\application.yml" >nul
echo %DATE% %TIME% backup OK: %DEST% >> "%ROOT%\logs\backup.log"

rem --- keep only the last 30 days ------------------------------
powershell -NoProfile -Command "Get-ChildItem '%ROOT%\backup' -Directory | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } | Remove-Item -Recurse -Force"
echo [INFO] Backup complete: %DEST%
if not "%~1"=="auto" pause
exit /b 0
