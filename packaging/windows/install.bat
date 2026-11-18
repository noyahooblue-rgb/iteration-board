@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - offline installer (Windows 10/11/Server)
rem  Run as Administrator from the extracted package folder.
rem  All messages are ASCII-only on purpose (cmd codepage safe).
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "APP_PORT=8080"
if not "%~1"=="" set "APP_PORT=%~1"

rem --- 1. Administrator check --------------------------------
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please right-click install.bat and choose "Run as administrator".
  pause
  exit /b 1
)

rem --- 2. Path sanity check (no spaces recommended) ----------
rem NOTE: never test with "echo %ROOT% | findstr" - echo also
rem outputs the space before the pipe, so that test always
rem matches even for paths without spaces. Compare the path
rem against itself with all spaces removed instead.
if not "%ROOT%"=="%ROOT: =%" (
  echo [WARN] Install path contains a space: %ROOT%
  echo [WARN] A path without spaces and without non-ASCII characters is recommended, e.g. D:\iteration-board
  choice /C YN /M "Continue anyway"
  if errorlevel 2 exit /b 1
)

rem --- 3. VC++ 2015-2022 x64 runtime (app-local DLLs) ----------
rem PostgreSQL is built with MSVC and needs the VC++ 2015-2022
rem x64 runtime (the bundled JRE ships its own runtime DLLs).
rem The runtime DLLs ship inside this package, next to the
rem binaries that use them (app-local deployment of Microsoft's
rem redistributable DLLs; provenance and license are recorded
rem in THIRD_PARTY_NOTICES.md in the project repository).
rem Nothing is installed system-wide and vc_redist.x64.exe is
rem NOT used anymore: its silent install can hang indefinitely
rem on managed machines, with no error. This step only verifies
rem the DLLs are in place, and names every missing file if not.
set "VC_MISSING="
if not exist "%ROOT%\runtime\pgsql\bin\vcruntime140.dll"   set "VC_MISSING=%VC_MISSING% runtime\pgsql\bin\vcruntime140.dll"
if not exist "%ROOT%\runtime\pgsql\bin\vcruntime140_1.dll" set "VC_MISSING=%VC_MISSING% runtime\pgsql\bin\vcruntime140_1.dll"
if not exist "%ROOT%\runtime\pgsql\bin\msvcp140.dll"       set "VC_MISSING=%VC_MISSING% runtime\pgsql\bin\msvcp140.dll"
if not "%VC_MISSING%"=="" (
  echo [ERROR] Bundled VC++ runtime DLLs are missing from this package:
  for %%F in (%VC_MISSING%) do echo [ERROR]   - %%F
  echo [ERROR] The package is incomplete or was not fully extracted.
  echo [ERROR] Re-extract the full package ^(or re-apply the patch^),
  echo [ERROR] then run install.bat again.
  pause
  exit /b 1
)
echo [INFO] VC++ runtime DLLs are bundled in the package (app-local), nothing to install.

rem --- 4. Generate random DB password, write config ----------
set "CFG=%ROOT%\config\application.yml"
if exist "%ROOT%\config\.installed" (
  echo [INFO] Existing config detected (.installed marker). Keeping current config and database.
  goto :initdb_done
)
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N').Substring(0,16)"`) do set "DBPWD=%%P"
if "%DBPWD%"=="" (
  echo [ERROR] Failed to generate a random database password.
  pause
  exit /b 1
)
powershell -NoProfile -Command "(Get-Content '%CFG%') -replace '__DB_PASSWORD__','%DBPWD%' -replace '__APP_PORT__','%APP_PORT%' | Set-Content -Encoding ASCII '%CFG%'"
if errorlevel 1 (
  echo [ERROR] Failed to write config\application.yml.
  pause
  exit /b 1
)
echo installed %DATE% %TIME% > "%ROOT%\config\.installed"
echo [INFO] config\application.yml written (random DB password generated).

rem --- 5. initdb (UTF8) --------------------------------------
if exist "%ROOT%\data\pgdata\PG_VERSION" goto :initdb_done
echo [INFO] Initializing PostgreSQL data directory (UTF8) ...
if not exist "%ROOT%\data\pgdata" mkdir "%ROOT%\data\pgdata"
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
echo %DBPWD%> "%TEMP%\ib-pw.txt"
"%ROOT%\runtime\pgsql\bin\initdb.exe" -D "%ROOT%\data\pgdata" -U postgres -E UTF8 --auth=scram-sha-256 --pwfile="%TEMP%\ib-pw.txt"
set "IBRC=%ERRORLEVEL%"
del "%TEMP%\ib-pw.txt" >nul 2>&1
if not "%IBRC%"=="0" (
  echo [ERROR] initdb failed with code %IBRC%. See the initdb output above.
  if "%IBRC%"=="-1073741515" (
    echo [ERROR] Code -1073741515 ^(0xC0000135^) means a required DLL was not found.
    echo [ERROR] Check that vcruntime140.dll, vcruntime140_1.dll and msvcp140.dll
    echo [ERROR] exist in runtime\pgsql\bin, then run install.bat again.
  )
  pause
  exit /b 1
)
>>"%ROOT%\data\pgdata\postgresql.conf" echo port = 15432
>>"%ROOT%\data\pgdata\postgresql.conf" echo listen_addresses = 'localhost'
echo [INFO] Starting PostgreSQL temporarily to create the database ...
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" -D "%ROOT%\data\pgdata" -l "%ROOT%\logs\pg-init.log" -o "-p 15432" -w -t 60 start
if errorlevel 1 (
  echo [ERROR] Temporary PostgreSQL start failed. See logs\pg-init.log.
  pause
  exit /b 1
)
set "PGPASSWORD=%DBPWD%"
"%ROOT%\runtime\pgsql\bin\createdb.exe" -h 127.0.0.1 -p 15432 -U postgres iteration_board
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" -D "%ROOT%\data\pgdata" -m fast -w -t 60 stop >nul 2>&1
if not "%IBRC%"=="0" (
  echo [ERROR] createdb failed with code %IBRC%.
  pause
  exit /b 1
)
echo [INFO] Database iteration_board created.
:initdb_done

rem --- 6. Register services with WinSW ------------------------
echo [INFO] Registering Windows services ...
copy /Y "%ROOT%\runtime\winsw\WinSW-x64.exe" "%ROOT%\app\IterationBoardApp.exe" >nul
copy /Y "%ROOT%\runtime\winsw\WinSW-x64.exe" "%ROOT%\app\IterationBoardPG.exe" >nul
copy /Y "%ROOT%\service\IterationBoardApp.xml" "%ROOT%\app\IterationBoardApp.xml" >nul
copy /Y "%ROOT%\service\IterationBoardPG.xml" "%ROOT%\app\IterationBoardPG.xml" >nul
set "IB_ROOT=%ROOT%"
powershell -NoProfile -Command "Get-Content '%ROOT%\app\IterationBoardApp.xml' | ForEach-Object { $_.Replace('__INSTALL_DIR__', $env:IB_ROOT) } | Set-Content -Encoding ASCII '%ROOT%\app\IterationBoardApp.xml'"
powershell -NoProfile -Command "Get-Content '%ROOT%\app\IterationBoardPG.xml' | ForEach-Object { $_.Replace('__INSTALL_DIR__', $env:IB_ROOT) } | Set-Content -Encoding ASCII '%ROOT%\app\IterationBoardPG.xml'"
"%ROOT%\app\IterationBoardPG.exe" install
if errorlevel 1 ( echo [ERROR] Failed to register IterationBoardPG service. & pause & exit /b 1 )
"%ROOT%\app\IterationBoardApp.exe" install
if errorlevel 1 ( echo [ERROR] Failed to register IterationBoardApp service. & pause & exit /b 1 )

rem --- 7. Daily backup task at 02:00 --------------------------
schtasks /Create /TN "IterationBoardBackup" /TR "\"%ROOT%\backup.bat\"" /SC DAILY /ST 02:00 /RU SYSTEM /F >nul 2>&1
if errorlevel 1 (
  echo [WARN] Could not create the daily 02:00 backup scheduled task. You can create it later in Task Scheduler.
) else (
  echo [INFO] Daily backup task created (02:00, keeps 30 days).
)

rem --- 8. Start services --------------------------------------
call "%ROOT%\start.bat" --no-pause
if errorlevel 1 (
  echo [ERROR] Service start failed. Check the logs folder.
  pause
  exit /b 1
)

echo.
echo ============================================================
echo  Installation complete.
echo  Open in a browser:  http://localhost:%APP_PORT%/
echo  From other PCs on the LAN:  http://^<this-PC-IP^>:%APP_PORT%/
echo  IMPORTANT: the FIRST account registered becomes the
echo  administrator. Register it right away.
echo ============================================================
pause
exit /b 0

