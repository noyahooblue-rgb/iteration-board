@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem ============================================================
rem  Iteration Board - restore from a backup folder (10.4).
rem  Usage: restore.bat [backup-folder-name] [--yes] [--no-pause]
rem  Without a name argument, lists backup\<...>\ folders and asks.
rem  WARNING: restore REPLACES the current database content.
rem  A safety backup of the current state is taken first.
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

rem --- Install path character warning (ruling A) --------------
rem PostgreSQL on Windows cannot run reliably from a path with
rem non-English characters (root-cause ledger #6). An existing
rem installation that already runs from such a path is NOT
rem blocked here - blocking would strand the installation.
rem Warn only, and recommend a future move to an English path.
set "IB_ROOT=%ROOT%"
powershell -NoProfile -Command "if ($env:IB_ROOT -match '[^\x20-\x7E]') { exit 1 } else { exit 0 }"
set "IB_PATH_RC=%ERRORLEVEL%"
if "%IB_PATH_RC%"=="0" goto :ib_pathwarn_done
  echo [WARN] The install folder path contains non-English characters: %ROOT%.
  echo [WARN] PostgreSQL can fail on such a path on some systems. This operation
  echo [WARN] continues because the existing installation already runs from this
  echo [WARN] folder, but moving the installation to a path with only
  echo [WARN] English letters, digits and spaces, for example
  echo [WARN] D:\IterationBoard, is recommended for the future.
:ib_pathwarn_done

rem --- resolve the backup directory (config backup.dir, 1.1.0) -
set "BKROOT=%ROOT%\backup"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%ROOT%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\x22([^\x22]*)\x22') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
if not "%BKROOT%"=="" if not "%BKROOT:~1,1%"==":" set "BKROOT=%ROOT%\%BKROOT%"
if "%BKROOT%"=="" set "BKROOT=%ROOT%\backup"

rem SEL can be typed by the user; the empty tests below use a
rem sentinel suffix so a trailing backslash in the typed value
rem cannot break the if comparison (check-bat R8).
if not "%SEL%x"=="x" goto :sel_done
echo Available backups in %BKROOT% :
dir /B /AD "%BKROOT%" 2>nul
set /p "SEL=Type the backup folder name to restore: "
:sel_done
if "%SEL%x"=="x" (
  echo [ERROR] No backup selected.
  call :ib_pause
  exit /b 1
)
set "SRC=%BKROOT%\%SEL%"
if exist "%SEL%\db.dump" set "SRC=%SEL%"
if exist "%SRC%\db.dump" goto :src_ok
call :log [ERROR] "%SRC%\db.dump" not found.
call :ib_pause
exit /b 1
:src_ok

call :log [INFO] Selected backup: %SEL%
if "%YES%"=="1" goto :confirm_done
choice /C YN /M "Restore the selected backup now? Current data will be replaced"
if errorlevel 2 exit /b 0
:confirm_done

call :log [INFO] Taking a safety backup of the current state first ...
call "%ROOT%\backup.bat" auto
if errorlevel 1 (
  echo [ERROR] Safety backup failed. Restore aborted.
  call :ib_pause
  exit /b 1
)

call :log [INFO] Stopping application service ...
net stop IterationBoardApp >nul 2>&1

set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%ROOT%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" (
  echo [ERROR] Could not read DB password from config.
  call :ib_pause
  exit /b 1
)
set "PGPASSWORD=%DBPWD%"
"%ROOT%\runtime\pgsql\bin\pg_restore.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board --clean --if-exists "%SRC%\db.dump"
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [ERROR] pg_restore failed with code %IBRC%. The database may be inconsistent;
  echo         restore the safety backup that was just created, or check logs.
  call :ib_pause
  exit /b 1
)
if not exist "%SRC%\uploads" goto :uploads_done
if not exist "%ROOT%\data\uploads" mkdir "%ROOT%\data\uploads"
robocopy "%SRC%\uploads" "%ROOT%\data\uploads" /E /NFL /NDL /NJH /NJS >nul
if not errorlevel 8 goto :uploads_done
call :log [ERROR] uploads restore failed.
call :ib_pause
exit /b 1
:uploads_done
call :log [INFO] Starting application service ...
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Application failed to start after restore. See logs folder.
  call :ib_pause
  exit /b 1
)
call :log [INFO] Restore complete: %SEL%
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
