@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem Iteration Board - stop both services (app first, then PG).
rem Usage: stop.bat [--no-pause]
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
rem P23 (1.1.0): take one backup BEFORE stopping, while
rem PostgreSQL is still up. backup.bat in auto mode never
rem pauses and exits 0 when the services are already stopped
rem (it logs a [SKIP] line instead), and a failed backup must
rem never block the stop - so the result is not checked here.
call :log [INFO] Taking a pre-stop backup ...
call "%ROOT%\backup.bat" auto
call :log [INFO] Pre-stop backup step finished - stopping services.
net stop IterationBoardApp >nul 2>&1
if errorlevel 1 echo [WARN] IterationBoardApp stop returned an error - it may already be stopped.
rem PostgreSQL is stopped through pg_ctl: it signals the
rem postmaster directly and waits for it to exit. A plain
rem net stop on the pg_ctl-registered service proved
rem unreliable in the Windows runner verification - the
rem command reported success while the postmaster kept
rem running. net stop stays as a fallback.
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" stop -D "%ROOT%\data\pgdata" -m fast -w -t 60 >nul 2>&1
if not errorlevel 1 goto :pgstop_done
sc query IterationBoardPG >nul 2>&1
if not errorlevel 1 net stop IterationBoardPG >nul 2>&1
:pgstop_done
call :log [INFO] Services stopped.
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
