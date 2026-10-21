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
| 真机兼容矩阵（Top 12 × 2 浏览器） | ⚠️ 未完成 | 仅 HUAWEI LRT-W30 + 模拟器/桌面 Chrome |
| 真实音频听测 / iOS Safari / 微信 X5 | ⚠️ 未完成 | 需真机与真实麦克风 |
| AP 隔离错误提示（6004） | ⚠️ 部分 | 有排查清单/文案；未做路由器隔离环境的自动化触发 |
| M0-LAN E7（8 类播放环境） | ⚠️ 条件通过 | 桌面 Chrome/Chromium 路径通过；移动内核待补 |

**结论**：P1 的**代码与自动化证据链已闭环**，可在内测范围发布；**G1 正式通过需补齐真机矩阵与移动浏览器验证**（见 §6）。

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
| 诊断日志下载 `/s/{token}/logs` | ✅ | App 环形日志 + 设备状态 + 崩溃栈 |
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
