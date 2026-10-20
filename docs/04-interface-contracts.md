# 04 · 接口契约（Interface Contracts）

| 项目 | 值 |
|---|---|
| 文档状态 | 基线（Draft → Baseline） |
| 版本 | v1.1 |
| 依赖文档 | [00-conventions.md](./00-conventions.md)（唯一契约来源）、[03-cloud-service.md](./03-cloud-service.md) |
| 适用 PRD | §6、§7、§9.3、§11.2、§13、§14、§16.2 |
| 关注契约章节 | 00 §4 地址、§5 token、§6 REST、§7 媒体面、§8 LAN API、§9 错误码、§10 预算、§11 埋点 |
| 主责 | 服务端（云服务与接口契约作者） |
| 最后更新 | 2026-10-20 |

> 本文件是**可直接生成前后端代码与契约测试**的粒度定义。所有路径、字段名、枚举、错误码、token claims、TTL 与状态机与 00 **逐字一致**；本文件只做细化。凡 00 未定义的形状，本文给出冻结定义并登记到 §15 开放问题，请求 Lead 回写 00。

---

## 1. 目的与范围

### 1.1 目的

| 编号 | 目标 |
|---|---|
| G1 | 冻结 WAN REST 的全部端点：Header、字段、校验、示例、状态码、错误码、幂等、超时、重试、限流 |
| G2 | 冻结 SSE 事件：类型、`data` 结构、顺序、续传、心跳 |
| G3 | 冻结媒体面 WHIP/WHEP/FLV/HLS 的请求响应、SDP/ICE 约束、trickle、错误码 |
| G4 | 给出播放页调用顺序与降级判定，可据此写 E2E 测试 |
| G5 | 给出 LAN 与 WAN 的同构/差异对照，避免客户端两套模型 |
| G6 | 给出三状态机在接口上的映射，可直接生成状态机测试用例 |
| G7 | 提供 OpenAPI 3.1 片段与契约测试清单，纳入 CI |

### 1.2 范围内

- `{apiHost}/api/v1/*` REST 与 SSE。
- `{whipHost}/whip/*`、`{whipHost}/whep/*`、`{playHost}/live/*`、`{playHost}/hls/*`。
- LAN 端侧 API 与 WAN 的对照（详细实现见 05）。

### 1.3 范围外

| 项 | 归属 |
|---|---|
| 客户端内部类与状态管理 | 02 |
| 播放页 DOM/UI/播放器选型 | 06 |
| 端侧服务实现细节 | 05 |
| 威胁模型与渗透测试 | 07 |
| 指标字典 | 10 |

### 1.4 约定层级

```text
00-conventions.md  = 契约基线（不可改写）
04（本文）          = 契约细化 + 可执行形状（OpenAPI/示例/测试清单）
03-cloud-service.md = 服务端实现设计（不改变契约）
06-playback-page.md = 契约消费者实现
```

---

## 2. 通用约定

### 2.1 Base URL 与版本

| 环境 | `apiHost` | `whipHost`/`whepHost` | `playHost` |
|---|---|---|---|
| 生产 | `api.example.com` | `ingest.example.com` | `play.example.com` |
| 预发 | 同模板，独立子域 | 同模板 | 同模板 |
| 测试 | 同模板 | 同模板 | 同模板 |

- API 前缀固定 `{apiHost}/api/v1`（00 §4.2）；路径版本为 `v1`，破坏性变更升 `v2`（00 §6.3）。
- 媒体面路径固定：`/whip/{roomId}`、`/whep/{roomId}`、`/live/{roomId}.flv`、`/hls/{roomId}/index.m3u8`、`/hls/{roomId}/{segment}`。
- 域名只允许使用 00 §4.2 的占位值；任何真实域名视为契约违规。

### 2.2 请求头

| Header | 必填 | 适用端点 | 格式/取值 | 说明 |
|---|---|---|---|---|
| `Authorization` | 视端点 | `POST /rooms`、`GET /rooms/{roomId}`（账号 Bearer）；`DELETE`、`ingest:switch`（ingest Bearer）；`page-token:refresh`、`status`（page Bearer）；`WHIP`/`WHEP`（media/ingest Bearer） | `Bearer <JWT>` | 与 00 §5.1 一致 |
| `X-Page-Token` | 是 | `POST /rooms/{roomId}/play-session` | `<pageToken>` | 00 §6.2 冻结；**不使用** `Authorization` |
| `Content-Type` | 是（有 body 时） | REST | `application/json; charset=utf-8` | — |
| `Content-Type` | 是 | WHIP/WHEP | `application/sdp` | — |
| `Content-Type` | 是 | WHIP PATCH | `application/trickle-ice-sdpfrag` | — |
| `Accept` | 否 | 全部 | `application/json` / `application/sdp` | — |
| `Idempotency-Key` | 否 | `POST /rooms`、`DELETE /rooms/{roomId}` | `^[A-Za-z0-9_-]{16,64}$` | 24 h 内同键返回首次结果 |
| `X-Trace-Id` | 否 | 全部 | 16–32 位十六进制 | 缺省由服务端生成（见 §15 Q6） |
| `User-Agent` | 否 | 全部 | `CamPush/{appVersion} (android; {deviceModel})` | 仅统计用，不参与鉴权 |
| `Origin` | 否 | 播放页跨域请求 | `https://play.example.com` | CORS 见 §7.3 |

### 2.3 响应头

| Header | 出现条件 | 说明 |
|---|---|---|
| `X-Request-Id` | 全部响应 | `req_` + 16 位 Crockford（00 §4.1），用于排障与错误信封关联 |
| `Content-Type` | 全部 | `application/json; charset=utf-8`；媒体面按 §5 |
| `Cache-Control` | API | `no-store` |
| `Retry-After` | `429`/`503` | 秒数（如 `2`）或 HTTP-date；客户端两者都要解析 |
| `Location` | `POST /whip/{roomId}`、`POST /whep/{roomId}`（`201`） | 资源路径 |
| `Set-Cookie` | `GET /hls/{roomId}/index.m3u8` | `cmp_ps=<opaque>; Path=/hls/{roomId}; HttpOnly; Secure; SameSite=Lax`（00 §7） |

### 2.4 编码与时间

| 项 | 约定 |
|---|---|
| 字符编码 | UTF-8 |
| 时间 | RFC 3339 UTC，秒级，例 `2026-10-20T08:00:00Z`（00 §6） |
| 时长 | 整数秒（`expiresIn`、`maxDurationSec`、`graceMs` 为毫秒整数） |
| 布尔 | 原生 `true`/`false`，禁止 `"true"`/`1` |
| 枚举 | wire 值全小写，见 00 §3 |
| 未知字段 | 服务端忽略未知请求字段；客户端必须忽略未知响应字段 |
| 缺失 vs null | 可选字段缺失表示"未设置"；显式 `null` 仅用于 `lastFrameAt`/`switchedAt` 等确实无值 |

### 2.5 错误信封与判定顺序

错误响应体严格使用 00 §9.1：

```json
{
  "code": 3003,
  "error": "token_expired",
  "message": "Playback token has expired",
  "retryable": false,
  "userHint": "播放凭证已过期，请向分享者重新获取地址",
  "requestId": "req_9f3k2m7q4x8p1t5w"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | int | 是 | 业务码，**唯一判定依据**；客户端不得仅凭 HTTP 状态分支 |
| `error` | string | 是 | `snake_case` 标识，与 `code` 一一对应 |
| `message` | string | 是 | 英文开发者信息，不展示给用户 |
| `retryable` | boolean | 是 | `true` 时 UI 才展示"重试"（00 §9.2 规则） |
| `userHint` | string | 是 | 中文用户提示，可直接展示 |
| `requestId` | string | 是 | 与响应头 `X-Request-Id` 相同 |

**服务端校验顺序**（00 §5.1）：`签名 → exp → 房间状态 → 会话/并发`。

| 阶段 | 失败码 | HTTP |
|---|---|---|
| 无凭证 | `3001` | 401 |
| 签名/格式错误 | `3002` | 401 |
| 过期 | `3003` | 401 |
| 权限不匹配（`aud`/`scope`/`sub`） | `3007`（ingest）或 `3002`（page/media） | 403/401 |
| 房间不存在 | `2001` | 404 |
| 房间已关闭 | `2002` | 410 |
| 房间已过期 | `2003` | 410 |
| 状态不允许 | `2007` | 409 |
| 并发/配额 | `2008`、`2004` | 429/403 |
| 限流 | `3008` | 429 |

### 2.6 幂等

| 端点 | 幂等性 | 机制 | 重复请求行为 |
|---|---|---|---|
| `POST /rooms` | 条件幂等 | `Idempotency-Key`（推荐必带） | 24 h 内同键 → 原样返回首次 `201` 与同一 `roomId` |
| `DELETE /rooms/{roomId}` | 天然幂等 | 状态机（`closed` 再删返回 `204`） | 重复 `204`；同键 24 h 返回首次 `204` |
| `POST /ingest:switch` | 条件幂等 | 房间状态 `switching` + `switchId` | 进行中 → `409`+`2006`；完成后同键返回首次 `202`（缓存 10 min） |
| `POST /play-session` | 非幂等（可安全重试） | 每次返回新 `playSessionId` | 重试会新建会话，旧会话 60 s 空闲回收 |
| `POST /page-token:refresh` | 幂等（返回新 token） | 旧 token 在 TTL 内仍有效 | 重试产生多个 token，均有效至各自 `exp` |
| `WHIP POST` | 非幂等 | 单会话互斥 | 新会话顶掉旧会话（旧 `409`+`4004`） |
| `WHIP PATCH` | 幂等 | 候选去重 | 重复候选忽略 |
| `WHIP/WHEP DELETE` | 幂等 | 资源已结束返回 `204` | 重复 `204` |

**幂等键并发语义**：同键首个请求处理中时，第二个请求返回 `409` + `2005`（`retryable=true`，`Retry-After: 1`）；随后重试返回首次结果。

### 2.7 超时预算

| 端点 | 客户端超时 | 服务端处理上限 | 依据 |
|---|---|---|---|
| `POST /rooms` | 5 000 ms | 2 000 ms | 冷启动 ≤ 10 s（00 §10.3） |
| `GET /rooms/{roomId}` | 3 000 ms | 500 ms | — |
| `DELETE /rooms/{roomId}` | 5 000 ms | 1 000 ms 返回 `204`（`stopping → closed` ≤ 5 s） | 00 §3.1/§10.3 |
| `POST /ingest:switch` | 5 000 ms | 300 ms 返回 `202`（切换窗口 15 s 异步） | §4.3 |
| `GET /rooms/{roomId}/status` | 3 000 ms | 300 ms | — |
| `POST /play-session` | 3 000 ms | 500 ms | 首帧预算 250 ms（00 §10.2） |
| `POST /page-token:refresh` | 3 000 ms | 500 ms | — |
| SSE 建连 | 3 000 ms | — | 45 s 无任何事件视为断线（3 × 心跳） |
| `WHIP POST` | 5 000 ms | 3 000 ms | — |
| `WHIP PATCH`/`DELETE` | 2 000 ms | 500 ms | — |
| `WHEP POST` | 5 000 ms（降级判定 2.5 s） | 3 000 ms | 00 §10.2 降级阶梯 |
| `WHEP DELETE` | 2 000 ms | 500 ms | — |
| FLV 建连 | 2 000 ms | — | 00 §10.2 `flv(2.0s)` |
| HLS playlist/分片 | 5 000 ms | — | 00 §10.2 `hls(5.0s)` |

### 2.8 重试与退避

**重试判定**

| 情况 | 是否重试 |
|---|---|
| 网络错误、连接超时、读写超时 | 是 |
| `429`、`500`、`502`、`503`、`504` | 是（遵循 `Retry-After`） |
| `408`、`425` | 是（`425` 需带同一 `Idempotency-Key`） |
| `400`、`401`、`403`、`404`、`409`、`410`、`413`、`415` | 否（属于契约性错误，重试无意义） |
| 业务码 `retryable=false` | 否（即使 HTTP 为 5xx） |

**退避算法**（指数退避 + 全抖动）：

```text
delay(n) = random(0, min(cap, base × 2^n))
base = 300 ms，cap = 3 000 ms（REST）/ 8 000 ms（媒体重连）
n 从 0 开始；第 n 次重试前等待 delay(n)
```

| 场景 | 最大尝试次数 | 备注 |
|---|---|---|
| `GET /rooms`、`status` | 3 | 总耗时 ≤ 10 s |
| `POST /play-session` | 3 | 失败后进入 `playerState=failed` |
| `POST /rooms`（带幂等键） | 3 | 同键重试安全 |
| `POST /rooms`（无幂等键） | 1 | 避免重复建房 |
| `DELETE` | 3 | 同键或天然幂等 |
| WHIP/WHEP POST | 2 | 第 2 次前先 `DELETE` 旧会话 |
| FLV 重连 | 5 | `0/1/2/4/8 s` + 抖动，总 ≤ 30 s |
| SSE 重连 | 无限（会话期间） | 服务端建议 `retry: 3000`，带 `Last-Event-ID` |

`Retry-After` 优先于本地退避：若服务端给出 `Retry-After: 5`，客户端等待 ≥ 5 s（± 10% 抖动）。

### 2.9 限流

| 维度 | 限额 | 窗口 | 超限响应 |
|---|---|---|---|
| 账号（REST 全部） | 30 req/min（00 §6.3） | 滑动 60 s | `429`+`3008`，`Retry-After` |
| `play-session` 按 IP | 60 req/min（00 §6.3） | 滑动 60 s | `429`+`3008` |
| PIN 校验按 IP | 5 次/min（00 §5.2） | 固定 60 s | `429`+`3006`，`Retry-After` |
| PIN 连续失败 | 10 次 → 锁定 10 min（00 §5.2） | — | `429`+`3006`，`Retry-After: 600` |
| 单房间 SSE 连接 | `2 × maxViewers`（建议，待 00 确认，§15 Q8） | 并发数 | `429`+`2008` |
| 单房间 WHIP POST | 10 次/min（建议） | 滑动 60 s | `429`+`4001` |
| 单房间 `ingest:switch` | 5 次/min（建议） | 滑动 60 s | `429`+`2006` |

> 00 已冻结的限流（账号 30/min、`play-session` IP 60/min、PIN）必须实现；标"建议"的项需 Lead 确认后回写 00（§15 Q8）。

### 2.10 字段命名与兼容

- wire 字段一律 `camelCase`；枚举值全小写（00 §3）。
- `roomId`、`ingestId`、`playSessionId`、`switchId`、`requestId` 前缀与字符集见 00 §4.1。
- 新增字段必须**可选**且语义不重叠；删除/改名属于破坏性变更（§12）。
- 客户端不得依赖字段顺序；不得依赖未声明字段。

---

## 3. REST 端点契约

### 3.1 `POST /rooms` — 创建房间与推流凭证

| 项 | 值 |
|---|---|
| 方法/路径 | `POST {apiHost}/api/v1/rooms` |
| 鉴权 | 账号 `Authorization: Bearer <accountToken>` |
| 成功 | `201 Created` |
| 幂等 | `Idempotency-Key`（24 h） |
| 限流 | 账号 30 req/min |
| 超时 | 客户端 5 s / 服务端 2 s |
| 副作用 | 创建 `rooms` 记录（`state="created"`），签发 `pageToken`/`mediaToken` 模板/`ingestToken`，启动 10 min 超时定时器 |

**请求体字段**

| 字段 | 类型 | 必填 | 校验 | 默认 | 说明 |
|---|---|---|---|---|---|
| `accessScope` | string | 是 | 必须为 `"wan"`；`"lan"` → `400`+`2007` | — | 云端 API 只接受 WAN；LAN 走端侧（05） |
| `captureMode` | string | 是 | `single_back`/`single_front`/`dual` | — | 00 §3 |
| `pushProtocol` | string | 否 | `whip`/`srt`/`rtmp` | `"whip"` | 00 §3 |
| `dualLayout` | string | 否 | `pip`/`split_h`/`split_v`；仅 `captureMode="dual"` 有效 | `"pip"` | 非 `dual` 时忽略 |
| `dualPrimary` | string | 否 | `back`/`front`；仅 `dual` 有效 | `"back"` | — |
| `maxDurationSec` | int | 否 | 60–43 200，≤ 套餐上限 | 套餐上限 | 00 §6.2 `limits` |
| `pinRequired` | boolean | 否 | — | `false` | 00 §5.2 |
| `pin` | string | 否 | 4–6 位数字；`pinRequired=true` 时必填（见 §15 Q3） | — | 不得出现在 URL/日志 |
| `client.platform` | string | 否 | 固定 `"android"` | `"android"` | — |
| `client.appVersion` | string | 否 | `^\d+\.\d+\.\d+$` | — | 用于灰度 |
| `client.deviceModel` | string | 否 | ≤ 64 字符 | — | 仅统计 |

**请求示例**

```http
POST /api/v1/rooms HTTP/1.1
Host: api.example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json; charset=utf-8
Idempotency-Key: 8f2c1d4e6a9b3f70
X-Trace-Id: 4f2a9c1e8b7d6a53

{
  "accessScope": "wan",
  "captureMode": "single_back",
  "pushProtocol": "whip",
  "maxDurationSec": 43200,
  "pinRequired": false,
  "client": { "platform": "android", "appVersion": "1.0.0", "deviceModel": "Pixel 8" }
}
```

**响应 `201`**（字段名冻结，与 00 §6.2 完全一致）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `roomId` | string | 是 | `r` + 9 位 Crockford |
| `state` | string | 是 | 固定 `"created"` |
| `createdAt` | string | 是 | RFC 3339 UTC |
| `expireAt` | string | 是 | `createdAt + maxDurationSec` |
| `playUrl` | string | 是 | `{playHost}/s/{roomId}?token={pageToken}`，全程不变 |
| `ingest.protocol` | string | 是 | 与请求 `pushProtocol` 一致 |
| `ingest.whipUrl` | string | 是 | `{whipHost}/whip/{roomId}` |
| `ingest.rtmpUrl` | string | 是 | `rtmp://{whipHost}/live/{roomId}?token={ingestToken}` |
| `ingest.srtUrl` | string | 是 | `srt://{whipHost}:10080?streamid=#!::r={roomId},t={ingestToken}` |
| `ingest.token` | string | 是 | `ingestToken`（JWT），房间寿命内有效，≤ 12 h |
| `turn.urls` | string[] | 是 | 至少 1 个 TURN URL |
| `turn.username` | string | 是 | `{expiryEpoch}:{roomId}` |
| `turn.credential` | string | 是 | base64 HMAC-SHA1 |
| `limits.maxViewers` | int | 是 | 套餐快照 |
| `limits.maxDurationSec` | int | 是 | 套餐快照 |

```json
{
  "roomId": "r7k2m9x4qp",
  "state": "created",
  "createdAt": "2026-10-20T08:00:00Z",
  "expireAt": "2026-10-20T20:00:00Z",
  "playUrl": "https://play.example.com/s/r7k2m9x4qp?token=eyJ...",
  "ingest": {
    "protocol": "whip",
    "whipUrl": "https://ingest.example.com/whip/r7k2m9x4qp",
    "rtmpUrl": "rtmp://ingest.example.com/live/r7k2m9x4qp?token=eyJ...",
    "srtUrl": "srt://ingest.example.com:10080?streamid=#!::r=r7k2m9x4qp,t=eyJ...",
    "token": "eyJ..."
  },
  "turn": {
    "urls": ["turn:turn.example.com:3478?transport=udp"],
    "username": "1729400000:r7k2m9x4qp",
    "credential": "base64..."
  },
  "limits": { "maxViewers": 100, "maxDurationSec": 43200 }
}
```

**错误码映射**

| 情形 | HTTP | code | retryable |
|---|---|---|---|
| 无账号 token / 过期 | 401 | `3001` / `3003` | 否 / 是 |
| `accessScope="lan"` | 400 | `2007` | 否 |
| 枚举非法 | 400 | `2007` | 否 |
| `pinRequired=true` 但 `pin` 缺失/非法 | 400 | `3004` | 否 |
| 并发 `live` 房间超限 | 403 | `2004` | 否 |
| 租户被停用 | 403 | `2007` | 否 |
| 限流 | 429 | `3008` | 是 |
| 幂等键冲突（处理中） | 409 | `2005` | 是 |

### 3.2 `GET /rooms/{roomId}` — 查询房间（App 侧）

| 项 | 值 |
|---|---|
| 方法/路径 | `GET {apiHost}/api/v1/rooms/{roomId}` |
| 鉴权 | 账号 `Authorization: Bearer <accountToken>`；仅房间归属账号可读 |
| 成功 | `200` |
| 幂等 | 天然幂等 |
| 超时 | 3 s / 500 ms |
| 用途 | App 停止前确认状态；**协议切换后获取最新 `ingest.token`**（03 §18 Q1 方案 A） |

**响应 `200`**（形状由本文冻结，见 §15 Q1）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `roomId` | string | 是 | — |
| `state` | string | 是 | `created`/`live`/`switching`/`stopping`/`closed`/`failed` |
| `accessScope` | string | 是 | 固定 `"wan"` |
| `captureMode` | string | 是 | — |
| `pushProtocol` | string | 是 | 当前/目标协议 |
| `createdAt` / `expireAt` | string | 是 | — |
| `liveAt` | string\|null | 否 | 首次 `live` 时刻 |
| `closedAt` | string\|null | 否 | — |
| `failReason` | string\|null | 否 | 失败原因标识（非错误码） |
| `playUrl` | string | 是 | 不变 |
| `ingest` | object | 是 | 同 §3.1；**包含当前有效的 `token`** |
| `turn` | object | 是 | 同 §3.1 |
| `limits` | object | 是 | 同 §3.1 |
| `viewers` | int | 是 | 当前观看者数 |
| `lastFrameAt` | string\|null | 是 | 最近帧时间 |
| `source.pushProtocol` | string | 是 | 当前生效源 |
| `source.switchedAt` | string\|null | 是 | 最近一次源生效时刻 |

```json
{
  "roomId": "r7k2m9x4qp",
  "state": "live",
  "accessScope": "wan",
  "captureMode": "single_back",
  "pushProtocol": "srt",
  "createdAt": "2026-10-20T08:00:00Z",
  "expireAt": "2026-10-20T20:00:00Z",
  "liveAt": "2026-10-20T08:00:04Z",
  "closedAt": null,
  "failReason": null,
  "playUrl": "https://play.example.com/s/r7k2m9x4qp?token=eyJ...",
  "ingest": { "protocol": "srt", "whipUrl": "https://ingest.example.com/whip/r7k2m9x4qp", "rtmpUrl": "rtmp://ingest.example.com/live/r7k2m9x4qp?token=eyJ...", "srtUrl": "srt://ingest.example.com:10080?streamid=#!::r=r7k2m9x4qp,t=eyJ...", "token": "eyJ..." },
  "turn": { "urls": ["turn:turn.example.com:3478?transport=udp"], "username": "1729403600:r7k2m9x4qp", "credential": "base64..." },
  "limits": { "maxViewers": 100, "maxDurationSec": 43200 },
  "viewers": 3,
  "lastFrameAt": "2026-10-20T08:03:20Z",
  "source": { "pushProtocol": "srt", "switchedAt": "2026-10-20T08:03:00Z" }
}
```

错误：`401`+`3002`、`404`+`2001`（含"非本人房间"统一返回 404，不泄露存在性）、`429`+`3008`。

### 3.3 `DELETE /rooms/{roomId}` — 停止推流并销毁房间

| 项 | 值 |
|---|---|
| 方法/路径 | `DELETE {apiHost}/api/v1/rooms/{roomId}` |
| 鉴权 | `Authorization: Bearer <ingestToken>`（00 §6.1；pageToken **不可**停止房间） |
| 成功 | `204 No Content` |
| 幂等 | 天然幂等 + `Idempotency-Key` |
| 超时 | 5 s / 1 s 返回 `204` |
| 副作用 | `state → stopping`；媒体端点**立即** `410`；≤ 5 s 内 `closed`；撤销全部 token |

**请求示例**

```http
DELETE /api/v1/rooms/r7k2m9x4qp HTTP/1.1
Host: api.example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Idempotency-Key: a1b2c3d4e5f60718
```

**状态与响应**

| 当前 `state` | 响应 | 说明 |
|---|---|---|
| `created`/`live`/`switching` | `204` | 进入 `stopping`，5 s 内 `closed` |
| `stopping` | `204` | 幂等 |
| `closed` | `204` | 幂等成功 |
| `failed` | `204` | 幂等成功（资源回收确认） |
| 不存在 | `404`+`2001` | — |

错误：`3001`/`3002`/`3003`（`401`）、`2001`（`404`）、`3008`（`429`）。**不得**用 `pageToken` 调用（返回 `403`+`3007`）。

### 3.4 `POST /rooms/{roomId}/ingest:switch` — 热切换推流协议

| 项 | 值 |
|---|---|
| 方法/路径 | `POST {apiHost}/api/v1/rooms/{roomId}/ingest:switch` |
| 鉴权 | `Authorization: Bearer <ingestToken>` |
| 成功 | `202 Accepted` |
| 幂等 | 条件幂等（`switchId`，缓存 10 min） |
| 超时 | 5 s / 300 ms 返回 `202` |
| 前置条件 | `state=="live"`；否则 `409`+`2007` |
| 副作用 | `state → switching`；生成 `switchId`；旧 token 5 s grace；15 s 窗口内等待新源首帧 |

**请求体**

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `targetProtocol` | string | 是 | `whip`/`srt`/`rtmp` | 可与当前协议相同（同协议重推，§4.3） |

```json
{ "targetProtocol": "srt" }
```

**响应 `202`**（字段与 00 §6.2 逐字一致）

```json
{ "switchId": "sw_8k2m9x4qp7t3", "state": "switching", "graceMs": 5000, "playUrlUnchanged": true }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `switchId` | string | 是 | `sw_` + 12 位 Crockford |
| `state` | string | 是 | 固定 `"switching"` |
| `graceMs` | int | 是 | 固定 `5000`（00 §6.2） |
| `playUrlUnchanged` | boolean | 是 | 固定 `true` |

**错误码映射**

| 情形 | HTTP | code | retryable |
|---|---|---|---|
| `state=="switching"`（重复切换） | 409 | `2006` | 是（`Retry-After: 2`） |
| `state!="live"`（`created`/`stopping`/`closed`/`failed`） | 409 | `2007` | 否 |
| `targetProtocol` 非法 | 400 | `4002` | 否 |
| 目标协议不受支持（服务端能力） | 400 | `4002` | 否 |
| 房间不存在 | 404 | `2001` | 否 |
| 房间已关闭/过期 | 410 | `2002`/`2003` | 否 |
| ingest token 无效/过期 | 401 | `3007`/`3003` | 否/是 |

> **客户端后续动作**：App 在 `202` 后用 `GET /rooms/{roomId}` 取最新 `ingest.token`（若服务端轮换了 token），再用 `ingest.whipUrl`/`rtmpUrl`/`srtUrl` 以目标协议推流。该流程为 03 §18 Q1 方案 A，待 Lead 在 00 中确认。

### 3.5 `GET /rooms/{roomId}/status` — 房间状态

| 项 | 值 |
|---|---|
| 方法/路径 | `GET {apiHost}/api/v1/rooms/{roomId}/status` |
| 鉴权 | `pageToken`：`Authorization: Bearer <pageToken>`（兼容 `X-Page-Token`，见 §15 Q5） |
| 成功 | `200` |
| 幂等 | 天然幂等 |
| 超时 | 3 s / 300 ms |
| 用途 | 播放页启动快路径；SSE 不可用时的降级轮询（3 s 间隔） |

**响应 `200`**（字段集合与 00 §6.2 一致）

```json
{
  "roomId": "r7k2m9x4qp",
  "state": "live",
  "ingestState": "publishing",
  "pushProtocol": "whip",
  "viewers": 3,
  "pinRequired": false,
  "expireAt": "2026-10-20T20:00:00Z",
  "lastFrameAt": "2026-10-20T08:03:20Z"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `roomId` | string | 是 | — |
| `state` | string | 是 | `roomState` 枚举 |
| `ingestState` | string | 是 | `connecting`/`publishing`/`reconnecting`/`ended`/`failed` |
| `pushProtocol` | string | 是 | 当前/目标协议 |
| `viewers` | int | 是 | 当前观看者 |
| `pinRequired` | boolean | 是 | — |
| `expireAt` | string | 是 | — |
| `lastFrameAt` | string\|null | 是 | 无帧时 `null` |

> `closed` 房间返回 `200` + `state="closed"`（不返回 `410`），以便播放页渲染结束态；媒体端点仍为 `410`（00 §4.3）。
> 错误：`401`+`3002/3003`、`404`+`2001`、`429`+`3008`。

### 3.6 `POST /rooms/{roomId}/play-session` — 换播放凭证

| 项 | 值 |
|---|---|
| 方法/路径 | `POST {apiHost}/api/v1/rooms/{roomId}/play-session` |
| 鉴权 | Header `X-Page-Token: <pageToken>`（00 §6.2） |
| 成功 | `200` |
| 幂等 | 非幂等（每次新 `playSessionId`），可安全重试 |
| 限流 | IP 60 req/min；PIN 5 次/min |
| 超时 | 3 s / 500 ms |
| 用途 | 校验 PIN、换 `mediaToken`、返回可用播放协议与 TURN 凭证 |

**请求体**

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `pin` | string | 条件 | 4–6 位数字；`pinRequired=true` 时必填 | 错误 → `401`+`3005` |
| `protocolPreference` | string | 否 | v1 仅接受 `"auto"` | 预留枚举，见 §15 Q7 |

```http
POST /api/v1/rooms/r7k2m9x4qp/play-session HTTP/1.1
Host: api.example.com
X-Page-Token: eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json; charset=utf-8

{ "pin": "1234", "protocolPreference": "auto" }
```

**响应 `200`**（字段冻结）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `playSessionId` | string | 是 | `pls_` + 16 位 Crockford |
| `mediaToken` | string | 是 | JWT，TTL 300 s，`sid==playSessionId` |
| `expiresIn` | int | 是 | 固定 `300` |
| `pinRequired` | boolean | 是 | — |
| `roomState` | string | 是 | `roomState` 枚举 |
| `source.pushProtocol` | string | 是 | 当前生效源 |
| `source.switchedAt` | string\|null | 是 | — |
| `protocols.webrtc.url` | string | 是 | `{whepHost}/whep/{roomId}` |
| `protocols.flv.url` | string | 是 | `{playHost}/live/{roomId}.flv`（**不含 `mt`**，由播放页附加） |
| `protocols.hls.url` | string | 是 | `{playHost}/hls/{roomId}/index.m3u8`（不含 `mt`） |
| `protocols.hls.segmentDurationSec` | int | 是 | `2` |
| `turn` | object | 否 | TURN 凭证（可选，§15 Q2）；缺失时使用创建房间时下发的 `turn`（此时房间级凭证 TTL 必须覆盖房间寿命，见 [03 §7.2](./03-cloud-service.md#72-turn-集群)） |

```json
{
  "playSessionId": "pls_3m8x2k9q7p4t1w6z",
  "mediaToken": "eyJ...",
  "expiresIn": 300,
  "pinRequired": false,
  "roomState": "live",
  "source": { "pushProtocol": "whip", "switchedAt": "2026-10-20T08:03:00Z" },
  "protocols": {
    "webrtc": { "url": "https://ingest.example.com/whep/r7k2m9x4qp" },
    "flv": { "url": "https://play.example.com/live/r7k2m9x4qp.flv" },
    "hls": { "url": "https://play.example.com/hls/r7k2m9x4qp/index.m3u8", "segmentDurationSec": 2 }
  },
  "turn": {
    "urls": ["turn:turn.example.com:3478?transport=udp"],
    "username": "1729403600:r7k2m9x4qp",
    "credential": "base64..."
  }
}
```

> **URL 与 token 的组合规则**：`protocols.*.url` 是**基地址**；FLV 请求时附加 `?mt=${mediaToken}`，HLS playlist 附加 `?mt=${mediaToken}`，WHEP 使用 `Authorization: Bearer ${mediaToken}`。分片请求不带 `mt`，使用 Cookie `cmp_ps`（00 §7）。

**PIN 交互矩阵**

| `pinRequired` | 请求 `pin` | 结果 |
|---|---|---|
| `false` | 缺失 | `200` |
| `false` | 提供了错误值 | `200`（忽略，`pinRequired=false` 不校验） |
| `true` | 缺失 | `401`+`3004`（`retryable=false`） |
| `true` | 错误 | `401`+`3005`（`retryable=true`，剩余次数递减） |
| `true` | 正确 | `200` |
| 任意 | 连续 10 次失败 | `429`+`3006`，`Retry-After: 600` |

**错误码映射**

| 情形 | HTTP | code | retryable |
|---|---|---|---|
| 缺 `X-Page-Token` | 401 | `3001` | 否 |
| token 无效 | 401 | `3002` | 否 |
| token 过期 | 401 | `3003` | 是 |
| PIN 未提供/错误/锁定 | 401/401/429 | `3004`/`3005`/`3006` | 否/是/是 |
| 房间关闭 | 410 | `2002` | 否 |
| 房间过期/不存在 | 410/404 | `2003`/`2001` | 否 |
| 观看并发满 | 429 | `2008` | 是 |
| 限流 | 429 | `3008` | 是 |
| 服务端错误 | 500/503 | `5007` | 是 |

### 3.7 `POST /rooms/{roomId}/page-token:refresh` — 刷新页面 token

| 项 | 值 |
|---|---|
| 方法/路径 | `POST {apiHost}/api/v1/rooms/{roomId}/page-token:refresh` |
| 鉴权 | `Authorization: Bearer <pageToken>`（00 §5.1） |
| 成功 | `200` |
| 幂等 | 幂等（可重复刷新，旧 token 在 `exp` 前仍有效） |
| 超时 | 3 s / 500 ms |
| 前置 | 房间未 `closed`/`failed` |

**响应 `200`**（形状由本文冻结，§15 Q4）

```json
{ "pageToken": "eyJ...", "expiresIn": 3600 }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `pageToken` | string | 是 | 新 token；`exp ≤ min(now+3600, room.expireAt)` |
| `expiresIn` | int | 是 | 剩余秒数（≤ 3600，≤ 房间剩余寿命） |

**客户端约束**（00 §5.1）：播放页仅在内存/`sessionStorage` 保存新 token，**不得修改地址栏 URL**。

错误：`401`+`3001/3002/3003`、`410`+`2002/2003`、`429`+`3008`。

### 3.8 `GET /rooms/{roomId}/events` — 房间事件 SSE

| 项 | 值 |
|---|---|
| URL | `{apiHost}/api/v1/rooms/{roomId}/events?mt={mediaToken}`（00 §4.2） |
| 鉴权 | query `mt`（`EventSource` 无法设置 Header；`mediaToken` 语义同 00 §5.1） |
| 成功 | `200`，`Content-Type: text/event-stream; charset=utf-8` |
| 幂等 | 可多处订阅，每连接独立 |
| 超时 | 建连 3 s；空闲 45 s 判定断线 |
| 心跳 | 每 15 s `heartbeat`（00 §6.2） |

详见 §4。

---

### 3.9 `POST /telemetry/batch` — 埋点批量上报

| 项 | 值 |
|---|---|
| 方法/路径 | WAN：`POST {apiHost}/api/v1/telemetry/batch`；LAN：`POST http://{lanIp}:{port}/api/v1/telemetry/batch`（端侧转发，断网丢弃） |
| 鉴权 | WAN 播放页 `Authorization: Bearer <mediaToken>`；App 使用账号 token 或短期上报 token；LAN 使用 `mediaToken` |
| 成功 | `202`（仅入队，不保证落仓） |
| 批量 | 单请求 ≤ 50 事件、≤ 256 KB；同会话间隔 ≥ 10 s |
| 幂等 | 天然幂等：按 `eventId` 去重 24 h |
| 超时 | 3 s / 500 ms |
| 限流 | `429` + `3008`；超限 `413` |

请求体：

```json
{
  "events": [
    {
      "eventId": "evt_3m8x2k9q7p4t1w6z",
      "name": "play_first_frame",
      "ts": "2026-10-20T08:03:12.345Z",
      "critical": false,
      "props": { "traceId": "4f2a9c1e8b7d6a53", "playProtocol": "webrtc", "timeToFirstFrameMs": 1240 }
    }
  ]
}
```

响应 `202`：`{"accepted": 1, "dropped": 0}`。

约束（[00 §11](./00-conventions.md)）：

- `name` 必须在 00 §11 必埋清单内，未知事件直接丢弃并在 `dropped` 计数，不返回 `400`。
- 禁止携带 token、PIN、IP、完整播放地址；`roomId` 只允许哈希值；违反者整条丢弃并计入数据质量告警。
- `critical=true` 的事件（`push_failed`/`play_failed`/`permission_denied`/`degrade_triggered`）进入优先队列。
- 上报失败不重试、不阻塞播放；`debug` 包允许 `qa_` 前缀事件，生产 schema 拒收。

---

## 4. SSE 事件详表

### 4.1 传输格式

```text
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-store
Connection: keep-alive
X-Accel-Buffering: no

retry: 3000

id: 1041
event: state
data: {"roomState":"live","ingestState":"publishing"}

: heartbeat-comment

id: 1042
event: heartbeat
data: {}

```

- 每条事件含 `id`（单调递增，`room_events.event_id`）、`event`、`data`（单行 JSON）。
- 允许发送 `:` 注释行保活（中间代理防超时），客户端忽略。
- `retry: 3000` 为服务端建议重连间隔。
- 客户端重连必须携带 `Last-Event-ID: <最后收到的 id>`；服务端从该 id 之后重放（保留最近 100 条）。

### 4.2 事件类型

| `event` | 触发时机 | `data` 字段 | 频率 |
|---|---|---|---|
| `state` | `rooms.state` 或 `ingestState` 变化 | `roomState`、`ingestState` | 状态变化时（含 `live/switching/stopping/closed/failed`） |
| `source-switched` | 播放源实际生效（含回退） | `pushProtocol`、`switchedAt` | 每次有效切换 1 次 |
| `viewer-count` | 观看者数变化（去抖 1 s） | `viewers` | 变化时，最小间隔 1 s |
| `ended` | 房间进入 `closed`/`failed`，流即将关闭 | `reason` | 终态 1 次，随后服务端关闭连接 |
| `heartbeat` | 无事件时保活 | `{}` | 每 15 s |

### 4.3 `data` 示例与字段约束

**`state`**

```text
event: state
data: {"roomState":"switching","ingestState":"connecting"}
```

| 字段 | 类型 | 枚举 | 说明 |
|---|---|---|---|
| `roomState` | string | `created`/`live`/`switching`/`stopping`/`closed`/`failed` | 00 §3 |
| `ingestState` | string | `connecting`/`publishing`/`reconnecting`/`ended`/`failed` | 00 §3；`switching` 期间为新会话状态 |

**`source-switched`**

```text
event: source-switched
data: {"pushProtocol":"srt","switchedAt":"2026-10-20T08:03:00Z"}
```

| 字段 | 类型 | 枚举 | 说明 |
|---|---|---|---|
| `pushProtocol` | string | `whip`/`srt`/`rtmp` | **最终生效**协议 |
| `switchedAt` | string | RFC 3339 UTC | 源生效时刻 |

规则（与 03 §6.7 一致）：

1. 新源首次生效 → 必发 1 次。
2. 失败但源曾切到目标又回落 → 补发 1 次，`pushProtocol` 为旧协议。
3. 新源从未生效且回退旧源 → **不发** `source-switched`，只发 `state{live,publishing}`。

**`viewer-count`**

```text
event: viewer-count
data: {"viewers":3}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `viewers` | int | 持有有效 `mediaToken` 且连接存活的观看者数 |

**`ended`**

```text
event: ended
data: {"reason":"room_closed"}
```

| `reason` | 语义 | 触发 |
|---|---|---|
| `room_closed` | 正常停止 | `DELETE` 完成 |
| `room_expired` | 超时/达到 `maxDurationSec` | 定时器 |
| `ingest_failed` | 推流端异常且重连失败 | 30 s 判定 |
| `ingest_node_failure` | 接入节点故障 | 节点 NotReady |
| `switch_failed` | 切换失败且无源可回退 | 15 s 窗口超时 |
| `admin_stopped` | 运营/风控停止 | 后台操作 |

**`heartbeat`**

```text
event: heartbeat
data: {}
```

> `heartbeat` 不携带业务字段（避免与 `state` 混淆）；客户端只用于刷新断线计时。

### 4.4 顺序保证

| 场景 | 事件序列 |
|---|---|
| 创建 → 推流 | `state{created,connecting}` → `state{live,publishing}` |
| 正常切换 | `state{switching,connecting}` → `source-switched{new}` → `state{live,publishing}` |
| 切换回退 | `state{switching,connecting}` → `state{live,publishing}`（无 `source-switched`） |
| 切换失败 | `state{switching,connecting}` → `state{failed,ended}` → `ended{switch_failed}` |
| 停止 | `state{stopping,ended}` → `ended{room_closed}` → （连接关闭） |
| 断流重连 | `state{live,reconnecting}` → `state{live,publishing}` |

- 同一连接内事件按 `id` 严格递增；`viewer-count` 可插在任意位置。
- `ended` 之后不再发送任何业务事件（仅可能 1 个心跳后关闭）。

### 4.5 SSE 错误响应

| 情形 | HTTP | code | 说明 |
|---|---|---|---|
| 缺 `mt` | 401 | `3001` | 建连失败，`EventSource` 会重试；客户端应停止并走降级 |
| token 无效/过期 | 401 | `3002`/`3003` | 播放页重新 `play-session` 后重连 |
| 房间关闭 | 410 | `2002` | 播放页转 `ended` |
| 连接数超限 | 429 | `2008` | 降级为 3 s 轮询 `status` |
| 房间不存在 | 404 | `2001` | 展示错误 |

> `EventSource` 对 4xx 会持续重试，播放页必须根据状态码主动 `close()`（06 实现），避免无限重连。

---

## 5. 媒体面契约

### 5.1 WHIP 推流

#### 5.1.1 `POST /whip/{roomId}`

| 项 | 值 |
|---|---|
| URL | `POST {whipHost}/whip/{roomId}` |
| 鉴权 | `Authorization: Bearer <ingestToken>` |
| 请求头 | `Content-Type: application/sdp` |
| 成功 | `201` |
| 响应头 | `Content-Type: application/sdp`、`Location: /whip/{roomId}` |
| 超时 | 5 s |

**SDP Offer 约束（客户端必须满足）**

| 约束 | 值 | 违反后果 |
|---|---|---|
| 视频编码 | 仅 H.264，`packetization-mode=1`，`profile-level-id=42e01f`（Baseline）或 `4d0032`（Main） | `4005` |
| 音频编码 | 仅 Opus/48000/2（音频可关闭） | `4005` |
| ICE | `a=ice-ufrag`、`a=ice-pwd`、`a=fingerprint:sha-256` 必填 | `4001` |
| DTLS | `a=setup:actpass`（客户端 offer） | `4001` |
| BUNDLE | `a=group:BUNDLE`，视频与音频同 transport | `4001` |
| RTCP | `a=rtcp-mux` 必需 | `4001` |
| 方向 | `a=sendonly`（推流） | `4001` |
| Simulcast | v1 关闭（见 §15 Q9） | 服务端忽略 `a=simulcast` |
| 码率 | `b=AS` ≤ 6000（kbps） | `4006` |

> 00 §7 中「`a=setup:actpass`」的语义落在**客户端 Offer**（RFC 5763：offerer 发送 `actpass`）；服务端 Answer 固定回 `a=setup:active`，两者不冲突。WHEP 同理（浏览器 offer `actpass`，服务端 answer `active`）。

**Offer 示例（节选）**

```text
v=0
o=- 4611731400430051336 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0 1
m=video 9 UDP/TLS/RTP/SAVPF 96 97
c=IN IP4 0.0.0.0
a=rtcp-mux
a=ice-ufrag:4ZcD
a=ice-pwd:2/1muCWoOi3uLifh0NuRHlZw
a=fingerprint:sha-256 3A:1B:...
a=setup:actpass
a=mid:0
a=sendonly
a=rtpmap:96 H264/90000
a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
a=rtpmap:97 rtx/90000
a=fmtp:97 apt=96
m=audio 9 UDP/TLS/RTP/SAVPF 111
c=IN IP4 0.0.0.0
a=rtcp-mux
a=mid:1
a=sendonly
a=rtpmap:111 opus/48000/2
a=fmtp:111 minptime=10;useinbandfec=1
```

**Answer（`201` 响应体，服务端生成）**

```text
v=0
o=- 0 0 IN IP4 0.0.0.0
s=-
t=0 0
a=group:BUNDLE 0 1
m=video 9 UDP/TLS/RTP/SAVPF 96
c=IN IP4 198.51.100.20
a=rtcp-mux
a=ice-ufrag:svrA
a=ice-pwd:svrPwd...
a=fingerprint:sha-256 5C:2D:...
a=setup:active
a=mid:0
a=recvonly
a=rtpmap:96 H264/90000
a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
a=candidate:1 1 udp 2130706431 198.51.100.20 40012 typ host
m=audio 9 UDP/TLS/RTP/SAVPF 111
c=IN IP4 198.51.100.20
a=rtcp-mux
a=mid:1
a=recvonly
a=rtpmap:111 opus/48000/2
```

#### 5.1.2 `PATCH /whip/{roomId}`（Trickle ICE）

| 项 | 值 |
|---|---|
| 请求头 | `Content-Type: application/trickle-ice-sdpfrag` |
| body | SDP fragment，含 `a=ice-ufrag`、`a=ice-pwd`、`a=candidate`（可多个） |
| 成功 | `204` |
| 幂等 | 重复候选忽略 |
| 超时 | 2 s |

```text
PATCH /whip/r7k2m9x4qp HTTP/1.1
Authorization: Bearer eyJ...
Content-Type: application/trickle-ice-sdpfrag

a=ice-ufrag:4ZcD
a=ice-pwd:2/1muCWoOi3uLifh0NuRHlZw
m=video 9 UDP/TLS/RTP/SAVPF 96
a=mid:0
a=candidate:2 1 udp 1694498815 192.0.2.44 54321 typ srflx raddr 192.0.2.5 rport 54321
```

#### 5.1.3 `DELETE /whip/{roomId}`

| 项 | 值 |
|---|---|
| 成功 | `204` |
| 鉴权 | `Authorization: Bearer <ingestToken>`（grace 内旧 token 亦可） |
| 幂等 | 已结束返回 `204` |
| 超时 | 2 s |

**错误码映射**

| 情形 | HTTP | code |
|---|---|---|
| 缺/坏/过期 token | 401 | `3001`/`3002`/`3003` |
| token 非 ingest 域 | 403 | `3007` |
| 房间关闭/过期 | 410 | `2002`/`2003` |
| 会话冲突（已有 `publishing` 且非切换目标协议） | 409 | `4004` |
| SDP 缺媒体/编解码不支持 | 415 | `4005` |
| 码率超限 | 413 | `4006` |
| ICE/DTLS 建立失败 | 500 | `4001` |
| 首帧超时（异步） | — | `4003`（通过 SSE `state{failed}` 通知） |

### 5.2 WHEP 播放

#### 5.2.1 `POST /whep/{roomId}`

| 项 | 值 |
|---|---|
| URL | `POST {whepHost}/whep/{roomId}` |
| 鉴权 | `Authorization: Bearer <mediaToken>` |
| 请求头 | `Content-Type: application/sdp` |
| 成功 | `201` |
| 响应头 | `Content-Type: application/sdp`、`Location: /whep/{roomId}/{playSessionId}` |
| 超时 | 5 s（降级判定 2.5 s） |

**SDP Offer 约束**

| 约束 | 值 | 违反后果 |
|---|---|---|
| 方向 | `a=recvonly` 必须 | `5007` |
| 编码 | H.264（同推流）、Opus/48000/2 | `5007` |
| ICE | `ice-ufrag`/`ice-pwd`/`fingerprint` | `5007` |
| `setup` | `actpass` | `5007` |
| BUNDLE/rtcp-mux | 必需 | `5007` |
| 扩展 | 允许 `transport-cc`、`nack`、`pli`、`goog-remb` | 忽略未识别扩展 |

```text
v=0
o=- 1234 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97
c=IN IP4 0.0.0.0
a=rtcp-mux
a=ice-ufrag:plr1
a=ice-pwd:plpwd...
a=fingerprint:sha-256 AB:CD:...
a=setup:actpass
a=mid:0
a=recvonly
a=rtpmap:96 H264/90000
a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
a=rtcp-fb:96 nack
a=rtcp-fb:96 nack pli
```

**Answer（`201`）**：服务端生成 `sendonly` 方向、`a=setup:active`、host/srflx 候选；若仅 TURN 可达，候选为 TURN relay 地址并同时下发 `turn` 凭证（见 §15 Q2）。

#### 5.2.2 ICE 与 TURN 策略

| 阶段 | 行为 |
|---|---|
| 候选收集 | 客户端先尝试 host/srflx；服务端 Answer 给 host/srflx |
| 连通性失败（≥ 3 s） | 客户端使用 `turn.urls` + `username`/`credential` 重建 transport（ICE restart 不承诺，v1 走"新建会话"） |
| 仍失败 | `POST /whep` 返回 `500`+`5006`；播放页降级 FLV（`webrtc_unavailable`） |
| 无 TURN 可用 | `7005` 透传播放页 |

#### 5.2.3 `DELETE /whep/{roomId}/{playSessionId}`

| 项 | 值 |
|---|---|
| 鉴权 | `Authorization: Bearer <mediaToken>`（`sid` 必须等于路径 `playSessionId`） |
| 成功 | `204` |
| 幂等 | 已结束返回 `204` |
| 副作用 | 播放会话 `ended`，`viewers--`，SSE `viewer-count` |

**错误码映射**

| 情形 | HTTP | code |
|---|---|---|
| 缺/坏/过期 mediaToken | 401 | `3001`/`3002`/`3003` |
| `sid` 与路径不匹配 | 403 | `3002` |
| 房间关闭/过期 | 410 | `2002`/`2003` |
| 观看并发满 | 429 | `2008` |
| 房间无媒体（未推流） | 409 | `5004` |
| 编解码/方向不支持 | 415 | `5007` |
| ICE 失败 | 500 | `5006` |
| TURN 不可用 | 503 | `7005` |
| 服务端 SFU 错误 | 500 | `5001` |

### 5.3 HTTP-FLV

#### 5.3.1 `GET /live/{roomId}.flv?mt={mediaToken}`

| 项 | 值 |
|---|---|
| URL | `GET {playHost}/live/{roomId}.flv?mt={mediaToken}` |
| 鉴权 | query `mt` |
| 成功 | `200` |
| 响应头 | `Content-Type: video/x-flv`、`Cache-Control: no-store`、`Connection: keep-alive`、`Transfer-Encoding: chunked` |
| 首字节预算 | ≤ 2 000 ms（00 §10.2 降级阶梯） |
| 保活 | 每 5 s 至少 1 个 FLV tag（00 §7） |

**响应流结构**

```text
FLV Header (9 B): 46 4C 56 01 05 00 00 00 09
Tag1: onMetaData (script)
Tag2: AVC sequence header (AVCPacketType=0, CompositionTime=0)
Tag3: AAC sequence header (SoundFormat=10, AACPacketType=0)
Tag4..N: 音视频数据（从 IDR 开始）
[静默期] script tag（保活，每 5 s）
[房间关闭] 服务端发送结束标记后关闭连接
```

**错误码映射**

| 情形 | HTTP | 说明 |
|---|---|---|
| 缺 `mt` | 401 | `3001` |
| token 无效/过期 | 401 | `3002`/`3003` |
| 房间关闭/过期 | 410 | `2002`/`2003` |
| 观看并发满 | 429 | `2008` |
| 转封装未就绪 | 503 | `5002` |
| 房间 `live` 但 5 s 内无帧 | 409 | `5004` |
| 房间不存在 | 404 | `2001` |

**重连语义**：连接中断后播放页按 §2.8 退避重连；服务端每次从最近 IDR 重新开始（客户端必须能处理序列头重复）。

### 5.4 HLS

#### 5.4.1 `GET /hls/{roomId}/index.m3u8?mt={mediaToken}`

| 项 | 值 |
|---|---|
| 鉴权 | query `mt`（00 §7） |
| 成功 | `200` |
| 响应头 | `Content-Type: application/vnd.apple.mpegurl`、`Cache-Control: no-store`、`Set-Cookie: cmp_ps=...` |
| 刷新 | 每 1 s 重写；客户端按 `targetduration` 轮询 |
| 首内容预算 | ≤ 5 000 ms（00 §10.2 `hls(5.0s)`） |

**playlist 示例**

```text
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:41
#EXT-X-MAP:URI="init.mp4"
#EXTINF:2.000,
seg-41.m4s
#EXTINF:2.000,
seg-42.m4s
#EXTINF:2.000,
seg-43.m4s
#EXTINF:2.000,
seg-44.m4s
#EXTINF:2.000,
seg-45.m4s
#EXTINF:2.000,
seg-46.m4s
```

| 参数 | 值 | 依据 |
|---|---|---|
| `EXT-X-TARGETDURATION` | `2` | 00 §7 |
| 分片数 | 6 | 00 §7 |
| 直播窗口 | ≥ 12 s | 00 §7 |
| 结束 | `#EXT-X-ENDLIST` 在 `closed` 时写入 | 00 §4.3/§7 |
| 分片命名 | `init.mp4` + `seg-{seq}.m4s` | fMP4（03 §18 Q4） |

#### 5.4.2 `GET /hls/{roomId}/{segment}`

| 项 | 值 |
|---|---|
| 鉴权 | Cookie `cmp_ps`（00 §7）；**不接受** query `mt` |
| 成功 | `200`，`Content-Type: video/mp4`（fMP4）或 `video/mp2t`（若启用 TS 备选） |
| 缓存 | `Cache-Control: public, max-age=60, immutable` |
| 失败 | 已过期分片 `404`；房间关闭 `410`+`2002` |

**错误码映射（HLS 整体）**

| 情形 | HTTP | code |
|---|---|---|
| `mt` 缺失/无效/过期 | 401 | `3001`/`3002`/`3003` |
| Cookie 缺失（分片） | 401 | `3002` |
| 房间关闭/过期 | 410 | `2002`/`2003` |
| 未切片/打包失败 | 503 | `5003` |
| 分片过期 | 404 | `5003`（`retryable=true`，重新拉 playlist） |

---

## 6. 播放页调用顺序与降级

### 6.1 调用顺序

```mermaid
sequenceDiagram
  autonumber
  participant B as 浏览器
  participant Page as 播放页 playHost
  participant API as apiHost
  participant Media as whep/playHost

  B->>Page: GET /s/{roomId}?token={pageToken}
  Page-->>B: HTML 单文件 内联 JS
  Page->>API: GET /rooms/{roomId}/status (Bearer pageToken) 可选快路径
  API-->>Page: {state, ...}
  Page->>API: POST /rooms/{roomId}/play-session (X-Page-Token)
  API-->>Page: {playSessionId, mediaToken, protocols}
  Page->>API: GET /events?mt=mediaToken  SSE 并行
  API-->>Page: event state/source-switched
  Page->>Media: POST /whep/{roomId} (Bearer mediaToken)
  Media-->>Page: SDP Answer
  Page->>Page: 首帧渲染 playerState=playing
  Note over Page,API: mediaToken 剩余 60s 时 play-session 续期
  Note over Page,API: pageToken 剩余 60s 时 page-token:refresh
```

| 步骤 | API | 超时 | 失败动作 |
|---|---|---|---|
| 1 | `GET /s/{roomId}?token=` | 2 s | `410` → 渲染"已结束"；`404` → "地址无效"；`401` → "凭证无效" |
| 2 | `GET /status`（可选） | 3 s | 失败不阻塞，继续步骤 3 |
| 3 | `POST /play-session` | 3 s | `3004/3005` → PIN 弹窗；`2008` → "房间已满"；`2002` → "已结束"；其他 → 重试 2 次后 `failed` |
| 4 | SSE 建连（与 5 并行） | 3 s | 失败 → 降级 3 s 轮询 `status` |
| 5 | 媒体尝试（阶梯） | 见 §6.2 | 逐级降级 |
| 6 | `mediaToken` 续期 | 3 s | 到期前 60 s 触发；失败则保留旧 token 至过期，过期后重连 |
| 7 | `pageToken` 刷新 | 3 s | 失败不阻塞播放，重试至过期 |
| 8 | 停止/结束 | — | 收到 `ended` → `playerState=ended`，展示结束页 |

### 6.2 降级阶梯（00 §10.2）

| 顺序 | 协议 | 判定成功 | 判定失败（进入下一级） | 跳过条件 |
|---|---|---|---|---|
| 1 | `webrtc` | WHEP `201` + 首帧渲染 | 2 500 ms 内无首帧，或 `5001/5006/5007` | 浏览器 `RTCPeerConnection` 不存在 → 直接跳过 |
| 2 | `flv` | 200 + 首个可解码帧 | 2 000 ms 内无帧，或 `5002/5004` | `MediaSource` 不支持 H.264 → 跳过 |
| 3 | `hls` | playlist 200 + 分片可播 | 5 000 ms 内无帧，或 `5003` | 浏览器不支持 HLS（非 Safari 且无 hls.js）→ 失败 |
| 全部失败 | — | — | `playerState=failed`，展示最后错误码 + 重试 | — |

**总耗时上限**：阶梯总耗时 ≤ 10 s（00 §10.2）；期间地址栏不变（00 §4.3-2）。

**降级埋点**：每次降级上报 `play_fallback{from,to,reason}`（00 §11）。

---

## 7. LAN 端侧 API 与 WAN API 差异

### 7.1 对照表（同构与差异）

| 能力项 | LAN（端侧） | WAN（云端） | 同构性 |
|---|---|---|---|
| Base URL | `http://{lanIp}:{port}`（默认 8089） | `https://{apiHost}/api/v1` | 不同 |
| 播放页 URL | `http://{lanIp}:{port}/s/{pageToken}`（token 在**路径**） | `https://{playHost}/s/{roomId}?token={pageToken}`（token 在 **query**） | 不同（00 §4.2 冻结） |
| 状态查询 | `GET /api/v1/status`，无 `roomId` | `GET /rooms/{roomId}/status` | 路径不同，字段有差异 |
| 状态字段 | `state, lanIp, port, viewers, maxViewers, pinRequired, mediaProtocols, uptimeSec` | `roomId, state, ingestState, pushProtocol, viewers, pinRequired, expireAt, lastFrameAt` | 字段集不同（00 §8 vs §6.2） |
| 播放会话 | `POST /api/v1/play-session`，body `{pin?, playSessionId?}` | `POST /rooms/{roomId}/play-session`，body `{pin?, protocolPreference}` | body 近似 |
| 播放会话鉴权 | `X-Page-Token` | `X-Page-Token` | **相同** |
| 播放会话响应 | `{playSessionId, mediaToken, expiresIn:300, protocols:{flv,mjpeg}, pinRequired}` | `{playSessionId, mediaToken, expiresIn:300, pinRequired, roomState, source, protocols:{webrtc,flv,hls}}` | 部分相同 |
| 媒体协议 | `flv`、`mjpeg`（M2 起 `webrtc`） | `webrtc`、`flv`、`hls` | 不同 |
| `pageToken` 续期 | `POST /api/v1/page-token:refresh`（M2 启用，M1 过期提示重取） | `POST /rooms/{roomId}/page-token:refresh` | 路径不同，语义相同 |
| 埋点上报 | `POST /api/v1/telemetry/batch`（端侧转发，断网丢弃） | `POST /api/v1/telemetry/batch`（`mediaToken`） | 路径同构、鉴权近似 |
| `mediaToken` TTL | 300 s（00 §5.1） | 300 s | **相同** |
| `pageToken` TTL | 60 min 且 ≤ 会话寿命 | 60 min 且 ≤ 房间寿命 | **相同** |
| token 签名 | 端侧随机 256 bit 密钥，停止销毁 | 云端 KMS/HMAC，`kid` 轮换 | 不同实现，claims 相同 |
| 事件推送 | **无 SSE**；播放页轮询 `status`（2 s） | SSE（`state`/`source-switched`/`viewer-count`/`ended`/`heartbeat`） | 不同 |
| 并发上限 | 1–3（00 §8），超限 `429`+`6005` | 套餐 `maxViewers`，超限 `429`+`2008` | 语义同、码不同 |
| 房间不存在/关闭 | 服务已停，连接拒绝（`connection refused`） | `410`+`2002` | 不同 |
| 错误信封 | 同 00 §9.1 | 同 00 §9.1 | **相同** |
| 错误码复用 | `3001`–`3006`、`6001`–`6007` | `2xxx`/`3xxx`/`4xxx`/`5xxx`/`7xxx` | 3xxx 语义**完全相同** |
| CORS | 不设置（同源） | 播放页 → API 跨域，需白名单 `Origin` | 不同 |
| 版本协商 | `Server: CamPush-LAN/1.0`，路径固定 `/api/v1` | 路径 `v1`，破坏性升 `v2` | 不同 |
| 停止语义 | ≤ 1 s 关服务、销毁密钥 | ≤ 2 s 媒体 `410`，≤ 5 s `closed` | 不同 |

### 7.2 同构设计原则（客户端共享模型）

1. **错误信封与 3xxx 码完全复用**：客户端只需一套错误解析与用户提示映射。
2. **`playSessionId`/`mediaToken`/`expiresIn` 语义一致**：播放会话模型可复用，仅 `protocols` 键不同。
3. **`X-Page-Token` 头一致**：播放页请求构造逻辑可复用，仅 base URL 与路径不同。
4. **`pageToken` 在 URL 中的位置不同是唯一"不可共享"点**：播放页需按 `accessScope` 分支解析（LAN 路径段 / WAN query），解析后统一为内存中的 `pageToken`，禁止把 LAN 的 token 拼到 WAN query。
5. **状态轮询可统一**：LAN 无 SSE，播放页统一实现"轮询 + 可选 SSE"的抽象，WAN 默认 SSE。

### 7.3 CORS（WAN）

| 项 | 值 |
|---|---|
| 允许 `Origin` | `https://play.example.com`（播放页同域） |
| 允许方法 | `GET, POST, DELETE, OPTIONS` |
| 允许头 | `Authorization, X-Page-Token, Content-Type, Idempotency-Key, X-Trace-Id` |
| 暴露头 | `X-Request-Id, Retry-After` |
| 凭证 | 不使用 Cookie 做 API 鉴权；HLS 分片 Cookie `cmp_ps` 为 `SameSite=Lax` 且限于 `/hls/{roomId}` 路径 |
| 预检缓存 | `Access-Control-Max-Age: 600` |

> LAN 端侧 **不设置** `Access-Control-Allow-Origin`（00 §8），确保仅同源播放页可访问。

---

## 8. 三状态机在接口上的映射

### 8.1 `roomState`（00 §3）

| `roomState` | 触发方 | 触发接口/事件 | `status.state` | SSE | 媒体端点 |
|---|---|---|---|---|---|
| `created` | 服务端 | `POST /rooms` | `created` | `state{created,connecting}` | 播放 `409`+`5004`（无媒体） |
| `live` | 服务端 | 首帧到达接入端 | `live` | `state{live,publishing}` | 全部可用 |
| `switching` | 服务端 | `POST /ingest:switch` | `switching` | `state{switching,connecting}` | 旧源可用；新播放连接可用 |
| `stopping` | 服务端 | `DELETE /rooms/{roomId}` | `stopping` | `state{stopping,ended}` | **立即 410** |
| `closed` | 服务端 | 释放完成；或 10 min 未推流 | `closed` | `ended{room_closed}` / `ended{room_expired}` | 410 |
| `failed` | 服务端 | 15 s 切换失败 / 30 s 重连失败 / 节点故障 | `failed` | `ended{...}` | 410 |

### 8.2 `ingestState`（00 §3）

| `ingestState` | 触发 | 触发接口 | 观测接口 | 说明 |
|---|---|---|---|---|
| `connecting` | 建连中 | `POST /whip`、SRT 握手、RTMP `publish` | `status.ingestState` | 最多 10 s |
| `publishing` | 首帧 IDR 到达 | — | `status.ingestState` | 稳定推流 |
| `reconnecting` | 30 s 无 RTP 但客户端仍在重连窗口 | — | `status.ingestState` | ≤ 30 s |
| `ended` | 正常结束 | `DELETE /whip`、`DELETE /rooms` | `status.ingestState` | 终态 |
| `failed` | 建连失败/首帧超时/码率超限/节点故障 | — | `status.ingestState` | 终态 |

### 8.3 `playerState`（00 §3）

| `playerState` | 进入条件 | 对应接口结果 | 出口 |
|---|---|---|---|
| `boot` | 页面加载 | — | → `auth` |
| `auth` | 解析 URL token、`play-session` | `200`/`401` | 成功 → `trying`；`410`/`403` → `failed` |
| `trying` | 建立媒体（阶梯） | `WHEP 201`/`FLV 200`/`HLS 200` | 首帧 → `playing`；失败 → 下一协议；全失败 → `failed` |
| `playing` | 首帧渲染 | — | 断流/切换 → `reconnecting`；降级 → `degraded`；结束 → `ended` |
| `degraded` | 播放中降级完成 | 新协议首帧 | → `playing` |
| `reconnecting` | 断流 / `state{switching}` / `source-switched` | SSE/媒体事件 | 成功 → `playing`；房间关闭 → `ended`；30 s → `failed` |
| `ended` | `ended` 事件 / `status.state=closed` | — | 终态 |
| `failed` | 全部协议失败 / token 不可恢复 | — | 终态（可重试 → `trying`） |

### 8.4 三机一致性矩阵

| `roomState` | 允许的 `ingestState` | 播放页应处 `playerState` | 冲突处理 |
|---|---|---|---|
| `created` | 无 / `connecting` | `trying`（等待） | 若播放页收到 `playing` → 以服务端为准，回 `trying` |
| `live` | `publishing` / `reconnecting` | `playing` / `reconnecting` | `lastFrameAt` > 10 s 且 `reconnecting` → 播放页显示"信号中断" |
| `switching` | `publishing`（旧）+ `connecting`（新） | `reconnecting` | 15 s 内必转 `live`/`failed` |
| `stopping` | `ended` | `ended` | — |
| `closed` | `ended` | `ended` | 媒体 410 |
| `failed` | `failed` / `ended` | `failed` | 展示 `failReason` 对应提示 |

---

## 9. 时序图

### 9.1 创建 → 推流 → 播放 → 切换 → 停止

```mermaid
sequenceDiagram
  autonumber
  participant App
  participant API as apiHost
  participant Edge as ingest.example.com
  participant SFU
  participant Page as H5 播放页

  App->>API: POST /rooms {accessScope:"wan",captureMode:"single_back",pushProtocol:"whip"}
  API-->>App: 201 {roomId, playUrl, ingest, turn, limits}
  App->>Edge: POST /whip/{roomId} SDP Offer
  Edge-->>App: 201 SDP Answer
  App->>Edge: PATCH /whip/{roomId} trickle
  App->>Edge: 媒体 RTP 首帧 IDR
  Edge->>SFU: Producer 建立
  SFU-->>Page: SSE state{live,publishing}
  Page->>API: POST /play-session X-Page-Token
  API-->>Page: {playSessionId, mediaToken, protocols}
  Page->>Edge: POST /whep/{roomId} Bearer mediaToken
  Edge-->>Page: 201 SDP Answer
  Page->>Page: 首帧 播放中
  App->>API: POST /ingest:switch {targetProtocol:"srt"}
  API-->>App: 202 {switchId,state:"switching",graceMs:5000,playUrlUnchanged:true}
  API-->>Page: SSE state{switching,connecting}
  App->>Edge: SRT 推流 新会话
  Edge->>SFU: 新 Producer
  SFU-->>Page: SSE source-switched{pushProtocol:"srt"}
  Page->>Edge: POST /whep 复用 mediaToken
  Edge-->>Page: 201 新 Answer
  API-->>Page: SSE state{live,publishing}
  App->>API: DELETE /rooms/{roomId} Bearer ingestToken
  API-->>App: 204
  API-->>Page: SSE ended{room_closed}
  Page->>API: GET /rooms/{roomId}/status
  API-->>Page: 200 {state:"closed"}
```

### 9.2 PIN 校验

```mermaid
sequenceDiagram
  autonumber
  participant U as 观看者
  participant Page as 播放页
  participant API as apiHost

  U->>Page: 打开 playUrl
  Page->>API: POST /play-session X-Page-Token
  API-->>Page: 401 {code:3004, error:pin_required, retryable:false}
  Page->>U: 弹出 PIN 输入框
  U->>Page: 输入 1234
  Page->>API: POST /play-session {pin:"1234"}
  alt PIN 正确
    API-->>Page: 200 {playSessionId, mediaToken, protocols}
    Page->>U: 开始播放
  else PIN 错误
    API-->>Page: 401 {code:3005, error:pin_invalid, retryable:true}
    Page->>U: 提示错误 剩余次数
  else 连续 10 次失败
    API-->>Page: 429 {code:3006, retryable:true} Retry-After: 600
    Page->>U: 锁定 10 分钟倒计时
  end
```

### 9.3 token 刷新

```mermaid
sequenceDiagram
  autonumber
  participant Page as 播放页
  participant API as apiHost
  participant Edge as ingest.example.com

  Note over Page: mediaToken 剩余 ≤ 60 s
  Page->>API: POST /play-session X-Page-Token
  API-->>Page: 200 {playSessionId 复用, mediaToken 新, expiresIn:300}
  Note over Page: 仅内存替换 不改地址栏
  Note over Page: pageToken 剩余 ≤ 60 s
  Page->>API: POST /page-token:refresh Bearer pageToken
  API-->>Page: 200 {pageToken 新, expiresIn:3600}
  Note over Page: sessionStorage 保存 不改地址栏
  Page->>Edge: 后续媒体请求携带新 mediaToken
```

### 9.4 断流重连

```mermaid
sequenceDiagram
  autonumber
  participant App
  participant Edge as ingest.example.com
  participant API as apiHost
  participant Page as 播放页

  Note over App,Edge: 弱网 上行丢包
  App->>Edge: RTP 中断
  Edge->>API: 10 s 无帧 lastFrameAt 停滞
  API-->>Page: SSE state{live,reconnecting}
  Page->>Page: playerState=reconnecting 保留冻结帧
  App->>Edge: 重建 WHIP POST /whip/{roomId}
  Edge-->>App: 201 新 SDP Answer
  App->>Edge: 首帧 IDR
  Edge->>API: first_frame 恢复
  API-->>Page: SSE state{live,publishing}
  Page->>Page: playerState=playing
  Note over App,Edge: 若 30 s 未恢复
  API-->>Page: SSE state{failed,ended} + ended{ingest_failed}
  Page->>Page: playerState=failed 展示重试
```

### 9.5 播放降级（补充）

```mermaid
sequenceDiagram
  autonumber
  participant Page as 播放页
  participant Edge as ingest.example.com
  participant CDN as play.example.com

  Page->>Edge: POST /whep/{roomId}
  Edge-->>Page: 500 {code:5006, error:ice_failed}
  Page->>Page: play_fallback webrtc→flv
  Page->>CDN: GET /live/{roomId}.flv?mt=...
  CDN-->>Page: 200 video/x-flv
  Page->>Page: 首帧 playing
  Note over Page: 若 FLV 2s 无帧
  Page->>CDN: GET /hls/{roomId}/index.m3u8?mt=...
  CDN-->>Page: 200 m3u8
  Page->>CDN: GET /hls/{roomId}/seg-41.m4s Cookie cmp_ps
  CDN-->>Page: 200 video/mp4
  Page->>Page: 首帧 playing
```

---

## 10. OpenAPI 3.1 片段

```yaml
openapi: 3.1.0
info:
  title: CamPush WAN API
  version: "1.0.0"
  description: 公网模式 REST 契约（细化自 00 §6）
servers:
  - url: https://api.example.com/api/v1
security:
  - accountBearer: []
paths:
  /rooms:
    post:
      operationId: createRoom
      summary: 创建房间与推流凭证
      security:
        - accountBearer: []
      parameters:
        - name: Idempotency-Key
          in: header
          required: false
          schema:
            type: string
            pattern: "^[A-Za-z0-9_-]{16,64}$"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateRoomRequest"
      responses:
        "201":
          description: 已创建
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Room"
        "400":
          $ref: "#/components/responses/BadRequest"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "403":
          $ref: "#/components/responses/Forbidden"
        "409":
          $ref: "#/components/responses/Conflict"
        "429":
          $ref: "#/components/responses/TooManyRequests"
  /rooms/{roomId}:
    get:
      operationId: getRoom
      summary: 查询房间
      parameters:
        - $ref: "#/components/parameters/RoomId"
      responses:
        "200":
          description: 房间详情
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Room"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "404":
          $ref: "#/components/responses/NotFound"
    delete:
      operationId: deleteRoom
      summary: 停止推流并销毁房间
      parameters:
        - $ref: "#/components/parameters/RoomId"
        - name: Idempotency-Key
          in: header
          required: false
          schema:
            type: string
            pattern: "^[A-Za-z0-9_-]{16,64}$"
      responses:
        "204":
          description: 已受理停止
        "401":
          $ref: "#/components/responses/Unauthorized"
        "404":
          $ref: "#/components/responses/NotFound"
        "429":
          $ref: "#/components/responses/TooManyRequests"
  /rooms/{roomId}/status:
    get:
      operationId: getRoomStatus
      summary: 播放页查询房间状态
      parameters:
        - $ref: "#/components/parameters/RoomId"
      responses:
        "200":
          description: 状态
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/RoomStatus"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "404":
          $ref: "#/components/responses/NotFound"
  /rooms/{roomId}/play-session:
    post:
      operationId: createPlaySession
      summary: 换 mediaToken 并返回可用播放协议
      parameters:
        - $ref: "#/components/parameters/RoomId"
        - name: X-Page-Token
          in: header
          required: true
          schema:
            type: string
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/PlaySessionRequest"
      responses:
        "200":
          description: 播放会话
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/PlaySessionResponse"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "410":
          $ref: "#/components/responses/Gone"
        "429":
          $ref: "#/components/responses/TooManyRequests"
  "/rooms/{roomId}/ingest:switch":
    post:
      operationId: switchIngest
      summary: 热切换推流协议（播放地址不变）
      parameters:
        - $ref: "#/components/parameters/RoomId"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/SwitchRequest"
      responses:
        "202":
          description: 已受理切换
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/SwitchResponse"
        "400":
          $ref: "#/components/responses/BadRequest"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "409":
          $ref: "#/components/responses/Conflict"
        "410":
          $ref: "#/components/responses/Gone"
components:
  securitySchemes:
    accountBearer:
      type: http
      scheme: bearer
      bearerFormat: JWT
    ingestBearer:
      type: http
      scheme: bearer
      bearerFormat: JWT
    pageBearer:
      type: http
      scheme: bearer
      bearerFormat: JWT
    mediaBearer:
      type: http
      scheme: bearer
      bearerFormat: JWT
  parameters:
    RoomId:
      name: roomId
      in: path
      required: true
      schema:
        type: string
        pattern: "^r[a-hj-km-np-z2-9]{9}$"
  responses:
    BadRequest:
      description: 请求非法
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
    Unauthorized:
      description: 鉴权失败
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
    Forbidden:
      description: 无权限
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
    NotFound:
      description: 房间不存在
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
    Conflict:
      description: 状态冲突
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
    Gone:
      description: 房间已关闭或过期
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
    TooManyRequests:
      description: 限流或并发超限
      headers:
        Retry-After:
          schema:
            type: integer
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Error"
  schemas:
    Error:
      type: object
      required: [code, error, message, retryable, userHint, requestId]
      additionalProperties: false
      properties:
        code:
          type: integer
          minimum: 1000
          maximum: 7999
        error:
          type: string
        message:
          type: string
        retryable:
          type: boolean
        userHint:
          type: string
        requestId:
          type: string
    CreateRoomRequest:
      type: object
      required: [accessScope, captureMode]
      additionalProperties: false
      properties:
        accessScope:
          type: string
          const: "wan"
        captureMode:
          type: string
          enum: [single_back, single_front, dual]
        pushProtocol:
          type: string
          enum: [whip, srt, rtmp]
          default: whip
        dualLayout:
          type: [string, "null"]
          enum: [pip, split_h, split_v, null]
        dualPrimary:
          type: [string, "null"]
          enum: [back, front, null]
        maxDurationSec:
          type: integer
          minimum: 60
          maximum: 43200
        pinRequired:
          type: boolean
          default: false
        pin:
          type: [string, "null"]
          pattern: "^[0-9]{4,6}$"
        client:
          type: object
          additionalProperties: false
          properties:
            platform:
              type: string
              enum: [android]
            appVersion:
              type: string
            deviceModel:
              type: string
    Room:
      type: object
      required: [roomId, state, createdAt, expireAt, playUrl, ingest, turn, limits]
      properties:
        roomId:
          type: string
        state:
          $ref: "#/components/schemas/RoomState"
        accessScope:
          type: string
          enum: [wan]
        captureMode:
          type: string
          enum: [single_back, single_front, dual]
        pushProtocol:
          $ref: "#/components/schemas/PushProtocol"
        createdAt:
          type: string
          format: date-time
        expireAt:
          type: string
          format: date-time
        liveAt:
          type: [string, "null"]
          format: date-time
        closedAt:
          type: [string, "null"]
          format: date-time
        failReason:
          type: [string, "null"]
        playUrl:
          type: string
        ingest:
          $ref: "#/components/schemas/Ingest"
        turn:
          $ref: "#/components/schemas/Turn"
        limits:
          $ref: "#/components/schemas/Limits"
        viewers:
          type: integer
        lastFrameAt:
          type: [string, "null"]
          format: date-time
        source:
          $ref: "#/components/schemas/Source"
    Ingest:
      type: object
      required: [protocol, whipUrl, rtmpUrl, srtUrl, token]
      properties:
        protocol:
          $ref: "#/components/schemas/PushProtocol"
        whipUrl:
          type: string
        rtmpUrl:
          type: string
        srtUrl:
          type: string
        token:
          type: string
    Turn:
      type: object
      required: [urls, username, credential]
      properties:
        urls:
          type: array
          minItems: 1
          items:
            type: string
        username:
          type: string
        credential:
          type: string
    Limits:
      type: object
      required: [maxViewers, maxDurationSec]
      properties:
        maxViewers:
          type: integer
          minimum: 1
        maxDurationSec:
          type: integer
          minimum: 60
          maximum: 43200
    Source:
      type: object
      required: [pushProtocol, switchedAt]
      properties:
        pushProtocol:
          $ref: "#/components/schemas/PushProtocol"
        switchedAt:
          type: [string, "null"]
          format: date-time
    RoomState:
      type: string
      enum: [created, live, switching, stopping, closed, failed]
    IngestState:
      type: string
      enum: [connecting, publishing, reconnecting, ended, failed]
    PushProtocol:
      type: string
      enum: [whip, srt, rtmp]
    RoomStatus:
      type: object
      required: [roomId, state, ingestState, pushProtocol, viewers, pinRequired, expireAt, lastFrameAt]
      additionalProperties: false
      properties:
        roomId:
          type: string
        state:
          $ref: "#/components/schemas/RoomState"
        ingestState:
          $ref: "#/components/schemas/IngestState"
        pushProtocol:
          $ref: "#/components/schemas/PushProtocol"
        viewers:
          type: integer
        pinRequired:
          type: boolean
        expireAt:
          type: string
          format: date-time
        lastFrameAt:
          type: [string, "null"]
          format: date-time
    PlaySessionRequest:
      type: object
      additionalProperties: false
      properties:
        pin:
          type: [string, "null"]
          pattern: "^[0-9]{4,6}$"
        protocolPreference:
          type: string
          enum: [auto]
          default: auto
    PlaySessionResponse:
      type: object
      required: [playSessionId, mediaToken, expiresIn, pinRequired, roomState, source, protocols]
      properties:
        playSessionId:
          type: string
        mediaToken:
          type: string
        expiresIn:
          type: integer
          const: 300
        pinRequired:
          type: boolean
        roomState:
          $ref: "#/components/schemas/RoomState"
        source:
          $ref: "#/components/schemas/Source"
        protocols:
          type: object
          required: [webrtc, flv, hls]
          properties:
            webrtc:
              type: object
              required: [url]
              properties:
                url:
                  type: string
            flv:
              type: object
              required: [url]
              properties:
                url:
                  type: string
            hls:
              type: object
              required: [url, segmentDurationSec]
              properties:
                url:
                  type: string
                segmentDurationSec:
                  type: integer
                  const: 2
        turn:
          $ref: "#/components/schemas/Turn"
    SwitchRequest:
      type: object
      required: [targetProtocol]
      additionalProperties: false
      properties:
        targetProtocol:
          $ref: "#/components/schemas/PushProtocol"
    SwitchResponse:
      type: object
      required: [switchId, state, graceMs, playUrlUnchanged]
      additionalProperties: false
      properties:
        switchId:
          type: string
          pattern: "^sw_[a-hj-km-np-z2-9]{12}$"
        state:
          type: string
          const: switching
        graceMs:
          type: integer
          const: 5000
        playUrlUnchanged:
          type: boolean
          const: true
```

> 说明：`roomId` 正则使用 Crockford Base32 字符集（去掉 `0 1 i l o u`）。**SSE 与媒体面不在 OpenAPI 中描述**（`text/event-stream` 与 SDP/FLV 流无法用 OpenAPI 精确表达），由 §4/§5 的专章契约与 §11 集成测试覆盖。

---

## 11. 契约测试清单

### 11.1 静态校验

| ID | 测试 | 工具 | 通过标准 |
|---|---|---|---|
| CT-01 | OpenAPI 语法与规范 | `spectral lint`（`oas` + `spectral:oas`） | 0 error |
| CT-02 | 示例与 schema 一致 | `prism validate` / 自研 schema 校验 | 所有 `example` 通过 schema |
| CT-03 | 枚举与 00 一致 | 自研脚本比对 `00` 表格 | 枚举集合完全相等，无新增 wire 值 |
| CT-04 | 错误码表覆盖 | 自研脚本解析 00 §9.2 | 本文引用的每个码都存在于 00；无同名异义 |
| CT-05 | URL 模板一致 | 正则比对 00 §4.2 | 路径、占位域名、query 参数名一致 |
| CT-06 | 禁词扫描 | `grep` 规则 | 无真实域名/密钥/IP；仅 `example.com` 与 `192.0.2.0/24`、`198.51.100.0/24` |

### 11.2 消费者契约（Pact）

| ID | 消费者 → 提供者 | 交互 | 断言 |
|---|---|---|---|
| CT-07 | App → API | `POST /rooms` 201 | 字段集、类型、`playUrl` 格式、`ingest` 4 字段、`turn` 3 字段、`limits` 2 字段 |
| CT-08 | App → API | `GET /rooms/{roomId}` 200 | 房间形状、`ingest.token` 可读 |
| CT-09 | App → API | `DELETE /rooms/{roomId}` 204 | 无 body、`X-Request-Id` 存在 |
| CT-10 | App → API | `POST /ingest:switch` 202 | 4 字段且 `graceMs=5000`、`playUrlUnchanged=true` |
| CT-11 | 播放页 → API | `POST /play-session` 200 | `playSessionId` 前缀、`expiresIn=300`、`protocols` 三键 |
| CT-12 | 播放页 → API | `POST /play-session` 401 | 错误信封 6 字段、`code` 为 3004/3005 |
| CT-13 | 播放页 → API | `GET /status` 200 | 8 字段、`lastFrameAt` 可 null |
| CT-14 | 播放页 → API | `GET /events` SSE | `content-type`、`retry`、事件类型 |
| CT-15 | 播放页 → 媒体面 | `POST /whep` 201 | `content-type: application/sdp`、`Location` 头 |
| CT-16 | 播放页 → 媒体面 | FLV 200 | `content-type: video/x-flv`、`cache-control: no-store` |
| CT-17 | 播放页 → 媒体面 | HLS playlist 200 | `content-type`、`Set-Cookie: cmp_ps`、`TARGETDURATION:2` |

### 11.3 兼容性与回归

| ID | 测试 | 方法 | 通过标准 |
|---|---|---|---|
| CT-18 | 向后兼容 | 用 `v1.0` 客户端契约跑新服务端 | 全部通过，无缺失字段 |
| CT-19 | 未知字段容忍 | 响应注入额外字段 | 客户端正常解析 |
| CT-20 | 未知枚举容忍 | 注入未知 `roomState` | 客户端不崩溃（按 `failed` 兜底） |
| CT-21 | 缺失可选字段 | 移除 `turn`（play-session） | 播放页回落到房间级 `turn` |
| CT-22 | 幂等回归 | 同 `Idempotency-Key` 并发 10 次 `POST /rooms` | 仅 1 个 `roomId`，其余返回同结果或 `2005` |
| CT-23 | 停止幂等 | 连续 3 次 `DELETE` | 均 `204`，无 5xx |
| CT-24 | 切换幂等 | 同 `switchId` 重放 | 不产生第二次切换 |
| CT-25 | 错误码稳定性 | 全错误码回归 | `code→http` 映射与 §2.5 表一致 |

### 11.4 端到端与集成

| ID | 测试 | 方法 | 通过标准 |
|---|---|---|---|
| CT-26 | 创建→推流→播放→停止 | 自动化 E2E（WHIP + WHEP） | 全链路 ≤ 10 s，停止后 2 s 内 410 |
| CT-27 | 协议切换地址不变 | 自动化（WHIP→SRT→RTMP） | `playUrl` 字节不变，`playSessionId` 不变 |
| CT-28 | 切换失败回退 | 阻断新协议推流 | 15 s 内回退 `live`，播放恢复 |
| CT-29 | PIN 流程 | 4/6 位 PIN、错误 10 次 | 3004/3005/3006 与 `Retry-After` |
| CT-30 | 降级链 | 关闭 WHEP / FLV | `webrtc→flv→hls` 顺序与埋点 |
| CT-31 | SSE 续传 | 断开后带 `Last-Event-ID` 重连 | 事件不丢、不重复（≤ 1 条重复可接受） |
| CT-32 | 限流 | 超 30 req/min | `429`+`3008`+`Retry-After` |
| CT-33 | 并发上限 | 超 `maxViewers` | `429`+`2008` |
| CT-34 | 410 语义 | 停止后访问全部媒体端点 | 无媒体字节输出 |
| CT-35 | SDP 模糊测试 | 畸形 SDP 100 例 | 无 5xx 崩溃，返回 4xx+错误码 |
| CT-36 | 媒体面鉴权 | 错 `aud`、错 `sub`、过期、重放 | 分别返回 3007/3002/3003，`jti` 重放拒绝 |

---

## 12. 版本演进与灰度

### 12.1 变更分类

| 类别 | 示例 | 兼容性 | 流程 |
|---|---|---|---|
| 新增可选字段 | `play-session.turn` | 向后兼容 | 直接发布，更新 00 后升次版本 |
| 新增端点 | 观众端统计 | 兼容 | 直接发布 |
| 新增枚举值（客户端可忽略） | `ended.reason` 新增原因 | 兼容 | 客户端必须容错 |
| 改字段语义 | `viewers` 口径变化 | **破坏** | 升 `v2` |
| 删除字段/端点 | — | **破坏** | 升 `v2` 并双跑 ≥ 90 天（00 §6.3） |
| 改错误码 | 同语义换码 | **破坏** | 禁止；只追加不复用（00 §9.2） |

### 12.2 灰度策略

| 维度 | 策略 |
|---|---|
| 服务端 | 按 PoP 金丝雀 5% → 25% → 100%，每阶段观察 ≥ 30 min，错误率与首帧指标不回归才继续 |
| 客户端 | 按 `appVersion` 灰度（`client.appVersion` 上报），旧版本强制走旧路径 |
| 契约 | 新字段默认关闭，feature flag 控制；关闭时行为与 v1.0 完全一致 |
| 回滚 | 服务端保留前一版本 ≥ 24 h；契约变更回滚需同版本号内可逆（仅新增字段） |
| 双跑 | 破坏性变更 `v1` 与 `v2` 双跑 ≥ 90 天，`v1` 期间只修 bug 不加功能 |

### 12.3 契约治理

1. 任何契约改动必须先改 00（Lead 审批），再改本文，再改代码。
2. CI 门禁：OpenAPI lint + Pact 验证 + 枚举/错误码比对脚本（§11.1）。
3. 契约变更记录写入本文 §16 与 00 §16；破坏性变更需 ADR（11）。
4. 客户端 SDK 与服务端共享 `contract` 子模块（生成的 Kotlin/TS 类型），禁止手写重复类型。

---

## 13. 失败与降级

| 编号 | 失败 | 客户端动作 | 最终态 | 错误码 |
|---|---|---|---|---|
| E1 | `POST /rooms` 超时 | 用同 `Idempotency-Key` 重试 2 次 | 仍失败 → 提示重试 | — |
| E2 | `play-session` 401 PIN | 弹 PIN | `auth` | `3004`/`3005` |
| E3 | `play-session` 410 | 展示结束页 | `ended` | `2002`/`2003` |
| E4 | `play-session` 429 | 退避重试（`Retry-After`） | 30 s 后 `failed` | `2008`/`3006`/`3008` |
| E5 | WHEP `5006` | 降级 FLV | `degraded` | `5006` |
| E6 | FLV `5002` | 降级 HLS | `degraded` | `5002` |
| E7 | HLS `5003` | 展示重试 | `failed` | `5003` |
| E8 | SSE 断开 | `Last-Event-ID` 重连；失败 3 次转轮询 | 不改变播放 | — |
| E9 | `source-switched` 后无首帧 | 3 s 后重连媒体，15 s 后降级 | `degraded`/`failed` | `5004` |
| E10 | `viewer-count` 丢失 | 忽略（非关键） | — | — |
| E11 | 媒体 token 过期 | 重新 `play-session`（复用会话） | 继续播放 | `3003` |
| E12 | 房间 `failed` | 展示失败与重试 | `failed` | `5004` |

---

## 14. 验收检查点

- [ ] **C1** 允许的 8 个 REST 端点、3 个 WHIP 方法、2 个 WHEP 方法、FLV、HLS 全部按 §3/§5 实现，路径与 00 §4.2 一致。
- [ ] **C2** `POST /rooms` 响应字段与 00 §6.2 逐字一致，无多余必填字段。
- [ ] **C3** `POST /rooms` 幂等：同键 24 h 内返回同一 `roomId`。
- [ ] **C4** 错误信封 6 字段齐全，`X-Request-Id` 与 `requestId` 一致。
- [ ] **C5** 校验顺序为 `签名 → exp → 房间状态 → 会话/并发`，对应错误码符合 §2.5。
- [ ] **C6** `play-session` 响应 `expiresIn=300`、`playSessionId` 前缀 `pls_`、`mediaToken.sid == playSessionId`。
- [ ] **C7** PIN 三种错误码与 `Retry-After` 行为正确，连续 10 次锁定 10 min。
- [ ] **C8** `ingest:switch` 响应 4 字段且 `graceMs=5000`、`playUrlUnchanged=true`。
- [ ] **C9** `status` 响应 8 字段，`closed` 返回 `200` 而非 `410`。
- [ ] **C10** SSE 5 种事件类型、`data` 结构、`id` 单调、`retry: 3000`、心跳 15 s。
- [ ] **C11** SSE 支持 `Last-Event-ID` 续传且事件顺序符合 §4.4。
- [ ] **C12** WHIP Offer/Answer 满足 §5.1.1 约束，Answer 含 `recvonly`、`setup:active`、host/srflx 候选。
- [ ] **C13** WHIP PATCH 支持 trickle，重复候选幂等。
- [ ] **C14** WHEP 强制 `recvonly`，非 `recvonly` 返回 `5007`。
- [ ] **C15** FLV 响应头 `video/x-flv` + `no-store`，首包顺序为 `onMetaData → AVC seq → AAC seq → IDR`，5 s 保活。
- [ ] **C16** HLS `TARGETDURATION=2`、6 分片、`Set-Cookie: cmp_ps`、`closed` 写 `ENDLIST`。
- [ ] **C17** HLS 分片仅接受 Cookie，不接受 `mt`。
- [ ] **C18** 播放页调用顺序与 §6.1 一致，降级阶梯与 00 §10.2 完全一致。
- [ ] **C19** LAN 与 WAN 的 3xxx 错误码语义一致，客户端错误映射可复用。
- [ ] **C20** 三状态机映射表 §8 的每种迁移都有测试用例。
- [ ] **C21** §9 的 5 个时序图对应的自动化用例全部通过。
- [ ] **C22** OpenAPI 通过 `spectral lint`（0 error）且示例通过 schema 校验。
- [ ] **C23** Pact 消费者测试覆盖 CT-07 至 CT-17。
- [ ] **C24** 兼容性回归 CT-18 至 CT-25 全绿。
- [ ] **C25** 契约中无真实域名/密钥/IP；占位值符合 00 §4.2。
- [ ] **C26** 新增字段（`play-session.turn`、`GET /rooms` 形状、`page-token:refresh` 响应、`POST /rooms` 的 `pin`）已在 §15 登记并向 Lead 提交契约变更请求。

---

## 15. 开放问题

| ID | 问题 | 候选方案 | 推荐 | 影响 | 决策人 |
|---|---|---|---|---|---|
| Q1 | `GET /rooms/{roomId}` 的响应形状 00 未冻结 | A. 复用 `Room` 对象（本文 §3.2 定义）；B. 极简 `{roomId,state,playUrl,ingest}` | **A** | 影响 App 与切换后取新凭证；本文已按 A 冻结，需 Lead 回写 00 | Lead |
| Q2 | `play-session` 是否返回 `turn` 凭证 | A. 不返回，使用创建房间时的 `turn`（长 TTL）；B. 返回可选 `turn`（本文 §3.6） | **B** | 影响 TURN 凭证轮换与长时观看；A 需 `turn` TTL 覆盖房间寿命 | Lead |
| Q3 | `POST /rooms` 是否接受 `pin` | A. 接受可选 `pin`（App 生成并上报）；B. 服务端生成并在响应返回 | **A** | B 会把 PIN 放进响应体，增加泄露面；00 §6.2 请求体未列 `pin`，需增补 | Lead + 安全 |
| Q4 | `page-token:refresh` 响应形状未定义 | A. `{pageToken,expiresIn}`（本文）；B. 仅返回 `pageToken` | **A** | 播放页需要 `expiresIn` 规划刷新 | Lead |
| Q5 | `status` 鉴权头在 00 中未指定 | A. `Authorization: Bearer`；B. `X-Page-Token`；C. 两者兼容 | **C** | 影响播放页实现；建议 00 §6 统一为 `Authorization` + 兼容 `X-Page-Token` | Lead |
| Q6 | `X-Trace-Id` 是否纳入契约 | A. 可选头（缺失服务端生成）；B. 不纳入 | **A** | 端到端排障与 10 号文档 `traceId` 对齐 | Lead |
| Q7 | `protocolPreference` 枚举是否扩展 | A. v1 仅 `auto`；B. 增加 `webrtc`/`flv`/`hls` | **A**（v1 保持 `auto`，M2 再评估） | 与"播放协议用户不可见"的 PRD 承诺一致 | 产品 + 播放页 |
| Q8 | 新增限流（SSE 连接、WHIP POST、switch）是否入 00 | A. 写入 00 §6.3；B. 作为服务端实现细节 | **A** | 影响契约测试与客户端错误处理 | Lead |
| Q9 | WHIP 是否启用 Simulcast | A. v1 关闭（本文）；B. 开启（多档位） | **A** | 开启会增加 SFU 与客户端复杂度，收益在弱网较小 | 架构 |
| Q10 | `GET /rooms` 非本人房间返回 404 还是 403 | A. 统一 404（不泄露存在性）；B. 403 | **A** | 安全口径需与 07 对齐 | 安全 |
| Q11 | HLS 分片命名与容器（fMP4 vs TS） | A. fMP4 `init.mp4`+`seg-{n}.m4s`；B. TS `seg-{n}.ts` | **A**（与 03 §18 Q4 一致） | 影响 CDN 缓存键与老设备兼容 | 播放页 + 服务端 |
| Q12 | 是否使用 HTTP `425 Too Early` 表达幂等键处理中 | A. 用 `409`+`2005`；B. 用 `425` | **A**（与 00 现有码一致） | 避免新增码语义 | Lead |
| Q13 | SSE 事件 `heartbeat` 是否携带时间戳 | A. `{}`（本文）；B. `{serverTime}` | **A**（保持最小契约） | B 便于时钟校准但增加字段 | 播放页 |

---

## 16. 变更记录

| 版本 | 日期 | 变更人 | 内容 |
|---|---|---|---|
| v1.0 | 2026-10-20 | 云服务与接口契约作者 | 首次冻结 WAN REST/SSE/媒体面契约：通用约定、8 个 REST 端点、5 类 SSE 事件、WHIP/WHEP/FLV/HLS、播放页调用顺序、LAN/WAN 对照、三状态机映射、5 个时序图、OpenAPI 3.1 片段、36 项契约测试、版本与灰度策略 |
| v1.1 | 2026-10-20 | Lead（集成） | 新增 §3.9 埋点批量上报契约（`/api/v1/telemetry/batch`，对齐 00 v1.4 §11）；§7.1 增补 `pageToken` 续期、埋点上报、LAN `playSessionId` 差异行；§2.5 示例码勘误为 `3003` |
