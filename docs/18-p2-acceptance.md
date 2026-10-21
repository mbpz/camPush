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
| T0-A2 Android WHIP | ⚠️ 代码完成/待真机 | 模拟器无 H264 硬编，SRS `5018`；真机需验证 |
| T1-S1 房间 REST | ✅ 骨架 | 创建/查询/停止/play-session/status/SSE/PIN/额度；`cloud-core` 10/10 |
| T1-W1 播放页 | ✅ | WHEP→FLV→HLS 三级兜底、同源代理、PIN、HUD；cloud 10/10 |
| T2-A4 LAN RTMP | ✅ 端侧/客户端 | ingest E2E `rtmpFrames=60`；App Tee→SRS `publish.active=true`、FLV 5.9MB |
| T0-A1 双摄探测 | ✅ 单机 | 设置页「双摄能力」；仅 1 台设备数据 |
| T0-A3 双摄 PiP | ⛔ 未实现 | 缺 Top20 支持率（D-3 门槛）与可用双摄真机；按决策保持实验性，待环境 |
| TURN | ⚠️ 配置完成/服务未起 | coturn 镜像拉取被 Docker 凭据阻断；play-session/播放页已带 TURN 配置 |
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
| coturn 镜像拉取失败 | TURN 无法本地验证 | 修复 Docker 凭据或使用已缓存镜像/外部 TURN |
| 双摄机型数据不足 | T0-A3 无法达到 D-3 门槛 | 收集 Top20 机型数据；不足则维持“实验性”并记录 |

## 4. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-10-21 | 首版滚动验收状态；记录 T0-S1 闭环、RTMP E2E、真机/TURN/双摄阻塞 |
