@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem ============================================================
rem  Iteration Board - rollback to the last upgrade (1.1.0)
rem  Usage: rollback.bat [bundle-folder-name] [--yes] [--no-pause]
rem  upgrade.bat and apply-patch.bat record every run in a
rem  bundle folder inside the configured backup directory:
rem    <backup-dir>\upgrade-<yyyyMMdd-HHmmss>\
rem      upgrade-info.txt   DbBackup / target / versions
rem      files\             copies of the replaced program files
rem  This script restores BOTH sides from the newest bundle
rem  (or the named one): the database FIRST, by calling
rem  restore.bat with the recorded pre-upgrade database
rem  backup, then the program files from files\. The order
rem  is load-bearing (root-cause ledger #14): restore.bat
rem  must run while the CURRENT scripts are still in place -
rem  restoring files first would replace it with the bundle's
rem  old copy, which cannot resolve the recorded backup path.
rem  restore.bat takes its own safety backup first, so a
rem  rollback can itself be undone.
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "SEL="
set "YES=0"
set "NOPAUSE=0"
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--yes" set "YES=1"
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i not "%~1"=="--yes" if /i not "%~1"=="--no-pause" set "SEL=%~1"
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

rem --- resolve the backup directory (config backup.dir, 1.1.0) -
set "BKROOT=%ROOT%\backup"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%ROOT%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\x22([^\x22]*)\x22') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
if not "%BKROOT%"=="" if not "%BKROOT:~1,1%"==":" set "BKROOT=%ROOT%\%BKROOT%"
if "%BKROOT%"=="" set "BKROOT=%ROOT%\backup"

rem --- pick the bundle -----------------------------------------
if not "%SEL%"=="" goto :sel_done
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "Get-ChildItem '%BKROOT%' -Directory -Filter 'upgrade-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { $_.Name }"`) do set "SEL=%%D"
:sel_done
if not "%SEL%"=="" goto :sel_ok
call :log [ERROR] No upgrade bundle found in %BKROOT%
call :log [ERROR] rollback.bat only works after upgrade.bat or apply-patch.bat
call :log [ERROR] from version 1.1.0 on has recorded a bundle there.
call :ib_pause
exit /b 1
:sel_ok
set "BUNDLE=%BKROOT%\%SEL%"
if exist "%BUNDLE%\upgrade-info.txt" goto :bundle_ok
call :log [ERROR] "%BUNDLE%\upgrade-info.txt" not found - not a rollback bundle.
call :ib_pause
exit /b 1
:bundle_ok
set "DBBACKUP="
for /f "tokens=1,2 delims==" %%A in ('findstr /C:"DbBackup=" "%BUNDLE%\upgrade-info.txt"') do set "DBBACKUP=%%B"
call :log [INFO] Rollback bundle: %BUNDLE%
call :log [INFO] Database backup to restore: %DBBACKUP%
if "%YES%"=="1" goto :confirm_done
choice /C YN /M "Roll back program files and database to this pre-upgrade state now"
if errorlevel 2 exit /b 0
:confirm_done

rem --- stop the application (keep PostgreSQL up for restore) ---
net stop IterationBoardApp >nul 2>&1

rem --- restore the database FIRST, via restore.bat ---------------
rem (Ledger #14: see the header. IB_NO_APP_START keeps
rem restore.bat from booting the still-NEW program files
rem against the just-restored OLD database - its Flyway would
rem migrate the old database straight back up before the
rem program files are restored.)
if "%DBBACKUP%"=="" goto :db_skipped
call :log [INFO] Restoring the database from %DBBACKUP% ...
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 goto :db_nopg
set "IB_NO_APP_START=1"
call "%ROOT%\restore.bat" "%DBBACKUP%" --yes --no-pause
set "IBRC=%ERRORLEVEL%"
set "IB_NO_APP_START="
if not "%IBRC%"=="0" goto :db_fail
goto :db_done
:db_skipped
echo [WARN] The bundle records no database backup - the database
echo [WARN] is left as it is; only program files are rolled back.
goto :db_done
:db_nopg
echo [ERROR] PostgreSQL could not be started, database restore is impossible.
echo [ERROR] Nothing was changed - program files were not restored yet.
call :ib_pause
exit /b 1
:db_fail
echo [ERROR] Database restore failed - see the messages above.
echo [ERROR] Program files were NOT restored, and restore.bat loads
echo [ERROR] atomically after taking a safety backup, so the
echo [ERROR] installation is not half-rolled-back. The application
echo [ERROR] service is stopped; fix the problem and run
echo [ERROR] rollback.bat again.
call :ib_pause
exit /b 1
:db_done

rem --- restore program files ------------------------------------
rem restore.bat was told not to start the application; stop it
rem once more defensively in case an older restore.bat in the
rem target ignored the request and started it.
net stop IterationBoardApp >nul 2>&1
if not exist "%BUNDLE%\files" goto :files_done
call :log [INFO] Restoring program files from the bundle ...
robocopy "%BUNDLE%\files" "%ROOT%" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul
if errorlevel 8 goto :files_fail
rem If the pre-upgrade install had no version.txt (1.0.4 /
rem 1.0.1), the bundle holds none either, and robocopy does
rem not delete files: remove the version.txt the patch
rem wrote, or the rolled-back install keeps reporting the
rem new version and the version gate refuses later patches.
if not exist "%BUNDLE%\files\version.txt" del "%ROOT%\version.txt" >nul 2>&1
goto :files_done
:files_fail
call :log [ERROR] Program file restore failed. The database was
call :log [ERROR] already restored; fix the problem and run
call :log [ERROR] rollback.bat again to finish the file restore.
call :ib_pause
exit /b 1
:files_done

rem --- start the rolled-back application -------------------------
net start IterationBoardApp >nul 2>&1
call :log [INFO] Rollback complete: program files and database are back
call :log [INFO] to the state recorded in %SEL%.
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
