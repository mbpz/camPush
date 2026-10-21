# 18 · P2 验收状态与证据（滚动更新）

| 项 | 值 |
|---|---|
| 状态 | 滚动验收中（非最终 G2 报告） |
| 更新 | 2026-10-21 |
| 依据 | docs/09 P2 WBS、docs/15 P2 kickoff、docs/04 契约 |

> 本文件只收录**可复现证据**。未完成项明确标注阻塞条件，不写“已完成”代替证据。

## 1. Gate / 任务状态

| 任务 | 状态 | 证据 / 阻塞 |
|---|---|---|
| Top5 决策 | ✅ | docs/15 §2 |
| T0-S1 WHIP→WHEP | ✅ | `docs/15 §4.1`：WHIP publisher established；SRS WHEP `readyState=4/320x240/currentTime=7.78s`；cloud player `WHEP 已连接/readyState=4/currentTime=9.38s` |
| T0-S2 RTMP→FLV/HLS | ✅ | SRS 测试源 FLV 200/894KB/4s；HLS master 200+切片 |
| T0-A2 Android WHIP | ✅ 模拟器 E2E | 前摄 + 软件 H264：WHIP H264 180x320，SRS `Accept sync Video`；WHEP 播放 `readyState=4/4.8s/180x320`；真机硬件 H264 仍待验证 |
| T1-S1 房间 REST | ✅ 骨架 | 创建/查询/停止/play-session/status/SSE/PIN 锁定/额度/限流/关闭 410；WAN 地址复制/分享；`cloud-core` 14/14 |
| T1-W1 播放页 | ✅ | WHEP/FLV/HLS 强制兜底；页内协议切换 FLV→HLS URL 不变、readyState=4；PIN/HUD/指标；cloud 14/14 |
| T2-A4 LAN RTMP | ✅ 端侧/客户端 | ingest E2E `rtmpFrames=60`；App Tee→SRS `publish.active=true`、FLV 5.9MB |
| T0-A1 双摄探测 | ✅ 单机 | 设置页「双摄能力」；仅 1 台设备数据 |
| T0-A3 双摄 PiP | ⚠️ 实验性原型 | `DualPipActivity` 模拟器 56fps/thermal=0；设置入口按 `dualCameraSupported` 隐藏（AC-05 不支持设备无入口）；未接入推流，D-3 未达 40% |
| TURN | ✅ 本地 relay | coturn STUN/TURN 分配；播放页 `&relay=1` WHEP `readyState=4/320x240`；公网跨 NAT 待真实环境 |
| 真机 WHIP | ⛔ 阻塞 | 需华为平板安装 APK 并填 Mac 局域网 IP:1985 |
| P2 G2 正式验收 | ⛔ 未到 | 缺真机 WHIP、TURN、双摄实验结论 |

## 2. 可复现命令

```bash
# 单元/集成
scripts/build.sh :lan-core:test :cloud-core:test :app:assembleDebug
python3 scripts/check_docs.py --strict

# WHIP/WHEP（本机浏览器）
CANDIDATE=192.168.107.2 docker compose -f tools/m0-wan/docker-compose.yml up -d --force-recreate srs
# 发布页：http://127.0.0.1:18080/players/whip.html?app=live&stream=<room>
# 播放页：http://127.0.0.1:18080/players/whep.html?...
# cloud 播放页：http://127.0.0.1:18090/s/<room>?token=<pageToken>

# 端侧 RTMP ingest
adb forward tcp:19351 tcp:1935
ffmpeg -re -f lavfi -i testsrc=size=320x180:rate=15 -c:v libx264 -t 4 -f flv rtmp://127.0.0.1:19351/live/<stream>
```

## 3. 当前阻塞与建议

| 阻塞 | 影响 | 建议 |
|---|---|---|
| 无 H264 硬编真机 | Android WHIP 无法完成实测 | 华为平板安装最新 APK，设置 WHIP URL 为 `http://<Mac-IP>:1985/rtc/v1/whip/?app=live&stream=...` |
| 本地 ICE 仅容器 candidate 可用 | 跨设备/手机 ICE 不通 | 起 TURN 或提供可用公网/局域网 UDP 路径 |
| TURN 跨 NAT 未验证 | 真实弱网/蜂窝场景未覆盖 | 用手机/公网 TURN 验证；本地服务已可用 |
| 双摄机型数据不足 | T0-A3 无法达到 D-3 门槛 | 原型已跑通；收集 Top20 数据，不足则维持“实验性”并记录 |

## 4. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-10-21 | 首版滚动验收状态；记录 T0-S1 闭环、RTMP E2E、真机/TURN/双摄阻塞 |
| v1.1 | 2026-10-21 | TURN relay 通过（`&relay=1` WHEP readyState=4）；T1-S1 限流/PIN 锁定；D-3 实验性结论；剩余阻塞：真机 WHIP、公网 TURN、PiP 编码 |
