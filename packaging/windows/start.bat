@echo off
setlocal EnableExtensions
rem Iteration Board - start both services (PG first, then app).
rem Usage: start.bat [--no-pause]
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "NOPAUSE=0"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~2"=="--no-pause" set "NOPAUSE=1"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)
rem PostgreSQL is registered with pg_ctl (a real Windows service),
rem the application with WinSW; net start drives both. A service
rem that is already running makes net start return an error, so
rem the state query afterwards is the source of truth. Every
rem failure path MUST exit /b 1 - the old one-line
rem "echo ... & if ... & exit /b 1" form let the exit be swallowed
rem by the inner if and masked real failures (field round 2).
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Failed to start IterationBoardPG.
  call :ib_pause
  exit /b 1
)
rem P23 (1.1.0): if the last successful backup is more than
rem 24 hours old (or none was ever recorded), back up BEFORE
rem starting the application. PostgreSQL is already running,
rem which is all backup.bat needs. A failed backup does NOT
rem block the start; it only prints a warning.
set "IB_BK_STALE="
for /f "usebackq delims=" %%S in (`powershell -NoProfile -Command "$f = '%ROOT%\data\backup-status.json'; $stale = $true; if (Test-Path $f) { try { $j = Get-Content $f -Raw | ConvertFrom-Json; if ($j.lastSuccessTime) { if ((Get-Date).ToUniversalTime() -lt ([DateTimeOffset]::Parse($j.lastSuccessTime)).UtcDateTime.AddHours(24)) { $stale = $false } } } catch {} }; if ($stale) { 'yes' }"`) do set "IB_BK_STALE=%%S"
if "%IB_BK_STALE%"=="yes" (
  echo [INFO] Last successful backup is over 24 hours old - backing up before start ...
  call "%ROOT%\backup.bat" auto
  if errorlevel 1 echo [WARN] The pre-start backup failed. Starting anyway - run backup.bat manually later.
)
rem P01 (1.1.0): once the admin account has changed its initial
rem password, the backend clears app_user.must_change_password;
rem that flag is the ground truth, so when it is false and the
rem initial-password file still exists, delete the file here.
if exist "%ROOT%\config\admin-initial-password.txt" call :ib_pwfile_cleanup
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Failed to start IterationBoardApp. See logs folder.
  call :ib_pause
  exit /b 1
)
echo [INFO] Services started.
call :ib_pause
exit /b 0

:ib_pwfile_cleanup
rem Runs as a subroutine on purpose: the probe result must be read
rem with fresh % expansion - inside the caller's parenthesized block
rem it would expand once at block parse time (always empty) and the
rem password file would never be deleted.
set "IB_ADMIN_CHANGED="
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%ROOT%\config\application.yml"') do set "DBPWD=%%B"
if not "%DBPWD%"=="" (
  set "PGPASSWORD=%DBPWD%"
  "%ROOT%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -t -A -c "SELECT must_change_password FROM app_user WHERE login_name = 'admin'" > "%TEMP%\ib-q-changed.txt" 2>nul
  if exist "%TEMP%\ib-q-changed.txt" set /p IB_ADMIN_CHANGED=<"%TEMP%\ib-q-changed.txt"
  if exist "%TEMP%\ib-q-changed.txt" del "%TEMP%\ib-q-changed.txt"
  set "PGPASSWORD="
)
if "%IB_ADMIN_CHANGED%"=="f" (
  del /Q "%ROOT%\config\admin-initial-password.txt" >nul 2>&1
  echo [INFO] Removed config\admin-initial-password.txt - the admin password has been changed.
)
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
