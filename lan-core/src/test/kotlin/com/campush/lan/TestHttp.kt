package com.campush.lan

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

internal data class HttpResp(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val text: String get() = String(body, StandardCharsets.UTF_8)
}

internal class StreamResp(
    val socket: Socket,
    val status: Int,
    val headers: Map<String, String>,
    private val input: InputStream,
) : Closeable {

    fun readAtLeast(minBytes: Int, timeoutMs: Int = 3_000): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = timeoutMs
            val chunk = ByteArray(8 * 1024)
            while (out.size() < minBytes) {
                val read = input.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
            }
        } catch (_: SocketTimeoutException) {
            // 返回已读取字节
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
        return out.toByteArray()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** 测试用最小 HTTP 客户端（不引入第三方库）。 */
internal object TestHttp {

    fun request(
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 3_000,
    ): HttpResp {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            val payload = body ?: ByteArray(0)
            writeRequest(socket, method, path, headers, payload)
            val (status, responseHeaders) = readHead(socket.getInputStream())
            val bodyBytes = when (val length = responseHeaders["content-length"]) {
                null -> socket.getInputStream().readBytes()
                else -> readN(socket.getInputStream(), length.toInt())
            }
            return HttpResp(status, responseHeaders, bodyBytes)
        } finally {
            runCatching { socket.close() }
        }
    }

    fun postJson(
        port: Int,
        path: String,
        headers: Map<String, String> = emptyMap(),
        json: String = "{}",
        timeoutMs: Int = 3_000,
    ): HttpResp = request(
        port = port,
        method = "POST",
        path = path,
        headers = headers + mapOf("Content-Type" to "application/json"),
        body = json.toByteArray(StandardCharsets.UTF_8),
        timeoutMs = timeoutMs,
    )

    fun openStream(
        port: Int,
        path: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 3_000,
    ): StreamResp {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        writeRequest(socket, "GET", path, headers, ByteArray(0))
        val (status, responseHeaders) = readHead(socket.getInputStream())
        return StreamResp(socket, status, responseHeaders, socket.getInputStream())
    }

    private fun writeRequest(
        socket: Socket,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
    ) {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: 127.0.0.1\r\n")
        sb.append("Connection: close\r\n")
        for ((name, value) in headers) sb.append(name).append(": ").append(value).append("\r\n")
        if (method == "POST") sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val buffer = ByteArrayOutputStream(512)
        var tail = 0
        while (true) {
            val value = input.read()
            check(value >= 0) { "unexpected EOF while reading response head" }
            buffer.write(value)
            tail = (tail shl 8) or value
            if (tail == 0x0D0A0D0A) break
        }
        val text = String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val statusParts = lines[0].split(' ')
        check(statusParts.size >= 2) { "bad status line: ${lines[0]}" }
        val status = statusParts[1].toInt()
        val headers = LinkedHashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return status to headers
    }

    private fun readN(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) out else out.copyOf(offset)
    }
}

// ---------------------------------------------------------------- FLV 解析

internal data class FlvTagView(
    val type: Int,
    val data: ByteArray,
    val timestamp: Long,
    val offset: Int,
) {
    val dataSize: Int get() = data.size

    fun isVideo(): Boolean = type == 9

    fun isAudio(): Boolean = type == 8

    fun isScript(): Boolean = type == 18

    /** video tag data[0] 高 4 位：1=关键帧，2=非关键帧。 */
    fun videoFrameType(): Int = if (data.isEmpty()) -1 else (data[0].toInt() ushr 4) and 0x0F

    /** video tag data[1]：0=sequence header，1=NALU。 */
    fun avcPacketType(): Int = if (data.size < 2) -1 else data[1].toInt() and 0xFF

    /** audio tag data[1]：0=AudioSpecificConfig，1=raw。 */
    fun aacPacketType(): Int = if (data.size < 2) -1 else data[1].toInt() and 0xFF
}

internal object FlvParser {

    fun parseTags(bytes: ByteArray, from: Int = 13): List<FlvTagView> {
        val tags = ArrayList<FlvTagView>()
        var pos = from
        while (pos + 11 <= bytes.size) {
            val type = bytes[pos].toInt() and 0xFF
            val dataSize = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            if (pos + 11 + dataSize + 4 > bytes.size) break
            val timestamp = ((bytes[pos + 7].toLong() and 0xFF) shl 24) or
                ((bytes[pos + 4].toLong() and 0xFF) shl 16) or
                ((bytes[pos + 5].toLong() and 0xFF) shl 8) or
                (bytes[pos + 6].toLong() and 0xFF)
            tags.add(
                FlvTagView(
                    type = type,
                    data = bytes.copyOfRange(pos + 11, pos + 11 + dataSize),
                    timestamp = timestamp,
                    offset = pos,
                ),
            )
            pos += 11 + dataSize + 4
        }
        return tags
    }

    fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (i in 0..haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}

internal fun jsonCode(body: String): Int? {
    val parsed = try {
        MiniJson.parse(body) as? Map<*, *>
    } catch (_: Exception) {
        null
    }
    return (parsed?.get("code") as? Number)?.toInt()
}

internal fun jsonField(body: String, name: String): Any? {
    val parsed = try {
        MiniJson.parse(body) as? Map<*, *>
    } catch (_: Exception) {
        null
    }
    return parsed?.get(name)
}

internal fun expectLanCode(code: Int, block: () -> Unit) {
    try {
        block()
    } catch (e: LanException) {
        org.junit.Assert.assertEquals("LAN error code", code, e.code)
        return
    }
    org.junit.Assert.fail("expected LanException($code)")
}
