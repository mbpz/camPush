package com.campush.lan

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 解析后的 LAN HTTP 请求（契约：05 §7.4 的简化实现）。
 */
internal data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String,
    val requestId: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun queryParam(name: String): String? = query[name]
}

/**
 * 路由结果：短响应（JSON/HTML）或流式响应（FLV/MJPEG 长连接）。
 * 流式响应的 [onFinally] 一定会被调用（用于观看者计数释放）。
 */
internal sealed class HttpResult {
    class Short(
        val status: Int,
        val contentType: String?,
        val body: ByteArray,
        val extraHeaders: Map<String, String> = emptyMap(),
    ) : HttpResult()

    class Stream(
        val contentType: String,
        val body: (OutputStream, Socket) -> Unit,
        val onFinally: () -> Unit = {},
        val extraHeaders: Map<String, String> = emptyMap(),
    ) : HttpResult()
}

/** 路由层抛出的业务错误（携带 HTTP 语义所需的附加信息）。 */
internal class HttpError(
    val code: Int,
    val retryAfterSec: Int? = null,
    val userHintOverride: String? = null,
) : Exception("HTTP error $code")

/**
 * 手写 HTTP/1.1 服务（零第三方依赖；契约：05 §5/§6）。
 *
 * 线程模型：1 个 accept 线程；短请求进固定池（8 线程 + 64 有界队列）；
 * 媒体连接各自独占一个写线程（阻塞写 socket，天然背压），绝不占用请求池。
 * 请求头 8 KB 上限、请求行 2 KB 上限、请求体 4 KB 上限、读超时 5 s。
 */
internal class HttpServer(
    private val serverSocket: ServerSocket,
    private val route: (HttpRequest) -> HttpResult,
) {
    companion object {
        const val SERVER_HEADER = "CamPush-LAN/1.0"
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_REQUEST_LINE = 2 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024
        private const val READ_TIMEOUT_MS = 5_000
        private const val SHORT_POOL_SIZE = 8
        private const val SHORT_QUEUE_SIZE = 64
    }

    private val running = AtomicBoolean(false)
    private val activeSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val mediaThreads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    private var acceptThread: Thread? = null

    private val shortPool = ThreadPoolExecutor(
        SHORT_POOL_SIZE,
        SHORT_POOL_SIZE,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(SHORT_QUEUE_SIZE),
        { runnable -> Thread(runnable, "lan-http-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    val localPort: Int get() = serverSocket.localPort

    fun start() {
        running.set(true)
        val thread = Thread({ acceptLoop() }, "lan-http-accept")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
    }

    /** 关闭 ServerSocket、全部连接与线程池；总耗时受 [graceMs] 约束。 */
    fun stop(graceMs: Long = 600L) {
        if (!running.compareAndSet(true, false)) {
            closeQuietly(serverSocket)
            return
        }
        closeQuietly(serverSocket)
        for (socket in activeSockets.toList()) closeQuietly(socket)
        shortPool.shutdownNow()
        val deadline = System.currentTimeMillis() + graceMs
        acceptThread?.let { joinUntil(it, deadline) }
        for (thread in mediaThreads.toList()) joinUntil(thread, deadline)
        activeSockets.clear()
    }

    // ------------------------------------------------------------- accept/分派

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            activeSockets.add(socket)
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = READ_TIMEOUT_MS
                val request = parseRequest(socket)
                if (request == null) {
                    activeSockets.remove(socket)
                    closeQuietly(socket)
                    continue
                }
                if (isMediaRequest(request)) {
                    startMediaThread(socket, request)
                } else {
                    dispatchShort(socket, request)
                }
            } catch (e: HttpParseException) {
                writeStatusOnly(socket, e.statusCode)
                activeSockets.remove(socket)
                closeQuietly(socket)
            } catch (_: SocketTimeoutException) {
                activeSockets.remove(socket)
                closeQuietly(socket)
            } catch (_: Exception) {
                // 单连接异常不影响 accept 线程
                activeSockets.remove(socket)
                closeQuietly(socket)
            }
        }
    }

    private fun dispatchShort(socket: Socket, request: HttpRequest) {
        try {
            shortPool.execute { handleShort(socket, request) }
        } catch (_: RejectedExecutionException) {
            writeErrorEnvelope(socket, request.requestId, 6005, null, "请求过多，请稍后重试")
            activeSockets.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun startMediaThread(socket: Socket, request: HttpRequest) {
        val thread = Thread({ handleMedia(socket, request) }, "lan-media-${request.requestId}")
        thread.isDaemon = true
        mediaThreads.add(thread)
        thread.start()
    }

    private fun isMediaRequest(request: HttpRequest): Boolean =
        request.method == "GET" && (request.path == "/live.flv" || request.path == "/stream.mjpg")

    // ------------------------------------------------------------- 处理器

    private fun handleShort(socket: Socket, request: HttpRequest) {
        try {
            val result = routeSafely(socket, request) ?: return
            when (result) {
                is HttpResult.Short -> writeShortResponse(socket, request.requestId, result)
                is HttpResult.Stream -> runStreaming(socket, request, result)
            }
        } catch (_: IOException) {
            // 客户端断开
        } finally {
            activeSockets.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun handleMedia(socket: Socket, request: HttpRequest) {
        var streamResult: HttpResult.Stream? = null
        try {
            val result = routeSafely(socket, request) ?: return
            when (result) {
                is HttpResult.Stream -> {
                    streamResult = result
                    writeStreamHeaders(socket, request.requestId, result)
                    result.body(socket.getOutputStream(), socket)
                }
                is HttpResult.Short -> writeShortResponse(socket, request.requestId, result)
            }
        } catch (_: IOException) {
            // 客户端断开/服务停止
        } finally {
            val result = streamResult
            if (result != null) {
                try {
                    result.onFinally()
                } catch (_: Exception) {
                    // 释放回调异常不影响其他连接
                }
            }
            mediaThreads.remove(Thread.currentThread())
            activeSockets.remove(socket)
            closeQuietly(socket)
        }
    }

    /** 执行路由；业务错误就地写出错误信封并返回 null。 */
    private fun routeSafely(socket: Socket, request: HttpRequest): HttpResult? = try {
        route(request)
    } catch (e: HttpError) {
        writeErrorEnvelope(socket, request.requestId, e.code, e.retryAfterSec, e.userHintOverride)
        null
    } catch (e: LanException) {
        writeErrorEnvelope(socket, request.requestId, e.code, null, e.userHint)
        null
    } catch (e: IOException) {
        throw e
    } catch (_: Exception) {
        writeErrorEnvelope(socket, request.requestId, 6006, null, null)
        null
    }

    private fun runStreaming(socket: Socket, request: HttpRequest, result: HttpResult.Stream) {
        try {
            writeStreamHeaders(socket, request.requestId, result)
            result.body(socket.getOutputStream(), socket)
        } finally {
            result.onFinally()
        }
    }

    // ------------------------------------------------------------- 响应写入

    private fun writeShortResponse(socket: Socket, requestId: String, result: HttpResult.Short) {
        writeResponseHeaders(socket, requestId, result.status, result.contentType, result.body.size.toLong(), result.extraHeaders)
        if (result.body.isNotEmpty()) {
            val out = socket.getOutputStream()
            out.write(result.body)
            out.flush()
        }
    }

    private fun writeStreamHeaders(socket: Socket, requestId: String, result: HttpResult.Stream) {
        writeResponseHeaders(socket, requestId, 200, result.contentType, null, result.extraHeaders)
    }

    private fun writeResponseHeaders(
        socket: Socket,
        requestId: String,
        status: Int,
        contentType: String?,
        contentLength: Long?,
        extraHeaders: Map<String, String>,
    ) {
        val head = buildString(256) {
            append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            append("Server: ").append(SERVER_HEADER).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength != null) append("Content-Length: ").append(contentLength).append("\r\n")
            append("Connection: close\r\n")
            append("X-Request-Id: ").append(requestId).append("\r\n")
            for ((name, value) in extraHeaders) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun writeStatusOnly(socket: Socket, status: Int) {
        try {
            val head = "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
                "Server: $SERVER_HEADER\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
        } catch (_: IOException) {
        }
    }

    private fun writeErrorEnvelope(
        socket: Socket,
        requestId: String,
        code: Int,
        retryAfterSec: Int?,
        userHintOverride: String?,
    ) {
        try {
            val spec = LanErrors.specFor(code)
            val body = LanErrors.envelope(code, requestId, userHintOverride).toByteArray(StandardCharsets.UTF_8)
            val extra = if (retryAfterSec != null) mapOf("Retry-After" to retryAfterSec.toString()) else emptyMap()
            writeShortResponse(socket, requestId, HttpResult.Short(spec.httpStatus, JSON_CONTENT_TYPE, body, extra))
        } catch (_: IOException) {
            // 客户端可能已断开
        }
    }

    // ------------------------------------------------------------- 请求解析

    private class HttpParseException(val statusCode: Int) : Exception("bad request: $statusCode")

    private fun parseRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val headBytes = readHead(input) ?: return null
        val head = String(headBytes, StandardCharsets.ISO_8859_1)
        val lines = head.split("\r\n")
        if (lines.isEmpty() || lines[0].isEmpty()) throw HttpParseException(400)
        val requestLine = lines[0]
        if (requestLine.length > MAX_REQUEST_LINE) throw HttpParseException(414)
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw HttpParseException(400)
        val method = parts[0].uppercase()
        if (method != "GET" && method != "POST" && method != "OPTIONS") throw HttpParseException(405)
        val target = parts[1]
        val queryIndex = target.indexOf('?')
        val rawPath = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val rawQuery = if (queryIndex >= 0) target.substring(queryIndex + 1) else ""
        val path = decodeComponent(rawPath)
        val query = parseQuery(rawQuery)

        val headers = LinkedHashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isNotEmpty() && !headers.containsKey(name)) headers[name] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0) throw HttpParseException(400)
        if (contentLength > MAX_BODY_BYTES) throw HttpParseException(413)
        val body = if (contentLength > 0) readBody(input, contentLength) ?: throw HttpParseException(400) else ByteArray(0)

        return HttpRequest(
            method = method,
            path = path,
            query = query,
            headers = headers,
            body = body,
            remoteIp = socket.inetAddress?.hostAddress ?: "unknown",
            requestId = RandomIds.requestId(),
        )
    }

    private fun readHead(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(1024)
        var tail = 0
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) return null
                throw HttpParseException(400)
            }
            buffer.write(value)
            if (buffer.size() > MAX_HEAD_BYTES) throw HttpParseException(431)
            tail = (tail shl 8) or value
            if (tail == 0x0D0A0D0A) return buffer.toByteArray()
        }
    }

    private fun readBody(input: InputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return body
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val key = if (equals >= 0) pair.substring(0, equals) else pair
            val value = if (equals >= 0) pair.substring(equals + 1) else ""
            result[decodeComponent(key)] = decodeComponent(value)
        }
        return result
    }

    private fun decodeComponent(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    // ------------------------------------------------------------- 工具

    private fun joinUntil(thread: Thread, deadline: Long) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return
        try {
            thread.join(remaining.coerceAtMost(500L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(socket: ServerSocket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        423 -> "Locked"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Error"
    }
}
