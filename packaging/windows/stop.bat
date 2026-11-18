@echo off
setlocal EnableExtensions
rem Iteration Board - stop both services (app first, then PG).
rem Usage: stop.bat [--no-pause]
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
net stop IterationBoardApp >nul 2>&1
if errorlevel 1 echo [WARN] IterationBoardApp stop returned an error - it may already be stopped.
rem PostgreSQL is stopped through pg_ctl: it signals the
rem postmaster directly and waits for it to exit. A plain
rem net stop on the pg_ctl-registered service proved
rem unreliable in the Windows runner verification - the
rem command reported success while the postmaster kept
rem running. net stop stays as a fallback.
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" stop -D "%ROOT%\data\pgdata" -m fast -w -t 60 >nul 2>&1
if errorlevel 1 (
  sc query IterationBoardPG >nul 2>&1
  if not errorlevel 1 net stop IterationBoardPG >nul 2>&1
)
echo [INFO] Services stopped.
call :ib_pause
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
