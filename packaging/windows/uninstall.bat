@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - uninstall services.
rem  Program files and data are NOT deleted automatically.
rem  You will be asked separately about deleting data\ (the
rem  database) - the default is to KEEP it.
rem  Usage: uninstall.bat [--yes] [--no-pause] [--delete-data]
rem    --yes          skip the confirmation prompts
rem    --delete-data  also delete data\ (only with --yes, or
rem                   after two interactive confirmations)
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "YES=0"
set "NOPAUSE=0"
set "DELDATA=0"
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--yes" set "YES=1"
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~1"=="--delete-data" set "DELDATA=1"
shift
goto :parse_args
:args_done
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)

if "%YES%"=="0" (
  choice /C YN /M "Stop and remove the Iteration Board services"
  if errorlevel 2 exit /b 0
)

rem Application service (WinSW wrapper).
if exist "%ROOT%\app\IterationBoardApp.exe" (
  "%ROOT%\app\IterationBoardApp.exe" stop >nul 2>&1
  "%ROOT%\app\IterationBoardApp.exe" uninstall >nul 2>&1
) else (
  net stop IterationBoardApp >nul 2>&1
  sc delete IterationBoardApp >nul 2>&1
)

rem PostgreSQL service: pg_ctl registration since 1.0.3; older
rem installs used a WinSW wrapper - remove either form, and fall
rem back to sc delete if a registration is left over.
if exist "%ROOT%\app\IterationBoardPG.exe" (
  "%ROOT%\app\IterationBoardPG.exe" stop >nul 2>&1
  "%ROOT%\app\IterationBoardPG.exe" uninstall >nul 2>&1
  del /Q "%ROOT%\app\IterationBoardPG.exe" >nul 2>&1
  del /Q "%ROOT%\app\IterationBoardPG.xml" >nul 2>&1
)
net stop IterationBoardPG >nul 2>&1
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" unregister -N IterationBoardPG >nul 2>&1
sc query IterationBoardPG >nul 2>&1
if not errorlevel 1 sc delete IterationBoardPG >nul 2>&1

schtasks /Delete /TN "IterationBoardBackup" /F >nul 2>&1
echo [INFO] Services and backup task removed.

if "%YES%"=="1" (
  if "%DELDATA%"=="1" goto :delete_data
  goto :keep
)
choice /C YN /N /M "Also DELETE the database and all data in data\ ? [y/N] "
if errorlevel 2 goto :keep
choice /C YN /M "Really delete ALL data? This cannot be undone"
if errorlevel 2 goto :keep
:delete_data
rmdir /S /Q "%ROOT%\data"
echo [INFO] data\ deleted.
goto :done
:keep
echo [INFO] Data kept in data\ . To reinstall later, run install.bat again in this folder.
:done
echo [INFO] Uninstall complete. You may now delete this folder manually if wanted.
call :ib_pause
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
