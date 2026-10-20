#!/usr/bin/env bash
# 在 workspace 内准备 Android 构建工具链（不写用户主目录，适配沙箱）。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
JBR="${JBR:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

mkdir -p .tooling .android-sdk/cmdline-tools .android-sdk/platforms .android-sdk/build-tools .android-sdk/licenses .android-home

if [ ! -d .android-sdk/cmdline-tools/latest ]; then
  curl -sSL -o .tooling/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
  unzip -q -o .tooling/cmdline-tools.zip -d .tooling/cmdline-tools-tmp
  mv .tooling/cmdline-tools-tmp/cmdline-tools .android-sdk/cmdline-tools/latest
fi
if [ ! -d .tooling/gradle-8.7 ]; then
  curl -sSL -o .tooling/gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
  unzip -q -o .tooling/gradle.zip -d .tooling/
fi

printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' > .android-sdk/licenses/android-sdk-license
printf '84831b9409646a918e30573bab4c9c91346d8abd\n' > .android-sdk/licenses/android-sdk-preview-license

if [ ! -d .android-sdk/platforms/android-34 ]; then
  curl -sSL -o .tooling/platform-34.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
  rm -rf .tooling/plat && mkdir -p .tooling/plat
  unzip -q -o .tooling/platform-34.zip -d .tooling/plat
  mv .tooling/plat/android-34 .android-sdk/platforms/android-34
fi
if [ ! -d .android-sdk/build-tools/34.0.0 ]; then
  curl -sSL -o .tooling/build-tools-34.zip https://dl.google.com/android/repository/build-tools_r34-macosx.zip
  rm -rf .tooling/bt && mkdir -p .tooling/bt
  unzip -q -o .tooling/build-tools-34.zip -d .tooling/bt
  mv .tooling/bt/android-14 .android-sdk/build-tools/34.0.0
fi

echo "sdk.dir=$ROOT/.android-sdk" > local.properties

if [ ! -f app/debug.keystore ]; then
  "$JBR/bin/keytool" -genkeypair -keystore app/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
fi

echo "工具链就绪：JAVA_HOME=$JBR ANDROID_HOME=$ROOT/.android-sdk"
echo "构建：scripts/build.sh :app:assembleDebug"
