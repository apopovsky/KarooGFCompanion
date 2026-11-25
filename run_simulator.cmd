@echo off
setlocal
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMULATOR=%SDK%\emulator\emulator.exe"
set "SIM_APK=%~dp0simulator\build\outputs\apk\debug\simulator-debug.apk"

echo [1/5] Building :app and :simulator...
call "%~dp0gradlew.bat" :app:assembleDebug :simulator:assembleDebug
if errorlevel 1 (
    echo Failed to build APKs.
    exit /b 1
)

set "APP_APK="
for %%f in ("%~dp0app\build\outputs\apk\debug\*.apk") do set "APP_APK=%%~ff"

echo [2/5] Checking for connected emulator/device...
"%ADB%" start-server >nul 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo Starting emulator Medium_Phone_API_35...
    start "" "%EMULATOR%" -avd Medium_Phone_API_35
)

echo [3/5] Waiting for Android boot...
"%ADB%" wait-for-device
:wait_boot
for /f "delims=" %%i in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set "BOOT=%%i"
if not "%BOOT%"=="1" (
    timeout /t 2 /nobreak >nul
    goto wait_boot
)

echo [4/5] Installing GF Companion App and Simulator...
if defined APP_APK (
    "%ADB%" install -r "%APP_APK%"
)
"%ADB%" install -r "%SIM_APK%"

echo [5/5] Launching GF Companion Simulator...
"%ADB%" shell am force-stop com.karoogfcompanion >nul 2>&1
"%ADB%" shell am force-stop com.karoogfcompanion.simulator >nul 2>&1
"%ADB%" shell am start -n com.karoogfcompanion.simulator/.GfSimulatorActivity
echo Done!
