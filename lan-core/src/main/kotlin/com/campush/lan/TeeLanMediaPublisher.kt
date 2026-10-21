package com.campush.lan

/**
 * 发布侧分流：主链路（LAN MediaHub）优先，旁路（如 RTMP 推流）失败不影响主链路。
 */
class TeeLanMediaPublisher(
    private val primary: LanMediaPublisher,
    private val secondary: LanMediaPublisher?,
) : LanMediaPublisher {

    override fun publishJpeg(jpeg: ByteArray) {
        primary.publishJpeg(jpeg)
        runCatching { secondary?.publishJpeg(jpeg) }
    }

    override fun publishH264(unit: H264AccessUnit) {
        primary.publishH264(unit)
        runCatching { secondary?.publishH264(unit) }
    }

    override fun publishAac(unit: AacAccessUnit) {
        primary.publishAac(unit)
        runCatching { secondary?.publishAac(unit) }
    }

    override fun stats(): MediaStats = primary.stats()
    override fun requestKeyFrame() = primary.requestKeyFrame()
    override fun isMjpegWanted(): Boolean = primary.isMjpegWanted()
}
