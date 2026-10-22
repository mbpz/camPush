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
| 2 | T3-S2 | 套餐/额度/计费（free/pro/proplus、房间分钟数、2004） | 待做 |
| 3 | T3-A3 | App 登录/套餐入口/额度展示 | 待做 |
| 4 | T3-W1/T3-S5 | 观众端 Web + 房间列表/进入权限 API | 待做 |
| 5 | T3-S3/T3-A4 | BYO 自定义地址/连通性测试 | 待做 |
| 6 | T3-A1/T3-A2 | 双路独立/高级合成（受 D-3 实验性约束） | 待做 |
| 7 | T3-A5 | 日志导出与脱敏 | 待做 |
| 8 | 合规/灰度/压测 | T3-S8/T3-Q2/Q3/P4 等 | 待做 |

## 3. 当前约束

- 本地 Docker/SRS 已清理；本轮先完成**纯 JVM/Android 代码与单测**，不依赖媒体环境；
- 真机/公网验收按 P2 边界处理：开发完成≠G3 通过；
- D-3 双摄仍实验性；T3-A1 双路独立按 D-3 结论延后/裁剪。

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
