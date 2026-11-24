@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem Iteration Board - start both services (PG first, then app).
rem Usage: start.bat [--no-pause]
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "NOPAUSE=0"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~2"=="--no-pause" set "NOPAUSE=1"
rem --- IB unified contract: automation hooks + anti-crash shell --
rem IB_AUTO=1 (CI): no pause, no prompts, no cmd /k wrapper; the
rem exit code and this script's log file are the contract.
rem IB_YES=1: confirmations default to yes. A plain double-click
rem run is re-launched once under cmd /k (IB_WRAPPED) so the
rem window stays open on any error instead of flashing away.
if "%IB_AUTO%"=="1" set "NOPAUSE=1"
if defined IB_WRAPPED goto :ib_wrap_done
if "%IB_AUTO%"=="1" goto :ib_wrap_done
if "%NOPAUSE%"=="1" goto :ib_wrap_done
set "IB_WRAPPED=1"
cmd /k ""%~f0" %IB_ARGS%"
exit /b
:ib_wrap_done
rem --- IB unified contract: log file next to this script --------
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "IB_TS=%%T"
if "%IB_TS%"=="" set "IB_TS=run"
set "IB_LOG=%~dp0%~n0-%IB_TS%.log"
call :log [INFO] %~n0 started - log file: %IB_LOG%
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"
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
if not "%IB_BK_STALE%"=="yes" goto :bk_done
call :log [INFO] Last successful backup is over 24 hours old - backing up before start ...
call "%ROOT%\backup.bat" auto
if errorlevel 1 echo [WARN] The pre-start backup failed. Starting anyway - run backup.bat manually later.
:bk_done
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Failed to start IterationBoardApp. See logs folder.
  call :ib_pause
  exit /b 1
)
call :log [INFO] Services started.
call :ib_pause
exit /b 0

rem --- subroutine: self-elevate with the original arguments -----
rem Reached by goto from the admin check. The elevated instance
rem inherits IB_ELEVATED=1 (loop guard) and IB_WRAPPED, starts in
rem the script folder (Start-Process WorkingDirectory plus the
rem cd /d after the admin check), and this instance exits 0.
rem Arguments travel through the IB_ARGS / IB_SELF / IB_DIR
rem environment variables only - never string-built into the
rem PowerShell command together with user data.
:ib_elevate
if "%IB_ELEVATED%"=="1" goto :ib_elevate_fail
set "IB_ELEVATED=1"
set "IB_SELF=%~f0"
set "IB_DIR=%~dp0"
powershell -NoProfile -Command "Start-Process -FilePath $env:IB_SELF -ArgumentList $env:IB_ARGS -Verb RunAs -WorkingDirectory $env:IB_DIR"
if errorlevel 1 goto :ib_elevate_fail
exit /b 0
:ib_elevate_fail
call :log [ERROR] Please run as administrator - auto-elevation failed
call :log [ERROR] or was declined. Right-click the script and choose
call :log [ERROR] Run as administrator instead.
call :ib_pause
exit /b 1

:ib_pause
if defined IB_LOG echo [INFO] Log file: %IB_LOG%
if "%NOPAUSE%"=="0" pause
exit /b 0

rem --- subroutine: write a line to console AND the script log ---
:log
echo %*
if defined IB_LOG echo %*>>"%IB_LOG%"
exit /b 0
