# 13 · P1 局域网闭环验收报告（G1 证据包）

| 项目 | 值 |
|---|---|
| 文档状态 | 验收证据（Draft → 待真机矩阵补齐） |
| 版本 | v1.0 |
| 适用阶段 | P1（局域网可用闭环，LAN-first） |
| 验收依据 | [09 §2.9](./09-task-plan.md)、[08 M0-LAN](./08-m0-verification.md)、[00 §20](./00-conventions.md) |
| 生成日期 | 2026-10-21 |
| 结论摘要 | **功能与自动化验收通过；真机兼容矩阵、真实音频听测、iOS/微信内核验证待补 → 不能宣布 G1 完全通过** |

---

## 1. 验收结论

| 维度 | 状态 | 说明 |
|---|---|---|
| P1 功能闭环（起服务 → 同网观看 → 停止失效） | ✅ 通过 | 模拟器 + 桌面 Chrome 端到端；自动化测试覆盖 |
| 30fps / 1080p 局域网推流 | ✅ 通过（模拟器） | Camera2+MediaRecorder Surface 管线，1080p 30fps；真机 HUAWEI 实测 30fps |
| 停止 ≤ 1 s 失效 | ✅ 通过 | `LanServerE2ETest.stopInvalidatesPortAndIsFast` |
| PIN / 并发 / 限速 | ✅ 通过 | PIN 3004/3005/3006/3008、并发 6005 自动化测试（48/48） |
| 埋点 | ✅ P1 子集 | App 队列/批量上报 + 端侧计数摘要；播放页 5 事件 |
| 真机兼容矩阵 | ⚠️ **条件接受** | 仅 HUAWEI LRT-W30 平板；Mac Chrome + iOS Safari 通过；其余机型无设备，按简化方案豁免（§1.3） |
| 真实音频听测 / iOS Safari / 微信 X5 | ⚠️ 未完成 | 需真机与真实麦克风 |
| AP 隔离错误提示（6004） | ⚠️ 简化验收 | 有排查清单/文案；无路由器隔离环境，按静态检查验收（§1.3） |
| M0-LAN E7（8 类播放环境） | ✅ 条件通过 | Mac Chrome、Android WebView、iOS Safari（VPN 关闭后实测通过）；其余环境按简化方案豁免 |

**结论**：P1 的**代码与自动化证据链已闭环**；在“单真机 + 3 类播放环境”的简化方案下，**可签署条件 G1（内测范围）**。iOS Safari 的 5004 已确认由 VPN 引起，关闭 VPN 后通过；同时 Surface 管线已补 `ImageReader→JPEG` 的 MJPEG 兜底。剩余仅需 AC-01 的 10 次计时样本与条件 G1 签字。

### 1.3 简化验收方案（无多机型条件）

用户仅有一台真机（HUAWEI LRT-W30 平板），以下项目按**条件验收**简化，风险由 Lead/产品接受并记录：

| 原验收项 | 简化方式 | 风险/豁免 |
|---|---|---|
| T1-Q2 Top12 × 2 浏览器 | 1 台真机 + Mac Chrome + iOS Safari（共 3 环境） | 其余机型/浏览器未覆盖，发布说明标注“内测版” |
| E7 8 类播放环境 | Mac Chrome、Android WebView、iOS Safari 三条路径；其余以“无设备”豁免 | iOS Safari 复测通过即视为 E7 条件通过 |
| AP 隔离（6004）真实路由器 | 静态检查：文案/排查清单可见、代码路径存在；不做真实隔离 | 排障依赖提示，不承诺自动检测 |
| 安全/隐私用例（T1-Q4） | 以 JVM 自动化 48 项 + 日志脱敏抽检替代人工安全测试 | 不做渗透/外部安全审计 |
| M0 评审/回归报告/发布物料（T0-P3/T1-Q5/T1-P6） | 由 Lead 以 docs/13 + docs/14 完成评审记录、报告骨架、发布清单 | 签字为项目内评审，非外部 QA 签字 |

**条件 G1 通过标准**：AC-01（采样≥10 次）、AC-04、AC-06、AC-08、N-01、N-02 在简化环境通过；iOS Safari MJPEG 复测通过；无 P0/P1 缺陷。

### 1.1 P1 任务完成度（交付物 vs 验收执行）

> 不含 P1 可选项 T0-A1（双摄探测，5 pd，为 P2 D-3 提供数据）。
> 口径区分：**交付物完成** = 代码/文档/用例/模板已产出；**验收执行** = 真机/浏览器实跑并回填证据。

| 维度 | 数量 | 说明 |
|---|---:|---|
| P1 承诺任务总数 | 31 | docs/09 §2.3.1 |
| 交付物完成 | **31 / 31（100%）** | 代码、文档、用例库、矩阵模板、评审/发布模板均已交付 |
| 真机/浏览器验收执行并回填 | **0 / 10 项执行类** | 缺测试机、真实麦克风、iOS/微信环境 |
| G1 正式通过 | **未通过** | 需 §1.2 的 10 项执行证据 |

**已交付的执行类材料（docs/14）**

| 任务 | 交付物 | 执行状态 |
|---|---|---|
| T0-Q1 设备/浏览器矩阵与验证用例 | [docs/14 §2/§3](./14-p1-acceptance-kit.md)（Top12 矩阵 + E7 8 类 + 86 条用例） | 待真机执行 |
| T0-Q2 M0-LAN 证据归档 | [docs/14 §6](./14-p1-acceptance-kit.md) 证据清单（自动化部分已勾选） | 待补真机录屏 |
| T0-Q3 首帧/延迟计时 | [docs/14 §5](./14-p1-acceptance-kit.md) AC-01a/b 方法与校验步骤 | 待采样 |
| T0-P3 M0 评审与范围冻结 | [docs/14 §6.2](./14-p1-acceptance-kit.md) 评审记录模板 | 待评审 |
| T1-W5 移动浏览器兼容 | [docs/14 §2.2](./14-p1-acceptance-kit.md) E7 矩阵 | iOS/微信待测 |
| T1-Q4 安全与隐私用例 | [docs/14 §4](./14-p1-acceptance-kit.md) | 待执行归档 |
| T1-Q5 P1 回归与验收报告 | 本文件 + [docs/14 §8](./14-p1-acceptance-kit.md) 回填流程 | 待回填真机证据 |
| T1-P1 UI 稿/交互说明 | [docs/14 §7.1](./14-p1-acceptance-kit.md) | 已交付 |
| T1-Q2 Top12 × 2 浏览器矩阵 | [docs/14 §2.1](./14-p1-acceptance-kit.md) | 待执行 |
| T1-P6 发布检查单/内测素材 | [docs/14 §7.4](./14-p1-acceptance-kit.md) 清单模板 | 待补素材 |

**本轮新增完成的代码/验证**

- T1-A10 埋点补齐**离线补报**：失败事件落盘（≤1000 条 / 7 天），下次起服自动回放；端侧收到后计数；
- T1-Q1 用例库：86 条可执行用例（docs/14 §3）；
- T1-P4/P5：P1 埋点口径表与 LAN 看板/告警需求（docs/14 §7.2/§7.3）；
- PIN 限速/锁定 E2E：IP 5 次/分→3008、连续 10 次→3006、锁定期正确 PIN 仍拒绝。

**结论**：P1 的**交付物侧已无代码/文档缺口**；G1 只差真机与浏览器执行证据（10 项），无法在当前无测试机环境内伪造完成。

---

## 2. M0-LAN 门禁

| 门禁 | 方法 | 证据 | 状态 |
|---|---|---|---|
| E6 LAN 端侧服务 | 起服 → 同网浏览器打开 → 播放 → 停止拒绝 | `LanServerE2ETest`（48 项，含 page/status/healthz/FLV/MJPEG/PIN/并发/停止）；Chrome `readyState=4` | ✅ |
| E7 播放环境矩阵（≥8 类各 1 条路径） | 8 类环境打开唯一地址 | 桌面 Chrome/Chromium 已验证；Android WebView 预览已验证；iOS Safari/微信 X5/系统浏览器待真机 | ⚠️ 条件通过 |

---

## 3. P1 必过 AC 对照

| AC | 要求 | 证据 | 状态 |
|---|---|---|---|
| AC-01（LAN） | 打开/点击开始 → 地址可用 ≤10 s；点开始 p95 ≤3 s | 引擎记录 `push_start_success.durationMs`；模拟器手工计时约 1–3 s（含相机初始化） | ⚠️ 机制就绪，Top20 样本待补 |
| AC-04（LAN） | 停止后 ≤1 s 连接拒绝 | `stopInvalidatesPortAndIsFast`：停止后端口拒绝、耗时 <1 s | ✅ |
| AC-06 | 3 人可播；第 4 人 6005；错误 PIN 3005；限速 3008；锁定 3006；AP 隔离提示 6004；停止 ≤1 s | `viewerLimitReturns429AndCountsConnections`、`pinFlow`、`pinRateLimitAndLockout`、`logDownloadRequiresValidPageToken` | ✅（AP 隔离提示为文案+排查清单，6004 未做自动触发） |
| AC-08（P1 形态） | WAN 置灰“阶段二开放”；推流中不可切换；切换后地址更新/旧地址失效 | UI 实测与截图；`selectAccessScope` 推流中锁定 | ✅ |
| N-01 | 相机拒绝 1001 阻断；麦克风拒绝静音继续；通知拒绝提示 | `P1ControllerImpl.start` 分支 + 单测/手工 | ✅ |
| N-02 | IP 5 次/分 → 3008；房间 20 次/分 → 3008；10 次失败 → 3006 + 10 min | `pinRateLimitAndLockout` | ✅ |
| N-03（P1 部分） | 进程重启后旧 LAN token 失效 | 每会话随机密钥；停止/重启后旧地址 3002（真机曾复现） | ✅ |

---

## 4. P1 DoD 检查

- [x] 飞行模式/无外网可完成闭环（LAN 服务无云端依赖，静态扫描无 `playHost`/`apiHost`）；
- [x] 停止后 ≤1 s 连接拒绝；
- [x] 端口冲突顺延 8089→8099；
- [x] PIN 双层限速（3008/3006）自动化通过；
- [ ] AP 隔离提示（6004）在真实路由器隔离环境验证；
- [x] 代码中无云端域名依赖；
- [x] `:lan-core:test` **48/48**；
- [x] 播放页 selftest **26/26**，`node --check` 通过；
- [x] `:app:assembleDebug` 构建通过；
- [x] 文档门禁 `scripts/check_docs.py --strict` 0 error / 0 warning。

---

## 5. 功能清单（P1 交付）

| 能力 | 状态 | 说明 |
|---|---|---|
| 单摄推流（前/后、切换、torch、静音） | ✅ | Surface 管线默认；CameraX 兼容回退 |
| 唯一播放地址 / 二维码 / 复制 / 分享 / 本机预览 | ✅ | 地址卡；停止即失效 |
| 播放页 FLV→MSE，失败降级 MJPEG | ✅ | 26 项自检；PIN 输入 |
| PIN（4–6 位）+ 限速 + 锁定 | ✅ | 3004/3005/3006/3008 |
| 并发 1–3，第 4 人 6005 | ✅ | 429 + 错误页 |
| 独立诊断日志服务 | ✅ | 应用打开即可下载（设置入口，18089–18099），不依赖推流；累计落盘+轮转 |
| 画质/分辨率/码率/帧率设置与持久化 | ✅ | 1080p 自动 ≥8–12 Mbps；High Profile |
| 旋转适配（`?rot=` + 播放页按钮） | ✅ | 解决手机向上/画面向右 |
| 埋点（P1 子集） | ✅ | `app_open`/push 系列/`lan_viewer_*`/`permission_denied`/地址卡事件；端侧计数 |
| 省电模式（480p/24fps/≤1Mbps） | ✅ | 基础版 |
| AP 隔离排查提示 | ⚠️ | 文案/清单；未做自动 6004 |

---

## 6. 未完成项与下一步（P1 收尾）

| # | 事项 | 归属任务 | 建议 | 阻塞 G1 |
|---|---|---|---|---|
| 1 | Top 12 真机 × 2 浏览器矩阵 | T1-Q2 | 内测机型覆盖；重点 HUAWEI/小米/OPPO/vivo + Chrome/系统浏览器 | 是 |
| 2 | iOS Safari / 微信 X5 播放验证 | T1-W5 | 至少 3 端内测；必要时调整 MJPEG 降级 | 是 |
| 3 | 真实麦克风 AAC 听测 | T1-Q4 | 真机 48 kHz 录音、A/V 同步主观测试 | 否（可带条件） |
| 4 | AP 隔离真实路由器验证（6004） | T2-A5/T2-Q4 | 路由器开启客户端隔离，确认提示链路 | 否（可带条件） |
| 5 | 兼容性/回归报告与录屏归档 | T1-Q5 | 以本文件为骨架补证据 | 是 |
| 6 | LAN token 60 min 续期 | P1-IMPL-05 | 保持“过期提示重取地址”，M2 再做 | 否 |
| 7 | App 内预览方向（TextureView transform） | 新增 | SurfaceView 预览在个别机型可能方向不一致 | 否 |

---

## 7. 复现命令

```bash
scripts/build.sh :lan-core:test :app:assembleDebug
node tools/player-selftest/run.mjs
python3 scripts/check_docs.py --strict
```

模拟器端到端：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 授权 CAMERA / RECORD_AUDIO 后启动，开始推流
# 浏览器打开 http://<手机IP>:<端口>/s/<token>
# 停止推流后再次访问应 connection refused（≤1s）
```

诊断日志：推流中地址卡「日志」复制 `…/logs`，或直接浏览器打开该地址下载。
