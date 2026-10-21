package com.campush.lan

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 最小 RTMP ingest（P2 T2-A4 / D-4）。
 *
 * 支持：简单握手、AMF0 connect/createStream/publish、H.264/AAC 发布。
 * 不包含：RTMPS、复杂鉴权、GOP cache（由 MediaHub 负责）。
 * 仅依赖 LanMediaPublisher 契约，保持 lan-core 不反向依赖 app。
 */
class RtmpIngestServer(
    private val publisher: LanMediaPublisher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap<Socket, Boolean>()
    private var acceptThread: Thread? = null

    fun start(port: Int = 1935, bindAddress: String = "0.0.0.0"): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port))
        server = s
        acceptThread = Thread({
            while (!s.isClosed) {
                try {
                    val socket = s.accept()
                    clients[socket] = true
                    Thread({ RtmpConnection(socket, publisher).run() }, "rtmp-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Throwable) {
                }
            }
        }, "rtmp-ingest").apply { isDaemon = true; start() }
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        clients.keys.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { acceptThread?.join(500) }
    }
}

private class RtmpConnection(
    private val socket: Socket,
    private val publisher: LanMediaPublisher,
) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val states = HashMap<Int, Partial>()
    private var chunkSize = 128
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var published = false

    fun run() {
        try {
            handshake()
            while (true) {
                val msg = readMessage() ?: break
                handle(msg)
            }
        } catch (_: EOFException) {
        } catch (_: Throwable) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handshake() {
        val c0 = input.readUnsignedByte()
        if (c0 != 3) throw IllegalStateException("unsupported RTMP version $c0")
        val c1 = input.readN(1536)
        val s1 = ByteArray(1536).also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 0 }
        output.writeByte(3)
        output.write(s1)
        output.write(c1)
        output.flush()
        input.readN(1536)
    }

    private fun readMessage(): Message? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val fmt = (first ushr 6) and 0x3
            var csid = first and 0x3f
            if (csid == 0) csid = input.readUnsignedByte() + 64
            else if (csid == 1) csid = input.readUnsignedShortLE() + 64

            val st = states.getOrPut(csid) { Partial() }
            when (fmt) {
                0 -> {
                    st.timestamp = input.readUInt24()
                    st.length = input.readUInt24()
                    st.type = input.readUnsignedByte()
                    st.streamId = input.readIntLE()
                    st.extended = st.timestamp == 0xFFFFFF
                    if (st.extended) st.timestamp = input.readUInt32()
                    st.pos = 0
                    st.buffer = ByteArray(st.length)
                }
                1 -> {
                    val delta = input.readUInt24()
                    st.timestamp += delta
                    st.length = input.readUInt24()
                    st.type = input.readUnsignedByte()
                    st.extended = delta == 0xFFFFFF
                    if (st.extended) st.timestamp += input.readUInt32()
                    st.pos = 0
                    st.buffer = ByteArray(st.length)
                }
                2 -> {
                    val delta = input.readUInt24()
                    st.timestamp += delta
                    st.extended = delta == 0xFFFFFF
                    if (st.extended) st.timestamp += input.readUInt32()
                    st.pos = 0
                    st.buffer = ByteArray(st.length)
                }
                3 -> {
                    if (st.extended) st.timestamp += input.readUInt32()
                }
            }

            if (st.buffer.isEmpty()) {
                st.buffer = ByteArray(st.length)
            }
            val remain = st.length - st.pos
            val toRead = minOf(remain, chunkSize)
            if (toRead > 0) {
                input.readFully(st.buffer, st.pos, toRead)
                st.pos += toRead
            }
            if (st.pos >= st.length) {
                return Message(st.type, st.streamId, st.timestamp, st.buffer)
            }
        }
    }

    private fun handle(msg: Message) {
        when (msg.type) {
            20 -> handleAmf(msg)
            18 -> handleData(msg)
            9 -> handleVideo(msg)
            8 -> handleAudio(msg)
            1 -> if (msg.payload.size >= 4) chunkSize = beInt(msg.payload, 0)
            else -> Unit
        }
    }

    private fun handleAmf(msg: Message) {
        val values = Amf0.decodeAll(msg.payload)
        val command = values.getOrNull(0) as? String ?: return
        val tx = (values.getOrNull(1) as? Double) ?: 0.0
        when (command) {
            "connect" -> {
                sendControl(5, beBytes(2_500_000))
                sendControl(6, beBytes(2_500_000) + byteArrayOf(2))
                sendUserControl(0, 0)
                val result = Amf0.encodeCommand(
                    "_result", tx,
                    mapOf(
                        "fmsVer" to "FMS/3,0,1,123",
                        "capabilities" to 31.0,
                    ),
                    mapOf(
                        "level" to "status",
                        "code" to "NetConnection.Connect.Success",
                        "description" to "Connection succeeded.",
                        "objectEncoding" to 0.0,
                    ),
                )
                sendMessage(20, 0, 0, result)
            }
            "createStream" -> {
                val result = Amf0.encodeCommand("_result", tx, null, 1.0)
                sendMessage(20, 0, 0, result)
            }
            "publish" -> {
                published = true
                sendUserControl(0, 1)
                val status = Amf0.encodeCommand(
                    "onStatus", 0.0, null,
                    mapOf(
                        "level" to "status",
                        "code" to "NetStream.Publish.Start",
                        "description" to "Start publishing.",
                    ),
                )
                sendMessage(20, 1, 1, status)
            }
            "deleteStream", "closeStream" -> published = false
            else -> Unit
        }
    }

    private fun handleData(msg: Message) {
        // @setDataFrame / onMetaData ignored
    }

    private fun handleVideo(msg: Message) {
        val p = msg.payload
        if (p.size < 2) return
        val frameType = (p[0].toInt() ushr 4) and 0x0f
        val codec = p[0].toInt() and 0x0f
        if (codec != 7) return
        val packetType = p[1].toInt() and 0xff
        val ptsUs = msg.timestamp * 1000L
        if (packetType == 0) {
            parseAvcConfig(p)?.let { (s, q) -> sps = s; pps = q }
            return
        }
        if (packetType != 1) return
        var o = 5
        val nals = ArrayList<ByteArray>()
        while (o + 4 <= p.size) {
            val len = beInt(p, o)
            o += 4
            if (len <= 0 || o + len > p.size) break
            nals += p.copyOfRange(o, o + len)
            o += len
        }
        if (nals.isEmpty()) return
        publisher.publishH264(
            H264AccessUnit(
                nalUnits = nals,
                ptsUs = ptsUs,
                isKeyFrame = frameType == 1,
                sps = sps,
                pps = pps,
            ),
        )
    }

    private fun handleAudio(msg: Message) {
        val p = msg.payload
        if (p.size < 2) return
        val codec = (p[0].toInt() ushr 4) and 0x0f
        if (codec != 10) return
        val packetType = p[1].toInt() and 0xff
        val frame = p.copyOfRange(2, p.size)
        publisher.publishAac(
            AacAccessUnit(
                frame = frame,
                ptsUs = msg.timestamp * 1000L,
                isSequenceHeader = packetType == 0,
            ),
        )
    }

    private fun parseAvcConfig(p: ByteArray): Pair<ByteArray, ByteArray>? {
        if (p.size < 7) return null
        var o = 6
        val numSps = p[5].toInt() and 0x1f
        var s: ByteArray? = null
        repeat(numSps) {
            if (o + 2 > p.size) return null
            val len = ((p[o].toInt() and 0xff) shl 8) or (p[o + 1].toInt() and 0xff)
            o += 2
            if (o + len > p.size) return null
            s = p.copyOfRange(o, o + len)
            o += len
        }
        if (o >= p.size) return null
        val numPps = p[o].toInt() and 0xff
        o++
        var q: ByteArray? = null
        repeat(numPps) {
            if (o + 2 > p.size) return null
            val len = ((p[o].toInt() and 0xff) shl 8) or (p[o + 1].toInt() and 0xff)
            o += 2
            if (o + len > p.size) return null
            q = p.copyOfRange(o, o + len)
            o += len
        }
        return if (s != null && q != null) s!! to q!! else null
    }

    private fun sendControl(type: Int, payload: ByteArray) = sendMessage(type, 0, 0, payload)

    private fun sendUserControl(event: Int, streamId: Int) {
        val payload = ByteArray(6)
        payload[0] = ((event ushr 8) and 0xff).toByte()
        payload[1] = (event and 0xff).toByte()
        payload[2] = ((streamId ushr 24) and 0xff).toByte()
        payload[3] = ((streamId ushr 16) and 0xff).toByte()
        payload[4] = ((streamId ushr 8) and 0xff).toByte()
        payload[5] = (streamId and 0xff).toByte()
        sendMessage(4, 0, 0, payload)
    }

    private fun sendMessage(type: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        out.write((timestamp ushr 16) and 0xff)
        out.write((timestamp ushr 8) and 0xff)
        out.write(timestamp and 0xff)
        out.write((payload.size ushr 16) and 0xff)
        out.write((payload.size ushr 8) and 0xff)
        out.write(payload.size and 0xff)
        out.write(type and 0xff)
        out.write(streamId and 0xff)
        out.write((streamId ushr 8) and 0xff)
        out.write((streamId ushr 16) and 0xff)
        out.write((streamId ushr 24) and 0xff)
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

    private class Partial {
        var timestamp: Int = 0
        var length: Int = 0
        var type: Int = 0
        var streamId: Int = 0
        var pos: Int = 0
        var buffer: ByteArray = ByteArray(0)
        var extended: Boolean = false
    }

    private data class Message(val type: Int, val streamId: Int, val timestamp: Int, val payload: ByteArray)
}

private object Amf0 {
    fun decodeAll(bytes: ByteArray): List<Any?> {
        val out = ArrayList<Any?>()
        var p = 0
        while (p < bytes.size) {
            val (v, n) = decode(bytes, p) ?: break
            out += v
            p = n
        }
        return out
    }

    private fun decode(b: ByteArray, start: Int): Pair<Any?, Int>? {
        if (start >= b.size) return null
        var p = start
        return when (b[p].toInt() and 0xff) {
            0x00 -> {
                if (p + 9 > b.size) return null
                var d = 0L
                for (i in 1..8) d = (d shl 8) or (b[p + i].toLong() and 0xff)
                val v = java.lang.Double.longBitsToDouble(d)
                v to p + 9
            }
            0x01 -> (b[p + 1].toInt() != 0) to p + 2
            0x02 -> {
                if (p + 3 > b.size) return null
                val len = ((b[p + 1].toInt() and 0xff) shl 8) or (b[p + 2].toInt() and 0xff)
                if (p + 3 + len > b.size) return null
                String(b, p + 3, len) to p + 3 + len
            }
            0x03 -> {
                p++
                val map = LinkedHashMap<String, Any?>()
                while (p + 2 < b.size) {
                    val len = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
                    if (len == 0) {
                        if (p + 3 <= b.size && (b[p + 2].toInt() and 0xff) == 0x09) p += 3
                        break
                    }
                    if (p + 2 + len > b.size) return null
                    val key = String(b, p + 2, len)
                    val (v, n) = decode(b, p + 2 + len) ?: break
                    map[key] = v
                    p = n
                }
                map to p
            }
            0x05 -> null to p + 1
            0x06 -> null to p + 1
            0x08 -> {
                if (p + 5 > b.size) return null
                val len = ((b[p + 1].toInt() and 0xff) shl 24) or ((b[p + 2].toInt() and 0xff) shl 16) or
                    ((b[p + 3].toInt() and 0xff) shl 8) or (b[p + 4].toInt() and 0xff)
                if (p + 5 + len > b.size) return null
                val map = LinkedHashMap<String, Any?>()
                map["__ecma"] = String(b, p + 5, len)
                map to p + 5 + len
            }
            else -> null to p + 1
        }
    }

    fun encodeCommand(name: String, tx: Double, vararg args: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, name)
        writeNumber(out, tx)
        args.forEach { writeValue(out, it) }
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(0x05)
            is String -> writeString(out, v)
            is Double -> writeNumber(out, v)
            is Int -> writeNumber(out, v.toDouble())
            is Boolean -> { out.write(0x01); out.write(if (v) 1 else 0) }
            is Map<*, *> -> writeObject(out, v)
            else -> writeString(out, v.toString())
        }
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray()
        out.write(0x02)
        out.write((b.size ushr 8) and 0xff)
        out.write(b.size and 0xff)
        out.write(b)
    }

    private fun writeNumber(out: ByteArrayOutputStream, d: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToLongBits(d)
        for (i in 7 downTo 0) out.write(((bits ushr (i * 8)) and 0xff).toInt())
    }

    private fun writeObject(out: ByteArrayOutputStream, map: Map<*, *>) {
        out.write(0x03)
        map.forEach { (k, v) ->
            val key = (k as? String ?: return@forEach).toByteArray()
            out.write((key.size ushr 8) and 0xff)
            out.write(key.size and 0xff)
            out.write(key)
            writeValue(out, v)
        }
        out.write(0x00); out.write(0x00); out.write(0x09)
    }
}

private fun DataInputStream.readN(n: Int): ByteArray = ByteArray(n).also { readFully(it) }
private fun DataInputStream.readUInt24(): Int = (readUnsignedByte() shl 16) or (readUnsignedByte() shl 8) or readUnsignedByte()
private fun DataInputStream.readUInt32(): Int = (readInt())
private fun DataInputStream.readUnsignedShortLE(): Int = (readUnsignedByte()) or (readUnsignedByte() shl 8)
private fun DataInputStream.readIntLE(): Int =
    (readUnsignedByte()) or (readUnsignedByte() shl 8) or (readUnsignedByte() shl 16) or (readUnsignedByte() shl 24)
private fun beInt(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
private fun beBytes(v: Int): ByteArray = byteArrayOf(
    (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
)
