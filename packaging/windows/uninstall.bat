@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
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
rem --- IB unified contract: automation hooks + anti-crash shell --
rem IB_AUTO=1 (CI): no pause, no prompts, no cmd /k wrapper; the
rem exit code and this script's log file are the contract.
rem IB_YES=1: confirmations default to yes. A plain double-click
rem run is re-launched once under cmd /k (IB_WRAPPED) so the
rem window stays open on any error instead of flashing away.
if "%IB_AUTO%"=="1" set "NOPAUSE=1"
if "%IB_AUTO%"=="1" set "YES=1"
if "%IB_YES%"=="1" set "YES=1"
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

if "%YES%"=="1" goto :confirm_done
choice /C YN /M "Stop and remove the Iteration Board services"
if errorlevel 2 exit /b 0
:confirm_done

rem Application service (WinSW wrapper).
if not exist "%ROOT%\app\IterationBoardApp.exe" goto :app_svc_fallback
"%ROOT%\app\IterationBoardApp.exe" stop >nul 2>&1
"%ROOT%\app\IterationBoardApp.exe" uninstall >nul 2>&1
goto :app_svc_done
:app_svc_fallback
net stop IterationBoardApp >nul 2>&1
sc delete IterationBoardApp >nul 2>&1
:app_svc_done

rem PostgreSQL service: pg_ctl registration since 1.0.3; older
rem installs used a WinSW wrapper - remove either form, and fall
rem back to sc delete if a registration is left over.
if not exist "%ROOT%\app\IterationBoardPG.exe" goto :oldpg_done
"%ROOT%\app\IterationBoardPG.exe" stop >nul 2>&1
"%ROOT%\app\IterationBoardPG.exe" uninstall >nul 2>&1
del /Q "%ROOT%\app\IterationBoardPG.exe" >nul 2>&1
del /Q "%ROOT%\app\IterationBoardPG.xml" >nul 2>&1
:oldpg_done
net stop IterationBoardPG >nul 2>&1
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" unregister -N IterationBoardPG >nul 2>&1
sc query IterationBoardPG >nul 2>&1
if not errorlevel 1 sc delete IterationBoardPG >nul 2>&1

schtasks /Delete /TN "IterationBoardBackup" /F >nul 2>&1
call :log [INFO] Services and backup task removed.

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
call :log [INFO] data\ deleted.
goto :done
:keep
call :log [INFO] Data kept in data\ . To reinstall later, run install.bat again in this folder.
:done
call :log [INFO] Uninstall complete. You may now delete this folder manually if wanted.
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
