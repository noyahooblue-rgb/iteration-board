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

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
