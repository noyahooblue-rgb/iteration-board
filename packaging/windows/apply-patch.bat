@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - generic patch applier (1.1.0 template)
rem  Usage: apply-patch.bat [install-folder] [--yes] [--no-pause]
rem  Ships inside each patch zip next to SHA256SUMS.txt and the
rem  patched files. Also included in the full package so the
rem  install folder always has a copy. Flow (1.1.0, revision 3):
rem    1. verify every file listed in SHA256SUMS.txt by SHA-256;
rem    2. database backup into the CONFIGURED backup directory
rem       (target config backup.dir) + copies of every file that
rem       will be replaced, recorded as a rollback bundle
rem       <backup-dir>\upgrade-<yyyyMMdd-HHmmss>\ ;
rem    3. copy the patched files in and verify the copies
rem       (apply-patch.bat itself is verified but never copied
rem       over the running script);
rem    4. restart the services and run the health check - on
rem       failure the log tails are printed and the exact
rem       rollback command is shown (rollback.bat restores
rem       program files AND the database from the bundle);
rem    5. the application itself creates the built-in "admin"
rem       account on first start if none exists and writes the
rem       initial password to config\admin-initial-password.txt
rem       on the target; this script shows that file after the
rem       health check when it exists.
rem  data\ and the target config\application.yml are never
rem  overwritten by a patch.
rem ============================================================
set "PATCHROOT=%~dp0"
if "%PATCHROOT:~-1%"=="\" set "PATCHROOT=%PATCHROOT:~0,-1%"
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
if "%TARGET%"=="" set "TARGET=%PATCHROOT%"
rem --- Install path character warning (ruling A) --------------
rem PostgreSQL on Windows cannot run reliably from a path with
rem non-English characters (root-cause ledger #6). An existing
rem installation that already runs from such a path is NOT
rem blocked here - blocking would strand the installation.
rem Warn only, and recommend a future move to an English path.
set "IB_ROOT=%TARGET%"
powershell -NoProfile -Command "if ($env:IB_ROOT -match '[^\x20-\x7E]') { exit 1 } else { exit 0 }"
set "IB_PATH_RC=%ERRORLEVEL%"
if not "%IB_PATH_RC%"=="0" (
  echo [WARN] The install folder path contains non-English characters: %TARGET%.
  echo [WARN] PostgreSQL can fail on such a path on some systems. This operation
  echo [WARN] continues because the existing installation already runs from this
  echo [WARN] folder, but moving the installation to a path with only
  echo [WARN] English letters, digits and spaces, for example
  echo [WARN] D:\IterationBoard, is recommended for the future.
)
if not exist "%TARGET%\tmp" mkdir "%TARGET%\tmp"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)
if not exist "%TARGET%\install.bat" (
  echo [ERROR] Install folder not found or invalid: %TARGET%
  echo [ERROR] Usage: apply-patch.bat [path-to-install-folder]
  call :ib_pause
  exit /b 1
)
rem --- source version gate: IB_EXPECTED_FROM is stamped by the
rem patch builder (empty = no gate, e.g. the full-package copy).
rem The check runs before anything is stopped or copied; a
rem mismatch stops here and changes nothing.
set "IB_EXPECTED_FROM="
if "%IB_EXPECTED_FROM%"=="" goto :ver_gate_done
set "CURVER="
if exist "%TARGET%\version.txt" set /p CURVER=<"%TARGET%\version.txt"
if "%CURVER%"=="%IB_EXPECTED_FROM%" goto :ver_gate_done
echo [ERROR] This patch upgrades version %IB_EXPECTED_FROM% only, but the
echo [ERROR] install folder reports version "%CURVER%". Nothing was changed.
echo [ERROR] Use the patch that matches your current version, or reinstall
echo [ERROR] from the full package.
call :ib_pause
exit /b 1
:ver_gate_done
if not exist "%PATCHROOT%\SHA256SUMS.txt" (
  echo [ERROR] SHA256SUMS.txt is missing next to apply-patch.bat.
  echo [ERROR] Extract the complete patch zip first.
  call :ib_pause
  exit /b 1
)
if "%YES%"=="0" (
  choice /C YN /M "Apply this patch to %TARGET% now"
  if errorlevel 2 exit /b 0
)

rem --- step 1: verify the patch files themselves -----------------
echo [INFO] Verifying patch files against SHA256SUMS.txt ...
powershell -NoProfile -Command "$fail = 0; foreach ($line in Get-Content '%PATCHROOT%\SHA256SUMS.txt') { if ($line -match '^\s*#' -or $line -notmatch '^\s*([0-9a-fA-F]{64})\s+(.+)$') { continue }; $exp = $Matches[1]; $rel = $Matches[2].Trim(); $p = Join-Path '%PATCHROOT%' $rel; if (-not (Test-Path $p)) { Write-Host \"[ERROR] $rel missing in the patch folder\"; $fail = 1; continue }; $h = (Get-FileHash $p -Algorithm SHA256).Hash; if ($h -ne $exp) { Write-Host \"[ERROR] $rel hash mismatch\"; $fail = 1 } else { Write-Host \"[OK] $rel\" } }; exit $fail"
if errorlevel 1 (
  echo [ERROR] Patch files did not verify - nothing was changed.
  echo [ERROR] Re-extract the patch zip and try again.
  call :ib_pause
  exit /b 1
)

rem --- resolve the target backup directory (backup.dir) ----------
set "BKROOT=%TARGET%\backup"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\x22([^\x22]*)\x22') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
if not "%BKROOT%"=="" if not "%BKROOT:~1,1%"==":" set "BKROOT=%TARGET%\%BKROOT%"
if "%BKROOT%"=="" set "BKROOT=%TARGET%\backup"
if not exist "%BKROOT%" mkdir "%BKROOT%"

rem --- step 2: database backup + replaced-file bundle -------------
echo [INFO] Backing up target database, uploads and config ...
call "%TARGET%\backup.bat" auto
if errorlevel 1 (
  echo [ERROR] Backup failed. Patch aborted, nothing changed.
  call :ib_pause
  exit /b 1
)
set "DBBACKUP="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$f = '%TARGET%\data\backup-status.json'; if (Test-Path $f) { try { $j = Get-Content $f -Raw | ConvertFrom-Json; if ($j.success -and $j.lastBackupPath -and (Test-Path (Join-Path $j.lastBackupPath 'db.dump')) -and ((Get-Item (Join-Path $j.lastBackupPath 'db.dump')).LastWriteTime -gt (Get-Date).AddMinutes(-15))) { $j.lastBackupPath } } catch {} }"`) do set "DBBACKUP=%%P"
if "%DBBACKUP%"=="" (
  echo [ERROR] No fresh database backup was produced - PostgreSQL is
  echo [ERROR] probably stopped, so backup.bat skipped. Start the
  echo [ERROR] services with start.bat in the target folder first,
  echo [ERROR] then run apply-patch.bat again. Nothing was changed.
  call :ib_pause
  exit /b 1
)
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "BUNDLE=%BKROOT%\upgrade-%TS%"
mkdir "%BUNDLE%\files" >nul 2>&1
powershell -NoProfile -Command "foreach ($line in Get-Content '%PATCHROOT%\SHA256SUMS.txt') { if ($line -notmatch '^\s*([0-9a-fA-F]{64})\s+(.+)$') { continue }; $rel = $Matches[2].Trim(); $t = Join-Path '%TARGET%' $rel; if (Test-Path $t) { $d = Join-Path '%BUNDLE%\files' $rel; New-Item -ItemType Directory -Force (Split-Path $d) | Out-Null; Copy-Item $t $d -Force } }"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%BUNDLE%\upgrade-info.txt', \"DbBackup=%DBBACKUP%`r`nTarget=%TARGET%`r`nPatchSource=%PATCHROOT%`r`nDate=$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))`r`n\", [Text.Encoding]::ASCII)"
echo [INFO] Pre-patch database backup: %DBBACKUP%
echo [INFO] Rollback bundle recorded: %BUNDLE%

rem Make sure the rollback tool itself exists in the target
rem before anything is replaced (a pre-1.1.0 target has none).
if exist "%PATCHROOT%\rollback.bat" copy /Y "%PATCHROOT%\rollback.bat" "%TARGET%\rollback.bat" >nul
if exist "%PATCHROOT%\reset-admin.bat" copy /Y "%PATCHROOT%\reset-admin.bat" "%TARGET%\reset-admin.bat" >nul

rem --- step 3: stop the app, copy files in, verify copies ----------
echo [INFO] Stopping the application service ...
net stop IterationBoardApp >nul 2>&1
echo [INFO] Copying patched files into %TARGET% ...
powershell -NoProfile -Command "$fail = 0; foreach ($line in Get-Content '%PATCHROOT%\SHA256SUMS.txt') { if ($line -notmatch '^\s*([0-9a-fA-F]{64})\s+(.+)$') { continue }; $exp = $Matches[1]; $rel = $Matches[2].Trim(); if ($rel -ieq 'apply-patch.bat') { continue }; $s = Join-Path '%PATCHROOT%' $rel; $t = Join-Path '%TARGET%' $rel; New-Item -ItemType Directory -Force (Split-Path $t) | Out-Null; Copy-Item $s $t -Force; if ((Get-FileHash $t -Algorithm SHA256).Hash -ne $exp) { Write-Host \"[ERROR] $rel copy verification failed\"; $fail = 1 } else { Write-Host \"[OK] $rel\" } }; exit $fail"
if errorlevel 1 (
  echo [ERROR] Copying the patch failed partway. Roll back with:
  echo [ERROR]   "%TARGET%\rollback.bat"
  call :ib_pause
  exit /b 1
)
if exist "%PATCHROOT%\version.txt" copy /Y "%PATCHROOT%\version.txt" "%TARGET%\version.txt" >nul

rem --- step 4: restart + health check -------------------------------
echo [INFO] Starting services - database migration runs automatically ...
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL failed to start after the patch.
  goto :failed
)
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Application failed to start after the patch.
  goto :failed
)
set "APP_PORT=18080"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
echo [INFO] Health check on http://127.0.0.1:%APP_PORT%/ - polling up to 90 seconds ...
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(90); $ok = $false; while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 5; $ok = $true; break } catch { if ($_.Exception.Response) { $ok = $true; break } else { Start-Sleep -Seconds 3 } } }; if ($ok) { exit 0 } else { exit 1 }"
if errorlevel 1 (
  echo [ERROR] The application did not answer the HTTP health check.
  goto :failed
)

rem --- step 5: built-in admin account (created by the app) --------
call :show_admin_pwfile
echo [INFO] Patch applied successfully. Rollback bundle: %BUNDLE%
echo [INFO] To undo this patch completely, run rollback.bat in the
echo [INFO] target folder.
call :ib_pause
exit /b 0

:failed
echo.
echo [ERROR] Last 50 lines of logs\app.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\app.log' -Tail 50 -ErrorAction SilentlyContinue"
echo [ERROR] Last 50 lines of logs\IterationBoardApp.out.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\IterationBoardApp.out.log' -Tail 50 -ErrorAction SilentlyContinue"
echo.
echo [ERROR] The patch did not complete. To roll back program files
echo [ERROR] AND the database to the pre-patch state, run this
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
