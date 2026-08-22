@echo off
REM ============================================================
REM  Photon Remote smoke test script (plan Todo 41)
REM
REM  One-shot verification: setup env -> assembleDebug -> testDebugUnitTest
REM  Exit code 0 = all passed; 1 = any failure (CI friendly).
REM
REM  Usage: tools\smoke.bat
REM  Test report: app\build\reports\tests\testDebugUnitTest\index.html
REM  Note: ASCII output only (cmd code-page safe). UTF-8 Chinese
REM  literals in .bat break parsing on GBK consoles.
REM ============================================================
setlocal

REM ---------- build environment (same as project docs) ----------
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
set "ANDROID_HOME=D:\Android\Sdk"

REM project root = parent of this script's directory
set "PROJECT_ROOT=%~dp0.."
set "GRADLEW=%PROJECT_ROOT%\gradlew.bat"

echo [smoke] JAVA_HOME=%JAVA_HOME%
echo [smoke] ANDROID_HOME=%ANDROID_HOME%
echo [smoke] project root=%PROJECT_ROOT%

REM ---------- 1/4 lint ----------
echo.
echo [smoke] == [1/4] lintDebug ==
call "%GRADLEW%" :app:lintDebug
if errorlevel 1 (
    echo [smoke] [FAIL] lintDebug failed, report: app\build\reports\lint-results-debug.html
    exit /b 1
)
echo [smoke] [PASS] lintDebug

REM ---------- 2/4 debug build ----------
echo.
echo [smoke] == [2/4] assembleDebug ==
call "%GRADLEW%" :app:assembleDebug
if errorlevel 1 (
    echo [smoke] [FAIL] assembleDebug failed, see log above.
    exit /b 1
)
echo [smoke] [PASS] assembleDebug

REM ---------- 3/4 unit tests ----------
echo.
echo [smoke] == [3/4] testDebugUnitTest ==
call "%GRADLEW%" :app:testDebugUnitTest
if errorlevel 1 (
    echo [smoke] [FAIL] testDebugUnitTest has failures, report: app\build\reports\tests\testDebugUnitTest\index.html
    exit /b 1
)
echo [smoke] [PASS] testDebugUnitTest

REM ---------- 4/4 release build (unsigned fallback if no keystore) ----------
echo.
echo [smoke] == [4/4] assembleRelease ==
call "%GRADLEW%" :app:assembleRelease
if errorlevel 1 (
    echo [smoke] [FAIL] assembleRelease failed, see log above.
    exit /b 1
)
echo [smoke] [PASS] assembleRelease

REM ---------- summary ----------
echo.
echo ============================================================
echo [smoke] ALL PASSED: lintDebug + assembleDebug + testDebugUnitTest + assembleRelease
echo ============================================================
exit /b 0
