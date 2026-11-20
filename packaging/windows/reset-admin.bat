@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - emergency admin reset (1.1.0)
rem  Usage: reset-admin.bat [--login=<login-name>] [--no-pause]
rem         reset-admin.bat [login-name] [--no-pause]
rem    The login name defaults to admin.
rem  Run on the SERVER computer, as administrator.
rem
rem  Since 1.1.0 the reset itself is done by the application,
rem  not by SQL in this script: this script only locates the
rem  bundled Java runtime and the application jar and calls
rem  the application ops command
rem      java -jar app\iteration-board.jar --ib.ops=reset-admin
rem  The application resets the named account in Java, writes
rem  an audit row and puts the new temporary password into
rem  config\admin-initial-password.txt, whose first line is
rem      admin initial password: <password>
rem  This script displays that file after a successful run and
rem  does NOT delete it: keep it safe until the password has
rem  been changed - the application deletes the file
rem  automatically once the forced password change succeeds.
rem  The account must change the password at first login.
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
if not exist "%ROOT%\tmp" mkdir "%ROOT%\tmp"
set "NOPAUSE=0"
set "LOGINARG="
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~1"=="--no-pause" goto :next_arg
if /i "%~1"=="--yes" set "NOPAUSE=1"
if /i "%~1"=="--yes" goto :next_arg
set "LOGINARG=%~1"
:next_arg
shift
goto :parse_args
:args_done
rem Normalize the login argument to the ops-command form:
rem a bare login name becomes --login=<name>; a --login=<name>
rem argument is passed through unchanged.
if "%LOGINARG%"=="" goto :login_ready
if /i "%LOGINARG:~0,8%"=="--login=" goto :login_ready
set "LOGINARG=--login=%LOGINARG%"
:login_ready
net session >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please right-click reset-admin.bat and choose "Run as administrator".
  call :ib_pause
  exit /b 1
)
set "JAVA=%ROOT%\runtime\jre\bin\java.exe"
set "JAR=%ROOT%\app\iteration-board.jar"
if not exist "%JAVA%" (
  echo [ERROR] Bundled Java runtime not found:
  echo [ERROR]   %JAVA%
  echo [ERROR] The installation in %ROOT% looks incomplete.
  echo [ERROR] Re-extract the full package or re-apply the patch,
  echo [ERROR] then run reset-admin.bat again.
  call :ib_pause
  exit /b 1
)
if not exist "%JAR%" (
  echo [ERROR] Application jar not found:
  echo [ERROR]   %JAR%
  echo [ERROR] The installation in %ROOT% looks incomplete.
  echo [ERROR] Re-extract the full package or re-apply the patch,
  echo [ERROR] then run reset-admin.bat again.
  call :ib_pause
  exit /b 1
)
echo [INFO] Running the application ops command: reset-admin ...
"%JAVA%" -jar "%JAR%" --ib.ops=reset-admin %LOGINARG%
set "IBRC=%ERRORLEVEL%"
if not "%IBRC%"=="0" goto :failed
echo.
echo ============================================================
echo  Admin reset complete.
if exist "%ROOT%\config\admin-initial-password.txt" (
  echo  The new temporary password is:
  type "%ROOT%\config\admin-initial-password.txt"
  echo  It is stored in config\admin-initial-password.txt - this
  echo  script does NOT delete that file. Keep it safe until the
  echo  password has been changed: the application deletes the
  echo  file automatically once the forced password change at
  echo  first login succeeds.
) else (
  echo  The ops command reported success but no password file
  echo  was found at config\admin-initial-password.txt.
  echo  Check the application logs in the logs folder for the
  echo  line about the initial password file.
)
echo  The account must change the password at first login.
echo  The reset was written to the platform audit log.
echo ============================================================
call :ib_pause
exit /b 0
:failed
echo.
echo [ERROR] The admin reset failed - ops command exit code %IBRC%.
echo [ERROR] Nothing was changed by this script. For details see
echo [ERROR] the application logs in the logs folder:
echo [ERROR]   %ROOT%\logs
echo [ERROR] Fix the cause named there, then run reset-admin.bat again.
call :ib_pause
exit /b %IBRC%
:ib_pause
if "%NOPAUSE%"=="0" pause
exit /b 0
