#!/usr/bin/env bash
# 构建 CamPush P1 APK。依赖：workspace 内 .android-sdk 与 .tooling/gradle-8.7（见 setup-toolchain.sh）
set -euo pipefail
cd "$(dirname "$0")/.."

JBR="${JBR:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
if [ ! -x "$JBR/bin/java" ]; then
  echo "JAVA_HOME 候选不存在：$JBR；请设置 JBR 环境变量指向 JDK 17+" >&2
  exit 1
fi

export JAVA_HOME="$JBR"
export ANDROID_HOME="$PWD/.android-sdk"
export ANDROID_USER_HOME="$PWD/.android-home"
export GRADLE_USER_HOME="$PWD/.gradle-home"

if [ -x ./gradlew ]; then
  exec ./gradlew --no-daemon --console=plain "$@"
fi
exec .tooling/gradle-8.7/bin/gradle --no-daemon --console=plain "$@"
