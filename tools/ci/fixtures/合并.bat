@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem ============================================================
rem  Iteration Board offline package 1.1.0 - merge split volumes
rem  Put this file in the SAME folder as the .001/.002 volume
rem  files and double-click it. It merges the volumes with the
rem  built-in copy /b command and verifies the SHA-256 of the
rem  merged zip with the built-in certutil tool.
rem  No third-party software and no administrator rights are
rem  required: this script only concatenates files and checks
rem  a hash, it never touches services or system settings
rem  (merge-script ruling, script re-audit list section 4).
rem  IB_AUTO=1 (automation): no pause at the end; the exit
rem  code is the contract (0 = merged and verified).
rem ============================================================
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
set "OUT=%DIR%\iteration-board-offline-1.1.0.zip"
set "EXPECT=c411549e77aa03c408247740030e146bda5465b99deb428efc35650d12b70fb5"

if not exist "%DIR%\iteration-board-offline-1.1.0.zip.001" goto :missing
if not exist "%DIR%\iteration-board-offline-1.1.0.zip.002" goto :missing

echo Merging volumes into iteration-board-offline-1.1.0.zip ...
copy /b "%DIR%\iteration-board-offline-1.1.0.zip.001"+"%DIR%\iteration-board-offline-1.1.0.zip.002" "%OUT%" >nul
if errorlevel 1 goto :mergefail
echo Merge done. Verifying SHA-256 ...
set "ACTUAL="
for /f "usebackq skip=1 delims=" %%H in (`certutil -hashfile "%OUT%" SHA256`) do if not defined ACTUAL set "ACTUAL=%%H"
set "ACTUAL=%ACTUAL: =%"
if /i "%ACTUAL%"=="%EXPECT%" goto :ok
echo.
echo [FAILED] SHA-256 mismatch.
echo   Expected: %EXPECT%
echo   Actual  : %ACTUAL%
echo One of the volume files is incomplete or corrupted.
echo Copy all volumes again and re-run this script.
if not "%IB_AUTO%"=="1" pause
exit /b 1

:ok
echo.
echo [OK] Verification passed. iteration-board-offline-1.1.0.zip is complete.
echo You can extract it now and follow the deployment manual to install.
if not "%IB_AUTO%"=="1" pause
exit /b 0

:missing
echo [ERROR] Volume file(s) missing. This script must be in the
echo same folder as iteration-board-offline-1.1.0.zip.001 and .002.
echo If you opened this script from inside a zip file, extract
echo everything to a normal folder first, then run it again.
if not "%IB_AUTO%"=="1" pause
exit /b 1

:mergefail
echo [ERROR] Merge failed - copy /b returned an error.
echo Check that the volume files are readable and the disk has
echo at least 200 MB of free space, then re-run this script.
if not "%IB_AUTO%"=="1" pause
exit /b 1
