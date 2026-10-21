package com.campush.lan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class RtmpIngestServerTest {

    private var server: RtmpIngestServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private class FakePublisher : LanMediaPublisher {
        val h264 = mutableListOf<H264AccessUnit>()
        override fun publishJpeg(jpeg: ByteArray) = Unit
        override fun publishH264(unit: H264AccessUnit) { h264 += unit }
        override fun publishAac(unit: AacAccessUnit) = Unit
        override fun stats(): MediaStats = MediaStats()
        override fun requestKeyFrame() = Unit
    }

    @Test
    fun acceptsPublishAndFeedsH264ToHub() {
        val publisher = FakePublisher()
        val rtmp = RtmpIngestServer(publisher)
        server = rtmp
        val port = rtmp.start(0, "127.0.0.1")

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            val out = DataOutputStream(socket.getOutputStream())

            // simple handshake
            out.writeByte(3)
            out.write(ByteArray(1536))
            out.flush()
            input.readNBytes(1)
            input.readNBytes(1536)
            input.readNBytes(1536)
            out.write(ByteArray(1536))
            out.flush()

            sendMessage(out, 20, 0, 0, amfCommand("connect", 1.0, amfObject(mapOf("app" to "live"))))
            sendMessage(out, 20, 0, 0, amfCommand("createStream", 2.0))
            sendMessage(out, 20, 0, 1, amfCommand("publish", 3.0, amfNull(), amfString("stream")))
            // AVC sequence header (SPS/PPS)
            val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
            val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
            val config = ByteArrayOutputStream().apply {
                write(byteArrayOf(1, 0x42, 0x00, 0x1F, 0xFF.toByte(), 0xE1.toByte()))
                write(byteArrayOf(0, sps.size.toByte()))
                write(sps)
                write(byteArrayOf(1, 0, pps.size.toByte()))
                write(pps)
            }.toByteArray()
            val seq = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x17, 0x00, 0, 0, 0))
                write(config)
            }.toByteArray()
            sendMessage(out, 9, 1, 0, seq)

            val nal = byteArrayOf(0x65, 1, 2, 3)
            val frame = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x17, 0x01, 0, 0, 0))
                write(byteArrayOf(0, 0, 0, nal.size.toByte()))
                write(nal)
            }.toByteArray()
            sendMessage(out, 9, 1, 0, frame)

            val deadline = System.currentTimeMillis() + 3000
            while (publisher.h264.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }

        assertTrue("expected h264 publish", publisher.h264.isNotEmpty())
        assertEquals(1, publisher.h264.size)
        assertTrue(publisher.h264[0].isKeyFrame)
        assertEquals(1, publisher.h264[0].nalUnits.size)
    }

    private fun sendMessage(out: DataOutputStream, type: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
        val csid = 3
        out.writeByte(0x03)
        out.writeByte((timestamp ushr 16) and 0xff)
        out.writeByte((timestamp ushr 8) and 0xff)
        out.writeByte(timestamp and 0xff)
        out.writeByte((payload.size ushr 16) and 0xff)
        out.writeByte((payload.size ushr 8) and 0xff)
        out.writeByte(payload.size and 0xff)
        out.writeByte(type)
        out.writeByte(streamId and 0xff)
        out.writeByte((streamId ushr 8) and 0xff)
        out.writeByte((streamId ushr 16) and 0xff)
        out.writeByte((streamId ushr 24) and 0xff)
        out.write(payload)
        out.flush()
    }

    private fun amfCommand(name: String, tx: Double, vararg args: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(amfString(name))
        out.write(amfNumber(tx))
        args.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun amfString(s: String): ByteArray {
        val b = s.toByteArray()
        return byteArrayOf(0x02, (b.size ushr 8).toByte(), b.size.toByte()) + b
    }

    private fun amfNumber(d: Double): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        val bits = java.lang.Double.doubleToLongBits(d)
        for (i in 7 downTo 0) out.write(((bits ushr (i * 8)) and 0xff).toInt())
        return out.toByteArray()
    }

    private fun amfNull(): ByteArray = byteArrayOf(0x05)

    private fun amfObject(map: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        map.forEach { (k, v) ->
            val key = k.toByteArray()
            out.write((key.size ushr 8) and 0xff)
            out.write(key.size and 0xff)
            out.write(key)
            out.write(amfString(v))
        }
        out.write(byteArrayOf(0x00, 0x00, 0x09))
        return out.toByteArray()
    }
}
