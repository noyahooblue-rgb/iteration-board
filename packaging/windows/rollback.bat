@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - rollback to the last upgrade (1.1.0)
rem  Usage: rollback.bat [bundle-folder-name] [--yes] [--no-pause]
rem  upgrade.bat and apply-patch.bat record every run in a
rem  bundle folder inside the configured backup directory:
rem    <backup-dir>\upgrade-<yyyyMMdd-HHmmss>\
rem      upgrade-info.txt   DbBackup / target / versions
rem      files\             copies of the replaced program files
rem  This script restores BOTH sides from the newest bundle
rem  (or the named one): program files from files\, and the
rem  database by calling restore.bat with the recorded
rem  pre-upgrade database backup. restore.bat takes its own
rem  safety backup first, so a rollback can itself be undone.
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
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)

rem --- resolve the backup directory (config backup.dir, 1.1.0) -
set "BKROOT=%ROOT%\backup"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%ROOT%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\"([^\"]*)\"') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
if not "%BKROOT%"=="" if not "%BKROOT:~1,1%"==":" set "BKROOT=%ROOT%\%BKROOT%"
if "%BKROOT%"=="" set "BKROOT=%ROOT%\backup"

rem --- pick the bundle -----------------------------------------
if "%SEL%"=="" (
  for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "Get-ChildItem '%BKROOT%' -Directory -Filter 'upgrade-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { $_.Name }"`) do set "SEL=%%D"
)
if "%SEL%"=="" (
  echo [ERROR] No upgrade bundle found in %BKROOT%
  echo [ERROR] rollback.bat only works after upgrade.bat or apply-patch.bat
  echo [ERROR] from version 1.1.0 on has recorded a bundle there.
  call :ib_pause
  exit /b 1
)
set "BUNDLE=%BKROOT%\%SEL%"
if not exist "%BUNDLE%\upgrade-info.txt" (
  echo [ERROR] %BUNDLE%\upgrade-info.txt not found - not a rollback bundle.
  call :ib_pause
  exit /b 1
)
set "DBBACKUP="
for /f "tokens=1,2 delims==" %%A in ('findstr /C:"DbBackup=" "%BUNDLE%\upgrade-info.txt"') do set "DBBACKUP=%%B"
echo [INFO] Rollback bundle: %BUNDLE%
echo [INFO] Database backup to restore: %DBBACKUP%
if "%YES%"=="0" (
  choice /C YN /M "Roll back program files and database to this pre-upgrade state now"
  if errorlevel 2 exit /b 0
)

rem --- stop the application (keep PostgreSQL up for restore) ---
net stop IterationBoardApp >nul 2>&1

rem --- restore program files ------------------------------------
if exist "%BUNDLE%\files" (
  echo [INFO] Restoring program files from the bundle ...
  robocopy "%BUNDLE%\files" "%ROOT%" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul
  if errorlevel 8 (
    echo [ERROR] Program file restore failed.
    call :ib_pause
    exit /b 1
  )
)

rem --- restore the database via restore.bat ----------------------
rem restore.bat starts PostgreSQL if needed through its own
rem flow, takes a safety backup of the current state, restores
rem the recorded backup and starts the application again.
if "%DBBACKUP%"=="" (
  echo [WARN] The bundle records no database backup - program files
  echo [WARN] were restored, the database was left as it is.
  net start IterationBoardApp >nul 2>&1
  call :ib_pause
  exit /b 0
)
echo [INFO] Restoring the database from %DBBACKUP% ...
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL could not be started, database restore is impossible.
  call :ib_pause
  exit /b 1
)
call "%ROOT%\restore.bat" "%DBBACKUP%" --yes --no-pause
if errorlevel 1 (
  echo [ERROR] Database restore failed - see the messages above.
  echo [ERROR] Program files were already rolled back.
  call :ib_pause
  exit /b 1
)
echo [INFO] Rollback complete: program files and database are back
echo [INFO] to the state recorded in %SEL%.
call :ib_pause
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
