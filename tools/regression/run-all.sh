#!/usr/bin/env bash
# Android 回测统一入口：单测/构建/冒烟 + LAN + WAN 房间 + 双摄合成。
set -euo pipefail
cd "$(dirname "$0")/../.."

export PATH="$PWD/.android-sdk/platform-tools:$PATH"
export ADB="${ADB:-$PWD/.android-sdk/platform-tools/adb}"
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.android-sdk}"
export HOME="${HOME:-$PWD/.tooling/home}"
if [ -z "${FFMPEG:-}" ]; then
  FFMPEG="$(PYTHONPATH="$PWD/.tooling/pylibs" python3 -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())' 2>/dev/null || true)"
  export FFMPEG
fi

echo "== 1/5 构建 + 单测 + 文档 =="
scripts/build.sh :lan-core:test :cloud-core:test :app:assembleDebug >/tmp/campush-all-build.log 2>&1
python3 scripts/check_docs.py --strict

echo "== 2/5 Android 启动冒烟 =="
tools/regression/run-android-smoke.sh

echo "== 3/5 LAN 推流/水印/录制 =="
python3 tools/regression/lan_e2e.py

echo "== 4/5 WAN 房间控制面 =="
python3 tools/regression/wan_room_e2e.py

echo "== 5/5 双摄合成直播 =="
python3 tools/regression/dual_live_e2e.py

echo "ALL ANDROID REGRESSION OK"
