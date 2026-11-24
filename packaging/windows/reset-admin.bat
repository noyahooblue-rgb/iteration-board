@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
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
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"
set "JAVA=%ROOT%\runtime\jre\bin\java.exe"
set "JAR=%ROOT%\app\iteration-board.jar"
if exist "%JAVA%" goto :java_ok
call :log [ERROR] Bundled Java runtime not found:
call :log [ERROR]   "%JAVA%"
call :log [ERROR] The installation in "%ROOT%" looks incomplete.
call :log [ERROR] Re-extract the full package or re-apply the patch,
call :log [ERROR] then run reset-admin.bat again.
call :ib_pause
exit /b 1
:java_ok
if exist "%JAR%" goto :jar_ok
call :log [ERROR] Application jar not found:
call :log [ERROR]   "%JAR%"
call :log [ERROR] The installation in "%ROOT%" looks incomplete.
call :log [ERROR] Re-extract the full package or re-apply the patch,
call :log [ERROR] then run reset-admin.bat again.
call :ib_pause
exit /b 1
:jar_ok
call :log [INFO] Running the application ops command: reset-admin ...
"%JAVA%" -jar "%JAR%" --ib.ops=reset-admin %LOGINARG%
set "IBRC=%ERRORLEVEL%"
if not "%IBRC%"=="0" goto :failed
echo.
echo ============================================================
echo  Admin reset complete.
if exist "%ROOT%\config\admin-initial-password.txt" goto :pw_yes
echo  The ops command reported success but no password file
echo  was found at config\admin-initial-password.txt.
echo  Check the application logs in the logs folder for the
echo  line about the initial password file.
goto :pw_done
:pw_yes
echo  The new temporary password is:
type "%ROOT%\config\admin-initial-password.txt"
echo  It is stored in config\admin-initial-password.txt - this
echo  script does NOT delete that file. Keep it safe until the
echo  password has been changed: the application deletes the
echo  file automatically once the forced password change at
echo  first login succeeds.
:pw_done
echo  The account must change the password at first login.
echo  The reset was written to the platform audit log.
echo ============================================================
call :ib_pause
exit /b 0
:failed
echo.
call :log [ERROR] The admin reset failed - ops command exit code %IBRC%.
call :log [ERROR] Nothing was changed by this script. For details see
call :log [ERROR] the application logs in the logs folder:
call :log [ERROR]   "%ROOT%\logs"
call :log [ERROR] Fix the cause named there, then run reset-admin.bat again.
call :ib_pause
exit /b %IBRC%
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
