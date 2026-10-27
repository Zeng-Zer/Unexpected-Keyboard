#!/data/data/com.termux/files/usr/bin/bash
# Builds Unexpected Keyboard on-device (Termux), without Gradle.
# Installs as a separate app (juloo.keyboard2.glide) next to the official one.
#   ondevice/build.sh            build + install
#   ondevice/build.sh noinstall  build only
set -euo pipefail
export PATH=$PREFIX/bin:/system/bin
cd "$(dirname "$0")/.."
APP_ID=juloo.keyboard2.glide
ANDROID_JAR=$HOME/.android-sdk/android-36/android.jar
JDK=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
B=build-ondevice
VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' build.gradle.kts)-glide
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' build.gradle.kts)

rm -rf $B && mkdir -p $B/{gen-res/xml,gen-res/raw,gen-res/values,gen,classes,dex,lib/arm64-v8a}

# 1. Generated resources (what Gradle's copy tasks and resValue do)
cp srcs/layouts/*.xml $B/gen-res/xml/
cp srcs/layouts/latn_qwerty_us.xml $B/gen-res/raw/
cat > $B/gen-res/values/gen.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="app_name" translatable="false">Unexpected Keyboard (Glide)</string></resources>
EOF
sed 's|<manifest |<manifest package="juloo.keyboard2" |' AndroidManifest.xml > $B/AndroidManifest.xml

# 2. Compile + link resources
aapt2 compile --dir res -o $B/res.zip
aapt2 compile --dir $B/gen-res -o $B/gen-res.zip
aapt2 link -I "$ANDROID_JAR" --manifest $B/AndroidManifest.xml \
  --rename-manifest-package $APP_ID \
  --min-sdk-version 21 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A assets --java $B/gen -o $B/unsigned.apk $B/res.zip $B/gen-res.zip

# 3. Java -> dex
SRCS=$(find srcs/juloo.keyboard2 vendor/cdict/java/juloo.cdict $B/gen -name '*.java' \
  ! -path 'srcs/juloo.keyboard2/FoldStateTracker.java')
javac -nowarn -Xlint:none --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d $B/classes \
  $SRCS ondevice/FoldStateTracker.java 2>&1 | grep -v '^Note:' || true
[ -n "$(find $B/classes -name '*.class' | head -1)" ] || { echo "javac failed"; exit 1; }
d8 --release --min-api 21 --lib "$ANDROID_JAR" --output $B/dex $(find $B/classes -name '*.class')

# 4. Native dictionary library
clang -O2 -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
  -I vendor/cdict/libcdict -I "$JDK/include" -I "$JDK/include/linux" \
  vendor/cdict/libcdict/libcdict.c vendor/cdict/java/jni/juloo_cdict_Cdict.c \
  -o $B/lib/arm64-v8a/libcdict_java.so

# 5. Package, align, sign
python3 -I - $B <<'PY'
import sys, zipfile
b = sys.argv[1]
with zipfile.ZipFile(b + '/unsigned.apk', 'a') as z:
    z.write(b + '/dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    z.write(b + '/lib/arm64-v8a/libcdict_java.so', 'lib/arm64-v8a/libcdict_java.so',
            compress_type=zipfile.ZIP_DEFLATED)
PY
zipalign -f -p 4 $B/unsigned.apk $B/aligned.apk
KS=ondevice/debug.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -alias uk \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=UK Glide" >/dev/null 2>&1
apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
  --out UnexpectedKeyboard-glide.apk $B/aligned.apk
apksigner verify UnexpectedKeyboard-glide.apk
echo "Built $(pwd)/UnexpectedKeyboard-glide.apk ($(du -h UnexpectedKeyboard-glide.apk | cut -f1), $VERSION_NAME)"

if [ "${1:-}" != noinstall ]; then
  cp UnexpectedKeyboard-glide.apk "$PREFIX/tmp/uk-glide.apk"
  su -c "pm install -r $PREFIX/tmp/uk-glide.apk"
  rm -f "$PREFIX/tmp/uk-glide.apk"
fi
