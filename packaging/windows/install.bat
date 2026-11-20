@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - offline installer (Windows 10/11/Server)
rem  Run as Administrator from the extracted package folder.
rem  All messages are ASCII-only on purpose (cmd codepage safe).
rem
rem  Usage: install.bat [port] [--autostart] [--no-pause]
rem    port         application port, default 18080
rem    --autostart  register both services to start automatically
rem                 at boot. Default is MANUAL start.
rem    --no-pause   never wait for a key press (used by CI runs).
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "APP_PORT=18080"
set "NOPAUSE=0"
set "AUTOSTART=0"
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~1"=="--autostart" set "AUTOSTART=1"
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i not "%~1"=="--no-pause" if /i not "%~1"=="--autostart" if /i not "%~1"=="--yes" set "APP_PORT=%~1"
shift
goto :parse_args
:args_done
set "PG_START_TYPE=demand"
if "%AUTOSTART%"=="1" set "PG_START_TYPE=auto"

rem --- 1. Administrator check --------------------------------
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please right-click install.bat and choose "Run as administrator".
  call :ib_pause
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
  if "%NOPAUSE%"=="1" (
    echo [WARN] Continuing anyway because --no-pause was given.
  ) else (
    choice /C YN /M "Continue anyway"
    if errorlevel 2 exit /b 1
  )
)

rem --- 2b. The application port must be free ------------------
rem Detect a busy port BEFORE installing, name the process that
rem owns it, and let the user pick another port.
:check_port
call :port_listening %APP_PORT%
if "%PORT_BUSY%"=="0" goto :port_ok
echo [WARN] Port %APP_PORT% is already in use by:
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%APP_PORT% .*LISTENING"') do (
  for /f "usebackq delims=" %%N in (`powershell -NoProfile -Command "(Get-Process -Id %%P -ErrorAction SilentlyContinue).ProcessName"`) do echo [WARN]   PID %%P - %%N
)
if "%NOPAUSE%"=="1" (
  echo [ERROR] Port %APP_PORT% is busy and --no-pause was given.
  echo [ERROR] Free the port, or pass another one, e.g. install.bat 18081
  call :ib_pause
  exit /b 1
)
set /p "APP_PORT=Type another port and press Enter, or press Ctrl+C to abort: "
goto :check_port
:port_ok

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
  echo [ERROR] Re-extract the full package or re-apply the patch,
  echo [ERROR] then run install.bat again.
  call :ib_pause
  exit /b 1
)
echo [INFO] VC++ runtime DLLs are bundled in the package - app-local, nothing to install.

rem --- 4. Generate random DB password, write config ----------
set "CFG=%ROOT%\config\application.yml"
set "DBPWD="
if exist "%ROOT%\config\.installed" (
  echo [INFO] Existing config detected - .installed marker present. Keeping current config and database.
  goto :config_done
)
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N').Substring(0,16)"`) do set "DBPWD=%%P"
if "%DBPWD%"=="" (
  echo [ERROR] Failed to generate a random database password.
  call :ib_pause
  exit /b 1
)
powershell -NoProfile -Command "(Get-Content '%CFG%') -replace '__DB_PASSWORD__','%DBPWD%' -replace '__APP_PORT__','%APP_PORT%' | Set-Content -Encoding ASCII '%CFG%'"
if errorlevel 1 (
  echo [ERROR] Failed to write config\application.yml.
  call :ib_pause
  exit /b 1
)
findstr /C:"__DB_PASSWORD__" /C:"__APP_PORT__" "%CFG%" >nul 2>&1
if not errorlevel 1 (
  echo [ERROR] config\application.yml still contains unreplaced placeholders.
  call :ib_pause
  exit /b 1
)
echo installed %DATE% %TIME% > "%ROOT%\config\.installed"
echo [INFO] config\application.yml written - random DB password generated.
:config_done
rem The effective port comes from the config file: an existing
rem install may already use a different port than the argument.
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%CFG%' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
echo [INFO] Application port: %APP_PORT%

rem --- 5. initdb (UTF8, ICU locale zh-CN) ----------------------
rem Encoding is fixed to UTF8 and the locale is pinned to the
rem ICU provider with zh-CN (PostgreSQL 18 built-in ICU), so
rem Chinese sorting is correct and nothing depends on the OS
rem locale - an OS-default Chinese GBK locale made initdb pick
rem "Chinese (Simplified)_China.936" and miss a text search
rem configuration (field round 2).
if exist "%ROOT%\data\pgdata\PG_VERSION" goto :initdb_done
if "%DBPWD%"=="" (
  for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%CFG%"') do set "DBPWD=%%B"
)
echo [INFO] Initializing PostgreSQL data directory - UTF8, ICU locale zh-CN ...
if not exist "%ROOT%\data\pgdata" mkdir "%ROOT%\data\pgdata"
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
echo %DBPWD%> "%TEMP%\ib-pw.txt"
"%ROOT%\runtime\pgsql\bin\initdb.exe" -D "%ROOT%\data\pgdata" -U postgres -E UTF8 --locale-provider=icu --icu-locale=zh-CN --auth=scram-sha-256 --pwfile="%TEMP%\ib-pw.txt"
set "IBRC=%ERRORLEVEL%"
del "%TEMP%\ib-pw.txt" >nul 2>&1
if not "%IBRC%"=="0" (
  echo [ERROR] initdb failed with code %IBRC%. See the initdb output above.
  if "%IBRC%"=="-1073741515" (
    echo [ERROR] Code -1073741515 - 0xC0000135 - means a required DLL was not found.
    echo [ERROR] Check that vcruntime140.dll, vcruntime140_1.dll and msvcp140.dll
    echo [ERROR] exist in runtime\pgsql\bin, then run install.bat again.
  )
  echo [ERROR] If data\pgdata has no PG_VERSION file, it is a half-initialized
  echo [ERROR] leftover: delete the data\pgdata folder and run install.bat again.
  call :ib_pause
  exit /b 1
)
>>"%ROOT%\data\pgdata\postgresql.conf" echo port = 15432
>>"%ROOT%\data\pgdata\postgresql.conf" echo listen_addresses = 'localhost'
echo [INFO] Starting PostgreSQL temporarily to create the database ...
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" -D "%ROOT%\data\pgdata" -l "%ROOT%\logs\pg-init.log" -o "-p 15432" -w -t 60 start
if errorlevel 1 (
  echo [ERROR] Temporary PostgreSQL start failed. See logs\pg-init.log.
  call :ib_pause
  exit /b 1
)
set "PGPASSWORD=%DBPWD%"
"%ROOT%\runtime\pgsql\bin\createdb.exe" -h 127.0.0.1 -p 15432 -U postgres iteration_board
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" -D "%ROOT%\data\pgdata" -m fast -w -t 60 stop >nul 2>&1
if not "%IBRC%"=="0" (
  echo [ERROR] createdb failed with code %IBRC%.
  call :ib_pause
  exit /b 1
)
echo [INFO] Database iteration_board created.
:initdb_done

rem --- 6. Register services ------------------------------------
echo [INFO] Registering Windows services ...

rem PostgreSQL: official pg_ctl registration, NOT WinSW.
rem postgres.exe refuses to run with an administrator token, so
rem a WinSW wrapper running as LocalSystem could never start it:
rem the wrapper reported "started" while postgres exited at once
rem and the failure action restarted it forever (field round 3,
rem ). A service created by pg_ctl register runs
rem postgres with a restricted token and works under LocalSystem.
rem Start type: -S demand (manual) by default, -S auto only when
rem --autostart was given.
if exist "%ROOT%\app\IterationBoardPG.exe" (
  echo [INFO] Removing the old WinSW-based PostgreSQL service ...
  "%ROOT%\app\IterationBoardPG.exe" stop >nul 2>&1
  "%ROOT%\app\IterationBoardPG.exe" uninstall >nul 2>&1
  del /Q "%ROOT%\app\IterationBoardPG.exe" >nul 2>&1
  del /Q "%ROOT%\app\IterationBoardPG.xml" >nul 2>&1
)
sc qc IterationBoardPG 2>nul | findstr /C:"app\IterationBoardPG.exe" >nul 2>&1
if not errorlevel 1 (
  net stop IterationBoardPG >nul 2>&1
  sc delete IterationBoardPG >nul 2>&1
  echo [INFO] Removed a leftover WinSW-based PostgreSQL service registration.
)
sc query IterationBoardPG >nul 2>&1
if errorlevel 1 (
  "%ROOT%\runtime\pgsql\bin\pg_ctl.exe" register -N IterationBoardPG -D "%ROOT%\data\pgdata" -S %PG_START_TYPE%
  if errorlevel 1 (
    echo [ERROR] Failed to register the IterationBoardPG service with pg_ctl.
    call :ib_pause
    exit /b 1
  )
  echo [INFO] IterationBoardPG registered with pg_ctl - start type %PG_START_TYPE%.
) else (
  sc config IterationBoardPG start= %PG_START_TYPE% >nul 2>&1
  echo [INFO] IterationBoardPG was already registered; start type set to %PG_START_TYPE%.
)
rem Do NOT set SCM restart-on-failure actions for this service:
rem a pg_ctl-registered PostgreSQL exits in a way the SCM counts
rem as a failure even after a normal stop, so restart actions
rem resurrect it about 30 s after stop.bat (proven by the Windows
rem runner verification: the service was RUNNING at every sample
rem for 90 s after a successful stop). Explicitly clear any
rem failure actions an earlier 1.0.3 build may have set.
rem Crash resilience does not need them: PostgreSQL recovers
rem itself on the next start, and the application service keeps
rem its capped WinSW onfailure restarts.
sc failure IterationBoardPG reset= 0 actions= "" >nul 2>&1

rem Application service: WinSW wrapper around the JVM. It depends
rem on IterationBoardPG (see service\IterationBoardApp.xml).
sc query IterationBoardApp >nul 2>&1
if not errorlevel 1 (
  if exist "%ROOT%\app\IterationBoardApp.exe" "%ROOT%\app\IterationBoardApp.exe" stop >nul 2>&1
  if exist "%ROOT%\app\IterationBoardApp.exe" "%ROOT%\app\IterationBoardApp.exe" uninstall >nul 2>&1
)
copy /Y "%ROOT%\runtime\winsw\WinSW-x64.exe" "%ROOT%\app\IterationBoardApp.exe" >nul
copy /Y "%ROOT%\service\IterationBoardApp.xml" "%ROOT%\app\IterationBoardApp.xml" >nul
set "IB_ROOT=%ROOT%"
rem Read the whole file first, then write it back: piping
rem Get-Content into Set-Content on the SAME file fails with
rem "file in use" and silently leaves the placeholder in place
rem (field round 2). [IO.File] read/write avoids it.
powershell -NoProfile -Command "$p = '%ROOT%\app\IterationBoardApp.xml'; $c = [IO.File]::ReadAllText($p); [IO.File]::WriteAllText($p, $c.Replace('__INSTALL_DIR__', $env:IB_ROOT), [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to write the installation path into app\IterationBoardApp.xml.
  call :ib_pause
  exit /b 1
)
findstr /C:"__INSTALL_DIR__" "%ROOT%\app\IterationBoardApp.xml" >nul 2>&1
if not errorlevel 1 (
  echo [ERROR] app\IterationBoardApp.xml still contains the __INSTALL_DIR__ placeholder.
  call :ib_pause
  exit /b 1
)
if "%AUTOSTART%"=="1" (
  powershell -NoProfile -Command "$p = '%ROOT%\app\IterationBoardApp.xml'; $c = [IO.File]::ReadAllText($p); [IO.File]::WriteAllText($p, $c.Replace('<startmode>Manual</startmode>', '<startmode>Automatic</startmode>'), [Text.Encoding]::ASCII)"
)
"%ROOT%\app\IterationBoardApp.exe" install
if errorlevel 1 (
  echo [ERROR] Failed to register the IterationBoardApp service.
  call :ib_pause
  exit /b 1
)
echo [INFO] Services registered: IterationBoardPG via pg_ctl - start type %PG_START_TYPE%; IterationBoardApp via WinSW, depends on IterationBoardPG.

rem --- 7. Daily backup task at 02:00 ---------------------------
schtasks /Create /TN "IterationBoardBackup" /TR "\"%ROOT%\backup.bat\" auto" /SC DAILY /ST 02:00 /RU SYSTEM /F >nul 2>&1
if errorlevel 1 (
  echo [WARN] Could not create the daily 02:00 backup scheduled task. You can create it later in Task Scheduler.
) else (
  echo [INFO] Daily backup task created - every day at 02:00, keeps 30 days.
)

rem --- 8. Start services and verify they really work -----------
rem A service reporting "Running" is not proof: verify the
rem postgres process exists, port 15432 answers pg_isready, and
rem the application answers HTTP 200 (field rounds 2 and 3).
echo [INFO] Starting PostgreSQL service ...
net start IterationBoardPG >nul 2>&1
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] IterationBoardPG did not reach the RUNNING state.
  call :show_pg_log
  call :ib_pause
  exit /b 1
)
tasklist /FI "IMAGENAME eq postgres.exe" /NH | findstr /I /C:"postgres.exe" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No postgres.exe process is running although the service reports RUNNING.
  call :ib_pause
  exit /b 1
)
powershell -NoProfile -Command "$ok=$false; for($i=0;$i -lt 30;$i++){ & '%ROOT%\runtime\pgsql\bin\pg_isready.exe' -h 127.0.0.1 -p 15432 | Out-Null; if($LASTEXITCODE -eq 0){$ok=$true;break}; Start-Sleep -Seconds 2 }; if(-not $ok){exit 1}"
if errorlevel 1 (
  echo [ERROR] PostgreSQL is not accepting connections on port 15432 after 60 seconds.
  call :show_pg_log
  call :ib_pause
  exit /b 1
)
echo [INFO] PostgreSQL verified: process running, port 15432 listening, pg_isready OK.

echo [INFO] Starting application service ...
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] IterationBoardApp did not reach the RUNNING state. See the logs folder.
  call :ib_pause
  exit /b 1
)
powershell -NoProfile -Command "$ok=$false; for($i=0;$i -lt 45;$i++){ try { $r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/api/v1/meta' -TimeoutSec 4; if($r.StatusCode -eq 200){$ok=$true;break} } catch {}; Start-Sleep -Seconds 2 }; if(-not $ok){exit 1}"
if errorlevel 1 (
  echo [ERROR] The application did not answer on http://127.0.0.1:%APP_PORT%/ within 90 seconds.
  echo [ERROR] Last 50 lines of logs\app.log:
  powershell -NoProfile -Command "Get-Content '%ROOT%\logs\app.log' -Tail 50 -ErrorAction SilentlyContinue"
  echo [ERROR] Last 50 lines of logs\IterationBoardApp.out.log:
  powershell -NoProfile -Command "Get-Content '%ROOT%\logs\IterationBoardApp.out.log' -Tail 50 -ErrorAction SilentlyContinue"
  call :ib_pause
  exit /b 1
)
echo [INFO] Application verified: http://127.0.0.1:%APP_PORT%/ answers 200.

rem --- 9. P01: create the built-in admin account (1.1.0) --------
rem Revision 4 / P01 replaces the old "first registered account
rem becomes administrator" rule: the installer creates a fixed
rem login "admin" with a random password, shown at the end of
rem this script and written to config\admin-initial-password.txt.
rem HOW IT CONNECTS (assumption, same as reset-admin.bat): the
rem backend has no local bootstrap endpoint yet, so this follows
rem the established direct-PostgreSQL pattern of the backup
rem scripts. The hash comes from pgcrypto crypt() with
rem gen_salt('bf'), a $2a$ BCrypt hash the backend's Spring
rem BCryptPasswordEncoder verifies. The account is created
rem only when no login_name 'admin' row exists, so re-running
rem install.bat never resets it. An audit_log row
rem (ADMIN_BOOTSTRAP_VIA_INSTALL, actor = the new account) is
rem written in the same transaction. must_change_password is
rem set: the first login forces a password change, and once
rem that flag is cleared start.bat deletes the password file.
set "ADMIN_CREATED=0"
set "ADMIN_PW="
if "%DBPWD%"=="" (
  for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%CFG%"') do set "DBPWD=%%B"
)
set "PGPASSWORD=%DBPWD%"
set "EXISTS="
for /f "usebackq delims=" %%R in (`"%ROOT%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -t -A -c "SELECT count(*) FROM app_user WHERE login_name = 'admin'"`) do set "EXISTS=%%R"
rem The psql call below is intentionally NOT inside a
rem parenthesized block: its SQL string contains parentheses,
rem which cmd block parsing does not tolerate reliably.
set "DO_BOOTSTRAP=0"
if "%EXISTS%"=="0" (
  call :gen_password
  if not "%ADMIN_PW%"=="" set "DO_BOOTSTRAP=1"
)
if not "%DO_BOOTSTRAP%"=="1" goto :admin_sql_done
"%ROOT%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -v ON_ERROR_STOP=1 -v pw="%ADMIN_PW%" -c "BEGIN; CREATE EXTENSION IF NOT EXISTS pgcrypto; INSERT INTO app_user (login_name, display_name, password_hash, platform_admin, status, must_change_password) VALUES ('admin', E'\u5e73\u53f0\u7ba1\u7406\u5458', crypt(:'pw', gen_salt('bf', 10)), TRUE, 'ACTIVE', TRUE); INSERT INTO audit_log (team_id, actor_id, action, target_type, target_id, detail) SELECT NULL, id, 'ADMIN_BOOTSTRAP_VIA_INSTALL', 'USER', id, 'Built-in admin account created by install.bat (P01)' FROM app_user WHERE login_name = 'admin'; COMMIT;" >nul 2>&1
if not errorlevel 1 set "ADMIN_CREATED=1"
:admin_sql_done
set "PGPASSWORD="
if "%ADMIN_CREATED%"=="1" (
  powershell -NoProfile -Command "[IO.File]::WriteAllText('%ROOT%\config\admin-initial-password.txt', \"admin initial password: %ADMIN_PW%`r`nCreated by install.bat. This file is deleted automatically after the admin password is changed.`r`n\", [Text.Encoding]::ASCII)"
  echo [INFO] Built-in administrator account created - details below.
) else (
  if "%EXISTS%"=="0" (
    echo [WARN] The built-in admin account could not be created.
    echo [WARN] Register a normal account, then see reset-admin.bat.
  ) else (
    echo [INFO] An admin account already exists - left unchanged.
  )
)

echo.
echo ============================================================
echo  Installation complete.
echo  Open in a browser:  http://localhost:%APP_PORT%/
echo  From other PCs on the LAN:  http://^<this-PC-IP^>:%APP_PORT%/
if "%ADMIN_CREATED%"=="1" (
  echo  Platform administrator account:  admin
  echo  Initial password:  %ADMIN_PW%
  echo  The same password is in config\admin-initial-password.txt
  echo  Log in as admin NOW and change the password - the first
  echo  login forces a change, and the password file is deleted
  echo  automatically once the password has been changed.
  echo  Advice: after logging in as admin, also grant platform
  echo  administrator to your personal account and use that one
  echo  day to day. Keep at least TWO platform administrators,
  echo  so a forgotten password never locks everyone out.
) else (
  echo  Log in with the existing administrator account. New
  echo  self-registered accounts are normal users; platform
  echo  administrator rights are granted inside Platform Admin.
  echo  Keep at least TWO platform administrators.
)
if "%AUTOSTART%"=="1" (
  echo  Both services start automatically at boot.
) else (
  echo  Both services are set to MANUAL start: they will NOT
  echo  start automatically when the PC reboots. Run start.bat
  echo  to start them, or reinstall with --autostart.
)
echo  The daily 02:00 backup only runs while the services are
echo  up; when they are stopped it logs a skip line and does
echo  nothing. Run backup.bat manually after starting them.
echo ============================================================
call :ib_pause
exit /b 0

rem --- subroutines ----------------------------------------------
:gen_password
set "ADMIN_PW="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$rng = [Security.Cryptography.RNGCryptoServiceProvider]::Create(); $sets = @('ABCDEFGHJKLMNPQRSTUVWXYZ','abcdefghijkmnpqrstuvwxyz','23456789'); $all = $sets -join ''; $pw = ''; do { $bytes = New-Object byte[] 64; $rng.GetBytes($bytes); $pw = -join (0..19 | ForEach-Object { $all[$bytes[$_] %% $all.Length] }) } while (-not ($pw -cmatch '[A-Z]' -and $pw -cmatch '[a-z]' -and $pw -match '[0-9]')); $pw"`) do set "ADMIN_PW=%%P"
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0

:port_listening
set "PORT_BUSY=0"
netstat -ano | findstr /R /C:":%~1 .*LISTENING" >nul 2>&1
if not errorlevel 1 set "PORT_BUSY=1"
exit /b 0

:show_pg_log
if exist "%ROOT%\data\pgdata\log" (
  echo [ERROR] Last 50 lines of the newest PostgreSQL log file:
  powershell -NoProfile -Command "$f = Get-ChildItem '%ROOT%\data\pgdata\log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1; if ($f) { Get-Content $f.FullName -Tail 50 }"
)
exit /b 0
