@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - migration export (revision 3, 1.1.0)
rem  Usage: migrate-export.bat [install-folder] [--yes] [--no-pause]
rem  Run on the OLD computer. Produces, next to this script:
rem    iteration-board-migration-<yyyyMMdd>.zip
rem  containing:
rem    db.dump              pg_dump custom-format full export
rem    data\uploads\        uploaded image files
rem    config\prompts\      prompt templates (business config)
rem    config\application.yml  business config copy (port, AI
rem                         connection) with the database
rem                         password REMOVED - the password is
rem                         never exported; the new computer
rem                         keeps its own. No password file of
rem                         any kind is included.
rem    manifest.json        program version, Flyway schema
rem                         version, database server version,
rem                         export time, SHA-256 of every file
rem  The application service must be STOPPED during export
rem  (this script offers to stop it, and restarts it at the end
rem  if it did); PostgreSQL must stay RUNNING.
rem  If the zip is larger than 98,000,000 bytes it is split
rem  into .001/.002/... volumes of 98,000,000 bytes each, with
rem  a merge-migration.bat and the SHA-256 of the full zip,
rem  following the established split delivery practice.
rem  NOTE: the package contains the AI api-key from the config
rem  (part of the AI connection settings). Treat the package
rem  as a sensitive file.
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
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
if "%TARGET%"=="" set "TARGET=%ROOT%"
if not exist "%TARGET%\tmp" mkdir "%TARGET%\tmp"
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please run as administrator.
  call :ib_pause
  exit /b 1
)
if not exist "%TARGET%\config\application.yml" (
  echo [ERROR] Install folder not found or invalid: %TARGET%
  call :ib_pause
  exit /b 1
)

rem --- application service must be stopped -----------------------
set "STOPPED_BY_ME=0"
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if not errorlevel 1 (
  echo [INFO] The application service is still running. It must be
  echo [INFO] stopped during the export so the database and the
  echo [INFO] uploaded files stay consistent with each other.
  if "%YES%"=="0" (
    choice /C YN /M "Stop the application service now"
    if errorlevel 2 (
      echo [INFO] Aborted. Run stop.bat first, then run this script again.
      call :ib_pause
      exit /b 0
    )
  )
  net stop IterationBoardApp >nul 2>&1
  set "STOPPED_BY_ME=1"
  echo [INFO] Application service stopped.
)

rem --- PostgreSQL must be running ---------------------------------
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [INFO] PostgreSQL is not running - starting it ...
  net start IterationBoardPG >nul 2>&1
)
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL service is not running and could not be started.
  goto :fail
)

rem --- read DB password ----------------------------------------------
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%TARGET%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" (
  echo [ERROR] Could not read DB password from config\application.yml
  goto :fail
)

rem --- versions for the manifest --------------------------------------
set "PROGVER=unknown"
if exist "%TARGET%\version.txt" for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "(Get-Content '%TARGET%\version.txt' | Select-Object -First 1)"`) do set "PROGVER=%%V"
set "PGPASSWORD=%DBPWD%"
set "FLYVER=unknown"
for /f "usebackq delims=" %%R in (`"%TARGET%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -t -A -c "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1"`) do set "FLYVER=%%R"
set "DBVER=unknown"
for /f "usebackq delims=" %%R in (`"%TARGET%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -t -A -c "SHOW server_version"`) do set "DBVER=%%R"

rem --- staging ---------------------------------------------------------
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "TS=%%T"
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd"`) do set "DATES=%%D"
set "WORK=%TARGET%\tmp\ib-migrate-%TS%"
mkdir "%WORK%\config" >nul 2>&1
mkdir "%WORK%\data" >nul 2>&1

echo [INFO] Exporting the database with pg_dump ...
"%TARGET%\runtime\pgsql\bin\pg_dump.exe" -h 127.0.0.1 -p 15432 -U postgres -Fc -f "%WORK%\db.dump" iteration_board
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [ERROR] pg_dump failed with code %IBRC%.
  goto :fail
)
if exist "%TARGET%\data\uploads" (
  echo [INFO] Copying uploaded files ...
  robocopy "%TARGET%\data\uploads" "%WORK%\data\uploads" /E /NFL /NDL /NJH /NJS >nul
  if errorlevel 8 (
    echo [ERROR] Copying data\uploads failed.
    goto :fail
  )
)
if exist "%TARGET%\config\prompts" robocopy "%TARGET%\config\prompts" "%WORK%\config\prompts" /E /NFL /NDL /NJH /NJS >nul
echo [INFO] Writing the business config copy - database password removed ...
powershell -NoProfile -Command "$c = [IO.File]::ReadAllText('%TARGET%\config\application.yml'); $c = $c -replace '(?m)^(\s*password:\s*).*$', '$1""'; [IO.File]::WriteAllText('%WORK%\config\application.yml', $c, [Text.Encoding]::ASCII)"
findstr /C:"__DB_PASSWORD__" "%WORK%\config\application.yml" >nul 2>&1
if not errorlevel 1 (
  echo [ERROR] The exported config still contains a placeholder password.
  goto :fail
)

rem --- manifest ---------------------------------------------------------
echo [INFO] Building manifest.json with SHA-256 hashes ...
set "IB_WORK=%WORK%"
set "IB_PROGVER=%PROGVER%"
set "IB_FLYVER=%FLYVER%"
set "IB_DBVER=%DBVER%"
powershell -NoProfile -Command "function Get-Sha256([string]$path) { $fs = [IO.File]::OpenRead($path); try { return [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($fs)).Replace('-', '').ToLower() } finally { $fs.Close() } }; $root = $env:IB_WORK; $files = @(); Get-ChildItem $root -Recurse -File | ForEach-Object { $rel = $_.FullName.Substring($root.Length + 1).Replace('\', '/'); $files += [ordered]@{ path = $rel; sha256 = (Get-Sha256 $_.FullName); size = $_.Length } }; $m = [ordered]@{ format = 'iteration-board-migration'; programVersion = $env:IB_PROGVER; flywayVersion = $env:IB_FLYVER; databaseServerVersion = $env:IB_DBVER; exportTime = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); files = $files }; [IO.File]::WriteAllText((Join-Path $root 'manifest.json'), ($m | ConvertTo-Json -Depth 4), [Text.Encoding]::ASCII)"

rem --- zip + split if needed ----------------------------------------------
set "ZIP=%ROOT%\iteration-board-migration-%DATES%.zip"
if exist "%ZIP%" del /Q "%ZIP%" >nul 2>&1
echo [INFO] Creating %ZIP% ...
powershell -NoProfile -Command "Compress-Archive -Path '%WORK%\*' -DestinationPath '%ZIP%' -Force"
if errorlevel 1 (
  echo [ERROR] Creating the migration zip failed.
  goto :fail
)
powershell -NoProfile -Command "if ((Get-Item '%ZIP%').Length -gt 98000000) { exit 3 }"
if errorlevel 3 goto :split
echo.
echo ============================================================
echo  Migration export complete:
echo    %ZIP%
echo  Copy it to the new computer, install the same or a newer
echo  version there first, then run migrate-import.bat.
echo ============================================================
goto :cleanup

:split
echo [INFO] The package is larger than 98,000,000 bytes - splitting ...
set "IB_ZIP=%ZIP%"
powershell -NoProfile -Command "function Get-Sha256([string]$path) { $fs = [IO.File]::OpenRead($path); try { return [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($fs)).Replace('-', '').ToLower() } finally { $fs.Close() } }; $src = $env:IB_ZIP; $size = 98000000; $hash = (Get-Sha256 $src); [IO.File]::WriteAllText($src + '.sha256.txt', ($hash + '  ' + [IO.Path]::GetFileName($src) + \"`r`n\"), [Text.Encoding]::ASCII); $fs = [IO.File]::OpenRead($src); $buf = New-Object byte[] $size; $i = 1; $names = @(); while (($n = $fs.Read($buf, 0, $size)) -gt 0) { $name = $src + '.' + $i.ToString('000'); $out = [IO.File]::Create($name); $out.Write($buf, 0, $n); $out.Close(); $names += [IO.Path]::GetFileName($name); $i++ }; $fs.Close(); $base = [IO.Path]::GetFileName($src); $parts = ($names | ForEach-Object { '\"%%DIR%%' + $_ + '\"' }) -join '+'; $lines = @('@echo off', 'rem Merge the migration package volumes and show the SHA-256.', 'set \"DIR=%%~dp0\"', ('copy /b ' + $parts + ' \"%%DIR%%' + $base + '\"'), ('certutil -hashfile \"%%DIR%%' + $base + '\" SHA256'), ('echo Compare the hash above with ' + $base + '.sha256.txt next to this file.'), 'pause'); [IO.File]::WriteAllText((Join-Path (Split-Path $src) 'merge-migration.bat'), ($lines -join \"`r`n\") + \"`r`n\", [Text.Encoding]::ASCII)"
del /Q "%ZIP%" >nul 2>&1
echo.
echo ============================================================
echo  Migration export complete - split volumes:
echo    %ZIP%.001 ... plus merge-migration.bat and
echo    iteration-board-migration-%DATES%.zip.sha256.txt
echo  Copy ALL of them to one folder on the new computer and
echo  run merge-migration.bat there first, or pass the .001
echo  file to migrate-import.bat - it merges automatically.
echo ============================================================
goto :cleanup

:cleanup
rmdir /S /Q "%WORK%" >nul 2>&1
if "%STOPPED_BY_ME%"=="1" (
  echo [INFO] Restarting the application service ...
  net start IterationBoardApp >nul 2>&1
)
call :ib_pause
exit /b 0

:fail
rmdir /S /Q "%WORK%" >nul 2>&1
if "%STOPPED_BY_ME%"=="1" (
  echo [INFO] Restarting the application service ...
  net start IterationBoardApp >nul 2>&1
)
call :ib_pause
exit /b 1

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
