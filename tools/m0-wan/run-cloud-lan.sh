#!/usr/bin/env bash
# 启动 cloud-core 供同一局域网手机访问（绑定 0.0.0.0，使用 Mac 局域网 IP）。
set -euo pipefail
cd "$(dirname "$0")/../.."

IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
[ -n "$IP" ] || IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
[ -n "$IP" ] || { echo "未找到局域网 IP（en0/en1）" >&2; exit 1; }

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME
scripts/build.sh :cloud-core:installDist >/dev/null

export CAMPUSH_BIND_HOST=0.0.0.0
export CAMPUSH_API_HOST="http://$IP:18090"
export CAMPUSH_PLAY_HOST="http://$IP:18090"
export CAMPUSH_WHIP_HOST="http://$IP"
export CAMPUSH_WHIP_API_PORT=1985
export CAMPUSH_PLAYBACK_HOST="http://$IP"
export CAMPUSH_PLAYBACK_PORT=18080
export CAMPUSH_TURN_URLS="turn:$IP:3478?transport=udp"

echo "Cloud API: http://$IP:18090"
echo "手机设置 WAN Cloud API 填: http://$IP:18090"
exec cloud-core/build/install/cloud-core/bin/cloud-core
