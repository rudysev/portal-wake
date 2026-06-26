#!/usr/bin/env bash
# One-time on-device setup for Portal-Wake (headless wake-word listener).
#
# Because this app has NO launcher icon, there's no tap to (a) get it out of Android's "stopped"
# state — required before BOOT_COMPLETED is ever delivered — or (b) start it the first time. This
# script does both: installs, grants the mic, starts the (headless) foreground service once, and
# frees the single mic slot so background listening survives.
#
#   ./setup.sh            install + grant + start + free the mic slot (disable com.millennium)
#   ./setup.sh --restore  re-enable com.millennium (the native "Hey Alexa" detector) and stop the service
#
# Uses hzdb (Horizon Debug Bridge) in place of raw adb. Enable ADB on the Portal first
# (Settings → Debug → ADB Enabled), connect USB-C, and tap "Allow".
set -euo pipefail

PKG="com.portal.wake"
RECEIVER="$PKG/.service.BootReceiver"
APK="app/build/outputs/apk/debug/app-debug.apk"
ADB="npx -y @meta-quest/hzdb adb"

if [[ "${1:-}" == "--restore" ]]; then
  echo "Re-enabling com.millennium and stopping $PKG..."
  $ADB shell "pm enable com.millennium" || true
  $ADB shell "am force-stop $PKG" || true
  echo "Done. The native 'Hey Alexa' detector (com.millennium) is active again."
  exit 0
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found at $APK — build it first with: ./gradlew assembleDebug (JDK 17/21 toolchain)" >&2
  exit 1
fi

echo "1/4  Installing $APK..."
npx -y @meta-quest/hzdb app install -r "$APK"

echo "2/4  Granting RECORD_AUDIO..."
$ADB shell "pm grant $PKG android.permission.RECORD_AUDIO" || true

echo "3/4  Freeing the single mic slot (disabling com.millennium so background wake isn't starved)..."
# A normal app can't force-stop/disable another; this host-side command can. Persists across reboot.
# Only attempt the disable if the package actually exists — some Portal units don't ship com.millennium
# (the 'Hey Alexa' detector), and disabling an absent package would print a misleading failure warning.
if $ADB shell "pm list packages com.millennium" | grep -q "com.millennium"; then
  $ADB shell "pm disable-user --user 0 com.millennium" || \
    echo "   (could not disable com.millennium — background wake may be starved by the 'Hey Alexa' detector)"
else
  echo "   (com.millennium not installed on this device — nothing to disable)"
fi

echo "4/4  Starting the headless wake service (also clears the 'stopped' state so boot start works)..."
# Kick the exported BootReceiver (the service itself is private). -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES
# so the broadcast reaches the freshly-installed app, which is still in the "stopped" state.
$ADB shell "am broadcast -a com.portal.wake.action.START -n $RECEIVER -f 0x20"

echo
echo "Done. Portal-Wake is listening (no icon — it runs in the background)."
