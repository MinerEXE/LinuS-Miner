#!/usr/bin/env bash
# Build zen-mahjong.apk without Gradle or Android Studio.
#
# Needs: JDK 11+, Android build-tools 35 and platform android-35.
# Point ANDROID_SDK at a directory containing them, e.g.:
#   $ANDROID_SDK/build-tools/35.0.0/{aapt2,d8,zipalign,apksigner}
#   $ANDROID_SDK/platforms/android-35/android.jar
# or set BT (build-tools dir) and PLATFORM (android.jar path) directly.
#
# Usage: ./build-apk.sh [output.apk]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
OUT="${1:-$HERE/dist/zen-mahjong.apk}"

BT="${BT:-${ANDROID_SDK:?set ANDROID_SDK or BT+PLATFORM}/build-tools/35.0.0}"
PLATFORM="${PLATFORM:-$ANDROID_SDK/platforms/android-35/android.jar}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/assets" "$WORK/classes" "$(dirname "$OUT")"

# 1. game files -> APK assets
cp "$ROOT/index.html" "$ROOT/manifest.webmanifest" "$ROOT/sw.js" "$WORK/assets/"
cp -r "$ROOT/icons" "$WORK/assets/icons"

# 2. resources + manifest -> unsigned APK (assets included via -A)
"$BT/aapt2" compile --dir "$HERE/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 35 \
  -A "$WORK/assets" \
  "$WORK/res.zip"

# 3. java -> dex
javac --release 11 -classpath "$PLATFORM" -d "$WORK/classes" \
  "$HERE/src/com/zenmahjong/app/MainActivity.java"
(cd "$WORK" && "$BT/d8" --release --lib "$PLATFORM" --output . \
  $(find classes -name '*.class'))
(cd "$WORK" && zip -q -j unsigned.apk classes.dex)

# 4. align + sign (debug key, auto-generated once)
KEYSTORE="$HERE/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT" "$WORK/aligned.apk"

"$BT/apksigner" verify "$OUT"
echo "Built: $OUT"
