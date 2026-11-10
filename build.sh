#!/usr/bin/env bash
# 无 Gradle 的 Android 构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# 依赖（全部来自 Android SDK，不需要 Android Studio / Gradle）：
#   build-tools/<ver>/aapt2.exe, zipalign.exe, lib/d8.jar, lib/apksigner.jar
#   platforms/android-36/android.jar
#   JDK 11+（keytool / javac / java）
#
# 可用环境变量覆盖默认值：
#   ANDROID_SDK    SDK 根目录，默认 D:/Tools/android-sdk
#   BT_VERSION     build-tools 版本，默认 34.0.0
#   BINGBOT_JDK    JDK 目录，默认 D:/Tools/jdk-21.0.12.1+1
#   AJAR           android.jar 的完整路径（覆盖 ANDROID_SDK 推导结果）
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

ANDROID_SDK="${ANDROID_SDK:-D:/Tools/android-sdk}"
BT_VERSION="${BT_VERSION:-34.0.0}"
BT="$ANDROID_SDK/build-tools/$BT_VERSION"
AJAR="${AJAR:-$ANDROID_SDK/platforms/android-36/android.jar}"
JDK_DIR="${BINGBOT_JDK:-/d/Tools/jdk-21.0.12.1+1}"

# ---------------------------------------------------------------- 预检
missing=0
for f in "$BT/aapt2.exe" "$BT/zipalign.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$AJAR"; do
    if [ ! -f "$f" ]; then
        echo "缺少: $f" >&2
        missing=1
    fi
done
# JDK 目录允许写成 C:/... 或 C:\...，这里统一转成 MSYS 能识别的 POSIX 路径，
# 否则 PATH 里塞 Windows 形式的分隔符会导致 javac 找不到。
if [ ! -x "$JDK_DIR/bin/javac" ] && [ ! -x "$JDK_DIR/bin/javac.exe" ]; then
    if command -v cygpath >/dev/null 2>&1; then
        JDK_DIR="$(cygpath -u "$JDK_DIR")"
    fi
fi
if [ -x "$JDK_DIR/bin/javac.exe" ] || [ -x "$JDK_DIR/bin/javac" ]; then
    export JAVA_HOME="$JDK_DIR"
fi
if [ ! -x "$JAVA_HOME/bin/javac.exe" ] && [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "缺少 JDK：未找到 \$JAVA_HOME/bin/javac（当前 JAVA_HOME=$JAVA_HOME）" >&2
    missing=1
fi
if [ "$missing" = 1 ]; then
    echo "" >&2
    echo "请先安装 Android SDK build-tools 与 platform，或用环境变量指到正确位置：" >&2
    echo "  ANDROID_SDK=/path/to/sdk BT_VERSION=34.0.0 BINGBOT_JDK=/path/to/jdk ./build.sh" >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] 编译资源"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/6] 链接资源并生成 R.java"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  build/res.zip

echo "[3/6] 编译 Java"
find src build/gen -name "*.java" > build/sources.txt
# 注意：必须用 --release 11。用 -source 8 -target 8 时 d8 处理匿名内部类会崩。
javac -J-Duser.language=en -J-Duser.country=US \
  --release 11 -encoding UTF-8 -nowarn \
  -classpath "$AJAR" \
  -d build/classes @build/sources.txt 2>&1 | grep -a -viE "^注|^Note|warning|deprecat" || true
test -f build/classes/com/a11y/bingbot/MainActivity.class || { echo "编译失败：找不到 MainActivity.class"; exit 1; }

echo "[4/6] 生成 dex"
find build/classes -name "*.class" > build/classes.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJAR" --min-api 30 --release \
  --output build/dex @build/classes.txt

echo "[5/6] 封包"
python - <<'PY'
import zipfile, shutil, os
shutil.copyfile('build/base.apk', 'build/app-unsigned.apk')
with zipfile.ZipFile('build/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
print('   classes.dex 已写入，大小 %d' % os.path.getsize('build/dex/classes.dex'))
PY
"$BT/zipalign.exe" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "[6/6] 签名"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias anddebug \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "   已生成 debug.keystore"
fi
java -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out bingbot.apk build/app-aligned.apk

ls -lh bingbot.apk
echo "构建完成: $HERE/bingbot.apk"
