@echo off
setlocal EnableExtensions
rem Iteration Board - stop both services (app first, then PG).
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  pause
  exit /b 1
)
"%ROOT%\app\IterationBoardApp.exe" stop
if errorlevel 1 echo [WARN] IterationBoardApp stop returned an error (it may already be stopped).
"%ROOT%\app\IterationBoardPG.exe" stop
if errorlevel 1 echo [WARN] IterationBoardPG stop returned an error (it may already be stopped).
echo [INFO] Services stopped.
pause
exit /b 0
