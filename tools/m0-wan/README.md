# M0-WAN 本地验证栈

P2 的 M0-WAN 门禁（E2/E4/E5）开发环境。**仅本地验证**，不代表生产部署。

## 启动

```bash
cd tools/m0-wan
export CANDIDATE="$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1)"   # Mac Wi-Fi IP
docker compose up -d
curl -s http://127.0.0.1:1985/api/v1/versions
```

## RTMP 推流 → FLV/HLS 验证

```bash
# 推本地测试源（容器内运行，host.docker.internal 指向 Mac）
docker run --rm --network host linuxserver/ffmpeg \
  -re -f lavfi -i testsrc=size=1280x720:rate=30 \
  -c:v libx264 -preset veryfast -tune zerolatency -g 60 -b:v 2500k \
  -f flv rtmp://127.0.0.1:1935/live/campush

# 播放
ffplay http://127.0.0.1:18080/live/campush.flv
ffplay http://127.0.0.1:18080/live/campush.m3u8
```

## WHIP/WHEP（SRS 内置）

- 发布页：<http://127.0.0.1:18080/players/whip.html?app=live&stream=campush>
- 播放页：<http://127.0.0.1:18080/players/whep.html?app=live&stream=campush>
- 需要 `CANDIDATE` 指向本机局域网 IP，且 8000/udp 可访问；容器已映射 8000 udp/tcp。
- SRS 自带 WHIP/WHEP 仅用于 M0-WAN 验证；生产按文档接 Mediasoup/自建网关。

## 当前状态

- SRS 6 容器启动、HTTP API 200（已验证）；
- 本机 ffmpeg 测试源验证：HTTP-FLV 200（约 894 KB/4s）、HLS master 200 且切片生成；
- WHIP/WHEP 需要 Mediasoup（下一步），SRS 仅作 RTMP/FLV/HLS 终结；
- TURN 容器需要 Docker Hub 凭据（当前 macOS OrbStack 凭据弹窗导致拉取失败，待解决或用已有 coturn 镜像）。
