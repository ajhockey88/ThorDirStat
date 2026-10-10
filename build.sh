#!/usr/bin/env bash
# Builds ThorDirStat.apk without the Android Gradle plugin, using Ubuntu's packaged tools:
#   sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
# plus, from Maven Central, Robolectric's Android 14 framework jar to compile against and
# Shizuku's AIDL classes (deps/shizuku-aidl-13.1.5.jar):
#   https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/
# (The app also builds normally in Android Studio via the Gradle files in this folder.)
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_ALL=${ANDROID_ALL:-deps/android-all-14-robolectric-10818077.jar}
PLATFORM_JAR=${PLATFORM_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
SHIZUKU_AIDL=${SHIZUKU_AIDL:-deps/shizuku-aidl-13.1.5.jar}  # classes.jar of dev.rikka.shizuku:aidl:13.1.5 (Maven Central)
KEYSTORE=${KEYSTORE:-keystore/thordirstat.jks}
SRC=app/src/main

# Fetch the two compile-time jars from Maven Central if they aren't here yet.
fetch() { [ -f "$1" ] || { mkdir -p "$(dirname "$1")" && curl -sSfL -o "$1" "$2"; }; }
fetch "$ANDROID_ALL" https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
if [ ! -f "$SHIZUKU_AIDL" ]; then
    fetch deps/shizuku-aidl-13.1.5.aar https://repo1.maven.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar
    unzip -p deps/shizuku-aidl-13.1.5.aar classes.jar > "$SHIZUKU_AIDL"
fi
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

aapt package -f -m -J "$OUT/gen" -M "$SRC/AndroidManifest.xml" -S "$SRC/res" \
    -I "$PLATFORM_JAR" -F "$OUT/base.apk"

javac -nowarn --release 8 -encoding UTF-8 \
    -classpath "$ANDROID_ALL:$SHIZUKU_AIDL" -d "$OUT/classes" \
    $(find "$SRC/java" "$OUT/gen" -name '*.java')

rm -rf "$OUT/aidl" && mkdir -p "$OUT/aidl" && (cd "$OUT/aidl" && unzip -q "../../$SHIZUKU_AIDL" 'moe/*')
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes" "$OUT/aidl"

# Add the compiled code to the resources aapt packaged.
python3 - "$OUT/base.apk" "$OUT/classes.dex" "$OUT/unaligned.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1:]
shutil.copy(base, out)
with zipfile.ZipFile(out, 'a') as z:
    z.write(dex, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
PY

zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "No signing key at $KEYSTORE; creating a new one (APKs signed with it won't update older installs)."
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass thordirstat -keypass thordirstat \
        -alias thordirstat -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=ThorDirStat, O=ThorDirStat" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:thordirstat --key-pass pass:thordirstat \
    --ks-key-alias thordirstat --min-sdk-version 31 --out "$OUT/ThorDirStat.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/ThorDirStat.apk"
echo "Built $OUT/ThorDirStat.apk"
