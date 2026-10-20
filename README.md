# CamPush 工程化文档集

> 由《Android 摄像头实时推流产品方案手册 v1.0》（下称 PRD）派生的**可执行工程设计文档集**：回答“怎么建、边界在哪、怎么验、怎么排期、风险谁背”。
> 本仓库只交付文档与校验脚本，**不包含 App / 服务端 / 播放页代码实现**。

| 项目 | 值 |
|---|---|
| 文档集状态 | 基线（Baseline v1.0） |
| 契约基线版本 | [00-conventions.md](./docs/00-conventions.md) **v1.4** |
| 需求基线 | [PRD v1.0](./docs/PRD-v1.0.md) |
| 文档规模 | 13 份，约 1.42 万行，69 张 Mermaid 图，600+ 张表格 |
| 质量门禁 | `python3 scripts/check_docs.py --strict`（当前：通过） |
| 维护人 | Team Lead |
| 最后更新 | 2026-10-20 |

---

## 1. 这套文档解决什么

PRD 定义 **What / Why**；本集定义 **How / 边界 / 验收 / 排期 / 风险**。三条不可谈判的产品约束贯穿全部文档：

1. **推流协议与播放协议分离**：推流只有 WHIP / SRT / RTMP；HLS、HTTP-FLV 只存在于播放页内部。
2. **标准模式只输出一个播放地址**：复制、二维码、分享、本机预览全部指向同一个 `playUrl`，且协议热切换时字符串不变。
3. **服务端能力按访问范围拆分**：`仅局域网` 由端侧服务承担；`公网可访问` 依赖云端媒体服务，客户端场景开关二选一。

文档之间不是“并列文章”，而是**同一套契约的不同切面**：所有枚举、URL、token、错误码、性能数字只允许在 00 定义一次，子文档只能细化。

---

## 2. 文档地图

| 编号 | 文档 | 内容 | 主要读者 |
|---|---|---|---|
| 00 | [conventions](./docs/00-conventions.md) | 契约基线：枚举、地址、token、REST/媒体/端侧 API、错误码、状态机、性能预算、埋点、写作规范、v1.1–v1.4 裁定 | 全员（先读） |
| 01 | [architecture](./docs/01-architecture.md) | 双模式拓扑与部署、组件职责、端到端数据流、选型结论、容量成本、SLO 与故障域 | 架构 / 技术负责人 |
| 02 | [client-modules](./docs/02-client-modules.md) | Android 14 模块拆分、UDF 状态模型、四线程模型、CameraX 双摄、MediaCodec/ABR、OpenGL 合成、保活与降级、测试矩阵 | Android |
| 03 | [cloud-service](./docs/03-cloud-service.md) | 推流接入（WHIP/SRT/RTMP）、SFU 选型、切源机制、FLV/HLS、TURN、房间生命周期、配额、部署与容量成本 | 服务端 / SRE |
| 04 | [interface-contracts](./docs/04-interface-contracts.md) | 逐端点 REST 契约、SSE 事件、媒体面协议、LAN/WAN 对照、状态机映射、时序图、OpenAPI 片段、契约测试 | 服务端 / 客户端 / Web / QA |
| 05 | [lan-service](./docs/05-lan-service.md) | 端侧 HTTP 服务、MJPEG/FLV 分发与背压、PIN、并发 1–3、端口与网络检测、AP 隔离、≤1 s 停止 | Android / 测试 |
| 06 | [playback-page](./docs/06-playback-page.md) | 播放页状态机、WAN/LAN 降级阶梯、鉴权与 token 续期、切源处理、兼容矩阵、CSP、22 条 E2E | Web / QA |
| 07 | [security-privacy](./docs/07-security-privacy.md) | STRIDE、token 撤销矩阵、PIN 防爆破、隐私清单、权限话术、日志脱敏、合规、渗透清单、Go/No-Go | 安全 / 法务 / 后端 |
| 08 | [m0-verification](./docs/08-m0-verification.md) | M0 六项实验：假设、方法、设备矩阵、阈值、样本量、结论模板、回退；双摄支持率调研 | 测试 / 架构 / 产品 |
| 09 | [task-plan](./docs/09-task-plan.md) | 4 Epic / 31 Feature / 118 Task、依赖 DAG、关键路径、23 周排期、DoR/DoD、验收映射 | 项目管理 / 全员 |
| 10 | [observability](./docs/10-observability.md) | 11 项核心指标定义与公式、18 事件埋点 schema、漏斗、SLO/错误预算、23 条告警、埋点 QA | 数据 / QA / 服务端 |
| 11 | [risk-adr](./docs/11-risk-adr.md) | 18 条风险登记册、PRD §18.2 五问结论、ADR-001～012、风险降级路径 | 产品 / 技术负责人 |
| — | [PRD v1.0](./docs/PRD-v1.0.md) | 用户提供的需求原文（不改写，仅引用） | 全员 |
| — | [scripts/check_docs.py](./scripts/check_docs.py) | 文档一致性 CI：行数、章节、Mermaid、坏链、PRD 引用、错误码、占位域名、密钥泄漏 | 全员 |

---

## 3. 推荐阅读路径

| 角色 | 顺序 | 预计 |
|---|---|---|
| 产品 / 负责人 | README → 00 §1–4 → PRD → 09 → 11 → 10 §2 | 60 min |
| Android | 00 → 02 → 01 §4–5 → 05 → 06 §2 | 120 min |
| 服务端 | 00 §4–9 → 03 → 04 → 01 §6–8 | 120 min |
| Web / 播放页 | 00 §4/5/10 → 06 → 04 §3–4 → 05 §8 | 60 min |
| 安全 / 合规 | 07 → 00 §5/§9/§11 → 04 → 11 | 60 min |
| QA / 交付 | 08 → 10 → 09 §2 → 06 §12 | 90 min |

---

## 4. 关键不变量（可测试，不可谈判）

| ID | 不变量 | 验证方式 |
|---|---|---|
| INV-1 | 标准模式同一会话内 `playUrl` 字符串恒定（含协议热切换） | 04 §3.4 断言 SW-1～SW-10；E2E 比对字符串 |
| INV-2 | 切源不重建播放会话：`playSessionId` 不变，地址栏不变 | 03 §6 时序；06 E2E-12 |
| INV-3 | 停止推流后地址失效：LAN `connection refused` ≤1 s，WAN 媒体端点 `410` ≤2 s | 05 §3.3；04 §3.3；09 AC-05 |
| INV-4 | 推流协议枚举只有 `whip/srt/rtmp`；HLS/FLV 不出现在推流设置 | 00 §3；文档 CI 枚举扫描 |
| INV-5 | 双摄不支持设备不出现不可用选项（`1004` 降级并解释原因） | 02 §7；08 E1；09 AC-04 |
| INV-6 | 专业模式（双路/多地址/BYO）必须显式提示多地址，且不影响 INV-1 | 11 ADR-008；02 §11 |

---

## 5. 契约基线速查（详见 00）

### 5.1 枚举

`accessScope`: `lan` / `wan`（推流中锁定）
`captureMode`: `single_back` / `single_front` / `dual`
`pushProtocol`: `whip` / `srt` / `rtmp`
`playProtocol`: `webrtc` / `flv` / `hls` / `mjpeg`（用户不可见）
`roomState`: `created` / `live` / `switching` / `stopping` / `closed` / `failed`

### 5.2 地址

| 用途 | 模板 |
|---|---|
| 公网唯一播放地址 | `{playHost}/s/{roomId}?token={pageToken}` |
| 局域网唯一播放地址 | `http://{lanIp}:{port}/s/{pageToken}` |
| 云端 API | `{apiHost}/api/v1/...` |
| HTTP-FLV / HLS | `{playHost}/live/{roomId}.flv?mt=` / `{playHost}/hls/{roomId}/index.m3u8?mt=` |

### 5.3 token

| token | 位置 | TTL | 撤销 |
|---|---|---|---|
| `pageToken` | 播放地址 query / LAN 路径段 | 60 min | 房间关闭即失效 |
| `mediaToken` | 媒体面 `?mt=` / WHEP Bearer | 300 s，可续期 | 房间关闭 / 并发上限 |
| `ingestToken` | WHIP/SRT/RTMP 接入 | ≤12 h | 切源后 5 s grace |

### 5.4 降级阶梯与预算

- WAN 播放：`webrtc(2.5s) → flv(2.0s) → hls(5.0s)`；LAN：`flv(2.0s) → mjpeg(2.0s)`（M2 加 WebRTC）。
- 首帧 < 2 s（目标 ≤1.85 s）；端到端延迟 p50 ≈250 ms / p95 ≈630 ms。
- 点击→地址 ≤3 s；停止→失效 ≤1 s（LAN）/≤2 s（WAN）；切源中断 ≤3 s（p95 ≤5 s）。
- 错误码号段：1xxx 客户端、2xxx 房间、3xxx 鉴权、4xxx 推流、5xxx 播放、6xxx 端侧、7xxx 网络性能。

### 5.5 两种访问范围一页对比

| 维度 | 仅局域网（LAN） | 公网可访问（WAN） |
|---|---|---|
| 服务端 | APK 内端侧服务 | 云端 SFU + 转封装 + HLS + TURN |
| 观看条件 | 同一 Wi-Fi/热点，未被 AP 隔离 | 任意公网设备 |
| 播放兜底 | FLV → MJPEG，无 HLS | WebRTC → FLV → HLS |
| 并发 | 1–3（`6005`） | 按套餐（`2004`/`2008`） |
| 失效 | 服务关闭 + 密钥销毁 | 房间销毁 + `410` |
| 成本 | 0（手机上行承担） | 带宽/计算为主，出流量约占 88% |

---

## 6. 质量门禁

```bash
python3 scripts/check_docs.py --strict
```

检查 8 类问题，当前结果：**0 error / 0 warning**。

| # | 检查项 | 失败后果 |
|---|---|---|
| 1 | 文件存在与行数下限 | 文档被裁剪或占位 |
| 2 | 必需章节（验收检查点/开放问题/变更记录） | 不可评审、不可验收 |
| 3 | Mermaid 代码块闭合与存在 | 文档渲染失败 |
| 4 | 相对链接可达 | 阅读断链 |
| 5 | PRD §1–§19 引用合法 | 需求追溯断裂 |
| 6 | 错误码必须命中 00 §9.2 号段 | 客户端分支错乱 |
| 7 | 占位域名白名单（禁止真实域名/公网 IP） | 信息泄露/不可复现 |
| 8 | 密钥/token 明文与疑似 JWT | 安全事件 |

---

## 7. 文档规模与版本

| 文档 | 行数 | 图 | 状态 |
|---|---|---|---|
| 00 conventions | 711 | 2 | 基线 v1.4 |
| 01 architecture | 1421 | 11 | 基线 v1.2 |
| 02 client-modules | 1713 | 12 | 基线 v1.0 |
| 03 cloud-service | 1370 | 7 | 基线 v1.0 |
| 04 interface-contracts | 2109 | 6 | 基线 v1.1 |
| 05 lan-service | 1180 | 9 | 基线 v1.1 |
| 06 playback-page | 990 | 6 | 基线 v1.1 |
| 07 security-privacy | 1047 | 6 | 基线 v1.0 |
| 08 m0-verification | 925 | 3 | 基线 v1.0 |
| 09 task-plan | 795 | 3 | 基线 v1.0 |
| 10 observability | 826 | 2 | 基线 v1.0 |
| 11 risk-adr | 461 | 2 | 基线 v1.0 |
| PRD v1.0 | 643 | 0 | 需求基线（原文） |

> 子文档中 “00 v1.1/v1.2” 的引用为其写作时点；以 00 当前版本 v1.4 为准，v1.1–v1.4 的裁定见 [00 §17–§19](./docs/00-conventions.md)。

---

## 8. 待决策事项（进入评审）

| # | 决策项 | 推荐 | 决策人 | 截止 | 依据 |
|---|---|---|---|---|---|
| D-1 | 默认访问范围是否长期保持“公网”，还是按网络自动推荐 | 保持公网 + 首次引导 | 产品 | M1 前 | PRD §4.2；11 OQ |
| D-2 | 公网托管：自建媒体面 vs 第三方媒体云 | v1 自建（Mediasoup + SRS 终结）+ D2 评估混合 | 技术 + 产品 | M0 结束 | 11 ADR-005 |
| D-3 | 双摄白名单门槛与灰度策略 | 支持率 ≥40% 才作为 v1 卖点，否则降级实验性 | 产品 + Android | M0 结束 | 08 E1；11 R-001 |
| D-4 | 局域网 M1 是否支持端侧 RTMP | M1 仅本地直出 FLV/MJPEG，RTMP 进 M2 | 架构 | M1 启动前 | PRD §18.2 |
| D-5 | 麦克风拒绝是否允许静音推流 | 允许（已按 v1.1 §17.3 执行） | 产品 | 已定 | 00 §17.3 |
| D-6 | RTMPS 是否进入 M2 | 进入 M2 评估，v1 明文 + UI 明示 | 安全 + 产品 | 2027-01-15 | 11 ADR-009 |
| D-7 | 双路独立推流入口是否进专业模式二级页 | 是，且强提示多地址 | 产品 + 设计 | M2 设计前 | 11 ADR-008 |
| D-8 | 埋点是否接第三方 | 自建上报 + 数仓，不接 SDK | 数据 + 安全 | M1 前 | 11 ADR-011 |
| D-9 | HLS 兜底容器 | v1 fMP4，TS 作兼容开关 | 播放页 + 服务端 | 已定 | 00 §19；04 Q11 |
| D-10 | 盈利模式 | 流量/时长套餐 + BYO 免费工具双轨 | 产品 | M3 前 | PRD §18.2 |

---

## 9. 目录结构

```text
camPush/
├── README.md                 # 本导航
├── docs/
│   ├── PRD-v1.0.md           # 需求原文基线（不改写）
│   ├── 00-conventions.md     # 契约基线 + v1.1–v1.4 裁定
│   ├── 01-architecture.md
│   ├── 02-client-modules.md
│   ├── 03-cloud-service.md
│   ├── 04-interface-contracts.md
│   ├── 05-lan-service.md
│   ├── 06-playback-page.md
│   ├── 07-security-privacy.md
│   ├── 08-m0-verification.md
│   ├── 09-task-plan.md
│   ├── 10-observability.md
│   └── 11-risk-adr.md
└── scripts/
    └── check_docs.py         # 文档一致性 CI
```

---

## 10. 使用与维护

1. **契约优先**：任何字段/路径/枚举/错误码变更，先改 [00](./docs/00-conventions.md) 并升次版本，再由子文档引用；破坏性变更升主版本。
2. **变更流程**：提交 CR（影响文档、兼容性、迁移成本）→ Lead 2 个工作日内裁定 → 更新 00 与受影响文档 → 跑门禁 → 记入各文档变更记录。
3. **门禁强制**：合并前 `python3 scripts/check_docs.py --strict` 必须通过；CI 非零退出即阻断。
4. **不修改 PRD 原文**：PRD 是需求基线，产品口径变更须由产品更新 PRD 并同步本集。
5. **开放问题闭环**：每份文档的“开放问题”必须带候选方案/推荐/决策人/截止时间；决策后回写 00 与本 README 的 §8。

---

## 11. 变更记录

| 版本 | 日期 | 变更人 | 内容 |
|---|---|---|---|
| v1.0 | 2026-10-20 | Team Lead | 首次交付：13 份文档 + 一致性校验脚本；契约基线 v1.4；文档 CI 通过 |
