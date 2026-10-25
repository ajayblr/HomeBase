@echo off
rem Install or update HomeBase on the TV and make it the home screen.
rem Usage: scripts\install-to-tv.bat <TV-IP> [path\to\HomeBase.apk]
setlocal
set "TV=%~1"
set "APK=%~2"
if "%TV%"=="" set /p "TV=TV IP address: "
if not "%APK%"=="" goto :have_apk
if exist "HomeBase.apk" set "APK=HomeBase.apk" & goto :have_apk
if exist "app\build\outputs\apk\release\app-release.apk" set "APK=app\build\outputs\apk\release\app-release.apk" & goto :have_apk
if exist "app\build\outputs\apk\debug\app-debug.apk" set "APK=app\build\outputs\apk\debug\app-debug.apk" & goto :have_apk
echo No APK found. Build it first or pass its path.
exit /b 1

:have_apk
set "PKG=com.homebase.tvlauncher"
set "SVC=%PKG%/%PKG%.HomeWatcherService"
set "DEV=%TV%:5555"

adb connect %DEV%
echo Installing %APK% ...
adb -s %DEV% install -r "%APK%" || exit /b 1

rem Let HomeBase install its own updates
adb -s %DEV% shell appops set %PKG% REQUEST_INSTALL_PACKAGES allow
rem Ask Android to use HomeBase as home (Google TV may ignore this; the service below covers it)
adb -s %DEV% shell cmd package set-home-activity %PKG%/.MainActivity
rem Turn on the Home-button service, keeping any accessibility services already enabled
adb -s %DEV% shell "cur=$(settings get secure enabled_accessibility_services); case $cur in *HomeWatcherService*) ;; null|'') settings put secure enabled_accessibility_services %SVC% ;; *) settings put secure enabled_accessibility_services $cur:%SVC% ;; esac; settings put secure accessibility_enabled 1"

adb -s %DEV% shell am start -n %PKG%/.MainActivity
echo Done. Press Home on the remote to check it opens HomeBase.
endlocal
