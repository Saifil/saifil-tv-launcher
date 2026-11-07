#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"

export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"

SDK_DIR="/Users/saifil/Library/Android/sdk"
BUILD_TOOLS="$SDK_DIR/build-tools/36.1.0"
PLATFORM_JAR="$SDK_DIR/platforms/android-36/android.jar"
ADB="$SDK_DIR/platform-tools/adb"
TV_SERIAL="192.168.1.222:5555"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/apk"

echo "=== 1. Compiling Android Resources (aapt2) ==="
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/resources.zip"
"$BUILD_TOOLS/aapt2" link \
  -I "$PLATFORM_JAR" \
  --manifest "$PROJECT_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  -o "$BUILD_DIR/apk/unaligned.apk" \
  "$BUILD_DIR/resources.zip"

echo "=== 2. Compiling Java Sources (javac) ==="
javac -source 8 -target 8 \
  -bootclasspath "$PLATFORM_JAR" \
  -classpath "$PLATFORM_JAR" \
  -d "$BUILD_DIR/obj" \
  "$BUILD_DIR/gen/com/saifil/tvlauncher/R.java" \
  "$PROJECT_DIR/src/com/saifil/tvlauncher/"*.java

echo "=== 3. Converting Bytecode to DEX (d8) ==="
"$BUILD_TOOLS/d8" \
  --lib "$PLATFORM_JAR" \
  --min-api 28 \
  --output "$BUILD_DIR/apk" \
  "$BUILD_DIR/obj/com/saifil/tvlauncher/"*.class

echo "=== 4. Packaging & Aligning APK ==="
(cd "$BUILD_DIR/apk" && zip -q -u unaligned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD_DIR/apk/unaligned.apk" "$BUILD_DIR/apk/aligned.apk"

KEYSTORE="$PROJECT_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Saifil Momin,O=Incubator,C=US"
fi

FINAL_APK="$PROJECT_DIR/SaifilLauncher.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$FINAL_APK" \
  "$BUILD_DIR/apk/aligned.apk"

ls -lh "$FINAL_APK"

echo "=== 5. Installing & Syncing Optimized 1080p Wallpapers on TV ($TV_SERIAL) ==="
"$ADB" connect "$TV_SERIAL"
"$ADB" -s "$TV_SERIAL" install -r "$FINAL_APK"

REMOTE_BG_DIR="/sdcard/Android/data/com.saifil.tvlauncher/files/bg"
"$ADB" -s "$TV_SERIAL" shell "mkdir -p '$REMOTE_BG_DIR'"

CACHE_1080P_DIR="$PROJECT_DIR/bg/.cache_1080p"
mkdir -p "$CACHE_1080P_DIR"

if [ ! -x "$PROJECT_DIR/optimize_video" ]; then
  swiftc -O -module-cache-path /tmp/swift-module-cache "$PROJECT_DIR/optimize_video.swift" -o "$PROJECT_DIR/optimize_video"
fi

# Remove any remote .mp4 files on TV that are no longer in local bg/
remote_files="$("$ADB" -s "$TV_SERIAL" shell "ls -1 '$REMOTE_BG_DIR' 2>/dev/null" | tr -d '\r')"
for rfile in $remote_files; do
  [ -n "$rfile" ] || continue
  if [ ! -f "$PROJECT_DIR/bg/$rfile" ]; then
    echo "Removing old wallpaper from TV: $rfile"
    "$ADB" -s "$TV_SERIAL" shell "rm -f '$REMOTE_BG_DIR/$rfile'"
    rm -f "$CACHE_1080P_DIR/$rfile"
  fi
done

if [ -d "$PROJECT_DIR/bg" ]; then
  for mp4 in "$PROJECT_DIR/bg/"*.mp4; do
    [ -e "$mp4" ] || continue
    fname="$(basename "$mp4")"
    cached_mp4="$CACHE_1080P_DIR/$fname"
    if [ ! -f "$cached_mp4" ] || [ "$mp4" -nt "$cached_mp4" ]; then
      echo "Optimizing $fname to 1080p @ 5.5 Mbps (Apple Silicon VideoToolbox)..."
      "$PROJECT_DIR/optimize_video" "$mp4" "$cached_mp4" 5.5 h264
    fi
    local_size="$(stat -f%z "$cached_mp4")"
    remote_size="$("$ADB" -s "$TV_SERIAL" shell "stat -c%s '$REMOTE_BG_DIR/$fname' 2>/dev/null || echo 0" | tr -d '\r')"
    if [ "$local_size" != "$remote_size" ]; then
      echo "Pushing optimized 1080p wallpaper: $fname ($local_size bytes)..."
      "$ADB" -s "$TV_SERIAL" push "$cached_mp4" "$REMOTE_BG_DIR/$fname"
    else
      echo "Wallpaper already synced on TV: $fname"
    fi
  done
fi

echo "=== 6. Activating Saifil Launcher & Accessibility Service on TV ==="
# IMPORTANT: Do NOT call 'am force-stop' after enabling accessibility, as Android unbinds force-stopped services!
"$ADB" -s "$TV_SERIAL" shell "
settings put secure accessibility_enabled 0
settings put secure enabled_accessibility_services com.saifil.tvlauncher/com.saifil.tvlauncher.HomeInterceptorService
settings put secure accessibility_enabled 1
am start -n com.saifil.tvlauncher/.MainActivity
"

echo "=== Saifil Launcher Deployed & Active on TV! ==="
