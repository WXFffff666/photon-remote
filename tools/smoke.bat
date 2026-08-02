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

REM ---------- 1/3 debug build ----------
echo.
echo [smoke] == [1/3] assembleDebug ==
call "%GRADLEW%" :app:assembleDebug
if errorlevel 1 (
    echo [smoke] [FAIL] assembleDebug failed, see log above.
    exit /b 1
)
echo [smoke] [PASS] assembleDebug

REM ---------- 2/3 unit tests ----------
echo.
echo [smoke] == [2/3] testDebugUnitTest ==
call "%GRADLEW%" :app:testDebugUnitTest
if errorlevel 1 (
    echo [smoke] [FAIL] testDebugUnitTest has failures, report: app\build\reports\tests\testDebugUnitTest\index.html
    exit /b 1
)
echo [smoke] [PASS] testDebugUnitTest

REM ---------- 3/3 summary ----------
echo.
echo ============================================================
echo [smoke] ALL PASSED: assembleDebug + testDebugUnitTest
echo ============================================================
exit /b 0
