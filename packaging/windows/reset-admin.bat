@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - emergency admin reset (revision 3 / P01)
rem  Usage: reset-admin.bat [login-name]
rem    login-name defaults to admin.
rem  Run on the SERVER computer, as administrator. The named
rem  account is made a platform administrator and gets a new
rem  random temporary password (20 chars, upper + lower +
rem  digits), shown once in this window. The account must
rem  change it at first login (must_change_password is set).
rem
rem  HOW IT CONNECTS TO THE BACKEND (assumption, 1.1.0):
rem  The backend offers no local reset endpoint yet, so this
rem  script follows the established packaging pattern of the
rem  backup/restore scripts and talks to PostgreSQL directly
rem  with the bundled psql. The password hash is produced by
rem  the pgcrypto extension: crypt(pw, gen_salt('bf', 10))
rem  yields a $2a$ BCrypt hash, which the backend's Spring
rem  BCryptPasswordEncoder verifies. If the backend later
rem  ships a dedicated local reset command or endpoint, this
rem  script should be switched to it.
rem  AUDIT: the same transaction inserts one row into
rem  audit_log (schema V6: team_id NULL for platform-level,
rem  actor_id = the reset account itself - the column is NOT
rem  NULL with a foreign key to app_user, so a local script
rem  can only attribute the action to that account; action
rem  ADMIN_RESET_VIA_SCRIPT, target_type USER, detail states
rem  that the local reset-admin.bat tool was used).
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "LOGIN=%~1"
if "%LOGIN%"=="" set "LOGIN=admin"

net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please right-click reset-admin.bat and choose "Run as administrator".
  pause
  exit /b 1
)

rem --- validate the login name (it goes into SQL via a psql
rem --- variable, but keep the allowed charset tight anyway) ---
powershell -NoProfile -Command "if ('%LOGIN%' -notmatch '^[A-Za-z0-9_.-]+$') { exit 1 }"
if errorlevel 1 (
  echo [ERROR] Invalid login name: %LOGIN%
  echo [ERROR] Allowed characters: letters, digits, underscore, dot, hyphen.
  pause
  exit /b 1
)

rem --- PostgreSQL must be running; start it if needed ---------
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [INFO] PostgreSQL service is not running - starting it ...
  net start IterationBoardPG >nul 2>&1
)
sc query IterationBoardPG | findstr /C:"RUNNING" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL service IterationBoardPG is not running and could not be started.
  pause
  exit /b 1
)

rem --- read DB password from config\application.yml -----------
set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%ROOT%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" (
  echo [ERROR] Could not read DB password from config\application.yml
  pause
  exit /b 1
)
set "PSQL=%ROOT%\runtime\pgsql\bin\psql.exe"
set "PGPASSWORD=%DBPWD%"

rem --- the account must exist ---------------------------------
set "EXISTS="
"%PSQL%" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -t -A -v login="%LOGIN%" -c "SELECT count(*) FROM app_user WHERE login_name = :'login' AND deleted_at IS NULL" > "%TEMP%\ib-q-exists.txt" 2>nul
if exist "%TEMP%\ib-q-exists.txt" set /p EXISTS=<"%TEMP%\ib-q-exists.txt"
if exist "%TEMP%\ib-q-exists.txt" del "%TEMP%\ib-q-exists.txt"
if not "%EXISTS%"=="1" (
  echo [ERROR] No active account with login name "%LOGIN%" exists.
  echo [ERROR] Nothing was changed. The built-in admin account is
  echo [ERROR] created by install.bat / upgrade.bat - run those first
  echo [ERROR] if this is a fresh or pre-1.1.0 installation.
  set "PGPASSWORD="
  pause
  exit /b 1
)

rem --- generate the temporary password -------------------------
call :gen_password
if "%ADMIN_PW%"=="" (
  echo [ERROR] Failed to generate a temporary password.
  set "PGPASSWORD="
  pause
  exit /b 1
)

rem --- apply the reset + audit row in one transaction ----------
set "IB_PW=%ADMIN_PW%"
set "IB_LOGIN=%LOGIN%"
powershell -NoProfile -Command "$sql = 'BEGIN; CREATE EXTENSION IF NOT EXISTS pgcrypto; UPDATE app_user SET password_hash = crypt(''__PW__'', gen_salt(''bf'', 10)), platform_admin = TRUE, must_change_password = TRUE, status = ''ACTIVE'', failed_attempts = 0, locked_until = NULL, password_version = password_version + 1, updated_at = now() WHERE login_name = ''__LOGIN__''; INSERT INTO audit_log (team_id, actor_id, action, target_type, target_id, detail) SELECT NULL, id, ''ADMIN_RESET_VIA_SCRIPT'', ''USER'', id, ''Emergency reset by reset-admin.bat on the server: platform admin granted, temporary password set, must change at next login'' FROM app_user WHERE login_name = ''__LOGIN__''; COMMIT;'; $t = $sql.Replace('__PW__', $env:IB_PW.Replace([char]39, [char]39 + [char]39)).Replace('__LOGIN__', $env:IB_LOGIN.Replace([char]39, [char]39 + [char]39)); [IO.File]::WriteAllText($env:TEMP + '\ib-reset.sql', $t, [Text.Encoding]::ASCII)"
"%PSQL%" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -v ON_ERROR_STOP=1 -f "%TEMP%\ib-reset.sql"
del "%TEMP%\ib-reset.sql" 2>nul
set "IB_PW="
set "IB_LOGIN="
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [ERROR] The reset failed in the database - nothing was committed.
  pause
  exit /b 1
)

echo.
echo ============================================================
echo  Admin reset complete.
echo  Login name:        %LOGIN%
echo  Temporary password: %ADMIN_PW%
echo  This account is now a platform administrator and must
echo  change the password at first login. Write the password
echo  down now - it is not stored anywhere on disk.
echo  The reset was written to the platform audit log.
echo ============================================================
pause
exit /b 0

rem --- subroutine: random password, 20 chars, guaranteed to
rem --- contain upper case, lower case and digits --------------
:gen_password
rem Generate a 20-char password (upper+lower+digit guaranteed) via
rem PowerShell, returned through a temp file. The previous in-place
rem for /f backtick one-liner silently produced an empty password on
rem real Windows cmd (quote mangling inside for /f), which is why the
rem built-in admin account was never created. No for /f and no nested
rem double quotes here any more.
set "ADMIN_PW="
powershell -NoProfile -Command "$rng = [Security.Cryptography.RNGCryptoServiceProvider]::Create(); $sets = @('ABCDEFGHJKLMNPQRSTUVWXYZ','abcdefghijkmnpqrstuvwxyz','23456789'); $all = $sets -join ''; $pw = ''; do { $b = New-Object byte[] 64; $rng.GetBytes($b); $pw = -join (0..19 | ForEach-Object { $all[$b[$_] %% $all.Length] }) } while (-not ($pw -cmatch '[A-Z]' -and $pw -cmatch '[a-z]' -and $pw -match '[0-9]')); [IO.File]::WriteAllText($env:TEMP + '\ib-genpw.txt', $pw)"
if exist "%TEMP%\ib-genpw.txt" set /p ADMIN_PW=<"%TEMP%\ib-genpw.txt"
if exist "%TEMP%\ib-genpw.txt" del "%TEMP%\ib-genpw.txt"
exit /b 0
