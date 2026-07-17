#!/usr/bin/env bash
# Build explainers.apk without Gradle or Android Studio.
#
# Needs: JDK 11+, Android build-tools 35 and platform android-35, plus
# libs/jump3r-1.0.5.jar (pure-Java LAME MP3 encoder, LGPL — from Maven Central).
# Point ANDROID_SDK at a directory containing build-tools and platforms,
# or set BT (build-tools dir) and PLATFORM (android.jar path) directly.
#
# Usage: ./build-apk.sh [output.apk]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/dist/explainers.apk}"

BT="${BT:-${ANDROID_SDK:?set ANDROID_SDK or BT+PLATFORM}/build-tools/35.0.0}"
PLATFORM="${PLATFORM:-$ANDROID_SDK/platforms/android-35/android.jar}"
JUMP3R="$HERE/libs/jump3r-1.0.5.jar"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/gen" "$(dirname "$OUT")"

# 1. resources + manifest -> unsigned APK + generated R.java
"$BT/aapt2" compile --dir "$HERE/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 35 \
  --java "$WORK/gen" \
  "$WORK/res.zip"

# 2. strip the javax.sound-dependent classes from jump3r (unused on Android)
cp "$JUMP3R" "$WORK/jump3r.jar"
zip -q -d "$WORK/jump3r.jar" 'de/sciss/jump3r/lowlevel/*' 'de/sciss/jump3r/Main*' || true

# 3. java -> dex
javac --release 11 -classpath "$PLATFORM:$WORK/jump3r.jar" -d "$WORK/classes" \
  "$WORK/gen/com/explainers/app/R.java" "$HERE"/src/com/explainers/app/*.java
(cd "$WORK" && "$BT/d8" --release --lib "$PLATFORM" --min-api 29 --output . \
  $(find classes -name '*.class') jump3r.jar)
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
