package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaHubTest {

    private var now = 1_700_000_000_000L
    private val hub = MediaHub { now }
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    private fun publishVideo(isKeyFrame: Boolean, ptsUs: Long, payloadSize: Int = 128) {
        hub.publishH264(
            H264AccessUnit(
                nalUnits = listOf(ByteArray(payloadSize) { if (isKeyFrame) 0x65 else 0x41 }),
                ptsUs = ptsUs,
                isKeyFrame = isKeyFrame,
                sps = if (isKeyFrame) sps else null,
                pps = if (isKeyFrame) pps else null,
            ),
        )
    }

    @Test
    fun statsFpsBitrateAndTimestamps() {
        repeat(90) { index ->
            publishVideo(isKeyFrame = index % 30 == 0, ptsUs = index * 33_000L)
            now += 33L
        }
        val stats = hub.stats()
        assertTrue("fps=${stats.fps}", stats.fps in 28..31)
        assertTrue("bitrate=${stats.bitrateBps}", stats.bitrateBps > 0)
        assertEquals(now - 33L, stats.lastVideoAtMillis)
    }

    @Test
    fun aacStatsAndLatestJpeg() {
        hub.publishAac(AacAccessUnit(frame = byteArrayOf(0x11, 0x90.toByte()), ptsUs = 0, isSequenceHeader = true))
        hub.publishAac(AacAccessUnit(frame = byteArrayOf(1, 2, 3), ptsUs = 33_000L))
        assertEquals(now, hub.stats().lastAudioAtMillis)

        assertNull(hub.latestJpegVersioned())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        hub.publishJpeg(jpeg)
        val first = hub.latestJpegVersioned()
        assertNotNull(first)
        assertTrue(first!!.second.contentEquals(jpeg))
        hub.publishJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9))
        val second = hub.latestJpegVersioned()!!
        assertTrue(second.first > first.first)
        assertTrue(second.second.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9)))
    }

    @Test
    fun flvSubscriptionStartsWithKeyframeAndSequenceHeaders() {
        publishVideo(true, 0L)
        repeat(4) { index -> publishVideo(false, (index + 1) * 33_000L) }
        hub.publishAac(AacAccessUnit(frame = byteArrayOf(0x12, 0x10), ptsUs = 0, isSequenceHeader = true))
        hub.publishAac(AacAccessUnit(frame = byteArrayOf(1, 2, 3), ptsUs = 33_000L))

        val subscription = hub.openFlv()
        val initial = subscription.initialBytes()
        assertTrue(initial.size > 13)
        assertEquals('F'.code.toByte(), initial[0])
        val tags = FlvParser.parseTags(initial)
        assertTrue(tags.isNotEmpty())
        assertTrue("first tag should be onMetaData script", tags[0].isScript())
        assertTrue("video sequence header must be present", tags.any { it.isVideo() && it.avcPacketType() == 0 })
        assertTrue("audio sequence header must be present", tags.any { it.isAudio() && it.aacPacketType() == 0 })
        val videoTags = tags.filter { it.isVideo() && it.avcPacketType() == 1 }
        assertTrue(videoTags.isNotEmpty())
        assertTrue("起播首个视频帧必须是关键帧", videoTags.first().videoFrameType() == 1)
        assertTrue("应含 4 个非关键帧", videoTags.count { it.videoFrameType() == 2 } == 4)

        // 无新帧时短超时返回 null（keepalive 阈值为 5 s）
        assertNull(subscription.poll(50L))
    }

    @Test
    fun slowSubscriberJumpsToLatestKeyframeAfterEviction() {
        publishVideo(true, 0L)
        repeat(4) { index -> publishVideo(false, (index + 1) * 33_000L) }
        val subscription = hub.openFlv()
        subscription.initialBytes()

        // 时间戳推进 > 6 s（多个 GOP），旧 GOP 被整段淘汰
        var pts = 2_000_000L
        repeat(10) {
            publishVideo(true, pts)
            publishVideo(false, pts + 33_000L)
            pts += 2_000_000L
            now += 2_500L
        }

        val chunk = subscription.poll(1_000L)
        assertNotNull("追帧应返回数据", chunk)
        // 追帧 chunk 不含 FLV header，从 0 开始解析
        val tags = FlvParser.parseTags(chunk!!, from = 0)
        assertTrue("追帧先重发序列头", tags[0].isVideo() && tags[0].avcPacketType() == 0)
        val firstVideo = tags.first { it.isVideo() && it.avcPacketType() == 1 }
        assertEquals("追帧必须跳到关键帧", 1, firstVideo.videoFrameType())
    }

    @Test
    fun publishAfterCloseIsIgnoredUntilReopen() {
        hub.close()
        publishVideo(true, 0L)
        assertNull(hub.openFlv().initialBytes().let { bytes ->
            if (FlvParser.parseTags(bytes).any { it.isVideo() && it.avcPacketType() == 1 }) bytes else null
        })
        hub.reopen()
        publishVideo(true, 0L)
        val tags = FlvParser.parseTags(hub.openFlv().initialBytes())
        assertTrue(tags.any { it.isVideo() && it.avcPacketType() == 1 && it.videoFrameType() == 1 })
    }

    @Test
    fun gopRingBufferBoundedByDuration() {
        var pts = 0L
        repeat(12) {
            publishVideo(true, pts)
            repeat(2) { index -> publishVideo(false, pts + (index + 1) * 33_000L) }
            pts += 2_000_000L
            now += 2_000L
        }
        // 新的订阅者只能看到最近约 6 s 的 GOP（3 个 GOP 左右，而非全部 12 个）
        val tags = FlvParser.parseTags(hub.openFlv().initialBytes())
        val keyframes = tags.count { it.isVideo() && it.avcPacketType() == 1 && it.videoFrameType() == 1 }
        assertTrue("keyframes=$keyframes", keyframes in 1..4)
    }
}
