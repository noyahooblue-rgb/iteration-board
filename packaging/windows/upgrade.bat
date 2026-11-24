@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
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
if not "%TARGET%"=="" goto :target_ready
call :detect_target
if not "%IB_DET_COUNT%"=="1" goto :target_default
set "TARGET=%IB_DET_1%"
call :log [INFO] Detected existing install at: %TARGET%
if "%YES%"=="1" goto :target_ready
choice /C YN /M "Upgrade this installation with this package now"
if errorlevel 2 goto :target_default
goto :target_ready
:target_default
set "TARGET=%NEWROOT%"
call :log [INFO] No install folder argument - in-place target: %TARGET%
:target_ready
rem --- Install path character warning (ruling A) --------------
rem PostgreSQL on Windows cannot run reliably from a path with
rem non-English characters (root-cause ledger #6). An existing
rem installation that already runs from such a path is NOT
rem blocked here - blocking would strand the installation.
rem Warn only, and recommend a future move to an English path.
set "IB_ROOT=%TARGET%"
powershell -NoProfile -Command "if ($env:IB_ROOT -match '[^\x20-\x7E]') { exit 1 } else { exit 0 }"
set "IB_PATH_RC=%ERRORLEVEL%"
if "%IB_PATH_RC%"=="0" goto :ib_pathwarn_done
  echo [WARN] The install folder path contains non-English characters: %TARGET%.
  echo [WARN] PostgreSQL can fail on such a path on some systems. This operation
  echo [WARN] continues because the existing installation already runs from this
  echo [WARN] folder, but moving the installation to a path with only
  echo [WARN] English letters, digits and spaces, for example
  echo [WARN] D:\IterationBoard, is recommended for the future.
:ib_pathwarn_done
if not exist "%TARGET%\tmp" mkdir "%TARGET%\tmp"
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"
if exist "%TARGET%\app\iteration-board.jar" goto :tjar_ok
call :log [ERROR] Target install not found: %TARGET%
call :ib_pause
exit /b 1
:tjar_ok
if exist "%TARGET%\config\application.yml" goto :tcfg_ok
call :log [ERROR] Target config missing: "%TARGET%\config\application.yml"
call :ib_pause
exit /b 1
:tcfg_ok
if not exist "%TARGET%\version.txt" echo [WARN] No version.txt in the target - the rollback bundle records FromVersion=unknown.
if exist "%NEWROOT%\app\iteration-board.jar" goto :njar_ok
call :log [ERROR] New package jar not found in "%NEWROOT%\app"
call :ib_pause
exit /b 1
:njar_ok

call :log [INFO] Upgrade target: %TARGET%
if "%YES%"=="1" goto :confirm_done
choice /C YN /M "Upgrade the target now"
if errorlevel 2 exit /b 0
:confirm_done

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
call :log [INFO] Backing up target database, uploads and config ...
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
call :log [INFO] Pre-upgrade database backup: %DBBACKUP%

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
call :log [INFO] Rollback bundle recorded: %BUNDLE%

rem --- step 3: stop, replace, start ------------------------------
call :log [INFO] Stopping services on target ...
net stop IterationBoardApp >nul 2>&1
call :wait_app_exit
if "%IB_WAIT_OK%"=="1" goto :app_stopped
call :log [ERROR] The application java process is still running
call :log [ERROR] 60 seconds after the service stop. Upgrade
call :log [ERROR] aborted - stop it manually, then run
call :log [ERROR] upgrade.bat again. Nothing was replaced.
call :ib_pause
exit /b 1
:app_stopped
"%TARGET%\runtime\pgsql\bin\pg_ctl.exe" stop -D "%TARGET%\data\pgdata" -m fast -w -t 60 >nul 2>&1
if not errorlevel 1 goto :pgstop_done
sc query IterationBoardPG >nul 2>&1
if not errorlevel 1 net stop IterationBoardPG >nul 2>&1
:pgstop_done

call :log [INFO] Replacing program files - config\ and data\ untouched ...
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

call :log [INFO] Starting services - database migration runs automatically ...
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
call :log [INFO] Health check on http://127.0.0.1:%APP_PORT%/ - polling up to 90 seconds ...
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(90); $ok = $false; while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 5; $ok = $true; break } catch { if ($_.Exception.Response) { $ok = $true; break } else { Start-Sleep -Seconds 3 } } }; if ($ok) { exit 0 } else { exit 1 }"
if errorlevel 1 (
  echo [ERROR] The application service is running but the HTTP health
  echo [ERROR] check did not respond within 90 seconds.
  goto :failed
)

rem --- step 5: built-in admin account (created by the app) ------
call :show_admin_pwfile
call :log [INFO] Upgrade complete. Rollback bundle: %BUNDLE%
call :log [INFO] To undo this upgrade completely, run rollback.bat in
call :log [INFO] the target folder - it restores program files and the
call :log [INFO] database from that bundle.
call :ib_pause
exit /b 0

:failed
echo.
call :log [ERROR] Last 50 lines of logs\app.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\app.log' -Tail 50 -ErrorAction SilentlyContinue"
call :log [ERROR] Last 50 lines of logs\IterationBoardApp.out.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\IterationBoardApp.out.log' -Tail 50 -ErrorAction SilentlyContinue"
echo.
call :log [ERROR] Upgrade did not complete. To roll back program files
call :log [ERROR] AND the database to the pre-upgrade state, run this
call :log [ERROR] command as administrator:
call :log [ERROR]   "%TARGET%\rollback.bat"
call :log [ERROR] It uses the bundle recorded at %BUNDLE%
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
if exist "%TARGET%\config\admin-initial-password.txt" goto :sap_yes
call :log [INFO] No config\admin-initial-password.txt on the target
call :log [INFO] yet. If the application creates the built-in admin
call :log [INFO] account on this start, the initial password will
call :log [INFO] appear in config\admin-initial-password.txt in the
call :log [INFO] target folder once startup has completed. Otherwise
call :log [INFO] log in with the existing administrator account.
exit /b 0
:sap_yes
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

rem --- subroutine: detect the installed folder from the --------
rem IterationBoardApp service registration (sc qc binary path,
rem registry ImagePath as fallback). The service executable is
rem <install>\app\IterationBoardApp.exe, so the install folder
rem is two levels up. Distinct roots are counted in IB_DET_COUNT
rem (IB_DET_1 / IB_DET_2 hold the first two). Paths travel via
rem stdout of this PowerShell command only; nothing is judged
rem inside cmd.
:detect_target
set "IB_DET_COUNT=0"
set "IB_DET_1="
set "IB_DET_2="
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "$roots = @(); $qc = sc.exe qc IterationBoardApp 2>$null | Out-String; if ($qc -match 'BINARY_PATH_NAME\s*:\s*(.+)') { $p = $Matches[1].Trim().Trim([char]34); if ($p -match '^(.+?\.exe)') { $p = $Matches[1] }; $roots += $p }; try { $reg = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\IterationBoardApp' -ErrorAction Stop).ImagePath; if ($reg) { $r = $reg.Trim().Trim([char]34); if ($r -match '^(.+?\.exe)') { $r = $Matches[1] }; $roots += $r } } catch {}; $out = @(); foreach ($p in $roots) { $dir = Split-Path (Split-Path $p -Parent) -Parent; if ($dir -and ($out -notcontains $dir)) { $out += $dir } }; $out"`) do call :det_add "%%D"
exit /b 0

:det_add
if "%IB_DET_COUNT%"=="0" set "IB_DET_1=%~1"
if "%IB_DET_COUNT%"=="1" set "IB_DET_2=%~1"
set /a IB_DET_COUNT+=1
exit /b 0

rem --- subroutine: wait until the app java process is gone ------
rem Matches only java.exe processes whose command line contains
rem the target folder (passed via the IB_TARGET environment
rem variable), so unrelated Java programs on the machine do not
rem count. Polls once a second for up to 60 seconds and sets
rem IB_WAIT_OK=1 when the process is gone, 0 on timeout.
:wait_app_exit
set "IB_WAIT_OK=0"
set "IB_WAIT_N=0"
set "IB_TARGET=%TARGET%"
:wait_loop
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($env:IB_TARGET) }; if ($p) { exit 1 } else { exit 0 }"
if errorlevel 1 goto :wait_busy
set "IB_WAIT_OK=1"
exit /b 0
:wait_busy
set /a IB_WAIT_N+=1
if %IB_WAIT_N% GEQ 60 exit /b 0
timeout /T 1 /NOBREAK >nul 2>&1
goto :wait_loop

:ib_pause
if defined IB_LOG echo [INFO] Log file: %IB_LOG%
if "%NOPAUSE%"=="0" pause
exit /b 0

rem --- subroutine: write a line to console AND the script log ---
:log
echo %*
if defined IB_LOG echo %*>>"%IB_LOG%"
exit /b 0
