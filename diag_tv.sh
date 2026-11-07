#!/usr/bin/env bash
set -u
ADB="/Users/saifil/Library/Android/sdk/platform-tools/adb"
TV="192.168.1.222:5555"

echo "=== Accessibility Settings ==="
"$ADB" -s "$TV" shell "settings get secure enabled_accessibility_services; settings get secure accessibility_enabled"

echo "=== Accessibility Service Dump ==="
"$ADB" -s "$TV" shell "dumpsys accessibility" | grep -E "Enabled services|Bound services|Service\[|saifil" || true

echo "=== MediaPlayer / Video Logs ==="
"$ADB" -s "$TV" logcat -d -t 800 | grep -iE "saifil|MediaPlayer|NuPlayer|OMX|CCodec|ACodec|Video|Exo" | tail -n 60 || true
