@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem ============================================================
rem  Iteration Board - migration import (revision 3, 1.1.0)
rem  Usage: migrate-import.bat [package.zip | package.zip.001]
rem                             [install-folder] [--yes] [--no-pause]
rem  Run on the NEW computer AFTER the platform has been
rem  installed there with the SAME or a NEWER version than the
rem  exporting computer (install.bat from the new package).
rem  Steps:
rem    1. if a .001 volume is given, merge all volumes first;
rem    2. verify manifest.json and the SHA-256 of every file;
rem    3. version rule: a migration package may only be
rem       imported into a program of the SAME or a NEWER
rem       version. If the manifest programVersion is higher
rem       than this installation's version (version.txt, or
rem       this script's package version when the file is
rem       absent), the import is REFUSED. Versions compare as
rem       dotted numbers, e.g. 1.0.4 < 1.1.0 < 1.1.1;
rem    4. back up the new computer's current database first
rem       (into the configured backup directory);
rem    5. restore the database (pg_restore --clean), the
rem       uploaded files and the prompts; apply the exported
rem       business settings (application port and AI connection)
rem       to this installation's config - this installation's
rem       own database password is kept, the exported config
rem       contains no password by design;
rem    6. start the application: Flyway migrates the imported
rem       database to this version automatically.
rem  Tell colleagues the access address may change with the
rem  new computer (new IP) - see the deployment manual.
rem ============================================================
set "IB_VERSION=1.1.0"
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "PKG="
set "TARGET="
set "YES=0"
set "NOPAUSE=0"
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--yes" set "YES=1"
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i not "%~1"=="--yes" if /i not "%~1"=="--no-pause" if not "%PKG%"=="" if "%TARGET%"=="" set "TARGET=%~1"
if /i not "%~1"=="--yes" if /i not "%~1"=="--no-pause" if "%PKG%"=="" set "PKG=%~1"
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
if "%TARGET%"=="" set "TARGET=%ROOT%"
rem --- 0. Install path must be ASCII printable (ruling A) -----
rem PostgreSQL on Windows hands paths to its own helper
rem processes through the system ANSI code page; a character
rem outside the ASCII printable range can be replaced by "?"
rem there, and initdb then cannot find its own binary
rem (root-cause ledger #6). Block before changing anything.
rem The character test itself runs in PowerShell on an
rem environment variable - never judge characters inside cmd,
rem where quoting and codepage rules corrupt the test itself.
set "IB_ROOT=%TARGET%"
powershell -NoProfile -Command "if ($env:IB_ROOT -match '[^\x20-\x7E]') { exit 1 } else { exit 0 }"
set "IB_PATH_RC=%ERRORLEVEL%"
if "%IB_PATH_RC%"=="0" goto :ib_pathwarn_done
  echo [ERROR] The install folder path contains non-English characters: %TARGET%.
  echo [ERROR] PostgreSQL cannot run from such a path reliably. Move the whole
  echo [ERROR] folder to a path with only English letters, digits and spaces,
  echo [ERROR] for example D:\IterationBoard, then run migrate-import.bat again.
  call :ib_pause
  exit /b 1
:ib_pathwarn_done
if not exist "%TARGET%\tmp" mkdir "%TARGET%\tmp"
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"
if exist "%TARGET%\config\application.yml" goto :target_ok
call :log [ERROR] Install folder not found or invalid: %TARGET%
call :log [ERROR] Install the platform on this computer first.
call :ib_pause
exit /b 1
:target_ok
if not "%PKG%"=="" goto :pkg_done
for /f "usebackq delims=" %%F in (`powershell -NoProfile -Command "Get-ChildItem '%ROOT%\iteration-board-migration-*.zip' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { $_.FullName }"`) do set "PKG=%%F"
:pkg_done
if "%PKG%"=="" (
  echo [ERROR] No migration package given and none found next to this script.
  echo [ERROR] Usage: migrate-import.bat [package.zip or package.zip.001]
  call :ib_pause
  exit /b 1
)

rem --- step 1: merge volumes when a .001 file was given -----------
set "ZIP=%PKG%"
if /i not "%PKG:~-4%"==".001" goto :merge_done
set "ZIP=%PKG:~0,-4%"
call :log [INFO] Merging split volumes into %ZIP% ...
set "IB_FIRST=%PKG%"
powershell -NoProfile -Command "$first = $env:IB_FIRST; $dir = Split-Path $first; $base = [IO.Path]::GetFileName($first); $base = $base.Substring(0, $base.Length - 4); $out = [IO.File]::Create((Join-Path $dir $base)); $i = 1; while ($true) { $v = Join-Path $dir ($base + '.' + $i.ToString('000')); if (-not (Test-Path $v)) { break }; $in = [IO.File]::OpenRead($v); $in.CopyTo($out); $in.Close(); $i++ }; $out.Close(); if ($i -eq 1) { exit 1 }"
if not errorlevel 1 goto :merge_done
call :log [ERROR] Merging the volumes failed.
call :ib_pause
exit /b 1
:merge_done
if exist "%ZIP%" goto :zip_ok
call :log [ERROR] Migration package not found: "%ZIP%"
call :ib_pause
exit /b 1
:zip_ok

rem --- extract --------------------------------------------------------
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "WORK=%TARGET%\tmp\ib-migrate-import-%TS%"
mkdir "%WORK%" >nul 2>&1
call :log [INFO] Extracting the migration package ...
powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory('%ZIP%', '%WORK%')"
if errorlevel 1 (
  echo [ERROR] Extracting the migration package failed.
  goto :fail
)
if not exist "%WORK%\manifest.json" (
  echo [ERROR] manifest.json missing - this is not a migration package
  echo [ERROR] produced by migrate-export.bat.
  goto :fail
)
if not exist "%WORK%\db.dump" (
  echo [ERROR] db.dump missing in the migration package.
  goto :fail
)

rem --- step 2: verify hashes ---------------------------------------------
call :log [INFO] Verifying manifest and file hashes ...
set "IB_WORK=%WORK%"
powershell -NoProfile -Command "function Get-Sha256([string]$path) { $fs = [IO.File]::OpenRead($path); try { return [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($fs)).Replace('-', '').ToLower() } finally { $fs.Close() } }; $m = Get-Content (Join-Path $env:IB_WORK 'manifest.json') -Raw | ConvertFrom-Json; $fail = 0; foreach ($f in $m.files) { $p = Join-Path $env:IB_WORK ($f.path.Replace('/', '\')); if (-not (Test-Path $p)) { Write-Host \"[ERROR] missing file: $($f.path)\"; $fail = 1; continue }; $h = (Get-Sha256 $p); if ($h -ne $f.sha256) { Write-Host \"[ERROR] hash mismatch: $($f.path)\"; $fail = 1 } }; if ($fail -eq 0) { Write-Host '[INFO] All files verified.' }; exit $fail"
if errorlevel 1 (
  echo [ERROR] Hash verification failed - the package is incomplete
  echo [ERROR] or corrupted. Copy it again and re-run. Nothing changed.
  goto :fail
)

rem --- step 3: version rule ------------------------------------------------
set "LOCALVER=%IB_VERSION%"
if exist "%TARGET%\version.txt" for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "(Get-Content '%TARGET%\version.txt' | Select-Object -First 1)"`) do set "LOCALVER=%%V"
set "IB_LOCALVER=%LOCALVER%"
powershell -NoProfile -Command "$m = Get-Content (Join-Path $env:IB_WORK 'manifest.json') -Raw | ConvertFrom-Json; $pv = $m.programVersion; if (-not $pv -or $pv -eq 'unknown') { Write-Host '[WARN] The package does not record its program version.'; exit 2 }; try { $a = [version]$pv; $b = [version]$env:IB_LOCALVER } catch { exit 2 }; if ($a -gt $b) { Write-Host \"[ERROR] Package version $pv is NEWER than this installation $env:IB_LOCALVER.\"; exit 1 }; Write-Host \"[INFO] Version check OK: package $pv into installation $env:IB_LOCALVER.\"; exit 0"
set "VRC=%ERRORLEVEL%"
if "%VRC%"=="1" (
  echo [ERROR] Refusing to import newer data into an older program.
  echo [ERROR] Install the same or a newer version here first.
  goto :fail
)
if not "%VRC%"=="2" goto :vrc_done
call :log [WARN] Version comparison was not possible. Continuing only
call :log [WARN] because you confirm the versions are compatible.
if "%YES%"=="1" goto :vrc_done
choice /C YN /M "Continue without a version check"
if errorlevel 2 goto :fail
:vrc_done

call :log [INFO] Import target: %TARGET%
if "%YES%"=="1" goto :confirm_done
choice /C YN /M "Import this package into the target now - the current database here will be replaced after a backup"
if errorlevel 2 exit /b 0
:confirm_done

rem --- step 4: back up the current database on this computer -------------
call :log [INFO] Backing up the current database on this computer first ...
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 net start IterationBoardPG >nul 2>&1
call "%TARGET%\backup.bat" auto
if errorlevel 1 (
  echo [ERROR] The pre-import backup failed. Import aborted.
  goto :fail
)
set "DBBACKUP="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$f = '%TARGET%\data\backup-status.json'; if (Test-Path $f) { try { $j = Get-Content $f -Raw | ConvertFrom-Json; if ($j.success -and $j.lastBackupPath -and ((Get-Item (Join-Path $j.lastBackupPath 'db.dump')).LastWriteTime -gt (Get-Date).AddMinutes(-15))) { $j.lastBackupPath } } catch {} }"`) do set "DBBACKUP=%%P"
if "%DBBACKUP%"=="" (
  echo [ERROR] No fresh backup of the current database was produced.
  echo [ERROR] Import aborted, nothing changed.
  goto :fail
)
call :log [INFO] Current database backed up to: %DBBACKUP%

rem --- step 5: restore database, uploads, prompts, business config ---------
call :log [INFO] Stopping the application service ...
net stop IterationBoardApp >nul 2>&1
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%TARGET%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" (
  echo [ERROR] Could not read DB password from config\application.yml
  goto :fail
)
set "PGPASSWORD=%DBPWD%"
call :log [INFO] Restoring the database ...
"%TARGET%\runtime\pgsql\bin\pg_restore.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board --clean --if-exists "%WORK%\db.dump"
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if "%IBRC%"=="0" goto :restore_ok
call :log [ERROR] pg_restore failed with code %IBRC%. The pre-import
call :log [ERROR] backup is at %DBBACKUP% - restore it with restore.bat.
goto :fail
:restore_ok
if not exist "%WORK%\data\uploads" goto :uploads_done
call :log [INFO] Restoring uploaded files ...
if not exist "%TARGET%\data\uploads" mkdir "%TARGET%\data\uploads"
robocopy "%WORK%\data\uploads" "%TARGET%\data\uploads" /E /NFL /NDL /NJH /NJS >nul
if not errorlevel 8 goto :uploads_done
call :log [ERROR] Restoring uploaded files failed.
goto :fail
:uploads_done
if exist "%WORK%\config\prompts" robocopy "%WORK%\config\prompts" "%TARGET%\config\prompts" /E /NFL /NDL /NJH /NJS >nul
if not exist "%WORK%\config\application.yml" goto :settings_done
call :log [INFO] Applying the exported port and AI connection settings ...
set "IB_OLD=%WORK%\config\application.yml"
set "IB_NEW=%TARGET%\config\application.yml"
rem IB-SETTINGS-COMMAND-BEGIN
rem The powershell command below is extracted and run verbatim
rem by tools/ci/test-migrate-settings.ps1 - keep it on one line
rem and keep these markers. It writes the exported values with
rem braced group references: a plain $1 directly followed by a
rem digit is parsed by .NET as one large group number and
rem leaks into the file literally - root-cause ledger entry 8.
rem The command validates the rewritten file before returning;
rem on failure this script restores the previous config from
rem the pre-import backup and aborts without starting the app.
powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; try { $old = [IO.File]::ReadAllText($env:IB_OLD); $before = [IO.File]::ReadAllText($env:IB_NEW); $new = $before; $applied = @{}; foreach ($k in @('base-url','api-key','text-model','vision-model','mode')) { if ($old -match ('(?m)^\s*' + $k + ':\s*(.*)$')) { $v = $Matches[1].Trim(); $applied[$k] = $v; $new = $new -replace ('(?m)^(\s*' + $k + ':\s*).*$'), ('${1}' + $v.Replace('$', '$$')) } }; $wantPort = $null; if ($old -match '(?m)^\s*port:\s*(\d+)') { $wantPort = $Matches[1]; $new = $new -replace '(?m)^(\s*port:\s*)\d+', ('${1}' + $wantPort) }; [IO.File]::WriteAllText($env:IB_NEW, $new, [Text.Encoding]::ASCII); $written = [IO.File]::ReadAllText($env:IB_NEW); foreach ($line in ($written -split '\r?\n')) { if ($line -match '^\s*$' -or $line -match '^\s*#' -or $line -match '^\s*-(\s+.*)?$') { continue }; if ($line -notmatch '^(\s*)([A-Za-z0-9_.\-]+):(\s+.*)?$') { Write-Host ('[ERROR] Rewritten config line is not valid YAML mapping syntax: ' + $line); exit 1 }; if ($Matches[1].Contains([char]9)) { Write-Host '[ERROR] Rewritten config uses a tab character for indentation.'; exit 1 } }; if ($wantPort) { if ($written -notmatch '(?m)^\s*port:\s*(\d+)') { Write-Host '[ERROR] Rewritten config has no readable port line.'; exit 1 }; if ($Matches[1] -ne $wantPort) { Write-Host ('[ERROR] Rewritten config port is ' + $Matches[1] + ' but the exported port is ' + $wantPort + '.'); exit 1 } }; foreach ($k in $applied.Keys) { if ($written -notmatch ('(?m)^\s*' + $k + ':\s*(.*)$')) { Write-Host ('[ERROR] Rewritten config lost its ' + $k + ' line.'); exit 1 }; if ($Matches[1].Trim() -ne $applied[$k]) { Write-Host ('[ERROR] Rewritten config value for ' + $k + ' does not match the exported value.'); exit 1 } }; $pwdB = $null; if ($before -match '(?m)^\s*password:\s*(.*)$') { $pwdB = $Matches[1] }; $pwdA = $null; if ($written -match '(?m)^\s*password:\s*(.*)$') { $pwdA = $Matches[1] }; if ($pwdB -ne $pwdA) { Write-Host '[ERROR] Rewritten config changed the database password line.'; exit 1 }; Write-Host '[INFO] Rewritten config validated: YAML structure OK; port and AI values read back as intended.'; exit 0 } catch { Write-Host ('[ERROR] Applying the exported settings failed: ' + $_.Exception.Message); exit 1 }"
if errorlevel 1 goto :settings_fail
rem IB-SETTINGS-COMMAND-END
call :log [INFO] Port and AI settings applied and validated. This
call :log [INFO] installation's own database password was kept.
goto :settings_done
:settings_fail
call :log [ERROR] The exported settings were NOT applied safely: the
call :log [ERROR] rewritten config failed validation. Restoring the
call :log [ERROR] previous config from the pre-import backup ...
copy /Y "%DBBACKUP%\application.yml" "%TARGET%\config\application.yml" >nul
if not errorlevel 1 goto :cfg_restored
call :log [ERROR] Restoring the previous config FAILED too. Copy
call :log [ERROR] application.yml back manually from this folder:
call :log [ERROR]   "%DBBACKUP%"
:cfg_restored
call :log [ERROR] Import aborted - the application was not started.
call :log [ERROR] The database here was already restored from the
call :log [ERROR] migration package. To return to the pre-import
call :log [ERROR] database instead, run restore.bat and pick:
call :log [ERROR]   "%DBBACKUP%"
goto :fail
:settings_done

rem --- step 6: start the application - Flyway migrates -----------------------
call :log [INFO] Starting the application service ...
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if not errorlevel 1 goto :app_started
call :log [ERROR] The application failed to start after the import.
call :log [ERROR] See the logs folder in %TARGET%.
goto :fail
:app_started
set "APP_PORT=18080"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(90); $ok = $false; while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/' -TimeoutSec 5; $ok = $true; break } catch { if ($_.Exception.Response) { $ok = $true; break } else { Start-Sleep -Seconds 3 } } }; if ($ok) { exit 0 } else { exit 1 }"
if errorlevel 1 (
  echo [ERROR] The application did not answer the HTTP health check.
  echo [ERROR] The imported data is in place; check the logs folder.
  goto :fail
)
echo.
echo ============================================================
echo  Migration import complete.
echo  The database was migrated automatically by Flyway on start.
echo  Colleagues reach the platform at this computer's address:
echo    http://^<this-PC-IP^>:%APP_PORT%/
echo  If the address changed, tell the team the new one - see
echo  the deployment manual, chapter "Migrating to another PC".
echo ============================================================
rmdir /S /Q "%WORK%" >nul 2>&1
call :ib_pause
exit /b 0

:fail
rmdir /S /Q "%WORK%" >nul 2>&1
call :ib_pause
exit /b 1

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
