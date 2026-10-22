# 21 · P3 Kickoff（专业化与商业化）

| 项 | 值 |
|---|---|
| 状态 | 进行中 |
| 日期 | 2026-10-22 |
| 入口 | 用户确认 P2 里程碑完成后启动 |
| 依据 | docs/09 §2.1.5 P3 范围、docs/20 P2 完成报告 |

## 1. P3 目标（G3 v1.0 GA）

- 账号体系/套餐/额度、BYO 与多地址、录制/水印、观众端 Web/API、合规与灰度；
- 全量 AC 通过、4 条 SLO 与错误预算达标、商业化端到端可用、灰度/回滚演练。

## 2. 执行顺序（先地基后体验）

| 序 | 任务 | 说明 | 状态 |
|---|---|---|---|
| 1 | T3-S1 | 账号注册/登录/设备绑定/账号 token/额度骨架 | ✅ 首版（cloud-core 15/15） |
| 2 | T3-S2 | 套餐/额度/计费（free/pro/proplus、房间分钟数、2004） | ✅ 首版（cloud-core 16/16） |
| 3 | T3-A3 | App 登录/注册/套餐展示；账号 token 用于 WAN 房间 | ⚠️ 部分（无购买跳转） |
| 4 | T3-W1/T3-S5 | 观众端 Web + 房间列表/进入权限 API | ✅ 首版（公开列表/播放页复用） |
| 4b | T3-W2 | 账号/套餐 Web 页（登录/套餐展示/切换） | ✅ 首版（无支付） |
| 5 | T3-S3/T3-A4 | BYO 自定义地址/连通性测试 | 待做 |
| 6 | T3-A1/T3-A2 | 双路独立/高级合成（受 D-3 实验性约束） | 待做 |
| 7 | T3-A5 | 日志导出与脱敏 | ✅ 首版（LogRedactor + 导出分享） |
| 7f | T2-A7 本地录制 | ✅ 首版（H264/AAC→MP4，视频+音频） |
| 7g | T2-S7 可观测性看板 | ✅ 首版（`/metrics` 房间/首帧/切换） |
| 7h | 播放水印 | ⚠️ 播放页覆盖层（非编码烧录） |
| 7i | T3-A2 合成编码 | ✅ 首版（PiP/分屏/交换 + MP4 录制 + 烧录水印） |
| 8 | 合规/灰度/压测 | T3-S8/T3-Q2/Q3/P4 等 | 待做 |

## 3. 当前约束

- 本地 Docker/SRS 已清理；本轮先完成**纯 JVM/Android 代码与单测**，不依赖媒体环境；
- 真机/公网验收按 P2 边界处理：开发完成≠G3 通过；
- D-3 双摄仍实验性；T3-A1 双路独立按 D-3 结论延后/裁剪。

## 3.1 T3-S2 套餐/额度首版

```text
POST /api/v1/account/plan  {plan:"free|pro|proplus"} (Bearer) -> 更新套餐
GET  /api/v1/account/usage (Bearer) -> usedSeconds/monthlyMinutes/roomLimit
```

- free: 1 房间/60 min；pro: 10/1000 min；proplus: 50/5000 min；
- 房间创建按账号套餐限额；关闭房间累计 usedSeconds；超限 `429/2004`；
- 当前无支付链路（T3-W2/计费对账后续）。

## 3.2 T3-A3 App 账号入口（部分）

- 设置页「账号」：邮箱/密码、登录/注册、退出、套餐展示；
- 账号 token 持久化；WAN 房间创建使用账号 token（free/pro 额度生效）；
- 待补：购买跳转、套餐页 T3-W2、额度不足引导动画。

## 3.3 T3-W1/T3-S5 观众端首版

- `GET /api/v1/rooms`：公开房间列表（roomId/state/viewers/pinRequired/playUrl），过滤 closed/failed；
- `GET /rooms`：观众端 Web 列表页，5s 轮询，点击进入复用 `/s/{room}` 播放页；
- 待补：进入权限/观看限制、账号登录、列表分页/搜索、App 观众端 T3-A7。

## 3.4 T3-A5 日志导出/脱敏首版

- `LogRedactor`：移除 JWT/token query、PIN、明文 roomId；
- 设置页「诊断日志 → 导出脱敏日志」系统分享；
- `lan-core` 新增 `LogRedactorTest`；
- 待补：导出文件/邮件模板、服务端留存样本抽检。

## 3.5 T3-W2 账号/套餐页首版

- `GET /account`：Web 登录/注册、套餐/额度展示、套餐切换；
- 复用 `/api/v1/auth/*`、`/account`、`/account/plan`、`/account/usage`；
- 支付链路未接（额度演示）；待补 T3-W2 购买/对账。

## 3.6 T3-S3 BYO 服务端首版

- `GET/PUT /api/v1/account/byo`：账号自定义 `whipHost/playHost/rtmpHost`；
- 房间创建优先使用账号 BYO host（playUrl/whipUrl/rtmpUrl），否则回落托管配置；
- 校验 http(s)/rtmp；`mode=managed|byo`；
- 待补：T3-A4 App BYO 配置/连通性测试、回源校验、能力降级提示。

## 3.7 T3-A4 BYO 客户端首版

- 设置页「BYO 自定义地址」：WHIP/Play/RTMP host 保存、读取；
- 账号登录后自动拉取 BYO；保存后 WAN 房间 URL 按 BYO 生成；
- 「测试连通性」：HTTP(S) GET / RTMP TCP connect，输出 `WHIP=OK · Play=OK · RTMP=FAIL` 形式；
- 待补：真实 WHIP 回源推流验证、能力降级/错误细分。

## 3.8 T3-A7 App 观众端首版

- 设置页「观众端」按钮：WebView 打开 `${cloudApiHost}/rooms`；
- 复用 Web 观众端列表与 `/s/{room}` 播放页（WHEP/FLV/HLS、PIN）；
- 返回键优先 WebView 历史；离开销毁 WebView；
- 待补：原生列表/播放、账号态与观看限制提示。

## 3.9 T3-A6 App 多路 RTMP 首版

- 设置页 RTMP 旁路支持多行，最多 3 路；
- 每路独立 `AsyncRtmpPublisher`；单路连接/发送失败不影响 LAN 主链路与其他路；
- App 端多路 E2E 需 SRS/RTMP 环境；服务端多 ingest 会话（T3-S4）未做。

## 3.10 T3-S4 服务端多 ingest 地址首版

- `GET /api/v1/rooms/{id}/addresses`：返回 WHIP/RTMP/SRT 三路地址、ingest token、状态（idle/active）；
- 支持账号 BYO host 覆盖；关闭房间 `410/2002`；
- 真实多路媒体会话/每路独立失败隔离仍由接入网关/SRS 承担，本接口为控制面骨架。

## 3.11 T3-S8 合规钩子首版

- `GET /api/v1/compliance/policy`：举报分类、留存策略、封禁效果；
- `POST /api/v1/rooms/{id}/report`：举报房间（abuse/illegal/privacy/other）；
- `GET /api/v1/compliance/reports`：管理员查看（Bearer admin token）；
- `POST /api/v1/compliance/rooms/{id}/suspend`：审核封禁，媒体统一 410；
- 待补：审核回调/工单系统、举报限流、留存任务。

## 3.12 T2-A7 本地录制首版

- 设置页「本地录制 MP4」开关；推流同时把已编码 H264/AAC 写入 MP4；
- 不新增相机/编码器，录制失败只记日志，不影响推流；
- 输出：`/data/data/<pkg>/files/recordings/campush-<ts>.mp4`；
- 提取：`adb pull /data/data/com.campush.app/files/recordings/`（debug）；
- 待补：水印、录制中状态/文件列表、MediaStore 导出。

## 3.13 可观测性看板 + 播放水印首版

- `GET /metrics`：房间数、首帧样本、协议切换、首帧分位、原始指标，5s 刷新；
- 播放页右下角水印，默认 `CamPush`，支持 `?wm=` 覆盖；
- 说明：水印为播放页覆盖层，尚未烧录进录制/转码流。

## 3.13.1 单摄直播 GL 水印（实验，未验收）

- 已加入 Camera2→SurfaceTexture→GL→MediaRecorder 管线与设置开关；
- E2E 验证发现模拟器输出分块异常、水印未稳定出现，**暂不宣称完成**；
- 需要继续修 EGL/SurfaceTexture 变换与 MediaRecorder 记录链路。

## 3.14 T3-A2 合成编码首版

- `DualPipActivity` 支持 PiP/左右/上下分屏、主次交换；
- 「开始合成录制」：渲染到 MediaRecorder Surface，输出 H264 MP4（1280×720/30fps）；
- 合成画面右下角烧录 `CamPush` 水印（OpenGL 纹理叠加）；
- 文件：应用外部目录 `dual-<ts>.mp4`；
- 说明：合成录制为独立实验能力；单摄直播流的水印烧录仍需新 GL 管线。

- `DualPipActivity` 新增「合成」按钮：PiP → 左右分屏 → 上下分屏；
- 「交换主次」交换主画面/PiP 源；
- 仅 OpenGL 预览（D-3 仍实验性），未接入编码/推流；正式合成编码待 D-3。

## 3.15 播放页举报入口

- 播放页「举报」按钮：选择 abuse/illegal/privacy/other，POST `/api/v1/rooms/{id}/report`；
- 观众端列表提示进入后可举报；
- 审核列表/封禁仍由管理员 API 承担。

## 4. 首版账号 API（已落地）

```text
POST /api/v1/auth/register  {email,password,deviceId?} -> 201 {accountId,token,plan,roomLimit}
POST /api/v1/auth/login     {email,password,deviceId?} -> 200 {accountId,token,plan}
POST /api/v1/devices/bind   {deviceId} (Bearer) -> 200 {bound}
GET  /api/v1/account        (Bearer) -> plan/devices/roomLimit
POST /api/v1/rooms          (Bearer account token) -> 按 plan 限额；超限 429/2004
```

- `free` 1 房间、`pro` 10、`proplus` 50；
- 设备绑定上限 2；密码 PBKDF2 待替换为生产级哈希（当前骨架）。
