package com.campush.lan

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 独立诊断日志服务：仅局域网访问，不依赖推流/LanServer。
 *
 * - 应用启动即可运行；同一 Wi-Fi 下电脑浏览器打开 `http://{ip}:{port}/logs/{token}` 下载全部日志；
 * - 路径带随机 token，避免同网其他设备误访问；日志内容由 [logProvider] 提供；
 * - 与 LanServer 完全解耦（不同端口/不同生命周期），停止推流不影响日志下载。
 */
class DiagnosticLogServer(
    private val ipProvider: () -> String?,
    private val logProvider: () -> String,
    private val token: String,
    private val portRange: IntRange = 18089..18099,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var boundPort = 0
    @Volatile private var lanIp: String = ""

    val logsToken: String get() = token

    fun start(): String {
        if (running) return url()
        var socket: ServerSocket? = null
        var lastError: Throwable? = null
        for (port in portRange) {
            try {
                socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                boundPort = port
                break
            } catch (t: Throwable) {
                lastError = t
                runCatching { socket?.close() }
                socket = null
            }
        }
        val bound = socket ?: throw LanErrors.exception(7001, "diagnostic log port unavailable: ${lastError?.message}")
        serverSocket = bound
        running = true
        lanIp = ipProvider() ?: ""
        acceptThread = thread(name = "campush-log-server", isDaemon = true) {
            acceptLoop(bound)
        }
        return url()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.join(500) }
        acceptThread = null
    }

    fun url(): String = "http://$lanIp:$boundPort/logs/$token"

    fun isRunning(): Boolean = running

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(name = "campush-log-http", isDaemon = true) { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            // 跳过请求头
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
            val out = BufferedOutputStream(client.getOutputStream())
            when {
                path == "/healthz" -> writeText(out, 200, "text/plain; charset=utf-8", "ok")
                path == "/logs/$token" -> {
                    val text = runCatching { logProvider() }.getOrElse { "log provider failed: ${it.message}" }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
                    writeText(
                        out,
                        200,
                        "text/plain; charset=utf-8",
                        text,
                        mapOf("Content-Disposition" to "attachment; filename=\"campush-log-$stamp.txt\""),
                    )
                }
                path == "/" || path == "/logs" -> {
                    val html = """<!doctype html><meta charset="utf-8"><title>Campush 诊断日志</title>
<body style="font:15px -apple-system,Segoe UI,sans-serif;padding:24px;background:#0b0f14;color:#e8eef6">
<h2>Campush 诊断日志服务</h2>
<p>同一局域网内打开带 token 的地址下载日志：</p>
<p><a style="color:#34d399" href="/logs/$token">/logs/$token</a></p>
<p style="color:#8fa0b3">该服务独立于推流；应用打开即可下载。日志不含推流 token/PIN。</p></body>"""
                    writeText(out, 200, "text/html; charset=utf-8", html)
                }
                else -> writeText(out, 401, "text/plain; charset=utf-8", "unauthorized")
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
            if (bytes.size > 8192) return null
        }
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun writeText(
        out: OutputStream,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ')
            append(if (status == 200) "OK" else "Unauthorized").append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }
}
