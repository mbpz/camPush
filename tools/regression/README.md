# 回测矩阵（Android）

## 自动冒烟
```bash
tools/regression/run-android-smoke.sh
```
覆盖：lan-core/cloud-core 单测、APK 构建、文档门禁、模拟器启动、主界面「开始推流」可见。

## LAN 推流 E2E
```bash
python3 tools/regression/lan_e2e.py
```
覆盖：安装/授权、开始推流、读取 FLV（2MB）、停止推流；实测通过 `LAN E2E OK: start/FLV/stop`。

## 手工/环境矩阵

| 场景 | 步骤 | 期望 |
|---|---|---|
| LAN 推流 | Wi-Fi，开始推流，浏览器开地址 | H264/FLV 可播，停止 ≤1s |
| WAN 推流 | 配置 Cloud API，创建房间 | WHEP 可播，停止房间 410 |
| 前台服务 | WAN 推流后 Home/锁屏 5min | 不断流，通知常驻 |
| 相机控制 | WAN 切前后摄/闪光灯/静音 | 画面/音频切换生效 |
| 录制 | 开启录制推流 30s | MP4 可播放，列表可分享/删除 |
| 水印 | 开启直播水印，FLV 抽帧 | 画面有水印（当前 WIP） |
| 降级 | 播放页 force=flv/hls | 可播，URL 不变 |
| TURN | 公网/蜂窝 + relay=1 | 可播 |
| 后台/锁屏 | LAN/WAN 推流退后台 | 按产品定义保持/暂停 |
| 断网恢复 | 推流中断网 10s | 明确错误/可重试 |

## 真机矩阵
Android 8–15；前/后摄；华为平板优先。
