@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
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
rem --- IB unified contract: self-elevation ---------------------
rem A non-admin instance re-launches itself elevated with the
rem original arguments (IB_ARGS keeps them as typed, quotes
rem included) and then exits; the elevated instance continues
rem in the script folder, not in System32 (see :ib_elevate).
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"

rem --- ZIP self-check -------------------------------------------
rem Started from inside the zip (Explorer preview) the payload
rem files are not really on disk: refuse with a clear message.
if exist "%PATCHROOT%\SHA256SUMS.txt" goto :ib_zip_ok
call :log [ERROR] SHA256SUMS.txt is missing next to apply-patch.bat.
call :log [ERROR] You may have started apply-patch.bat from inside
call :log [ERROR] the zip file. Extract the complete patch zip
call :log [ERROR] first, then run apply-patch.bat from the
call :log [ERROR] extracted folder.
call :ib_pause
exit /b 1
:ib_zip_ok

rem --- resolve the target install folder ------------------------
rem NEVER default to the patch folder. Order: 1) the argument,
rem 2) the single install registered for the IterationBoardApp
rem service (sc qc / registry ImagePath), confirmed by the
rem user, 3) a path typed or dragged in by the user. The
rem result must contain the install markers (app jar and
rem config) or the patch stops, changing nothing. version.txt
rem is NOT a marker: pre-1.1.0 installs (1.0.4 / 1.0.1) shipped
rem without one - their version is fingerprinted from layout
rem markers instead (see :detect_version).
if not "%TARGET%x"=="x" goto :target_check
call :detect_target
if "%IB_DET_COUNT%"=="1" goto :target_detected
if "%IB_DET_COUNT%"=="0" goto :target_none
goto :target_multi
:target_none
call :log [INFO] No IterationBoardApp service registration was
call :log [INFO] found, so no installed location could be detected.
set "TARGET=__IB_NONE__"
set /p "TARGET=Type or drag the install folder path here, then press Enter: "
set "TARGET=%TARGET:"=%"
if "%TARGET%x"=="__IB_NONE__x" goto :target_missing
goto :target_check
:target_multi
rem Two or more DISTINCT install roots came back from the
rem service registration sources (Win32_Service vs registry
rem disagree, e.g. a delete-pending leftover next to the live
rem registration). Validate the install markers of each
rem candidate: when exactly one candidate is a complete
rem install folder, offer it as the suggested target - never
rem adopt it silently; it goes through the same confirmation
rem as a single detection (choice prompt, or the IB_AUTO /
rem --yes contract). Otherwise fall back to manual input.
call :log [WARN] The service registration reports %IB_DET_COUNT% different
call :log [WARN] install locations. Candidates:
call :log [WARN]   1: %IB_DET_1%
call :log [WARN]   2: %IB_DET_2%
set "IB_VALID_COUNT=0"
set "IB_VALID_ROOT="
call :det_valid "%IB_DET_1%"
if "%IB_VALID%"=="1" set "IB_VALID_ROOT=%IB_DET_1%"
if "%IB_VALID%"=="1" set /a IB_VALID_COUNT+=1
call :det_valid "%IB_DET_2%"
if "%IB_VALID%"=="1" set "IB_VALID_ROOT=%IB_DET_2%"
if "%IB_VALID%"=="1" set /a IB_VALID_COUNT+=1
if not "%IB_VALID_COUNT%"=="1" goto :target_multi_fail
call :log [INFO] Only one candidate is a complete install folder:
call :log [INFO]   %IB_VALID_ROOT%
set "TARGET=%IB_VALID_ROOT%"
goto :target_detected_body
:target_multi_fail
call :log [INFO] No single candidate could be confirmed as the
call :log [INFO] install folder. Type the path manually.
set "TARGET=__IB_NONE__"
set /p "TARGET=Type or drag the install folder path here, then press Enter: "
set "TARGET=%TARGET:"=%"
if "%TARGET%x"=="__IB_NONE__x" goto :target_missing
goto :target_check
:target_detected
set "TARGET=%IB_DET_1%"
:target_detected_body
call :log [INFO] Detected install at: %TARGET%
if "%YES%"=="1" goto :target_check
choice /C YN /M "Upgrade this installation now"
if errorlevel 2 goto :target_manual
goto :target_check
:target_manual
set "TARGET=__IB_NONE__"
set /p "TARGET=Type or drag the install folder path here, then press Enter: "
set "TARGET=%TARGET:"=%"
if "%TARGET%x"=="__IB_NONE__x" goto :target_missing
:target_check
rem The empty test uses a sentinel suffix: a typed or dragged
rem path can end in a backslash, and a bare "%TARGET%"=="" test
rem then puts the backslash directly before the closing quote,
rem which the cmd parser misreads and aborts on (1.1.0 CI
rem matrix, N3-1.0.1 manual branch). The sentinel form is
rem immune because the value never ends the quoted string.
if "%TARGET%x"=="x" goto :target_missing
if not exist "%TARGET%\app\iteration-board.jar" goto :target_bad
if not exist "%TARGET%\config\application.yml" goto :target_bad
goto :target_ready
:target_missing
call :log [ERROR] No install folder was given. Nothing was changed.
call :ib_pause
exit /b 1
:target_bad
call :log [ERROR] Not a complete install folder: %TARGET%
call :log [ERROR] It must contain app\iteration-board.jar and
call :log [ERROR] config\application.yml.
call :ib_pause
exit /b 1
:target_ready
if "%TARGET:~1,1%"==":" call :check_mapped

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
call :log [WARN] The install folder path contains non-English characters: %TARGET%
call :log [WARN] PostgreSQL can fail on such a path on some systems. This operation
call :log [WARN] continues because the existing installation already runs from this
call :log [WARN] folder, but moving the installation to a path with only
call :log [WARN] English letters, digits and spaces, for example
call :log [WARN] D:\IterationBoard, is recommended for the future.
:ib_pathwarn_done
if not exist "%TARGET%\tmp" mkdir "%TARGET%\tmp"

rem --- current version of the target (every layout) ------------
call :detect_version
call :log [INFO] Target version detected as: "%CURVER%"

rem --- source version gate: IB_EXPECTED_FROM is stamped by the
rem patch builder (empty = no gate, e.g. the full-package copy).
rem The check runs before anything is stopped or copied; a
rem mismatch stops here and changes nothing.
set "IB_EXPECTED_FROM="
if "%IB_EXPECTED_FROM%"=="" goto :ver_gate_done
if "%CURVER%"=="%IB_EXPECTED_FROM%" goto :ver_gate_done
call :log [ERROR] This patch upgrades version %IB_EXPECTED_FROM% only, but the
call :log [ERROR] install folder reports version "%CURVER%". Nothing was changed.
call :log [ERROR] Use the patch that matches your current version, or reinstall
call :log [ERROR] from the full package.
call :ib_pause
exit /b 1
:ver_gate_done
rem (the SHA256SUMS.txt presence check moved up: see ZIP self-check)
call :log [INFO] Target install folder: %TARGET%
if "%YES%"=="1" goto :confirm_done
choice /C YN /M "Apply this patch to the target now"
if errorlevel 2 exit /b 0
:confirm_done

rem --- step 1: verify the patch files themselves -----------------
call :log [INFO] Verifying patch files against SHA256SUMS.txt ...
powershell -NoProfile -Command "function Get-Sha256([string]$path) { $fs = [IO.File]::OpenRead($path); try { return [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($fs)).Replace('-', '').ToLower() } finally { $fs.Close() } }; $fail = 0; foreach ($line in Get-Content -Encoding UTF8 '%PATCHROOT%\SHA256SUMS.txt') { if ($line -match '^\s*#' -or $line -notmatch '^\s*([0-9a-fA-F]{64})\s+(.+)$') { continue }; $exp = $Matches[1]; $rel = $Matches[2].Trim(); $p = Join-Path '%PATCHROOT%' $rel; if (-not (Test-Path $p)) { Write-Host \"[ERROR] $rel missing in the patch folder\"; $fail = 1; continue }; $h = (Get-Sha256 $p); if ($h -ne $exp) { Write-Host \"[ERROR] $rel hash mismatch\"; $fail = 1 } else { Write-Host \"[OK] $rel\" } }; exit $fail"
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
rem The database backup is made HERE, by this script - never by
rem calling the target's backup.bat: pre-1.1.0 backup.bat
rem versions do not write data\backup-status.json at all (that
rem format arrived with 1.1.0), and the 1.0.4 one silently
rem SKIPS with exit code 0 when the PostgreSQL service is
rem stopped, so relying on it either aborted the patch on real
rem legacy installs or credited a backup that was never made.
rem The dump below is always fresh (pg_dump -Fc, taken seconds
rem before it is recorded - no reuse window on the patch path),
rem uses the same folder layout backup.bat produces, and is
rem registered in backup-status.json in the same agreed format.
call :log [INFO] Backing up target database, uploads and config ...
rem Before the PostgreSQL service is touched in any way, verify
rem that the ONE machine-wide IterationBoardPG registration
rem actually belongs to THIS install: pg_ctl registers the data
rem directory (-D) in the service ImagePath. A leftover
rem registration from another (meanwhile deleted) install would
rem otherwise be started here and would answer pg_dump on
rem 127.0.0.1:15432 with a different random password - the dump
rem then fails with a misleading authentication error (1.1.0
rem final-verification round, matrix N1/N3/N4). The check is
rem fail-closed: when the registered data directory cannot be
rem determined, the patch stops instead of trusting the port.
set "IB_PGDIR="
set "IB_PGCHK=2"
set "IB_TARGET=%TARGET%"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$raw = $null; try { $raw = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\IterationBoardPG' -ErrorAction Stop).ImagePath } catch {}; if (-not $raw) { Write-Output 'VERDICT:2'; exit 0 }; $exp = [Environment]::ExpandEnvironmentVariables($raw); $d = $null; if ($exp -match '-D\s+\x22([^\x22]+)\x22') { $d = $Matches[1] } elseif ($exp -match '-D\s+(\S+)') { $d = $Matches[1] }; if (-not $d) { Write-Output 'VERDICT:2'; exit 0 }; $reg = [IO.Path]::GetFullPath($d).TrimEnd('\'); Write-Output ('PGDIR:' + $reg); $tgt = [IO.Path]::GetFullPath((Join-Path $env:IB_TARGET 'data\pgdata')).TrimEnd('\'); if ($reg -ieq $tgt) { Write-Output 'VERDICT:0' } else { Write-Output 'VERDICT:1' }"`) do call :pg_add "%%V"
if "%IB_PGCHK%"=="0" goto :bk_pgdir_ok
if "%IB_PGCHK%"=="1" goto :bk_pgdir_wrong
call :log [ERROR] The data directory of the registered
call :log [ERROR] IterationBoardPG service could not be determined,
call :log [ERROR] so it cannot be verified to belong to this install.
call :log [ERROR] Patch aborted - nothing was changed. Reinstall
call :log [ERROR] PostgreSQL service registration from the target
call :log [ERROR] folder with install.bat, then run apply-patch.bat
call :log [ERROR] again.
call :ib_pause
exit /b 1
:bk_pgdir_wrong
call :log [ERROR] The registered IterationBoardPG service points at
call :log [ERROR] a DIFFERENT data directory:
call :log [ERROR]   registered: %IB_PGDIR%
set "IB_TGTPG=%TARGET%\data\pgdata"
call :log [ERROR]   this install: %IB_TGTPG%
call :log [ERROR] A leftover registration from another installation
call :log [ERROR] would answer the backup dump with the wrong
call :log [ERROR] database password. Patch aborted - nothing was
call :log [ERROR] changed. Remove the stale service registration
call :log [ERROR] (or reinstall from the target folder), then run
call :log [ERROR] apply-patch.bat again.
call :ib_pause
exit /b 1
:bk_pgdir_ok
call :log [INFO] PostgreSQL service registration verified for this
call :log [INFO] install (data directory: %IB_PGDIR%).
rem PostgreSQL must be up for the dump. Both services are
rem manual-start since 1.0.3, so start the PG service first
rem when it is stopped; the app service is stopped later, in
rem step 3, after the backup.
sc query IterationBoardPG >nul 2>&1
if errorlevel 1 goto :bk_pg_missing
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if not errorlevel 1 goto :bk_pg_up
net start IterationBoardPG >nul 2>&1
set "IB_PGWAIT=0"
:bk_pg_wait
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if not errorlevel 1 goto :bk_pg_up
set /a IB_PGWAIT+=1
if "%IB_PGWAIT%"=="15" goto :bk_pg_missing
powershell -NoProfile -Command "Start-Sleep -Seconds 2"
goto :bk_pg_wait
:bk_pg_missing
call :log [ERROR] The PostgreSQL service (IterationBoardPG) is
call :log [ERROR] not running and could not be started, so no
call :log [ERROR] database backup can be made. Patch aborted -
call :log [ERROR] start the services with start.bat in the
call :log [ERROR] target folder first, then run apply-patch.bat
call :log [ERROR] again. Nothing was changed.
call :ib_pause
exit /b 1
:bk_pg_up
set "DBPWD="
set "IB_CFG=%TARGET%\config\application.yml"
for /f "usebackq delims=" %%B in (`powershell -NoProfile -Command "$c = Get-Content $env:IB_CFG -ErrorAction SilentlyContinue; foreach ($l in $c) { if ($l -match '^\s*password:\s*(\S+)') { Write-Output $Matches[1]; break } }"`) do set "DBPWD=%%B"
if not "%DBPWD%"=="" goto :bk_pwd_ok
call :log [ERROR] Could not read the database password from
call :log [ERROR] config\application.yml. Patch aborted,
call :log [ERROR] nothing was changed.
call :ib_pause
exit /b 1
:bk_pwd_ok
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "DBBACKUP=%BKROOT%\%TS%"
mkdir "%DBBACKUP%" >nul 2>&1
set "PGPASSWORD=<redacted>"
"%TARGET%\runtime\pgsql\bin\pg_dump.exe" -h 127.0.0.1 -p 15432 -U postgres -Fc -f "%DBBACKUP%\db.dump" iteration_board
set "IB_DUMP_RC=%ERRORLEVEL%"
set "PGPASSWORD="
if "%IB_DUMP_RC%"=="0" goto :bk_dump_ok
call :log [ERROR] pg_dump failed with code %IB_DUMP_RC%. Patch
call :log [ERROR] aborted - nothing was changed.
call :log [ERROR] Diagnosis: the PostgreSQL service registration was
call :log [ERROR] verified above to point at THIS install, so the
call :log [ERROR] instance answering on 127.0.0.1:15432 is this
call :log [ERROR] install's database. A password rejection then means
call :log [ERROR] the password inside the data directory and the one
call :log [ERROR] in config\application.yml disagree - one of the two
call :log [ERROR] was changed without the other (or the data directory
call :log [ERROR] was replaced). Restore the matching pair before
call :log [ERROR] retrying; do not keep retrying this patch.
call :ib_pause
exit /b 1
:bk_dump_ok
if not exist "%TARGET%\data\uploads" goto :bk_uploads_done
robocopy "%TARGET%\data\uploads" "%DBBACKUP%\uploads" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul
if errorlevel 8 goto :bk_copy_fail
:bk_uploads_done
copy /Y "%TARGET%\config\application.yml" "%DBBACKUP%\application.yml" >nul
if errorlevel 1 goto :bk_copy_fail
goto :bk_done
:bk_copy_fail
call :log [ERROR] Copying uploads or config into the backup
call :log [ERROR] folder failed. Patch aborted - nothing was
call :log [ERROR] changed.
call :ib_pause
exit /b 1
:bk_done
set "IB_BK_RESULT=success"
set "IB_BK_PATH=%DBBACKUP%"
call :write_bk_status
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "BUNDLE=%BKROOT%\upgrade-%TS%"
mkdir "%BUNDLE%\files" >nul 2>&1
powershell -NoProfile -Command "foreach ($line in Get-Content -Encoding UTF8 '%PATCHROOT%\SHA256SUMS.txt') { if ($line -notmatch '^\s*([0-9a-fA-F]{64})\s+(.+)$') { continue }; $rel = $Matches[2].Trim(); $t = Join-Path '%TARGET%' $rel; if (Test-Path $t) { $d = Join-Path '%BUNDLE%\files' $rel; New-Item -ItemType Directory -Force (Split-Path $d) | Out-Null; Copy-Item $t $d -Force } }"
rem The whole app\ folder (and version.txt, when the install
rem has one) goes into the bundle as well (not only the files
rem this patch replaces), so the
rem pre-patch program state is fully recoverable. A failure
rem here aborts the patch before anything is overwritten.
robocopy "%TARGET%\app" "%BUNDLE%\files\app" /E /NFL /NDL /NJH /NJS /R:1 /W:1 >nul
if errorlevel 8 goto :bundle_fail
rem Legacy installs have no version.txt (see :detect_version);
rem record it in the bundle only when it exists.
if not exist "%TARGET%\version.txt" goto :bundle_vt_done
copy /Y "%TARGET%\version.txt" "%BUNDLE%\files\version.txt" >nul
if errorlevel 1 goto :bundle_fail
:bundle_vt_done
goto :bundle_done
:bundle_fail
call :log [ERROR] Recording the pre-patch program backup failed.
call :log [ERROR] Patch aborted - nothing was copied or changed.
call :ib_pause
exit /b 1
:bundle_done
powershell -NoProfile -Command "[IO.File]::WriteAllText('%BUNDLE%\upgrade-info.txt', \"DbBackup=%DBBACKUP%`r`nFromVersion=%CURVER%`r`nTarget=%TARGET%`r`nPatchSource=%PATCHROOT%`r`nDate=$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))`r`n\", [Text.Encoding]::ASCII)"
call :log [INFO] Pre-patch database backup: %DBBACKUP%
call :log [INFO] Rollback bundle recorded: %BUNDLE%

rem Make sure the rollback tool itself exists in the target
rem before anything is replaced (a pre-1.1.0 target has none).
if exist "%PATCHROOT%\rollback.bat" copy /Y "%PATCHROOT%\rollback.bat" "%TARGET%\rollback.bat" >nul
if exist "%PATCHROOT%\reset-admin.bat" copy /Y "%PATCHROOT%\reset-admin.bat" "%TARGET%\reset-admin.bat" >nul

rem --- step 3: stop the app, copy files in, verify copies ----------
call :log [INFO] Stopping the application service ...
net stop IterationBoardApp >nul 2>&1
call :wait_app_exit
if "%IB_WAIT_OK%"=="1" goto :app_stopped
call :log [ERROR] The application java process is still running
call :log [ERROR] 60 seconds after the service stop. Patch
call :log [ERROR] aborted - stop it manually, then run
call :log [ERROR] apply-patch.bat again. Nothing was copied.
call :ib_pause
exit /b 1
:app_stopped
call :log [INFO] Copying patched files into %TARGET% ...
powershell -NoProfile -Command "function Get-Sha256([string]$path) { $fs = [IO.File]::OpenRead($path); try { return [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($fs)).Replace('-', '').ToLower() } finally { $fs.Close() } }; $fail = 0; foreach ($line in Get-Content -Encoding UTF8 '%PATCHROOT%\SHA256SUMS.txt') { if ($line -notmatch '^\s*([0-9a-fA-F]{64})\s+(.+)$') { continue }; $exp = $Matches[1]; $rel = $Matches[2].Trim(); if ($rel -ieq 'apply-patch.bat') { continue }; if ($rel -ieq 'config\application.yml') { Write-Host \"[WARN] config\application.yml is listed in the patch but is NEVER overwritten - the target settings are kept\"; continue }; $s = Join-Path '%PATCHROOT%' $rel; $t = Join-Path '%TARGET%' $rel; New-Item -ItemType Directory -Force (Split-Path $t) | Out-Null; Copy-Item $s $t -Force; if ((Get-Sha256 $t) -ne $exp) { Write-Host \"[ERROR] $rel copy verification failed\"; $fail = 1 } else { Write-Host \"[OK] $rel\" } }; exit $fail"
if not errorlevel 1 goto :copy_ok
call :log [ERROR] Copying the patch failed partway. Roll back with:
call :log [ERROR]   "%TARGET%\rollback.bat"
call :ib_pause
exit /b 1
:copy_ok
if exist "%PATCHROOT%\version.txt" copy /Y "%PATCHROOT%\version.txt" "%TARGET%\version.txt" >nul

rem --- step 4: restart + health check -------------------------------
call :log [INFO] Starting services - database migration runs automatically ...
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
call :log [INFO] Health check on http://127.0.0.1:%APP_PORT%/ - polling up to 90 seconds ...
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(90); $ok = $false; while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 5; $ok = $true; break } catch { if ($_.Exception.Response) { $ok = $true; break } else { Start-Sleep -Seconds 3 } } }; if ($ok) { exit 0 } else { exit 1 }"
if errorlevel 1 (
  echo [ERROR] The application did not answer the HTTP health check.
  goto :failed
)

rem --- step 5: built-in admin account (created by the app) --------
call :show_admin_pwfile
call :log [INFO] Patch applied successfully. Rollback bundle: %BUNDLE%
call :log [INFO] To undo this patch completely, run rollback.bat in the
call :log [INFO] target folder.
call :ib_pause
exit /b 0

:failed
echo.
call :log [ERROR] Last 50 lines of logs\app.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\app.log' -Tail 50 -ErrorAction SilentlyContinue"
call :log [ERROR] Last 50 lines of logs\IterationBoardApp.out.log:
powershell -NoProfile -Command "Get-Content '%TARGET%\logs\IterationBoardApp.out.log' -Tail 50 -ErrorAction SilentlyContinue"
echo.
call :log [ERROR] The patch did not complete. To roll back program files
call :log [ERROR] AND the database to the pre-patch state, run this
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

rem --- subroutine: marker validation for one candidate root ---
rem Sets IB_VALID=1 when the candidate holds both install
rem markers (app jar and config), 0 otherwise. The candidate
rem travels as %~1 so no path variable is expanded inside a
rem parenthesized block.
:pg_add
set "IB_LINE=%~1"
if "%IB_LINE:~0,8%"=="VERDICT:" set "IB_PGCHK=%IB_LINE:~8%"
if "%IB_LINE:~0,6%"=="PGDIR:" set "IB_PGDIR=%IB_LINE:~6%"
exit /b 0

:det_valid
set "IB_VALID=0"
if not exist "%~1\app\iteration-board.jar" exit /b 0
if not exist "%~1\config\application.yml" exit /b 0
set "IB_VALID=1"
exit /b 0

rem --- subroutine: detect the installed folder from the --------
rem --- subroutine: record the patch-made backup in the
rem target's data\backup-status.json, in the same agreed
rem format and with the same merge semantics as backup.bat's
rem :write_status (1.1.0, P23) - the backend reads this file,
rem and a patch-made backup must look exactly like a
rem backup.bat one. Inputs: IB_BK_RESULT, IB_BK_PATH.
:write_bk_status
if not exist "%TARGET%\data" mkdir "%TARGET%\data"
powershell -NoProfile -Command "$f = '%TARGET%\data\backup-status.json'; $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); $prev = $null; if (Test-Path $f) { try { $prev = Get-Content $f -Raw | ConvertFrom-Json } catch {} }; $o = [ordered]@{}; $o.lastAttemptTime = $now; if ($env:IB_BK_RESULT -eq 'success') { $o.lastSuccessTime = $now } elseif ($prev -and $prev.lastSuccessTime) { $o.lastSuccessTime = $prev.lastSuccessTime } else { $o.lastSuccessTime = $null }; $o.lastResult = $env:IB_BK_RESULT; $o.success = ($env:IB_BK_RESULT -eq 'success'); if ($env:IB_BK_RESULT -eq 'success') { $o.lastBackupPath = $env:IB_BK_PATH } elseif ($prev -and $prev.lastBackupPath) { $o.lastBackupPath = $prev.lastBackupPath } else { $o.lastBackupPath = $null }; $o.lastAttemptAt = $o.lastAttemptTime; $o.lastSuccessAt = $o.lastSuccessTime; $o.result = $env:IB_BK_RESULT.ToUpper(); $o.detail = $o.lastResult; [IO.File]::WriteAllText($f, ($o | ConvertTo-Json -Compress), [Text.Encoding]::ASCII)"
exit /b 0
rem IterationBoardApp service registration. Two authoritative
rem sources are read WITHOUT parsing sc.exe text output (its
rem field labels are localized with the OS language, so the old
rem BINARY_PATH_NAME regex silently found nothing on e.g.
rem Chinese Windows): Win32_Service PathName first, the
rem registry ImagePath (environment variables expanded) as the
rem second source. Each value is normalized (quotes stripped,
rem cut at the .exe, GetFullPath) and the install folder is two
rem levels up from <install>\app\IterationBoardApp.exe.
rem Distinct roots are counted in IB_DET_COUNT (IB_DET_1 /
rem IB_DET_2 hold the first two) and logged by the caller;
rem disagreement between the two sources is a real anomaly
rem (stale or delete-pending registration) and surfaces as a
rem count of 2, never silently merged. Raw values travel via
rem stdout lines prefixed RAW: and are only logged.
:detect_target
set "IB_DET_COUNT=0"
set "IB_DET_1="
set "IB_DET_2="
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "$vals = @(); $svc = Get-CimInstance Win32_Service -Filter \x22Name='IterationBoardApp'\x22 -ErrorAction SilentlyContinue; if ($svc -and $svc.PathName) { $vals += $svc.PathName }; try { $reg = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\IterationBoardApp' -ErrorAction Stop).ImagePath; if ($reg) { $vals += [Environment]::ExpandEnvironmentVariables($reg) } } catch {}; $out = @(); foreach ($v in $vals) { Write-Output ('RAW:' + $v); $p = $v.Trim().Trim([char]34); if ($p -match '^(.+?\.exe)') { $p = $Matches[1] }; try { $p = [IO.Path]::GetFullPath($p) } catch {}; $dir = Split-Path (Split-Path $p -Parent) -Parent; if ($dir -and ($out -notcontains $dir)) { $out += $dir } }; $out"`) do call :det_add "%%D"
exit /b 0

:det_add
set "IB_LINE=%~1"
if "%IB_LINE:~0,4%"=="RAW:" goto :det_raw
if "%IB_DET_COUNT%"=="0" set "IB_DET_1=%~1"
if "%IB_DET_COUNT%"=="1" set "IB_DET_2=%~1"
set /a IB_DET_COUNT+=1
exit /b 0
:det_raw
call :log [INFO] Service registration value: %IB_LINE:~4%
exit /b 0

rem --- subroutine: detect the target install's version --------
rem Sets CURVER (empty when undetectable). Primary source:
rem version.txt, first line, read through PowerShell (the house
rem pattern, see upgrade.bat). set /p must NOT be used here:
rem version.txt is written with LF line endings by the Linux
rem packaging, and set /p then reads past the first line and
rem embeds the line break in the value, which corrupts the
rem next if comparison (1.1.0 CI matrix N1: the run died with
rem a syntax error at the version gate). Legacy installs
rem (1.0.4 / 1.0.1) predate version.txt; they are fingerprinted
rem by layout markers verified against the shipped packages:
rem 1.0.4 carries the app-local VC++ DLLs under
rem runtime\pgsql\bin and no runtime\vc_redist.x64.exe;
rem 1.0.1 is the reverse. (The old jars carry no usable
rem version string: MANIFEST and pom.properties both say
rem 0.1.0-SNAPSHOT in 1.0.4 and 1.0.1 alike.) Conflicting or
rem absent markers leave CURVER empty, and a stamped patch's
rem version gate then stops the run, changing nothing.
:detect_version
set "CURVER="
if exist "%TARGET%\version.txt" goto :dv_txt
if exist "%TARGET%\runtime\vc_redist.x64.exe" goto :dv_maybe101
if exist "%TARGET%\runtime\pgsql\bin\msvcp140.dll" set "CURVER=1.0.4"
exit /b 0
:dv_maybe101
if not exist "%TARGET%\runtime\pgsql\bin\msvcp140.dll" set "CURVER=1.0.1"
exit /b 0
:dv_txt
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "(Get-Content '%TARGET%\version.txt' | Select-Object -First 1)"`) do set "CURVER=%%V"
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

rem --- subroutine: warn when the target is on a mapped drive ----
rem An administrator session usually cannot see the drive
rem mappings of the interactive user. The check runs in
rem PowerShell on the IB_DRIVE_L environment variable.
:check_mapped
set "IB_DRIVE_L=%TARGET:~0,1%"
powershell -NoProfile -Command "$d = Get-PSDrive $env:IB_DRIVE_L -ErrorAction SilentlyContinue; if ($d -and $d.DisplayRoot) { exit 1 } else { exit 0 }"
if errorlevel 1 goto :mapped_warn
exit /b 0
:mapped_warn
call :log [WARN] The target folder is on a mapped network drive.
call :log [WARN] An administrator session usually cannot see your
call :log [WARN] drive mappings. If anything fails with path errors,
call :log [WARN] re-run with the UNC path or a local path instead.
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
