# 24 · P4 Android 功能收口与回测状态

| 项 | 值 |
|---|---|
| 状态 | Android 功能开发收口；回测环境/真机验收待外部资源 |
| 日期 | 2026-10-22 |
| 依据 | docs/23、P0/P1 清单、回测脚本 |

## 1. 功能状态

| 功能 | 状态 | 证据 |
|---|---|---|
| WAN 前台服务/后台 | ✅ | `WanStreamingService` |
| WAN 相机切换/闪光灯/静音 | ✅ | WanWhipClient + 主界面按钮 |
| LAN 直播烧录水印 | ✅ | YUV 管线，FLV 抽帧 2213 亮像素 |
| 本地录制 MP4 | ✅ | 9.12s/12MB，H264+AAC，带水印 |
| 录制列表/分享/删除 | ✅ | FileProvider + 设置页 |
| WAN 默认/网络推荐/错误映射 | ✅ | 默认 WAN、3002/7002/7003 |
| WAN 房间控制面 | ✅ | `wan_room_e2e.py` |
| WAN 水印 | ✅ 代码 | VideoProcessor I420；E2E 待媒体环境 |
| 双摄合成直播 | ✅ | `dual_live_e2e.py` 9.54s/4.1MB |
| 双摄合成水印 | ❌ 未接入 | GL 覆盖未进入编码输出；D-3 实验项 |
| 原生观众端列表 | ✅ | AudienceListActivity |
| 原生 WHEP 播放器 | ✅ 代码 | WanPlayerActivity；E2E 待媒体环境 |
| 多地址 UI | ✅ | WAN 设置页 |

## 2. 自动回测

```bash
tools/regression/run-all.sh
```
实测 `ALL ANDROID REGRESSION OK`：
构建/单测/文档 → 启动 → LAN 推流/水印/录制 → WAN 房间 → 双摄合成。

## 3. 外部阻塞

1. WAN 媒体 E2E：需 SRS/Cloudflare Realtime TURN 或公网媒体服务；
2. 真机矩阵：需 Android 真机（H264 硬编、后摄、后台/弱网）；
3. 双摄合成水印：D-3 实验项，GL 叠加未进入编码，需 CPU 解码重编码或 MediaCodec 链路。

## 4. 结论

Android P0/P1 除上述 3 项外均已落地，模拟器自动回测全绿；下一阶段是外部环境/真机验收，而非继续堆功能。
