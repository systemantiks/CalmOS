#!/usr/bin/env bash
# Builds home.apk: aapt + javac + d8/dx + zipalign + apksigner. See ../README.md.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
JAR="${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}"
BT="${BUILD_TOOLS:-$SDK/build-tools/debian}"
if [ -n "${JAVAC:-}" ]; then :
elif [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]; then JAVAC=/usr/lib/jvm/java-17-openjdk-amd64/bin/javac
else JAVAC=javac; fi
# Prefer build-tools' own aapt/zipalign/apksigner when present, else whatever is on PATH
for t in aapt zipalign apksigner; do [ -x "$BT/$t" ] && eval "$(echo $t | tr a-z A-Z)=\"$BT/$t\"" || eval "$(echo $t | tr a-z A-Z)=$t"; done

# Signing key: home.keystore is created on first build and must stay private (it is git-ignored).
KS_PASS="${KEYSTORE_PASS:-homehome}"

rm -rf build && mkdir -p build/gen build/classes

# 1. Resources + manifest (fonts stored uncompressed so they can be read directly)
"$AAPT" package -f -m -0 otf -J build/gen -M AndroidManifest.xml -S res -A assets -I "$JAR" -F build/unsigned.apk

# 2. Java -> class files (Java 8 bytecode for dx)
"$JAVAC" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -encoding UTF-8 \
  -d build/classes $(find src build/gen -name '*.java')

# 3. class files -> classes.dex (d8 in current build-tools, dx in older/Debian ones)
if [ -x "$BT/d8" ]; then
  "$BT/d8" --min-api 23 --lib "$JAR" --output build $(find build/classes -name '*.class')
else
  "$BT/dx" --dex --min-sdk-version=23 --output=build/classes.dex build/classes
fi

# 4. Add dex, align, sign
(cd build && "$AAPT" add -f unsigned.apk classes.dex >/dev/null)
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f home.keystore ]; then
  keytool -genkeypair -keystore home.keystore -alias home -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Home" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks home.keystore --ks-key-alias home --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out home.apk build/aligned.apk
"$APKSIGNER" verify home.apk
bash ../scripts/verify-apk.sh home.apk

# Keep the versioned release in sync with the APK we just built.
VERSION=$("$AAPT" dump badging home.apk | sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p")
if [[ ! "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "Invalid release version: $VERSION" >&2
  exit 1
fi
mkdir -p ../dist
cp home.apk "../dist/home-$VERSION.apk"
echo "Built $(pwd)/home.apk and ../dist/home-$VERSION.apk"
