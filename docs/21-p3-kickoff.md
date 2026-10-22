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
