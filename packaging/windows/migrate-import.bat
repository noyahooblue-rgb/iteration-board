@echo off
setlocal EnableExtensions
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
if "%TARGET%"=="" set "TARGET=%ROOT%"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)
if not exist "%TARGET%\config\application.yml" (
  echo [ERROR] Install folder not found or invalid: %TARGET%
  echo [ERROR] Install the platform on this computer first.
  call :ib_pause
  exit /b 1
)
if "%PKG%"=="" (
  for /f "usebackq delims=" %%F in (`powershell -NoProfile -Command "Get-ChildItem '%ROOT%\iteration-board-migration-*.zip' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { $_.FullName }"`) do set "PKG=%%F"
)
if "%PKG%"=="" (
  echo [ERROR] No migration package given and none found next to this script.
  echo [ERROR] Usage: migrate-import.bat [package.zip or package.zip.001]
  call :ib_pause
  exit /b 1
)

rem --- step 1: merge volumes when a .001 file was given -----------
set "ZIP=%PKG%"
if /i "%PKG:~-4%"==".001" (
  set "ZIP=%PKG:~0,-4%"
  echo [INFO] Merging split volumes into %ZIP% ...
  set "IB_FIRST=%PKG%"
  powershell -NoProfile -Command "$first = $env:IB_FIRST; $dir = Split-Path $first; $base = [IO.Path]::GetFileName($first); $base = $base.Substring(0, $base.Length - 4); $out = [IO.File]::Create((Join-Path $dir $base)); $i = 1; while ($true) { $v = Join-Path $dir ($base + '.' + $i.ToString('000')); if (-not (Test-Path $v)) { break }; $in = [IO.File]::OpenRead($v); $in.CopyTo($out); $in.Close(); $i++ }; $out.Close(); if ($i -eq 1) { exit 1 }"
  if errorlevel 1 (
    echo [ERROR] Merging the volumes failed.
    call :ib_pause
    exit /b 1
  )
)
if not exist "%ZIP%" (
  echo [ERROR] Migration package not found: %ZIP%
  call :ib_pause
  exit /b 1
)

rem --- extract --------------------------------------------------------
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
set "WORK=%TEMP%\ib-migrate-import-%TS%"
mkdir "%WORK%" >nul 2>&1
echo [INFO] Extracting the migration package ...
powershell -NoProfile -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%WORK%' -Force"
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
echo [INFO] Verifying manifest and file hashes ...
set "IB_WORK=%WORK%"
powershell -NoProfile -Command "$m = Get-Content (Join-Path $env:IB_WORK 'manifest.json') -Raw | ConvertFrom-Json; $fail = 0; foreach ($f in $m.files) { $p = Join-Path $env:IB_WORK ($f.path.Replace('/', '\')); if (-not (Test-Path $p)) { Write-Host \"[ERROR] missing file: $($f.path)\"; $fail = 1; continue }; $h = (Get-FileHash $p -Algorithm SHA256).Hash.ToLower(); if ($h -ne $f.sha256) { Write-Host \"[ERROR] hash mismatch: $($f.path)\"; $fail = 1 } }; if ($fail -eq 0) { Write-Host '[INFO] All files verified.' }; exit $fail"
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
if "%VRC%"=="2" (
  echo [WARN] Version comparison was not possible. Continuing only
  echo [WARN] because you confirm the versions are compatible.
  if "%YES%"=="0" (
    choice /C YN /M "Continue without a version check"
    if errorlevel 2 goto :fail
  )
)

if "%YES%"=="0" (
  choice /C YN /M "Import this package into %TARGET% now - the current database here will be replaced after a backup"
  if errorlevel 2 exit /b 0
)

rem --- step 4: back up the current database on this computer -------------
echo [INFO] Backing up the current database on this computer first ...
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
echo [INFO] Current database backed up to: %DBBACKUP%

rem --- step 5: restore database, uploads, prompts, business config ---------
echo [INFO] Stopping the application service ...
net stop IterationBoardApp >nul 2>&1
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%TARGET%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" (
  echo [ERROR] Could not read DB password from config\application.yml
  goto :fail
)
set "PGPASSWORD=%DBPWD%"
echo [INFO] Restoring the database ...
"%TARGET%\runtime\pgsql\bin\pg_restore.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board --clean --if-exists "%WORK%\db.dump"
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [ERROR] pg_restore failed with code %IBRC%. The pre-import
  echo [ERROR] backup is at %DBBACKUP% - restore it with restore.bat.
  goto :fail
)
if exist "%WORK%\data\uploads" (
  echo [INFO] Restoring uploaded files ...
  if not exist "%TARGET%\data\uploads" mkdir "%TARGET%\data\uploads"
  robocopy "%WORK%\data\uploads" "%TARGET%\data\uploads" /E /NFL /NDL /NJH /NJS >nul
  if errorlevel 8 (
    echo [ERROR] Restoring uploaded files failed.
    goto :fail
  )
)
if exist "%WORK%\config\prompts" robocopy "%WORK%\config\prompts" "%TARGET%\config\prompts" /E /NFL /NDL /NJH /NJS >nul
if exist "%WORK%\config\application.yml" (
  echo [INFO] Applying the exported port and AI connection settings ...
  set "IB_OLD=%WORK%\config\application.yml"
  set "IB_NEW=%TARGET%\config\application.yml"
  powershell -NoProfile -Command "$old = [IO.File]::ReadAllText($env:IB_OLD); $new = [IO.File]::ReadAllText($env:IB_NEW); foreach ($k in @('base-url','api-key','text-model','vision-model','mode')) { if ($old -match ('(?m)^\s*' + $k + ':\s*(.*)$')) { $v = $Matches[1]; $new = $new -replace ('(?m)^(\s*' + $k + ':\s*).*$'), ('$1' + $v) } }; if ($old -match '(?m)^\s*port:\s*(\d+)') { $p = $Matches[1]; $new = $new -replace '(?m)^(\s*port:\s*)\d+', ('$1' + $p) }; [IO.File]::WriteAllText($env:IB_NEW, $new, [Text.Encoding]::ASCII)"
  echo [INFO] Port and AI settings applied. This installation's own
  echo [INFO] database password was kept.
)

rem --- step 6: start the application - Flyway migrates -----------------------
echo [INFO] Starting the application service ...
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] The application failed to start after the import.
  echo [ERROR] See the logs folder in %TARGET%.
  goto :fail
)
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

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
