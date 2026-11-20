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
rem    5. P01: if the database has no "admin" account yet, one
rem       is created (random password shown here and written
rem       to config\admin-initial-password.txt on the target).
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
for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$c = Get-Content '%TARGET%\config\application.yml' -Raw -ErrorAction SilentlyContinue; if ($c -match '(?ms)^backup:[ \t]*\r?\n[ \t]+dir:[ \t]*\"([^\"]*)\"') { $Matches[1] } elseif ($c -match '(?m)^[ \t]+dir:[ \t]*(\S+)[ \t]*$') { $Matches[1] }"`) do set "BKROOT=%%V"
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

rem --- step 5: P01 - create the admin account if none exists --------
call :ensure_admin
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

rem --- subroutine: P01 admin bootstrap on the target ----------------
rem Same direct-DB pattern and assumptions as reset-admin.bat:
rem pgcrypto crypt() with gen_salt('bf') makes the $2a$ BCrypt
rem hash the backend verifies. Runs only when no login_name
rem 'admin' row exists; existing platform admins are untouched.
:ensure_admin
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%TARGET%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" exit /b 0
set "PGPASSWORD=%DBPWD%"
set "EXISTS="
for /f "usebackq delims=" %%R in (`"%TARGET%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -t -A -c "SELECT count(*) FROM app_user WHERE login_name = 'admin'"`) do set "EXISTS=%%R"
if not "%EXISTS%"=="0" (
  set "PGPASSWORD="
  exit /b 0
)
call :gen_password
if "%ADMIN_PW%"=="" (
  set "PGPASSWORD="
  echo [WARN] Could not generate the admin password - see the
  echo [WARN] header of reset-admin.bat for the manual remedy.
  exit /b 0
)
"%TARGET%\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -v ON_ERROR_STOP=1 -v pw="%ADMIN_PW%" -c "BEGIN; CREATE EXTENSION IF NOT EXISTS pgcrypto; INSERT INTO app_user (login_name, display_name, password_hash, platform_admin, status, must_change_password) VALUES ('admin', E'\u5e73\u53f0\u7ba1\u7406\u5458', crypt(:'pw', gen_salt('bf', 10)), TRUE, 'ACTIVE', TRUE); INSERT INTO audit_log (team_id, actor_id, action, target_type, target_id, detail) SELECT NULL, id, 'ADMIN_BOOTSTRAP_VIA_PATCH', 'USER', id, 'Built-in admin account created by apply-patch.bat (P01): no admin account existed' FROM app_user WHERE login_name = 'admin'; COMMIT;" >nul 2>&1
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [WARN] Creating the built-in admin account failed in the
  echo [WARN] database. The patch itself is complete; see the
  echo [WARN] header of reset-admin.bat for the manual remedy.
  exit /b 0
)
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TARGET%\config\admin-initial-password.txt', \"admin initial password: %ADMIN_PW%`r`nCreated by apply-patch.bat. This file is deleted automatically after the admin password is changed.`r`n\", [Text.Encoding]::ASCII)"
echo.
echo ============================================================
echo  A built-in platform administrator account was created:
echo  Login name:        admin
echo  Initial password:  %ADMIN_PW%
echo  It is also written to config\admin-initial-password.txt
echo  on the target. Log in as admin right away and change the
echo  password - the file is deleted automatically afterwards.
echo  Advice: after logging in as admin, also grant platform
echo  administrator to your personal account and use that one
echo  day to day. Keep at least TWO platform administrators.
echo ============================================================
exit /b 0

rem --- subroutine: random password, 20 chars, upper+lower+digit -----
:gen_password
set "ADMIN_PW="
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$rng = [Security.Cryptography.RNGCryptoServiceProvider]::Create(); $sets = @('ABCDEFGHJKLMNPQRSTUVWXYZ','abcdefghijkmnpqrstuvwxyz','23456789'); $all = $sets -join ''; $pw = ''; do { $bytes = New-Object byte[] 64; $rng.GetBytes($bytes); $pw = -join (0..19 | ForEach-Object { $all[$bytes[$_] %% $all.Length] }) } while (-not ($pw -cmatch '[A-Z]' -and $pw -cmatch '[a-z]' -and $pw -match '[0-9]')); $pw"`) do set "ADMIN_PW=%%P"
exit /b 0

:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
