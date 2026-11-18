#!/bin/bash
# 无 Gradle 的最小构建链路：javac → d8 → aapt2 link → zipalign → apksigner
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/opt/android-sdk}
BT=${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}
AJAR="$SDK/platforms/android-35/android.jar"
KS=${KEYSTORE:-debug.keystore}

[ -x "$BT/d8" ] || { echo "build-tools not found (set BUILD_TOOLS)"; exit 1; }
[ -f "$AJAR" ]  || { echo "android.jar not found (set ANDROID_SDK)"; exit 1; }

rm -rf out stubs/out dex build
mkdir -p out stubs/out dex build

# 1) Xposed API 桩类（运行时由 LSPosed 提供，编译期只需要签名）
javac -source 8 -target 8 -nowarn \
      -bootclasspath "$AJAR" -d stubs/out $(find stubs -name '*.java')

# 2) 模块本体
javac -source 8 -target 8 -nowarn \
      -bootclasspath "$AJAR" -classpath stubs/out \
      -d out src/com/nil/callrec/Hook.java

# 3) dex
"$BT/d8" --min-api 33 --output dex $(find out -name '*.class')

# 4) 资源 + 清单
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml \
      -I "$AJAR" --min-sdk-version 33 --target-sdk-version 36

cp build/base.apk build/unsigned.apk
python3 - <<'PY'
import zipfile
p='build/unsigned.apk'
with zipfile.ZipFile(p,'a',zipfile.ZIP_DEFLATED) as z:
    z.write('dex/classes.dex','classes.dex')
    z.write('assets/xposed_init','assets/xposed_init')
PY

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
      --out build/CallRecAuto.apk build/aligned.apk

echo "built: $(pwd)/build/CallRecAuto.apk"
