#!/usr/bin/env bash
set -euo pipefail

ADB="/Users/saifil/Library/Android/sdk/platform-tools/adb"
TV_SERIAL="192.168.1.222:5555"
OUT_FILE="${1:-/Users/saifil/.gemini/antigravity/brain/b3a36e00-6a7c-4bf6-a350-4a20400ee3f9/tv_screenshot.jpg}"
TMP_RAW="/tmp/tv_raw_screencap.png"

"$ADB" connect "$TV_SERIAL" >/dev/null 2>&1 || true
"$ADB" -s "$TV_SERIAL" shell screencap -p /sdcard/tv_screencap_tmp.png
"$ADB" -s "$TV_SERIAL" pull /sdcard/tv_screencap_tmp.png "$TMP_RAW" >/dev/null
"$ADB" -s "$TV_SERIAL" shell rm -f /sdcard/tv_screencap_tmp.png

# Re-encode through macOS sips into a clean standard RGB JPEG (1280px wide) to avoid any corrupt PNG chunks
sips -s format jpeg -s formatOptions 85 -Z 1280 "$TMP_RAW" --out "$OUT_FILE" >/dev/null
rm -f "$TMP_RAW"

file "$OUT_FILE"
ls -lh "$OUT_FILE"
