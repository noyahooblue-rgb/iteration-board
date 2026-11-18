@echo off
setlocal EnableExtensions
rem Iteration Board - start both services (PG first, then app).
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  if not "%~1"=="--no-pause" pause
  exit /b 1
)
"%ROOT%\app\IterationBoardPG.exe" start
if errorlevel 1 ( echo [ERROR] Failed to start IterationBoardPG. & if not "%~1"=="--no-pause" pause & exit /b 1 )
"%ROOT%\app\IterationBoardApp.exe" start
if errorlevel 1 ( echo [ERROR] Failed to start IterationBoardApp. See logs folder. & if not "%~1"=="--no-pause" pause & exit /b 1 )
echo [INFO] Services started.
if not "%~1"=="--no-pause" pause
exit /b 0
