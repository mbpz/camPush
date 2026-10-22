#!/usr/bin/env bash
# Android 回测冒烟：构建门禁 + 模拟器启动 + 关键 UI 断言。
set -euo pipefail
cd "$(dirname "$0")/../.."

ADB="${ADB:-$PWD/.android-sdk/platform-tools/adb}"
[ -x "$ADB" ] || { echo "adb not found: $ADB" >&2; exit 1; }

echo "[1/4] 单元测试 + APK"
scripts/build.sh :lan-core:test :cloud-core:test :app:assembleDebug >/tmp/campush-smoke-build.log 2>&1

echo "[2/4] 文档门禁"
python3 scripts/check_docs.py --strict >/tmp/campush-smoke-docs.log 2>&1

echo "[3/4] 安装/启动"
$ADB install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
$ADB shell am force-stop com.campush.app
$ADB shell am start -W -n com.campush.app/.MainActivity >/tmp/campush-smoke-start.log 2>&1

echo "[4/4] UI 断言"
sleep 3
$ADB shell uiautomator dump /sdcard/campush-smoke.xml >/dev/null
$ADB pull /sdcard/campush-smoke.xml /tmp/campush-smoke.xml >/dev/null
python3 - <<'PY'
import re, sys
xml = open('/tmp/campush-smoke.xml', encoding='utf-8').read()
texts = re.findall(r'text="([^"]*)"', xml)
need = ['开始推流']
missing = [n for n in need if not any(n in t for t in texts)]
if missing:
    print('SMOKE FAIL missing:', missing, file=sys.stderr)
    sys.exit(1)
print('SMOKE OK start button visible')
PY
