#!/usr/bin/env bash
# 1-Click Revert from Saifil Launcher back to Optimized Stock Fire TV State
set -u

ADB="/Users/saifil/Library/Android/sdk/platform-tools/adb"
TV_SERIAL="192.168.1.222:5555"

echo "=== Connecting to Fire TV ($TV_SERIAL) ==="
"$ADB" connect "$TV_SERIAL"

echo "=== 1. Disabling Saifil Launcher Accessibility Override ==="
"$ADB" -s "$TV_SERIAL" shell "
settings delete secure enabled_accessibility_services
settings put secure accessibility_enabled 0
"

echo "=== 2. Uninstalling Saifil Launcher (com.saifil.tvlauncher) ==="
"$ADB" -s "$TV_SERIAL" uninstall com.saifil.tvlauncher

echo "=== 3. Returning to Stock Fire TV Home Screen ==="
"$ADB" -s "$TV_SERIAL" shell input keyevent KEYCODE_HOME

echo "=== Reverted to Optimized Stock Fire TV State! ==="
