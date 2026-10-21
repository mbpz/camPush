# 17 · 本地 Docker 开发环境（M0-WAN / P2）

| 项目 | 值 |
|---|---|
| 状态 | 生效（用户已部署 SRS） |
| 用途 | P2 M0-WAN / WHIP·WHEP / RTMP·FLV·HLS 本地联调 |
| 约束 | **不修改宿主机、OrbStack 或 Docker 网络配置**；只使用已提供的容器/域名/端口 |

---

## 1. 已提供的环境（用户部署）

| 项 | 值 |
|---|---|
| 镜像 | `ossrs/srs:6`（已 `docker` 部署） |
| 容器名 | `campush-srs` |
| OrbStack 域名 | `srs.m0-wan.orb.local`（自动解析） |
| 容器 IP | `192.168.107.2` |
| Web/播放页端口 | `8080`（宿主机映射 `18080`） |
| SRS HTTP API / WHIP 信令 | `1985` |
| RTMP ingest | `1935` |
| WebRTC (UDP/TCP) | `8000` |

> `srs.m0-wan.orb.local` 解析到容器 IP；Chromium 浏览器可直接访问 `:8080` 页面与 `:1985` API。
> 不要为此域名添加反向代理/修改 OrbStack 设置；需要 HTTPS 时由部署方提供。

## 2. 常用 URL（直接用）

```text
# SRS 页面
http://srs.m0-wan.orb.local:8080/players/whep.html
http://srs.m0-wan.orb.local:8080/players/whip.html

# SRS API
http://srs.m0-wan.orb.local:1985/api/v1/versions
GET  http://srs.m0-wan.orb.local:1985/api/v1/streams/

# WHIP 推流（App/浏览器）
http://srs.m0-wan.orb.local:1985/rtc/v1/whip/?app=live&stream=<stream>

# WHEP 播放（页面 query 显式指定 API host/port）
http://srs.m0-wan.orb.local:8080/players/whep.html?app=live&stream=<stream>&server=srs.m0-wan.orb.local&api=1985&schema=http

# RTMP / FLV / HLS
rtmp://srs.m0-wan.orb.local:1935/live/<stream>
http://srs.m0-wan.orb.local:18080/live/<stream>.flv
http://srs.m0-wan.orb.local:18080/live/<stream>.m3u8
```

宿主机（本机脚本/ffmpeg）也可用映射端口：

```text
rtmp://127.0.0.1:1935/live/<stream>
http://127.0.0.1:18080/live/<stream>.flv
http://127.0.0.1:1985/api/v1/versions
```

## 3. 已验证

- SRS API/页面 `200`；RTMP 测试源 → HTTP-FLV `200`（约 894 KB/4s）、HLS master `200` 且切片生成；
- WHIP 页面通过 headless Chrome fake media 发起 publish，SRS 日志出现
  `RTC whip publish` / `RTC init session` / `RTC: Use candidates`；
- WHEP 页面可打开；无头浏览器自动化在拉流断言阶段不稳定（CDP 挂起），
  实际首帧由桌面 Chrome/App 验证。

## 3.1 已知限制

- SRS 的 RTC publish 只接受 **H.264**。Android 模拟器没有硬件 H.264 编码器，WebRTC SDK 的
  offer 只有 VP8/VP9 → SRS 返回 `5018 no found valid H.264 payload type`；
  App WHIP 需在真机验证，模拟器可用 RTMP→FLV/HLS 替代验证。
- `srs.m0-wan.orb.local` 在 macOS 浏览器可用；手机/模拟器访问时用 Mac 局域网 IP 或模拟器
  `10.0.2.2`（仅端口映射，不改网络配置）。
- **ICE 媒体面已于 2026-10-21 打通**：SRS 的 `CANDIDATE` 使用**容器 IP `192.168.107.2`**
  时，Chrome 可完成 DTLS/ICE，WHIP publisher / WHEP subscriber 均 `established`；
  使用宿主 Wi-Fi IP 时 UDP 包未到达（原因未定，未改宿主网络）。
  仅本机浏览器调试用容器 IP；手机/跨设备仍用宿主 IP/TURN。
  复现：`CANDIDATE=192.168.107.2 docker compose up -d --force-recreate srs`。

## 3.2 cloud-core 本地运行

```bash
scripts/build.sh :cloud-core:installDist
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
CAMPUSH_API_HOST=http://10.0.2.2:18090 \
CAMPUSH_WHIP_HOST=http://10.0.2.2 CAMPUSH_WHIP_API_PORT=1985 \
CAMPUSH_PLAYBACK_HOST=http://10.0.2.2 CAMPUSH_PLAYBACK_PORT=18080 \
  cloud-core/build/install/cloud-core/bin/cloud-core
# 默认 http://127.0.0.1:18090；/config 配置页；创建房间：
curl -X POST http://127.0.0.1:18090/api/v1/rooms \
  -H 'Authorization: Bearer acct_dev' -H 'Content-Type: application/json' \
  -d '{"accessScope":"wan","captureMode":"single_back"}'
```

## 3.3 端侧 RTMP ingest 本地验证

```bash
# App 开始局域网推流后，将模拟器 1935 转发到宿主机
adb forward tcp:19351 tcp:1935
ffmpeg -re -f lavfi -i testsrc=size=320x180:rate=15 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 15 -t 4 \
  -f flv rtmp://127.0.0.1:19351/live/d4test
# 查询端侧状态（需要页面 token）：rtmpFrames 应 >0
curl -H "X-Page-Token: <pageToken>" http://127.0.0.1:<forwardedPort>/api/v1/status
```

实测 2026-10-21：`rtmpFrames:60`。

## 4. 规则

1. **不要**修改宿主/OrbStack 网络、域名解析、端口映射；只用上表现值。
2. 需要新端口/证书/域名时，先在 [docs/15](./15-p2-kickoff.md) 登记并确认，不擅自改环境。
3. WebRTC `CANDIDATE` 必须使用浏览器/手机可达的地址（宿主局域网 IP 或已提供域名）；
   本地容器 `CANDIDATE` 可先设为 `192.168.3.184`（宿主 Wi-Fi IP）或按部署方提供的值。
4. 容器内 IP `192.168.107.2` 仅用于容器间/OrbStack 直连；跨设备访问用域名或宿主端口。
