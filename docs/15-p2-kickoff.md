# 15 · P2 启动记录与开发计划（WAN · 公网可访问）

| 项目 | 值 |
|---|---|
| 文档状态 | P2 启动中（Gate 待 Top5 确认与云资源） |
| 版本 | v0.1 |
| 生成日期 | 2026-10-21 |
| 上游依据 | [09 §2.1/§2.3.3](./09-task-plan.md)、[00 §20](./00-conventions.md)、[11 §2.6 Top5](./11-risk-adr.md)、[13 P1 验收](./13-p1-acceptance.md) |
| 结论 | **P1 条件 G1 通过（内测范围）**；P2 可进入 Gate 周，但正式 WAN 代码需先关闭 Top5 并具备云资源 |

---

## 1. P1 完成确认

| 项 | 状态 | 证据 |
|---|---|---|
| P1 交付物（31/31） | ✅ 完成 | [13 §1.1](./13-p1-acceptance.md) |
| P1 条件 G1（内测） | ✅ 通过 | AC-01 采样、iOS Safari、独立日志服务、自动化 49/49 |
| 未执行项 | ⚠️ 已登记豁免 | Top12 全矩阵、微信 X5、真实 AP 隔离、外部安全审计、真实麦克风听测 |
| P1 → P2 结论 | **允许进入 P2 Gate 周（不写业务代码）** | 09 §2.1 P2 门禁 |

> 说明：这是“条件 G1”，不是 Top20 全量正式 G1；正式 G1 可在 P2 内测期间补真机样本，不阻塞 P2 Gate 的设计/预研。

---

## 2. P2 启动门禁（G2-PRE）

| 门禁 | 要求 | 当前 |
|---|---|---|
| P1 条件 G1 | 通过 | ✅ |
| Top5 决策关闭 | D-1～D-5 有结论记录 | ✅ 已决（2026-10-21，见 §3） |
| M0-WAN E2/E4/E5 | NAT/网络矩阵、成本基线、TURN 验证 | ⚠️ 本机 RTMP→FLV/HLS 已通；公网/TURN 待做 |
| 云环境 | 域名 + TLS + TURN + 媒体服务器 + 监控 | ⚠️ 本机 Docker（SRS）已启动；公网/域名待提供 |
| 代码边界 | P2 代码不得破坏 P1 LAN 契约 | 约束已写入 AGENTS |

**规则**：Top5 未确认前只做设计与预研；确认后按 §4 顺序开工。

---

## 3. Top5 推荐结论（待确认，可直接“默认生效”）

| ID | 决策 | 结论（2026-10-21） | 对 P2 工作的影响 |
|---|---|---|---|
| D-1 | 默认访问范围 | **按网络自动推荐**：Wi-Fi 推荐 LAN、蜂窝/无 LAN 推荐 WAN；首启保留引导 | Android 首启/切换逻辑、AC-09 口径调整 |
| D-2 | 公网托管形态 | **v1 自建**：WHIP/SRS/Mediasoup + TURN | 云服务架构、成本模型、部署 |
| D-3 | 双摄门槛 | **支持率 ≥40% 才作卖点，否则实验性** | 先做 T0-A1 探测；P2 末再定 |
| D-4 | LAN 端侧 RTMP | **P2 实现** LAN RTMP 推流（范围新增） | 新增 Android RTMP 客户端 + 端侧 RTMP ingest，需评估工期 |
| D-5 | RTMPS | **P2 评估**；v1 明文 + UI 明示 | 安全评审与文案 |

> 决策已关闭（用户确认，2026-10-21）。D-4 为新增范围，需在 P2 WBS 中补 T2-A4（LAN RTMP ingest）与 Android RTMP 推流任务；D-1 与 00 §20.4 推荐值不同，已在验收/首启设计中记录。

---

## 4. P2 首批工作（Gate 周，按依赖排序）

| 顺序 | 任务 | 内容 | 依赖 | 交付 |
|---|---|---|---|---|
| 0 | T0-S4 | 验证环境：域名/TLS/TURN/媒体服务器/监控最小集 | 云资源 | 环境清单 + 访问方式 |
| 1 | T0-S1 | WHIP 接入 + WHEP 播放最小链路 | T0-S4、D-2 | 端到端可播录像 |
| 2 | T1-S1 | 房间 REST 骨架（创建/查询/停止、token、play-session、status；账号/额度/SSE 待补） | T0-S1 | `cloud-core` + JVM 测试 |
| 3 | T0-A2 | Android WHIP 推流最小闭环（单摄 720p30） | 接口初稿 | 成功日志 + 首帧时间 |
| 4 | T0-W1 | 播放页 WHEP 最小原型 + 错误态 | T0-S1 | 可打开 URL |
| 5 | T0-S2 | RTMP→FLV/HLS 兜底验证 | T0-S1 | HLS 可播 + 切片时长 |
| 6 | T0-A3 | 双摄 PiP OpenGL 合成（D-3 允许时） | T0-A1 | 合成+温升采样 |
| 7 | T2-A4（新增） | LAN 端侧 RTMP ingest（✅ 服务端骨架）+ Android RTMP 推流（待做） | P1 端侧服务 | 推流器接入 + FLV 播放 |

**开发原则**

- P2 新增 `cloud/` 服务与 `app` WAN 分支，**不得改动 P1 LAN 契约**；
- 播放页继续单文件、版本化；WAN/LAN 共用页面，按 `accessScope` 切换协议阶梯；
- 先跑通最小闭环（WHIP→WHEP/FLV），再补 HLS/TURN/双摄/切源；
- 每个任务必须有契约测试/首帧数据，禁止“只写通不验证”。

---

## 4.1 M0-WAN 本机环境进展（2026-10-21）

- Docker（OrbStack）可用；`tools/m0-wan/docker-compose.yml` 已提交；
- SRS 6 启动：`1935`（RTMP）、`18080`（HTTP-FLV/HLS）、`1985`（API），API 200；
- 用本地 ffmpeg 测试源推 `rtmp://127.0.0.1:1935/live/campush`：
  - HTTP-FLV `200`，约 **894 KB / 4s**；
  - HLS master `200`，切片 `live/campush-*.ts` 正常生成；
- TURN（coturn）镜像拉取受 macOS Docker 凭据弹窗阻塞，待解决；
- 已启用 SRS RTC（`rtc_server` + vhost `rtc.enabled`，8000/udp、8000/tcp）：
  - `players/whip.html` 通过 headless Chrome + fake media 点击 Publish，SRS 日志出现
    `RTC whip publish /live/campush`、`RTC init session`、`RTC: Use candidates 192.168.3.184`，
    说明 WHIP 信令与 ICE candidate 链路已打通；
  - `players/whep.html` 播放页面可打开（200）；headless CDP 自动化在拉流断言阶段不稳定，
    WHEP 实际首帧需用桌面 Chrome 手动/下一轮自动化验证；
  - **ICE 媒体面未通**：浏览器 WHIP 发布在 SRS 创建 session、answer 正常，但
    `publish.active=false / frames=0`；本地 Docker/OrbStack 下 `192.168.3.184:8000/udp`
    媒体包未到达 SRS。属环境/socket 路径问题，需 TURN 或 host 网络验证，未改宿主网络。
- 下一步：T0-S1 用 App/browser 完成 WHIP→WHEP 首帧并记录时延。

### 4.2 T1-S1 房间 REST 骨架（已启动）

- 新模块 `cloud-core/`（纯 JVM，无 Android/云依赖）：`CloudServer` + HS256 token；
- 已实现：`POST /api/v1/rooms`（accessScope=wan 校验、幂等键、roomId/pageToken/ingestToken/turn/limits）、
  `GET /rooms/{id}`、`DELETE /rooms/{id}`（ingest token）、`POST /rooms/{id}/play-session`、`GET /rooms/{id}/status`、`/healthz`；
- 测试：2/2 通过（创建→play-session→status、LAN scope 2007、无/坏 token 3001/3002）；
- SSE 已补：`GET /api/v1/rooms/{id}/events`（page token，`state`、`viewer-count`、`ended`、15s `heartbeat`，`retry:3000`）；
- WHIP/WHEP 反向代理已补：`POST /whep/{roomId}`、`POST /whip/{roomId}`，校验 media/ingest token，
  转发到 SRS `${whipHost}:${whipApiPort}`，带 CORS 头；
- 房间额度：`CloudConfig.maxRooms`（默认 20），超限 `429 + 2004 room_quota_exceeded`；
- 待补：账号套餐细分、play-session 60/min 限流、10 min 回收 clock 注入测试、真实 SDP 代理 E2E。

### 4.4 云端播放页（T0-W2 骨架）

- `GET /s/{roomId}?token=<pageToken>`：CloudPlayer 页面（WHEP 优先，HLS 兜底）；
- 页面流程：`POST play-session` → 拿 `mediaToken/protocols` → WHEP（`/whep/{roomId}`）5s 超时 →
  **FLV（mpegts.js，同源代理 `/live/{roomId}.flv?token=...`）** → HLS（`/assets/hls.min.js`）；
- 内置 `hls.min.js`（415 KB）与 `mpegts.js`（273 KB，Apache-2.0），无需外网 CDN；
- `CloudConfig` 增加 `playbackHost/playbackPort`（默认 `srs.m0-wan.orb.local:8080`），可在 `/config` 页修改；
- 本地启动：`cloud-core/build/install/cloud-core/bin/cloud-core`（JAVA_HOME 指向 JDK 17+），默认 `:18090`；
- 测试：`cloud-core` 10/10（播放页/资产、坏 token、PIN、FLV/HLS、SSE、房间额度 2004）。
- PIN：创建房间 `pinRequired=true` 必须提供 4–6 位数字（否则 400/3004）；
  `play-session` 校验 `pin`（错误/缺失 401/3005），播放页 3005 时弹窗输入重试；
- 播放页 HUD：WHEP 显示分辨率/fps/kbps（getStats），FLV/HLS 显示分辨率/丢帧；
- TURN：`play-session` 返回 `turn.urls/username/credential`，播放页 WebRTC 使用该 TURN；
- HLS 同源代理：`/hls/{roomId}.m3u8?token=` 与 `/hls/{seg}.ts?token=`，playlist 内 `.ts` 自动改写带 token；
  播放页不再直连 SRS，避免跨域/混合内容；
- 待补：MJPEG 兜底、真实媒体 E2E、首帧量化指标。

### 4.3 T0-A1 双摄能力探测（D-3 数据）

- 启动时通过 CameraManager 探测：逻辑多摄（`LOGICAL_MULTI_CAMERA` + `physicalCameraIds`）、
  API 30+ 并发相机组合（`getConcurrentCameraIds`）；
- 结果写入 `P1UiState.dualCameraSupported/physicalCameraCount/concurrentCameraPairs`，
  设置页「关于 → 双摄能力」显示，并记录 `CameraProbe` 诊断日志；
- 当前仅采集单机数据，D-3 的 ≥40% 支持率仍需 Top20 机型矩阵统计。

### 4.4 T2-A4 端侧 RTMP ingest（服务端骨架已完成）

- 新增 `RtmpIngestServer`（纯 JVM，依赖 `LanMediaPublisher` 契约）：
  - 简单握手、chunk 解析、AMF0 `connect/createStream/publish`；
  - H.264 AVC sequence header/NALU 与 AAC 帧 → `publishH264/publishAac`；
  - `LanConfig.rtmpPort`（App 默认 1935，失败不影响 HTTP/FLV 主链路）；
- 测试：`RtmpIngestServerTest` 与 `RtmpPushClientTest`（客户端→ingest 回环，H264+AAC）通过；
  `TeeLanMediaPublisherTest` 验证主链路成功/旁路失败隔离；
- 客户端：`RtmpPushClient`（简单握手、AMF0 publish、H.264/AAC FLV tag）已完成，
  与 `RtmpIngestServer` 回环测试通过；`TeeLanMediaPublisher` 支持 LAN 主链路 + RTMP 旁路（旁路失败不影响主链路）；
- 待做：App 管线接入 Tee + RTMP URL 设置、RTMPS 评估。

### 4.5 T0-A2 Android WHIP 客户端（已接入，待真机验证）

- 新增 `io.github.webrtc-sdk:android:125.6422.07`、`WanWhipClient`（Camera2 720p30 + PeerConnection）；
- 设置页新增「WAN WHIP 联调（P2）」：可填 WHIP URL、开始/停止、查看状态；
- Controller 扩展 `wanTest/startWanTest/stopWanTest` 契约，UI 不直接依赖 WebRTC 实现；
- 模拟器实测：WHIP offer 已到达 SRS（日志 `RTC whip publish /live/campush-dev`），
  但 SRS 返回 `5018 RtcSdpNegotiate: no found valid H.264 payload type`；
  原因是模拟器没有硬件 H.264 编码器，WebRTC SDK 只提供 VP8/VP9（SRS RTC 只收 H264）；
- 设置页已接入 **WAN 房间流程**：填 cloud-core API → 创建房间 → 取 `ingest.whipUrl` → WHIP 推流 → 停止时 DELETE 房间；
- 模拟器实测：房间创建成功（如 `rV190RZWSB`），拿到 roomId/playUrl，WHIP offer 到达 SRS 后因无 H264 失败；
- 下一步：在真机（有 H.264 硬编）验证 App→SRS WHIP 30fps；模拟器可用 RTMP/FLV 路径做替代验证；
- 注意：cloud-core 的 `CAMPUSH_API_HOST` 需设为手机/模拟器可达地址，否则返回的 playUrl 是 127.0.0.1。

**地址配置（本轮新增）**

- `CloudConfig` 不再硬编码：`apiHost`/`playHost`/`whipHost`/`whipApiPort`/`turnUrls` 均可配置；
- 默认值指向本地 Docker：`whipHost=http://srs.m0-wan.orb.local`、`whipApiPort=1985`，
  `apiHost/playHost=http://127.0.0.1:18090`（本机 cloud-core），TURN `turn:srs.m0-wan.orb.local:3478`；
- 配置页面：`GET /config`（表单保存）与 `GET/POST /api/v1/config`（JSON），保存后新建房间立即生效；
- 部署时可用环境变量覆盖：`CAMPUSH_API_HOST`、`CAMPUSH_PLAY_HOST`、`CAMPUSH_WHIP_HOST`、
  `CAMPUSH_WHIP_API_PORT`、`CAMPUSH_TURN_URLS`；
- 测试：`cloud-core` 5/5（默认值、配置页/保存、房间/play-session/status、SSE、媒体代理鉴权/CORS、错误码）。

> 本地 Docker 环境（SRS 域名/容器 IP/端口）已记录在 [17-local-dev-env.md](./17-local-dev-env.md)；
> 后续本地开发直接使用 `srs.m0-wan.orb.local:8080`（页面）与 `:1985`（API），不修改宿主/OrbStack 网络。

## 5. 需要用户/环境提供

| # | 资源 | 用途 | 无此项的替代 |
|---|---|---|---|
| 1 | 云账号（或本机 Docker） | 部署 Mediasoup/SRS/TURN | 本机 Docker 只能做 M0-WAN 逻辑验证，不能验证公网/NAT |
| 2 | 域名 + TLS 证书 | WHIP/WHEP/播放页 HTTPS | 无证书只能用 `http://IP`，无法验证浏览器安全上下文 |
| 3 | TURN 服务器/凭据 | NAT 穿透（`7005`） | 无 TURN 只能验证同网/公网直连 |
| 4 | 公网入口/端口 | E2 网络矩阵 | 无 |
| 5 | 监控/日志账号 | T1-S7 可观测 | 本地文件指标替代 |
| 6 | Top5 确认 | 决定默认值/双摄/RTMPS | 按推荐值“默认生效” |

---

## 6. P2 架构（按 D-2 推荐值）

```
App ──WHIP/SRT/RTMP──> 接入网关 ──> Mediasoup SFU ──WHEP──> 播放页
                              │            │
                              │            ├─> SRS 转封装 ─> FLV/HLS
                              └─> 房间/token/额度 REST + SSE
TURN（ICE 回落，7005）
监控：指标/结构化日志/traceId（T1-S7）
```

P2 契约：REST/SSE/媒体面严格按 [04](./04-interface-contracts.md)；播放页按 [06](./06-playback-page.md) 的 `webrtc→flv→hls` 阶梯。

---

## 7. P2 验收与风险

- **G2 门禁**：WAN E2E、唯一地址、停止 `410` ≤2s、首帧 p95 ≤1.85s、降级/HLS、并发 2008、Top5 关闭；
- **风险**：R-003 公网成本、R-004 NAT/TURN、R-019 决策延迟、R-001 双摄兼容；
- **首个演示**：App 公网 WHIP 推流 → 另一网络浏览器打开唯一 HTTPS 地址 → WHEP 播放 → 停止后 `410`。

---

## 8. 下一步

1. 用户确认 Top5（或按推荐值默认生效）；
2. 提供云环境（域名/TLS/TURN/媒体服务器）或同意先做本机 mock；
3. 完成 T0-S4 → T0-S1，产出 WHIP/WHEP 最小可播录像；
4. 进入 T1-S1 房间 REST 与 T0-A2 Android WHIP。

> 本文状态随 Gate 进展更新；P2 未正式启动前不得在 P1 分支引入云端运行时依赖。
