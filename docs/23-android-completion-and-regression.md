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
| WAN 前台服务/后台稳定 | WAN 推流在 Activity/Controller 内，退后台可能停 | 独立 ForegroundService + 通知；退后台不断流 |
| WAN 摄像头切换/闪光灯/静音 | 目前前摄固定，能力不完整 | 与 LAN 一致的切换/闪光灯/静音 |
| 单摄直播烧录水印 | GL 输出正确，但 FLV 不反映 GL；当前阻塞 | 采用 CameraX YUV+Canvas 管线或编码后处理，FLV 抽帧可见水印 |
| 本地录制文件管理 | 录制文件在私有目录，无列表/分享 | 列表、播放、分享/导出（MediaStore） |
| WAN 默认/网络推荐 | 默认仍 LAN，需手动配置 | 首次启动/蜂窝网络推荐 WAN，未配置引导 |
| 错误与回退 | 部分失败无 UI 引导 | 3002/3003/6003/7001 等统一提示与重试 |

### P1（专业能力）

- 双摄 PiP 合成直播（D-3 实验性，先保留预览+MP4）；
- 多地址配置 UI（已有多路 RTMP 与地址 API，补可视化）；
- App 观众端原生列表/播放（当前 WebView）；
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
