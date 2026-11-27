#!/usr/bin/env bash
# mos-fcmfix-magicos 构建脚本 —— 仅依赖 Android SDK build-tools 与 JDK，无需 Gradle / 网络
#
# 可选环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK 目录
#   BUILD_TOOLS                      指定 build-tools 目录（默认取已安装的最新版本）
#   ANDROID_JAR                      指定 android.jar 路径（默认取 API 最高的版本，需 >= 31）
#   JAVA_HOME                        JDK 目录
#   KEYSTORE                         签名密钥库路径（未设置且根目录无 keystore.jks 时自动生成临时测试签名）
#   KEY_ALIAS                        签名 alias（默认 debug）
#   KEYSTORE_PASS                    密钥库密码（默认 android）
#   KEY_PASS                         密钥密码（默认同 KEYSTORE_PASS）
#   APK_OUT                          输出 APK 路径（默认 build/mos-fcmfix-magicos.apk）
set -euo pipefail

PROJ=${PROJ:-$(cd "$(dirname "$0")" && pwd)}
SRC=$PROJ/app/src/main
OUT=$PROJ/build
STUB=$PROJ/libs-stub

# ---- 探测 Android SDK
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$SDK" ]; then
    for cand in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -d "$cand" ]; then
            SDK=$cand
            break
        fi
    done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "!! 找不到 Android SDK，请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME" >&2
    exit 1
fi

# ---- 探测 build-tools
if [ -z "${BUILD_TOOLS:-}" ]; then
    latest_bt=$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1 || true)
    if [ -n "$latest_bt" ]; then
        BUILD_TOOLS="$SDK/build-tools/$latest_bt"
    fi
fi
if [ -z "${BUILD_TOOLS:-}" ] || [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -x "$BUILD_TOOLS/d8" ]; then
    echo "!! 找不到有效的 Android build-tools（需包含 aapt2/d8/zipalign/apksigner），可设置 BUILD_TOOLS" >&2
    exit 1
fi
BT=$BUILD_TOOLS

# ---- 探测 android.jar（需 API >= 31）
if [ -z "${ANDROID_JAR:-}" ]; then
    latest_plat=$(ls -1 "$SDK/platforms" 2>/dev/null \
        | grep -E '^android-[0-9]+(\.[0-9]+)?$' \
        | sed 's/^android-//' \
        | sort -t. -k1,1n -k2,2n \
        | tail -n 1 || true)
    if [ -n "$latest_plat" ]; then
        ANDROID_JAR="$SDK/platforms/android-$latest_plat/android.jar"
    fi
fi
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "!! 找不到 android.jar（需 API >= 31），可设置 ANDROID_JAR" >&2
    exit 1
fi
PLATFORM=$ANDROID_JAR

# ---- 探测 JDK
find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME"
        return 0
    fi
    if command -v javac >/dev/null 2>&1 && javac -version >/dev/null 2>&1; then
        local jc
        jc=$(command -v javac)
        if [ -x /usr/libexec/java_home ]; then
            /usr/libexec/java_home 2>/dev/null && return 0 || true
        fi
        dirname "$(dirname "$(readlink -f "$jc" 2>/dev/null || echo "$jc")")"
        return 0
    fi
    if [ -x /usr/libexec/java_home ]; then
        /usr/libexec/java_home 2>/dev/null && return 0 || true
    fi
    for cand in \
        /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
        /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home; do
        if [ -x "$cand/bin/javac" ]; then
            echo "$cand"
            return 0
        fi
    done
    return 1
}

JAVA_HOME=$(find_jdk || true)
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "!! 找不到可用的 JDK，请安装 OpenJDK 并设置 JAVA_HOME" >&2
    exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
JAVAC=$JAVA_HOME/bin/javac
JAR=$JAVA_HOME/bin/jar
KEYTOOL=$JAVA_HOME/bin/keytool

echo "SDK:         $SDK"
echo "build-tools: $BT"
echo "platform:    $PLATFORM"
echo "JAVA_HOME:   $JAVA_HOME"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stub-classes" "$OUT/dex" "$OUT/res" "$OUT/gen"

# 桩只参与编译（相当于 compileOnly），绝不能进 dex：
# LSPosed 发现模块自带 de.robv.android.xposed.* 会拒绝加载
echo "==> 1/7 编译 Xposed API 桩（仅编译期）"
find "$STUB" -name "*.java" > "$OUT/stub_sources.txt"
"$JAVAC" -source 8 -target 8 -nowarn -d "$OUT/stub-classes" @"$OUT/stub_sources.txt" 2>&1 | grep -v "bootstrap class path" || true
"$JAR" cf "$OUT/xposed-stub.jar" -C "$OUT/stub-classes" .

echo "==> 2/7 编译模块代码"
find "$SRC/java" -name "*.java" > "$OUT/mod_sources.txt"
"$JAVAC" -source 8 -target 8 -nowarn \
    -classpath "$PLATFORM:$OUT/xposed-stub.jar" \
    -d "$OUT/classes" @"$OUT/mod_sources.txt" 2>&1 | grep -v "bootstrap class path" || true

if [ ! -f "$OUT/classes/com/superboyyy/mosfcmfix/HookEntry.class" ]; then
    echo "!! 编译失败：找不到 HookEntry.class" >&2
    exit 1
fi
echo "    编译产物：$(find "$OUT/classes" -name '*.class' | wc -l | tr -d ' ') 个 class"

echo "==> 3/7 打包资源 (aapt2)"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res/compiled.zip"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$OUT/gen" \
    -A "$SRC/assets" \
    "$OUT/res/compiled.zip"

echo "==> 4/7 生成 DEX (d8)"
"$BT/d8" --release --min-api 26 --output "$OUT/dex" \
    --lib "$PLATFORM" \
    --classpath "$OUT/xposed-stub.jar" \
    $(find "$OUT/classes" -name "*.class")

if [ -n "$(find "$OUT/classes" -path '*de/robv*' -name '*.class')" ]; then
    echo "!! Xposed 桩混进了模块 class 目录，终止" >&2
    exit 1
fi

echo "==> 5/7 合入 classes.dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
"$JAR" uf "$OUT/unsigned.apk" -C "$OUT/dex" classes.dex

echo "==> 6/7 签名"
KEY_ALIAS=${KEY_ALIAS:-debug}
KEYSTORE_PASS=${KEYSTORE_PASS:-android}
KEY_PASS=${KEY_PASS:-$KEYSTORE_PASS}
if [ -z "${KEYSTORE:-}" ]; then
    if [ -f "$PROJ/keystore.jks" ]; then
        KEYSTORE="$PROJ/keystore.jks"
    else
        KEYSTORE="$OUT/debug.jks"
        "$KEYTOOL" -genkeypair -v \
            -keystore "$KEYSTORE" \
            -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
            -dname "CN=Android Debug, O=Android, C=US" 2>/dev/null
        echo "    已生成临时测试签名密钥（正式发布请通过 KEYSTORE 等环境变量指定自有密钥）"
    fi
fi

APK_OUT=${APK_OUT:-$OUT/mos-fcmfix-magicos.apk}
mkdir -p "$(dirname "$APK_OUT")"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$APK_OUT" \
    "$OUT/aligned.apk"

echo "==> 7/7 校验"
"$BT/apksigner" verify --print-certs "$APK_OUT"
"$BT/aapt2" dump badging "$APK_OUT" 2>/dev/null | head -5

echo ""
echo "构建完成：$APK_OUT"
ls -la "$APK_OUT"
