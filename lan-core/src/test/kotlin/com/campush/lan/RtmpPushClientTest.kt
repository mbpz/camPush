package com.campush.lan

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpPushClientTest {

    private var server: RtmpIngestServer? = null

    @After
    fun tearDown() {
        server?.stop()
        client?.close()
    }

    private var client: RtmpPushClient? = null

    private class FakePublisher : LanMediaPublisher {
        val h264 = mutableListOf<H264AccessUnit>()
        val aac = mutableListOf<AacAccessUnit>()
        override fun publishJpeg(jpeg: ByteArray) = Unit
        override fun publishH264(unit: H264AccessUnit) { h264 += unit }
        override fun publishAac(unit: AacAccessUnit) { aac += unit }
        override fun stats(): MediaStats = MediaStats()
        override fun requestKeyFrame() = Unit
    }

    @Test
    fun pushesH264AndAacToIngest() {
        val publisher = FakePublisher()
        val rtmp = RtmpIngestServer(publisher)
        server = rtmp
        val port = rtmp.start(0, "127.0.0.1")

        val c = RtmpPushClient("rtmp://127.0.0.1:$port/live/test")
        client = c
        c.connect()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        c.sendVideo(
            H264AccessUnit(listOf(byteArrayOf(0x65, 1, 2, 3)), 0L, true, sps, pps),
        )
        c.sendAudio(
            AacAccessUnit(byteArrayOf(0x11, 0x90.toByte()), 0L, isSequenceHeader = true),
        )

        val deadline = System.currentTimeMillis() + 3_000
        while ((publisher.h264.isEmpty() || publisher.aac.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("h264=${publisher.h264.size} aac=${publisher.aac.size}", publisher.h264.isNotEmpty())
        assertTrue("aac=${publisher.aac.size}", publisher.aac.isNotEmpty())
        assertTrue(publisher.h264[0].isKeyFrame)
    }
}
