# 23 · Android 功能收口与回测方案（P4）

| 项 | 值 |
|---|---|
| 状态 | 执行中 |
| 日期 | 2026-10-22 |
| 决策 | ① Android 功能先全部收口；② 后续重心转回测/验证；③ 免费云资源优先 Cloudflare + Oracle/国内试用 |

## 1. Android 功能收口清单

### P0（必须先完成）

| 项 | 现状 | 收口标准 |
|---|---|---|
| WAN 前台服务/后台稳定 | ✅ 首版：`WanStreamingService` camera\|microphone 前台服务，WAN 开始即启动、停止/失败回收 | 退后台不断流（待真机） |
| WAN 摄像头切换/闪光灯/静音 | ✅ 首版：WanWhipClient 前后摄切换、后摄闪光灯、AudioTrack 静音；主界面按钮在 WAN 下生效 | 真机验证 |
| 单摄直播烧录水印 | ✅ LAN 首版：启用后走 CameraX YUV 兼容管线，在 YUV 输入前烧录 CamPush；FLV 抽帧实测可见（2026-10-22）。WAN/WebRTC 水印待做 | 真机性能验证 |
| 本地录制文件管理 | ✅ 首版：设置页录制列表、大小/时间、分享（FileProvider）、删除；文件仍在应用私有目录 | 真机验证分享 |
| WAN 默认/网络推荐 | ✅ 首版：已配置 Cloud API 默认 WAN；记住上次 LAN/WAN；非 LAN 启动提示配置 WAN | 真机验证 |
| 错误与回退 | ✅ 首版：WAN 鉴权/连接/其他错误统一 3002/7002/7001；LAN 非局域网提示 WAN 引导 | 真机验证 |

### P1（专业能力）

- WAN/WebRTC 烧录水印：✅ 首版（VideoProcessor I420 叠加 CamPush，待媒体环境 E2E）；
- 双摄 PiP 合成直播：✅ 首版（PiP/分屏合成 → MediaRecorder TS → TsDemuxer → RtmpPushClient，使用设置页首个 RTMP 地址；E2E 待 RTMP 环境）；
- 多地址配置 UI：✅ 首版（WAN 房间 WHIP/RTMP/SRT 地址列表、复制、刷新）；
- App 观众端原生列表：✅ 首版（原生房间列表，点击进 WebView 播放页）；
- 录制水印（双摄合成已烧录，单摄随水印方案）。

### P2（打磨）

- 账号/套餐商业化按用户决策**暂不做**；
- 多语言/A11y、帮助中心、灰度开关。

## 2. 回测/验证阶段

| 层级 | 内容 | 工具 |
|---|---|---|
| 单元/契约 | lan-core、cloud-core、redactor、player selftest | Gradle、node selftest |
| 构建 | debug/release APK、CI | GitHub Actions |
| 模拟器回归 | 启动、LAN 推流、录制、WAN 创建/停止、观众端 | adb + uiautomator 脚本 |
| 媒体回归 | WHIP/WHEP 首帧、FLV/HLS、TURN relay、停止 410 | SRS/coturn 环境 |
| 真机矩阵 | Android 8–15、前后摄、后台/锁屏、弱网 | 真机/云真机 |
| 性能 | 首帧、fps、码率、CPU/温度、30min 稳定 | 指标看板/日志 |

## 3. 免费云资源结论（2026-10 调研）

| 资源 | 免费额度 | 适用 |
|---|---|---|
| **Cloudflare Realtime TURN** | 1,000 GB/月出站免费；超出 $0.05/GB；STUN 免费不限 | **公网 TURN 首选**，免自建 |
| Cloudflare Workers/D1/R2/Pages | 免费档（请求/存储有限额） | 控制面/观众页/录制存储；不能跑 SRS/媒体 |
| Oracle Cloud Always Free | ARM 4 OCPU/24GB、公网 IPv4、10TB/月出站 | **长期自建 SRS+coturn+cloud-core 首选** |
| 腾讯云 CVM 试用 | 4核8G 5M 7 天 / 标准型 1 个月 | 短期 E2E、压测 |
| 阿里云 ECS 试用 | 300/660 元额度，最长 3 个月 | 较长回测窗口 |
| 华为云 Flexus/ECS | 2核2G/4G 1 个月 | 轻量回测 |
| LiveKit Cloud / Metered TURN | 有免费/试用档，额度以官网为准 | TURN/SFU 备选 |

> Cloudflare TURN 官方 FAQ（2026-07）：免费 1,000 GB/月，按 TURN 出站流量计费；STUN `stun.cloudflare.com` 免费不限。
> https://developers.cloudflare.com/realtime/turn/faq/
> Workers 限额：https://developers.cloudflare.com/workers/platform/limits/
> Oracle 免费层：https://www.oracle.com/cloud/free/
> 国内试用参考：https://cloud.tencent.com.cn/developer/article/2659144

## 4. 推荐组合

1. **控制面/观众页**：Cloudflare Workers + D1 + R2（或现有 cloud-core 跑 Oracle）；
2. **公网 TURN**：Cloudflare Realtime TURN（1TB/月免费）；
3. **媒体服务器**：Oracle Always Free ARM（SRS+coturn），或腾讯云 7 天试用做首次 E2E；
4. **回测环境**：腾讯云 4C8G 7 天做并发/首帧，阿里云额度做长周期回归；
5. 不再依赖本机 Docker/OrbStack 做公网验收。
