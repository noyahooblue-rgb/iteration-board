@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
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
set "PG_START_TYPE=demand"
if "%AUTOSTART%"=="1" set "PG_START_TYPE=auto"

rem --- 0. Install path must be ASCII printable (ruling A) -----
rem PostgreSQL on Windows hands paths to its own helper
rem processes through the system ANSI code page; a character
rem outside the ASCII printable range can be replaced by "?"
rem there, and initdb then cannot find its own binary
rem (root-cause ledger #6). Block before changing anything.
rem The character test itself runs in PowerShell on an
rem environment variable - never judge characters inside cmd,
rem where quoting and codepage rules corrupt the test itself.
set "IB_ROOT=%ROOT%"
powershell -NoProfile -Command "if ($env:IB_ROOT -match '[^\x20-\x7E]') { exit 1 } else { exit 0 }"
set "IB_PATH_RC=%ERRORLEVEL%"
if "%IB_PATH_RC%"=="0" goto :ib_pathwarn_done
  echo [ERROR] The install folder path contains non-English characters: %ROOT%.
  echo [ERROR] PostgreSQL cannot run from such a path reliably. Move the whole
  echo [ERROR] folder to a path with only English letters, digits and spaces,
  echo [ERROR] for example D:\IterationBoard, then run install.bat again.
  call :ib_pause
  exit /b 1
:ib_pathwarn_done
if not exist "%ROOT%\tmp" mkdir "%ROOT%\tmp"

rem --- 1. Administrator check --------------------------------
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"

rem --- 2. Path sanity check (no spaces recommended) ----------
rem NOTE: never test with "echo %ROOT% | findstr" - echo also
rem outputs the space before the pipe, so that test always
rem matches even for paths without spaces. Compare the path
rem against itself with all spaces removed instead.
if "%ROOT%"=="%ROOT: =%" goto :space_done
call :log [WARN] Install path contains a space: %ROOT%
call :log [WARN] A path without spaces is recommended, e.g. D:\IterationBoard
if "%NOPAUSE%"=="1" goto :space_cont
choice /C YN /M "Continue anyway"
if errorlevel 2 exit /b 1
goto :space_done
:space_cont
call :log [WARN] Continuing anyway because --no-pause was given.
:space_done

rem --- 2b. The application port must be free ------------------
rem Detect a busy port BEFORE installing, name the process that
rem owns it, and let the user pick another port.
:check_port
call :port_listening %APP_PORT%
if "%PORT_BUSY%"=="0" goto :port_ok
call :log [WARN] Port %APP_PORT% is already in use by:
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%APP_PORT% .*LISTENING"') do (
  for /f "usebackq delims=" %%N in (`powershell -NoProfile -Command "(Get-Process -Id %%P -ErrorAction SilentlyContinue).ProcessName"`) do echo [WARN]   PID %%P - %%N
)
if not "%NOPAUSE%"=="1" goto :port_prompt
call :log [ERROR] Port %APP_PORT% is busy and --no-pause was given.
call :log [ERROR] Free the port, or pass another one, e.g. install.bat 18081
call :ib_pause
exit /b 1
:port_prompt
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
if "%VC_MISSING%"=="" goto :vc_done
call :log [ERROR] Bundled VC++ runtime DLLs are missing from this package:
for %%F in (%VC_MISSING%) do echo [ERROR]   - %%F
call :log [ERROR] The package is incomplete or was not fully extracted.
call :log [ERROR] Re-extract the full package or re-apply the patch,
call :log [ERROR] then run install.bat again.
call :ib_pause
exit /b 1
:vc_done
call :log [INFO] VC++ runtime DLLs are bundled in the package - app-local, nothing to install.

rem --- 4. Generate random DB password, write config ----------
set "CFG=%ROOT%\config\application.yml"
set "DBPWD="
rem Split-state guard: an initialized data directory (PG_VERSION
rem present) whose install marker (.installed) is missing means
rem the config was lost or replaced while the database survived.
rem This script would generate a NEW random password below while
rem step 5 skips initdb for the existing data directory - a
rem guaranteed password mismatch that strands the install (the
rem app and every backup then fail authentication). Refuse and
rem say exactly how to recover instead of writing the mismatch.
if not exist "%ROOT%\data\pgdata\PG_VERSION" goto :cfg_split_ok
if exist "%ROOT%\config\.installed" goto :cfg_split_ok
echo [ERROR] The data directory data\pgdata is already initialized,
echo [ERROR] but the install marker config\.installed is missing.
echo [ERROR] Installing here would write a new random database
echo [ERROR] password while keeping the old database, and the two
echo [ERROR] would never match. Nothing was changed. Either:
echo [ERROR]   - restore the original config folder of this install
echo [ERROR]     (it contains .installed and application.yml), or
echo [ERROR]   - if the old data is NOT needed, delete the
echo [ERROR]     data\pgdata folder and run install.bat again to
echo [ERROR]     initialize a fresh, empty database.
call :ib_pause
exit /b 1
:cfg_split_ok
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
call :log [INFO] config\application.yml written - random DB password generated.
:config_done
rem The effective port comes from the config file: an existing
rem install may already use a different port than the argument.
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$c = Get-Content '%CFG%' -Raw; if ($c -match 'port:\s*(\d+)') { $Matches[1] }"`) do set "APP_PORT=%%P"
call :log [INFO] Application port: %APP_PORT%

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
call :log [INFO] Initializing PostgreSQL data directory - UTF8, ICU locale zh-CN ...
if not exist "%ROOT%\data\pgdata" mkdir "%ROOT%\data\pgdata"
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
echo %DBPWD%> "%ROOT%\tmp\ib-pw.txt"
"%ROOT%\runtime\pgsql\bin\initdb.exe" -D "%ROOT%\data\pgdata" -U postgres -E UTF8 --locale-provider=icu --icu-locale=zh-CN --auth=scram-sha-256 --pwfile="%ROOT%\tmp\ib-pw.txt"
set "IBRC=%ERRORLEVEL%"
del "%ROOT%\tmp\ib-pw.txt" >nul 2>&1
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
call :log [INFO] Starting PostgreSQL temporarily to create the database ...
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
call :log [INFO] Database iteration_board created.
:initdb_done

rem --- 6. Register services ------------------------------------
call :log [INFO] Registering Windows services ...

rem PostgreSQL: official pg_ctl registration, NOT WinSW.
rem postgres.exe refuses to run with an administrator token, so
rem a WinSW wrapper running as LocalSystem could never start it:
rem the wrapper reported "started" while postgres exited at once
rem and the failure action restarted it forever (field round 3,
rem ). A service created by pg_ctl register runs
rem postgres with a restricted token and works under LocalSystem.
rem Start type: -S demand (manual) by default, -S auto only when
rem --autostart was given.
if not exist "%ROOT%\app\IterationBoardPG.exe" goto :oldpg_done
call :log [INFO] Removing the old WinSW-based PostgreSQL service ...
"%ROOT%\app\IterationBoardPG.exe" stop >nul 2>&1
"%ROOT%\app\IterationBoardPG.exe" uninstall >nul 2>&1
del /Q "%ROOT%\app\IterationBoardPG.exe" >nul 2>&1
del /Q "%ROOT%\app\IterationBoardPG.xml" >nul 2>&1
:oldpg_done
sc qc IterationBoardPG 2>nul | findstr /C:"app\IterationBoardPG.exe" >nul 2>&1
if not errorlevel 1 (
  net stop IterationBoardPG >nul 2>&1
  sc delete IterationBoardPG >nul 2>&1
  echo [INFO] Removed a leftover WinSW-based PostgreSQL service registration.
)
sc query IterationBoardPG >nul 2>&1
if errorlevel 1 goto :pg_register
rem The service name is machine-wide unique: an existing
rem registration may belong to ANOTHER install folder (a
rem previous install whose folder was deleted without
rem uninstalling). Reusing it blindly would start that other
rem data directory under this install's name, and this install's
rem own database would never answer - every later backup and
rem patch then fails with a misleading password error. Verify
rem the registered -D against this folder first; on mismatch
rem (or when it cannot be determined) remove the stale
rem registration and register this folder's data directory.
set "IB_PGDIR="
set "IB_PGCHK=2"
set "IB_ROOTPG=%ROOT%"
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$raw = $null; try { $raw = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\IterationBoardPG' -ErrorAction Stop).ImagePath } catch {}; if (-not $raw) { Write-Output 'VERDICT:2'; exit 0 }; $exp = [Environment]::ExpandEnvironmentVariables($raw); $d = $null; if ($exp -match '-D\s+\x22([^\x22]+)\x22') { $d = $Matches[1] } elseif ($exp -match '-D\s+(\S+)') { $d = $Matches[1] }; if (-not $d) { Write-Output 'VERDICT:2'; exit 0 }; $reg = [IO.Path]::GetFullPath($d).TrimEnd('\'); Write-Output ('PGDIR:' + $reg); $tgt = [IO.Path]::GetFullPath((Join-Path $env:IB_ROOTPG 'data\pgdata')).TrimEnd('\'); if ($reg -ieq $tgt) { Write-Output 'VERDICT:0' } else { Write-Output 'VERDICT:1' }"`) do call :ib_pg_add "%%V"
if "%IB_PGCHK%"=="0" goto :pg_reuse
call :log [INFO] Removing a stale IterationBoardPG registration
call :log [INFO] (registered data directory: %IB_PGDIR%).
net stop IterationBoardPG >nul 2>&1
sc delete IterationBoardPG >nul 2>&1
set "IB_DELWAIT=0"
:pg_del_wait
sc query IterationBoardPG >nul 2>&1
if errorlevel 1 goto :pg_register
set /a IB_DELWAIT+=1
if "%IB_DELWAIT%"=="15" goto :pg_del_stuck
powershell -NoProfile -Command "Start-Sleep -Seconds 2"
goto :pg_del_wait
:pg_del_stuck
call :log [ERROR] The stale IterationBoardPG registration could not
call :log [ERROR] be removed (still present after 30 seconds). Close
call :log [ERROR] the Services console and any window using it, then
call :log [ERROR] run install.bat again.
call :ib_pause
exit /b 1
:pg_reuse
sc config IterationBoardPG start= %PG_START_TYPE% >nul 2>&1
call :log [INFO] IterationBoardPG was already registered for this
call :log [INFO] data directory; start type set to %PG_START_TYPE%.
goto :pg_reg_done
:pg_register
"%ROOT%\runtime\pgsql\bin\pg_ctl.exe" register -N IterationBoardPG -D "%ROOT%\data\pgdata" -S %PG_START_TYPE%
if errorlevel 1 goto :pg_register_fail
call :log [INFO] IterationBoardPG registered with pg_ctl - start type %PG_START_TYPE%.
goto :pg_reg_done
:pg_register_fail
call :log [ERROR] Failed to register the IterationBoardPG service with pg_ctl.
call :ib_pause
exit /b 1
:pg_reg_done
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
if errorlevel 1 goto :appreg_done
if exist "%ROOT%\app\IterationBoardApp.exe" "%ROOT%\app\IterationBoardApp.exe" stop >nul 2>&1
if exist "%ROOT%\app\IterationBoardApp.exe" "%ROOT%\app\IterationBoardApp.exe" uninstall >nul 2>&1
sc query IterationBoardApp >nul 2>&1
if errorlevel 1 goto :appreg_done
rem The registration survives but the wrapper exe of THIS
rem folder could not remove it - the classic case is a leftover
rem registration from another install folder whose exe was
rem deleted with it (the pre-1.1.0 installers had exactly this
rem blind spot, and the WinSW install below would now fail with
rem "service already exists"). Remove the stale registration
rem through the SCM directly and wait until it is gone.
call :log [INFO] Removing a stale IterationBoardApp registration
call :log [INFO] left over from a previous installation.
net stop IterationBoardApp >nul 2>&1
sc delete IterationBoardApp >nul 2>&1
set "IB_DELWAIT=0"
:app_del_wait
sc query IterationBoardApp >nul 2>&1
if errorlevel 1 goto :appreg_done
set /a IB_DELWAIT+=1
if "%IB_DELWAIT%"=="15" goto :app_del_stuck
powershell -NoProfile -Command "Start-Sleep -Seconds 2"
goto :app_del_wait
:app_del_stuck
echo [ERROR] The stale IterationBoardApp registration could not
echo [ERROR] be removed (still present after 30 seconds). Close
echo [ERROR] the Services console and any window using it, then
echo [ERROR] run install.bat again.
call :ib_pause
exit /b 1
:appreg_done
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
if "%AUTOSTART%"=="1" powershell -NoProfile -Command "$p = '%ROOT%\app\IterationBoardApp.xml'; $c = [IO.File]::ReadAllText($p); [IO.File]::WriteAllText($p, $c.Replace('<startmode>Manual</startmode>', '<startmode>Automatic</startmode>'), [Text.Encoding]::ASCII)"
"%ROOT%\app\IterationBoardApp.exe" install
if errorlevel 1 (
  echo [ERROR] Failed to register the IterationBoardApp service.
  call :ib_pause
  exit /b 1
)
call :log [INFO] Services registered: IterationBoardPG via pg_ctl - start type %PG_START_TYPE%; IterationBoardApp via WinSW, depends on IterationBoardPG.

rem --- 7. Daily backup task at 02:00 ---------------------------
rem IB-CHECK-EXEMPT R6 - the schtasks /TR value is a single
rem quoted argument with cmd-escaped inner quotes around the
rem backup.bat path; the R6 token scan cannot see that quoting
rem (documented approximation - see the recheck list).
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
call :log [INFO] Starting PostgreSQL service ...
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
call :log [INFO] PostgreSQL verified: process running, port 15432 listening, pg_isready OK.

call :log [INFO] Starting application service ...
net start IterationBoardApp >nul 2>&1
sc query IterationBoardApp | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] IterationBoardApp did not reach the RUNNING state. See the logs folder.
  call :ib_pause
  exit /b 1
)
powershell -NoProfile -Command "$ok=$false; for($i=0;$i -lt 45;$i++){ try { $r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:%APP_PORT%/api/v1/meta' -TimeoutSec 4; if($r.StatusCode -eq 200){$ok=$true;break} } catch {}; Start-Sleep -Seconds 2 }; if(-not $ok){exit 1}"
if not errorlevel 1 goto :app_http_ok
call :log [ERROR] The application did not answer on http://127.0.0.1:%APP_PORT%/ within 90 seconds.
call :log [ERROR] Last 50 lines of logs\app.log:
powershell -NoProfile -Command "Get-Content '%ROOT%\logs\app.log' -Tail 50 -ErrorAction SilentlyContinue"
call :log [ERROR] Last 50 lines of logs\IterationBoardApp.out.log:
powershell -NoProfile -Command "Get-Content '%ROOT%\logs\IterationBoardApp.out.log' -Tail 50 -ErrorAction SilentlyContinue"
call :ib_pause
exit /b 1
:app_http_ok
call :log [INFO] Application verified: http://127.0.0.1:%APP_PORT%/ answers 200.

rem --- 9. Built-in admin account (1.1.0) -----------------------
rem Since 1.1.0 the application itself creates the built-in
rem "admin" account on first start, after the Flyway migration,
rem and writes the initial password to
rem config\admin-initial-password.txt. This script contains no
rem business SQL any more: it only shows that file when the
rem application has already created it.
if exist "%ROOT%\config\admin-initial-password.txt" goto :pwfile_yes
call :log [INFO] No initial-password file was found. If this is a
call :log [INFO] first start, the application creates the built-in
call :log [INFO] admin account and writes the initial password to
call :log [INFO] config\admin-initial-password.txt - check there
call :log [INFO] after the application has started.
goto :pwfile_done
:pwfile_yes
call :log [INFO] A built-in administrator account was created by the
call :log [INFO] application on first start. Its initial password:
type "%ROOT%\config\admin-initial-password.txt"
call :log [INFO] Log in as admin and change the password right away -
call :log [INFO] the first login forces a change, and the application
call :log [INFO] deletes this file automatically once the password
call :log [INFO] has been changed.
:pwfile_done

echo.
echo ============================================================
echo  Installation complete.
echo  Open in a browser:  http://localhost:%APP_PORT%/
echo  From other PCs on the LAN:  http://^<this-PC-IP^>:%APP_PORT%/
echo  Log in with the administrator account. If the application
echo  created the built-in admin account on first start, its
echo  initial password is in config\admin-initial-password.txt
echo  - see step 9 above. Keep at least TWO platform
echo  administrators, so a forgotten password never locks
echo  everyone out.
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

:ib_pg_add
set "IB_LINE=%~1"
if "%IB_LINE:~0,8%"=="VERDICT:" set "IB_PGCHK=%IB_LINE:~8%"
if "%IB_LINE:~0,6%"=="PGDIR:" set "IB_PGDIR=%IB_LINE:~6%"
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

:port_listening
set "PORT_BUSY=0"
netstat -ano | findstr /R /C:":%~1 .*LISTENING" >nul 2>&1
if not errorlevel 1 set "PORT_BUSY=1"
exit /b 0

:show_pg_log
if not exist "%ROOT%\data\pgdata\log" exit /b 0
call :log [ERROR] Last 50 lines of the newest PostgreSQL log file:
powershell -NoProfile -Command "$f = Get-ChildItem '%ROOT%\data\pgdata\log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1; if ($f) { Get-Content $f.FullName -Tail 50 }"
exit /b 0
