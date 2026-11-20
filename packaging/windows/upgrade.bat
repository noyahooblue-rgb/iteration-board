@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - upgrade (design 10.4, 1.1.0 update)
rem  Run this script from the NEW package folder, as admin:
rem      upgrade.bat [existing-install-folder] [--yes] [--no-pause]
rem  Default target is this folder itself (in-place overlay).
rem  Flow (1.1.0):
rem    1. database backup into the CONFIGURED backup directory
rem       (config backup.dir on the target) - the upgrade aborts
rem       if no fresh backup folder with db.dump appears;
rem    2. copies of the files about to be replaced, plus an
rem       upgrade-info.txt, are recorded in the bundle folder
rem       <backup-dir>\upgrade-<yyyyMMdd-HHmmss>\ ;
rem    3. services stop, program files are replaced, services
rem       start (Flyway migrates the database automatically);
rem    4. health check - on failure the log tails are printed
rem       and the exact rollback command is shown. Nothing is
rem       silently rolled back: run rollback.bat in the target
rem       folder to restore program files AND the database
rem       from the bundle recorded in step 2;
rem    5. the application itself creates the built-in "admin"
rem       account on first start if none exists and writes the
rem       initial password to config\admin-initial-password.txt
rem       on the target; this script shows that file after the
rem       health check when it exists. Existing platform admins
rem       are never touched.
rem  config\ and data\ of the target are NEVER overwritten.
rem ============================================================
set "NEWROOT=%~dp0"
if "%NEWROOT:~-1%"=="\" set "NEWROOT=%NEWROOT:~0,-1%"
set "TARGET="
set "YES=0"
set "NOPAUSE=0"
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--yes" set "YES=1"
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i not "%~1"=="--yes" if /i not "%~1"=="--no-pause" set "TARGET=%~1"
shift
goto :parse_args
:args_done
if "%TARGET%"=="" set "TARGET=%NEWROOT%"
if not exist "%TARGET%\tmp" mkdir "%TARGET%\tmp"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)
if not exist "%TARGET%\app\iteration-board.jar" (
  echo [ERROR] Target install not found: %TARGET%
  call :ib_pause
  exit /b 1
)
if not exist "%NEWROOT%\app\iteration-board.jar" (
  echo [ERROR] New package jar not found in %NEWROOT%\app
  call :ib_pause
  exit /b 1
)

if "%YES%"=="0" (
  choice /C YN /M "Upgrade %TARGET% now"
  if errorlevel 2 exit /b 0
)

rem --- resolve the target backup directory (backup.dir) --------
set "BKROOT=%TARGET%\backup"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\x22([^\x22]*)\x22') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
if not "%BKROOT%"=="" if not "%BKROOT:~1,1%"==":" set "BKROOT=%TARGET%\%BKROOT%"
if "%BKROOT%"=="" set "BKROOT=%TARGET%\backup"
if not exist "%BKROOT%" mkdir "%BKROOT%"

rem --- step 1: database backup BEFORE stopping anything --------
rem pg_dump needs PostgreSQL running, and since 1.0.3 backup.bat
rem skips (exit 0) when the service is stopped - so afterwards
rem verify a FRESH backup really exists, do not trust exit 0.
echo [INFO] Backing up target database, uploads and config ...
call "%TARGET%\backup.bat" auto
if errorlevel 1 (
  echo [ERROR] Backup failed. Upgrade aborted, nothing changed.
  call :ib_pause
  exit /b 1
)
set "DBBACKUP="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$f = '%TARGET%\data\backup-status.json'; if (Test-Path $f) { try { $j = Get-Content $f -Raw | ConvertFrom-Json; if ($j.success -and $j.lastBackupPath -and (Test-Path (Join-Path $j.lastBackupPath 'db.dump')) -and ((Get-Item (Join-Path $j.lastBackupPath 'db.dump')).LastWriteTime -gt (Get-Date).AddMinutes(-15))) { $j.lastBackupPath } } catch {} }"`) do set "DBBACKUP=%%P"
if "%DBBACKUP%"=="" (
  echo [ERROR] No fresh database backup was produced - PostgreSQL is
  echo [ERROR] probably stopped, so backup.bat skipped. Start the
  echo [ERROR] services with start.bat in the target folder first,
  echo [ERROR] then run upgrade.bat again. Nothing was changed.
  call :ib_pause
  exit /b 1
)
echo [INFO] Pre-upgrade database backup: %DBBACKUP%

rem --- step 2: record the rollback bundle -----------------------
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "BUNDLE=%BKROOT%\upgrade-%TS%"
mkdir "%BUNDLE%\files\app" >nul 2>&1
copy /Y "%TARGET%\app\iteration-board.jar" "%BUNDLE%\files\app\iteration-board.jar" >nul
if exist "%TARGET%\config\prompts" robocopy "%TARGET%\config\prompts" "%BUNDLE%\files\config\prompts" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul
for %%F in (install start stop backup restore upgrade uninstall) do if exist "%TARGET%\%%F.bat" copy /Y "%TARGET%\%%F.bat" "%BUNDLE%\files\%%F.bat" >nul
set "FROMVER=unknown"
if exist "%TARGET%\version.txt" for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "(Get-Content '%TARGET%\version.txt' | Select-Object -First 1)"`) do set "FROMVER=%%V"
set "TOVER=unknown"
if exist "%NEWROOT%\version.txt" for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "(Get-Content '%NEWROOT%\version.txt' | Select-Object -First 1)"`) do set "TOVER=%%V"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%BUNDLE%\upgrade-info.txt', \"DbBackup=%DBBACKUP%`r`nTarget=%TARGET%`r`nFromVersion=%FROMVER%`r`nToVersion=%TOVER%`r`nDate=$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))`r`n\", [Text.Encoding]::ASCII)"
echo [INFO] Rollback bundle recorded: %BUNDLE%

rem --- step 3: stop, replace, start ------------------------------
echo [INFO] Stopping services on target ...
net stop IterationBoardApp >nul 2>&1
"%TARGET%\runtime\pgsql\bin\pg_ctl.exe" stop -D "%TARGET%\data\pgdata" -m fast -w -t 60 >nul 2>&1
if errorlevel 1 (
  sc query IterationBoardPG >nul 2>&1
  if not errorlevel 1 net stop IterationBoardPG >nul 2>&1
)

echo [INFO] Replacing program files - config\ and data\ untouched ...
copy /Y "%NEWROOT%\app\iteration-board.jar" "%TARGET%\app\iteration-board.jar" >nul
if errorlevel 1 (
  echo [ERROR] Jar copy failed. Program files are unchanged apart
  echo [ERROR] from this copy - start the services with start.bat
  echo [ERROR] in the target folder to return to the old version.
  call :ib_pause
  exit /b 1
)
if exist "%NEWROOT%\config\prompts" robocopy "%NEWROOT%\config\prompts" "%TARGET%\config\prompts" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul
if exist "%NEWROOT%\version.txt" copy /Y "%NEWROOT%\version.txt" "%TARGET%\version.txt" >nul
rem Bring the current helper scripts to the target as well -
rem a pre-1.1.0 target has no rollback.bat / reset-admin.bat /
rem migrate scripts yet, and the rollback hint below must
rem point at a file that really exists in the target folder.
for %%F in (install start stop backup restore upgrade uninstall apply-patch rollback reset-admin migrate-export migrate-import) do if exist "%NEWROOT%\%%F.bat" copy /Y "%NEWROOT%\%%F.bat" "%TARGET%\%%F.bat" >nul

echo [INFO] Starting services - database migration runs automatically ...
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL failed to start after the upgrade.
  goto :failed
)
timeout /T 3 /NOBREAK >nul
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Application failed to start after the upgrade.
  goto :failed
)

rem --- step 4: health check ---------------------------------------
set "APP_PORT=18080"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
echo [INFO] Health check on http://127.0.0.1:%APP_PORT%/ - polling up to 90 seconds ...
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(90); $ok = $false; while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 5; $ok = $true; break } catch { if ($_.Exception.Response) { $ok = $true; break } else { Start-Sleep -Seconds 3 } } }; if ($ok) { exit 0 } else { exit 1 }"
if errorlevel 1 (
  echo [ERROR] The application service is running but the HTTP health
  echo [ERROR] check did not respond within 90 seconds.
  goto :failed
)

rem --- step 5: built-in admin account (created by the app) ------
call :show_admin_pwfile
echo [INFO] Upgrade complete. Rollback bundle: %BUNDLE%
echo [INFO] To undo this upgrade completely, run rollback.bat in
echo [INFO] the target folder - it restores program files and the
echo [INFO] database from that bundle.
call :ib_pause
exit /b 0

:failed
echo.
echo [ERROR] Last 50 lines of logs\app.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\app.log' -Tail 50 -ErrorAction SilentlyContinue"
echo [ERROR] Last 50 lines of logs\IterationBoardApp.out.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\IterationBoardApp.out.log' -Tail 50 -ErrorAction SilentlyContinue"
echo.
echo [ERROR] Upgrade did not complete. To roll back program files
echo [ERROR] AND the database to the pre-upgrade state, run this
echo [ERROR] command as administrator:
echo [ERROR]   "%TARGET%\rollback.bat"
echo [ERROR] It uses the bundle recorded at %BUNDLE%
call :ib_pause
exit /b 1


rem --- subroutine: show the app-created admin password file ----
rem Since 1.1.0 the application itself creates the built-in
rem "admin" account on first start and writes the initial
rem password to config\admin-initial-password.txt on the
rem target. This script contains no business SQL: it only
rem shows that file when it already exists. The file is
rem written when the application starts, which has just
rem happened by the time the health check above has passed;
rem if it is not there yet, it appears in the target folder
rem once startup has completed.
:show_admin_pwfile
if exist "%TARGET%\config\admin-initial-password.txt" (
  echo.
  echo ============================================================
  echo  A built-in platform administrator account exists on the
  echo  target and its initial password file is present:
  type "%TARGET%\config\admin-initial-password.txt"
  echo  Log in as admin and change the password right away -
  echo  the first login forces a change, and the application
  echo  deletes this file automatically once the password has
  echo  been changed. Keep the file safe until then.
  echo ============================================================
) else (
  echo [INFO] No config\admin-initial-password.txt on the target
  echo [INFO] yet. If the application creates the built-in admin
  echo [INFO] account on this start, the initial password will
  echo [INFO] appear in config\admin-initial-password.txt in the
  echo [INFO] target folder once startup has completed. Otherwise
  echo [INFO] log in with the existing administrator account.
)
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
