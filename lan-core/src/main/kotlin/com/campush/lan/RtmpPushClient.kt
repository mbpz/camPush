package com.campush.lan

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * 最小 RTMP 推流客户端（P2 D-4 客户端侧）。
 *
 * 仅实现发布所需子集：简单握手、AMF0 connect/createStream/publish、
 * H.264(AVCC) / AAC 的 FLV tag 发送。供 Android 侧复用，不依赖 Android。
 */
class RtmpPushClient(
    private val rtmpUrl: String,
    private val connectTimeoutMs: Int = 5_000,
) {
    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var chunkSize = 128
    private var spsSent: ByteArray? = null
    private var ppsSent: ByteArray? = null
    private var aacConfigSent = false
    private var connected = false

    val isConnected: Boolean get() = connected

    fun connect() {
        if (connected) return
        val uri = URI(rtmpUrl)
        val host = uri.host ?: throw IllegalArgumentException("bad rtmp url")
        val port = if (uri.port > 0) uri.port else 1935
        val app = uri.path.trim('/').substringBefore('/')
        val stream = uri.path.trim('/').substringAfter('/', "")
        if (app.isEmpty() || stream.isEmpty()) throw IllegalArgumentException("rtmp url must be rtmp://host/app/stream")

        socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = connectTimeoutMs
        input = DataInputStream(socket.getInputStream().buffered())
        output = DataOutputStream(socket.getOutputStream().buffered())
        handshake()
        sendAmf(streamId = 0, command("connect", 1.0, objectValue(
            "app" to app, "type" to "nonprivate", "flashVer" to "FMLE/3.0",
        )))
        sendAmf(streamId = 0, command("createStream", 2.0))
        sendAmf(streamId = 1, command("publish", 3.0, nullValue(), stringValue(stream)))
        connected = true
    }

    fun sendVideo(unit: H264AccessUnit, timestampMs: Long = unit.ptsUs / 1000) {
        val sps = unit.sps
        val pps = unit.pps
        if (sps != null && pps != null && (!sps.contentEquals(spsSent ?: ByteArray(0)) || !pps.contentEquals(ppsSent ?: ByteArray(0)))) {
            val cfg = avcConfig(sps, pps)
            sendMessage(9, timestampMs.toInt(), ByteArrayOutputStream().apply {
                write(byteArrayOf(0x17, 0x00, 0, 0, 0))
                write(cfg)
            }.toByteArray())
            spsSent = sps.copyOf(); ppsSent = pps.copyOf()
        }
        val body = ByteArrayOutputStream()
        body.write(if (unit.isKeyFrame) 0x17 else 0x27)
        body.write(0x01)
        body.write(byteArrayOf(0, 0, 0))
        unit.nalUnits.forEach { nal ->
            val len = nal.size
            body.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            body.write(nal)
        }
        sendMessage(9, timestampMs.toInt(), body.toByteArray())
    }

    fun sendAudio(unit: AacAccessUnit, timestampMs: Long = unit.ptsUs / 1000) {
        val body = ByteArrayOutputStream()
        body.write(0xAF)
        body.write(if (unit.isSequenceHeader) 0x00 else 0x01)
        body.write(unit.frame)
        sendMessage(8, timestampMs.toInt(), body.toByteArray())
        if (unit.isSequenceHeader) aacConfigSent = true
    }

    fun close() {
        connected = false
        runCatching { sendAmf(0, command("deleteStream", 0.0, numberValue(1.0))) }
        runCatching { socket.close() }
    }

    private fun handshake() {
        output.writeByte(3)
        output.write(ByteArray(1536))
        output.flush()
        val s0 = input.readUnsignedByte()
        if (s0 != 3) throw IllegalStateException("bad RTMP version $s0")
        val s1 = input.readN(1536)
        input.readN(1536)
        output.write(s1)
        output.flush()
    }

    private fun sendAmf(streamId: Int, payload: ByteArray) = sendMessage(20, 0, payload, streamId)

    private fun sendMessage(type: Int, timestamp: Int, payload: ByteArray, streamId: Int = 1) {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        out.write((timestamp ushr 16) and 0xff); out.write((timestamp ushr 8) and 0xff); out.write(timestamp and 0xff)
        out.write((payload.size ushr 16) and 0xff); out.write((payload.size ushr 8) and 0xff); out.write(payload.size and 0xff)
        out.write(type)
        out.write(streamId and 0xff); out.write((streamId ushr 8) and 0xff)
        out.write((streamId ushr 16) and 0xff); out.write((streamId ushr 24) and 0xff)
        var off = 0
        while (off < payload.size) {
            val n = minOf(chunkSize, payload.size - off)
            out.write(payload, off, n)
            off += n
            if (off < payload.size) out.write(0xC3)
        }
        synchronized(output) {
            output.write(out.toByteArray())
            output.flush()
        }
    }

    private fun avcConfig(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write(sps[1].toInt() and 0xff)
        out.write(sps[2].toInt() and 0xff)
        out.write(sps[3].toInt() and 0xff)
        out.write(0xFF)
        out.write(0xE1)
        out.write((sps.size ushr 8) and 0xff); out.write(sps.size and 0xff)
        out.write(sps)
        out.write(1)
        out.write((pps.size ushr 8) and 0xff); out.write(pps.size and 0xff)
        out.write(pps)
        return out.toByteArray()
    }

    private fun command(name: String, tx: Double, vararg args: AmfValue): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, name); writeNumber(out, tx)
        args.forEach { writeValue(out, it) }
        return out.toByteArray()
    }

    private sealed class AmfValue
    private class StringValue(val v: String) : AmfValue()
    private class NumberValue(val v: Double) : AmfValue()
    private class NullValue : AmfValue()
    private class ObjectValue(val map: Map<String, AmfValue>) : AmfValue()

    private fun stringValue(s: String) = StringValue(s)
    private fun numberValue(d: Double) = NumberValue(d)
    private fun nullValue() = NullValue()
    private fun objectValue(vararg pairs: Pair<String, Any>): ObjectValue =
        ObjectValue(pairs.associate { (k, v) ->
            k to when (v) {
                is String -> StringValue(v)
                is Double -> NumberValue(v)
                is Int -> NumberValue(v.toDouble())
                else -> StringValue(v.toString())
            }
        })

    private fun writeValue(out: ByteArrayOutputStream, v: AmfValue) {
        when (v) {
            is StringValue -> writeString(out, v.v)
            is NumberValue -> writeNumber(out, v.v)
            is NullValue -> out.write(0x05)
            is ObjectValue -> {
                out.write(0x03)
                v.map.forEach { (k, value) ->
                    val key = k.toByteArray()
                    out.write((key.size ushr 8) and 0xff); out.write(key.size and 0xff); out.write(key)
                    writeValue(out, value)
                }
                out.write(0x00); out.write(0x00); out.write(0x09)
            }
        }
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray()
        out.write(0x02); out.write((b.size ushr 8) and 0xff); out.write(b.size and 0xff); out.write(b)
    }

    private fun writeNumber(out: ByteArrayOutputStream, d: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToLongBits(d)
        for (i in 7 downTo 0) out.write(((bits ushr (i * 8)) and 0xff).toInt())
    }

    private fun DataInputStream.readN(n: Int): ByteArray = ByteArray(n).also { readFully(it) }
}
