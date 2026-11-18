@echo off
setlocal EnableExtensions
rem ============================================================
rem  Iteration Board - restore from a backup folder (10.4).
rem  Usage: restore.bat [backup-folder-name]
rem  Without an argument, lists backup\<...>\ folders and asks.
rem  WARNING: restore REPLACES the current database content.
rem  A safety backup of the current state is taken first.
rem ============================================================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
net session >nul 2>&1
if errorlevel 1 ( echo [ERROR] Please run as administrator. & pause & exit /b 1 )

set "SEL=%~1"
if "%SEL%"=="" (
  echo Available backups in backup\ :
  dir /B /AD "%ROOT%\backup" 2>nul
  set /p "SEL=Type the backup folder name to restore: "
)
if "%SEL%"=="" ( echo [ERROR] No backup selected. & pause & exit /b 1 )
set "SRC=%ROOT%\backup\%SEL%"
if not exist "%SRC%\db.dump" ( echo [ERROR] %SRC%\db.dump not found. & pause & exit /b 1 )

choice /C YN /M "Restore %SEL% now? Current data will be replaced"
if errorlevel 2 exit /b 0

echo [INFO] Taking a safety backup of the current state first ...
call "%ROOT%\backup.bat" auto
if errorlevel 1 ( echo [ERROR] Safety backup failed. Restore aborted. & pause & exit /b 1 )

echo [INFO] Stopping application service ...
"%ROOT%\app\IterationBoardApp.exe" stop >nul 2>&1

set "DBPWD="
for /f "tokens=1,2" %%A in ('findstr /C:"password:" "%ROOT%\config\application.yml"') do set "DBPWD=%%B"
if "%DBPWD%"=="" ( echo [ERROR] Could not read DB password from config. & pause & exit /b 1 )
set "PGPASSWORD=%DBPWD%"
"%ROOT%\runtime\pgsql\bin\pg_restore.exe" -h 127.0.0.1 -p 15432 -U postgres -d iteration_board --clean --if-exists "%SRC%\db.dump"
set "IBRC=%ERRORLEVEL%"
set "PGPASSWORD="
if not "%IBRC%"=="0" (
  echo [ERROR] pg_restore failed with code %IBRC%. The database may be inconsistent;
  echo         restore the safety backup that was just created, or check logs.
  pause
  exit /b 1
)
if exist "%SRC%\uploads" (
  if not exist "%ROOT%\data\uploads" mkdir "%ROOT%\data\uploads"
  robocopy "%SRC%\uploads" "%ROOT%\data\uploads" /E /NFL /NDL /NJH /NJS >nul
  if errorlevel 8 ( echo [ERROR] uploads restore failed. & pause & exit /b 1 )
)
echo [INFO] Starting application service ...
"%ROOT%\app\IterationBoardApp.exe" start
if errorlevel 1 ( echo [ERROR] Application failed to start after restore. See logs folder. & pause & exit /b 1 )
echo [INFO] Restore complete: %SEL%
pause
exit /b 0
