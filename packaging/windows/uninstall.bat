@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - uninstall services.
rem  Program files and data are NOT deleted automatically.
rem  You will be asked separately about deleting data\ (the
rem  database) - the default is to KEEP it.
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
net session >nul 2>&1
if errorlevel 1 ( echo [ERROR] Please run as administrator. & pause & exit /b 1 )

choice /C YN /M "Stop and remove the Iteration Board services"
if errorlevel 2 exit /b 0
"%ROOT%\app\IterationBoardApp.exe" stop >nul 2>&1
"%ROOT%\app\IterationBoardPG.exe" stop >nul 2>&1
"%ROOT%\app\IterationBoardApp.exe" uninstall
"%ROOT%\app\IterationBoardPG.exe" uninstall
schtasks /Delete /TN "IterationBoardBackup" /F >nul 2>&1
echo [INFO] Services and backup task removed.

choice /C YN /N /M "Also DELETE the database and all data in data\ ? [y/N] "
if errorlevel 2 goto :keep
if errorlevel 1 (
  choice /C YN /M "Really delete ALL data? This cannot be undone"
  if errorlevel 1 (
    rmdir /S /Q "%ROOT%\data"
    echo [INFO] data\ deleted.
    goto :done
  )
)
:keep
echo [INFO] Data kept in data\ . To reinstall later, run install.bat again in this folder.
:done
echo [INFO] Uninstall complete. You may now delete this folder manually if wanted.
pause
exit /b 0
