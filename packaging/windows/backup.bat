@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem ============================================================
rem  Iteration Board - backup (design 10.4, 1.1.0 update)
rem  Creates <backup-dir>\<yyyyMMdd-HHmmss>\ with:
rem    db.dump        pg_dump custom-format dump
rem    uploads\       uploaded image files (if the folder exists)
rem    application.yml  config copy (contains the DB password -
rem                     keep backup folders private!)
rem  The backup directory defaults to backup\ inside the
rem  install folder and can be redirected in
rem  config\application.yml (backup.dir, 1.1.0 - see the
rem  deployment manual). Backups older than 30 days in the
rem  ACTIVE backup directory are deleted.
rem  Every run (success, failure or skip) records its result in
rem  data\backup-status.json - format agreed with the backend
rem  (1.1.0, P23): ISO-8601 UTC timestamps plus a success flag.
rem  The file carries BOTH field namings so the backend reader
rem  (lastAttemptAt/lastSuccessAt/result/detail) and the script
rem  side (lastAttemptTime/lastSuccessTime/lastResult/success/
rem  lastBackupPath) read the same file:
rem    {"lastAttemptTime":"...Z","lastSuccessTime":"...Z",
rem     "lastResult":"success|failed|skipped","success":true,
rem     "lastBackupPath":"<folder of the newest good backup>",
rem     "lastAttemptAt":"...Z","lastSuccessAt":"...Z",
rem     "result":"SUCCESS|FAILED|SKIPPED","detail":"..."}
rem  lastSuccessTime is only moved forward by a successful run.
rem  Runs unattended from Task Scheduler as SYSTEM, and can
rem  also be run manually (no pause in scheduled mode: pass
rem  the argument "auto").
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
if not exist "%ROOT%\data" mkdir "%ROOT%\data"
set "NOPAUSE=0"
if /i "%~1"=="auto" set "NOPAUSE=1"
rem --- IB unified contract: automation hooks + anti-crash shell --
rem IB_AUTO=1 (CI): no pause, no prompts, no cmd /k wrapper; the
rem exit code and this script's log file are the contract.
rem IB_YES=1: confirmations default to yes. A plain double-click
rem run is re-launched once under cmd /k (IB_WRAPPED) so the
rem window stays open on any error instead of flashing away.
if "%IB_AUTO%"=="1" set "NOPAUSE=1"
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

rem --- resolve the backup directory (config backup.dir) -------
set "BKROOT=%ROOT%\backup"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%ROOT%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\x22([^\x22]*)\x22') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
if not "%BKROOT%"=="" if not "%BKROOT:~1,1%"==":" set "BKROOT=%ROOT%\%BKROOT%"
if "%BKROOT%"=="" set "BKROOT=%ROOT%\backup"
if not exist "%BKROOT%" mkdir "%BKROOT%"

rem --- the services must be running ----------------------------
rem Since 1.0.3 both services default to MANUAL start, so at
rem 02:00 they may well be stopped. The scheduled run must never
rem start services on its own and must not end with an error
rem code: log a skip line and exit 0. A manual run
rem tells the user to start the services first.
sc query IterationBoardPG >nul 2>&1
if errorlevel 1 goto :svc_skip
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 goto :svc_skip
goto :svc_ok
:svc_skip
echo %DATE% %TIME% [SKIP] PostgreSQL service is not running; backup skipped. >> "%ROOT%\logs\backup.log"
call :write_status skipped
if "%~1"=="auto" exit /b 0
call :log [INFO] The PostgreSQL service is not running, so there is nothing to back up.
call :log [INFO] Start the services first - run start.bat - then run backup.bat again.
call :ib_pause
exit /b 0
:svc_ok

rem --- read DB password from config\application.yml -----------
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%ROOT%\config\application.yml"') do set "DBPWD=%%B"
if not "%DBPWD%"=="" goto :dbpwd_ok
echo [ERROR] Could not read DB password from config\application.yml >> "%ROOT%\logs\backup.log"
call :write_status failed
exit /b 1
:dbpwd_ok

for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "DEST=%BKROOT%\%TS%"
mkdir "%DEST%"
set "PGPASSWORD=%DBPWD%"
"%ROOT%\runtime\pgsql\bin\pg_dump.exe" -h 127.0.0.1 -p 15432 -U postgres -Fc -f "%DEST%\db.dump" iteration_board
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if "%IBRC%"=="0" goto :dump_ok
echo [ERROR] pg_dump failed with code %IBRC% >> "%ROOT%\logs\backup.log"
call :write_status failed
call :ib_pause
exit /b 1
:dump_ok
if not exist "%ROOT%\data\uploads" goto :uploads_done
robocopy "%ROOT%\data\uploads" "%DEST%\uploads" /E /NFL /NDL /NJH /NJS >nul
if not errorlevel 8 goto :uploads_done
echo [ERROR] uploads copy failed. >> "%ROOT%\logs\backup.log"
call :write_status failed
exit /b 1
:uploads_done
copy /Y "%ROOT%\config\application.yml" "%DEST%\application.yml" >nul
echo %DATE% %TIME% backup OK: %DEST% >> "%ROOT%\logs\backup.log"
set "LASTBACKUP=%DEST%"
call :write_status success

rem --- keep only the last 30 days ------------------------------
powershell -NoProfile -Command "Get-ChildItem '%BKROOT%' -Directory | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } | Remove-Item -Recurse -Force"
call :log [INFO] Backup complete: %DEST%
call :ib_pause
exit /b 0

rem --- subroutine: record the run in data\backup-status.json ---
rem %1 = success | failed | skipped. Merges with the previous
rem file so lastSuccessTime survives failed or skipped runs.
:write_status
set "IB_BK_RESULT=%~1"
set "IB_BK_PATH=%LASTBACKUP%"
powershell -NoProfile -Command "$f = '%ROOT%\data\backup-status.json'; $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); $prev = $null; if (Test-Path $f) { try { $prev = Get-Content $f -Raw | ConvertFrom-Json } catch {} }; $o = [ordered]@{}; $o.lastAttemptTime = $now; if ($env:IB_BK_RESULT -eq 'success') { $o.lastSuccessTime = $now } elseif ($prev -and $prev.lastSuccessTime) { $o.lastSuccessTime = $prev.lastSuccessTime } else { $o.lastSuccessTime = $null }; $o.lastResult = $env:IB_BK_RESULT; $o.success = ($env:IB_BK_RESULT -eq 'success'); if ($env:IB_BK_RESULT -eq 'success') { $o.lastBackupPath = $env:IB_BK_PATH } elseif ($prev -and $prev.lastBackupPath) { $o.lastBackupPath = $prev.lastBackupPath } else { $o.lastBackupPath = $null }; $o.lastAttemptAt = $o.lastAttemptTime; $o.lastSuccessAt = $o.lastSuccessTime; $o.result = $env:IB_BK_RESULT.ToUpper(); $o.detail = $o.lastResult; [IO.File]::WriteAllText($f, ($o | ConvertTo-Json -Compress), [Text.Encoding]::ASCII)"
exit /b 0


:ib_pause
if defined IB_LOG echo [INFO] Log file: %IB_LOG%
if "%NOPAUSE%"=="0" pause
exit /b 0

rem --- subroutine: write a line to console AND the script log ---
:log
echo %*
if defined IB_LOG echo %*>>"%IB_LOG%"
exit /b 0
