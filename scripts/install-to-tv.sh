#!/usr/bin/env bash
# Install or update HomeBase on the TV and make it the home screen.
# Usage: ./scripts/install-to-tv.sh <TV-IP> [path/to/HomeBase.apk]
set -euo pipefail
TV="${1:?Usage: $0 <TV-IP> [apk]}"
APK="${2:-}"
if [ -z "$APK" ]; then
  for f in HomeBase.apk app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/debug/app-debug.apk; do
    [ -f "$f" ] && APK="$f" && break
  done
fi
[ -f "$APK" ] || { echo "No APK found. Build it first or pass its path."; exit 1; }

PKG=com.homebase.tvlauncher
SVC=$PKG/$PKG.HomeWatcherService
DEV="$TV:5555"

adb connect "$DEV"
echo "Installing $APK ..."
adb -s "$DEV" install -r "$APK"

# Let HomeBase install its own updates without asking for the permission screen
adb -s "$DEV" shell appops set $PKG REQUEST_INSTALL_PACKAGES allow || true
# Ask Android to use HomeBase as home (Google TV may ignore this; the service below covers it)
adb -s "$DEV" shell cmd package set-home-activity $PKG/.MainActivity || true
# Turn on the Home-button service, keeping any accessibility services already enabled
adb -s "$DEV" shell "cur=\$(settings get secure enabled_accessibility_services); case \$cur in *HomeWatcherService*) ;; null|'') settings put secure enabled_accessibility_services $SVC ;; *) settings put secure enabled_accessibility_services \$cur:$SVC ;; esac; settings put secure accessibility_enabled 1"

adb -s "$DEV" shell am start -n $PKG/.MainActivity
echo "Done. Press Home on the remote to check it opens HomeBase."
