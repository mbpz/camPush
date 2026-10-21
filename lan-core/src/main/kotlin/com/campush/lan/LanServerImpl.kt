package com.campush.lan

import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LanServer 的内部实现（公共 API 见 Api.kt，签名冻结）。
 *
 * 生命周期（契约：05 §3）：start 分配端口 -> 绑定 -> 生成密钥/token -> state=LIVE ->
 * 返回 `http://{lanIp}:{port}/s/{pageToken}`；stop 在 ≤1 s 内关闭端口、断开连接、
 * 销毁密钥（zeroize），可重复调用。
 */
internal class LanServerImpl(
    val config: LanConfig,
    private val ipProvider: () -> String?,
    private val listener: LanEventListener?,
    private val clock: () -> Long,
) {
    private val mediaHub = MediaHub(clock)
    val publisher: LanMediaPublisher = mediaHub
    private var rtmpServer: RtmpIngestServer? = null
    private val telemetryCounts =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val telemetryAccepted = java.util.concurrent.atomic.AtomicLong()
    private val telemetryDropped = java.util.concurrent.atomic.AtomicLong()

    private val lock = Any()

    @Volatile
    private var state: LanRoomState = LanRoomState.CREATED

    @Volatile
    private var tokens: Tokens? = null

    @Volatile
    private var pinGuard: PinGuard? = null

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var lanIp: String = ""

    @Volatile
    private var boundPort: Int = config.port

    @Volatile
    private var startedAtMillis: Long = 0L

    @Volatile
    private var pinRequired: Boolean = config.pinRequired

    @Volatile
    private var viewingPin: String? = config.pin

    @Volatile
    private var pageToken: String? = null

    @Volatile
    private var stopRequested: Boolean = false

    private val viewers = AtomicInteger(0)
    private val sessions = ConcurrentHashMap<String, PlaySession>()

    @Volatile
    private var cachedPlayerHtml: ByteArray? = null

    @Volatile
    private var playerHtmlLoaded: Boolean = false

    private data class PlaySession(val playSessionId: String, val createdAtMillis: Long)

    // ------------------------------------------------------------- 生命周期

    fun start(): String = synchronized(lock) {
        if (state == LanRoomState.LIVE) {
            val token = pageToken
            if (token != null) return "http://$lanIp:$boundPort/s/$token"
        }
        if (state == LanRoomState.STOPPING) throw LanErrors.exception(6001, "LAN service is stopping")

        var allocated: ServerSocket? = null
        var http: HttpServer? = null
        val tempTokens = Tokens(config, clock)
        try {
            val ip = ipProvider()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw LanErrors.exception(6003)

            allocated = PortAllocator.allocate(config)
            tempTokens.newSession()
            val guard = PinGuard(clock, onLocked = { emit(LanEvent.PIN_LOCKED) })
            http = HttpServer(allocated) { request -> route(request) }
            http.start()
            if (config.rtmpPort > 0) {
                rtmpServer = runCatching {
                    RtmpIngestServer(mediaHub, clock).also { it.start(config.rtmpPort) }
                }.getOrNull()
            }

            stopRequested = false
            mediaHub.reopen()
            tokens = tempTokens
            pinGuard = guard
            httpServer = http
            serverSocket = allocated
            lanIp = ip
            boundPort = allocated.localPort
            startedAtMillis = clock()
            state = LanRoomState.LIVE
            sessions.clear()
            viewers.set(0)

            val token = tempTokens.issuePageToken(pinRequired)
            pageToken = token
            emit(LanEvent.SERVER_STARTED, viewers = 0)
            return "http://$ip:$boundPort/s/$token"
        } catch (t: Throwable) {
            runCatching { http?.stop(300L) }
            runCatching { allocated?.close() }
            tempTokens.destroy()
            tokens = null
            pinGuard = null
            httpServer = null
            serverSocket = null
            pageToken = null
            sessions.clear()
            viewers.set(0)
            mediaHub.reset()
            state = LanRoomState.FAILED
            val code = (t as? LanException)?.code ?: 6001
            emit(LanEvent.ERROR, message = t.message, code = code)
            throw if (t is LanException) t else LanErrors.exception(6001, t.message)
        }
    }

    fun stop() {
        var http: HttpServer? = null
        var sessionTokens: Tokens? = null
        synchronized(lock) {
            if (state == LanRoomState.CLOSED || state == LanRoomState.STOPPING) return
            state = LanRoomState.STOPPING
            stopRequested = true
            http = httpServer
            httpServer = null
            sessionTokens = tokens
        }
        // 先唤醒媒体订阅者，再关闭连接，保证 stop 总耗时 ≤1 s（05 §3.3）。
        mediaHub.close()
        runCatching { rtmpServer?.stop() }
        rtmpServer = null
        runCatching { http?.stop(600L) }
        synchronized(lock) {
            sessionTokens?.destroy()
            tokens = null
            pinGuard = null
            serverSocket = null
            pageToken = null
            sessions.clear()
            viewers.set(0)
            mediaHub.reset()
            state = LanRoomState.CLOSED
            emit(LanEvent.SERVER_STOPPED, viewers = 0)
        }
    }

    fun status(): LanStatus {
        val currentState = state
        val stats = mediaHub.stats()
        val uptime = if (currentState == LanRoomState.LIVE) {
            ((clock() - startedAtMillis).coerceAtLeast(0L)) / 1000L
        } else {
            0L
        }
        return LanStatus(
            state = currentState,
            lanIp = lanIp,
            port = boundPort,
            viewers = viewers.get(),
            maxViewers = config.maxViewers,
            pinRequired = pinRequired,
            mediaProtocols = listOf("flv", "mjpeg"),
            uptimeSeconds = uptime,
            bitrateBps = stats.bitrateBps,
            fps = stats.fps,
            startedAtMillis = startedAtMillis,
            rtmpFrames = rtmpServer?.videoFrames ?: 0,
        )
    }

    fun updatePin(required: Boolean, pin: String?) {
        synchronized(lock) {
            pinRequired = required
            viewingPin = pin
        }
    }

    // ------------------------------------------------------------- 路由

    private fun route(request: HttpRequest): HttpResult {
        if (request.method == "OPTIONS") return HttpResult.Short(204, null, ByteArray(0))

        if (state == LanRoomState.STOPPING || state == LanRoomState.CLOSED) {
            if (request.path.startsWith("/s/")) return pageError(2002)
            throw HttpError(2002)
        }

        return when {
            request.method == "GET" && request.path == "/healthz" -> json(200, mapOf("ok" to true))

            request.method == "GET" && request.path == "/favicon.ico" -> HttpResult.Short(204, null, ByteArray(0))

            request.method == "GET" && request.path.startsWith("/s/") &&
                request.path.endsWith("/logs") -> handleLogs(request)

            request.method == "GET" && request.path.startsWith("/s/") -> handlePage(request)

            request.method == "GET" && request.path == "/api/v1/status" -> handleStatus(request)

            request.method == "POST" && request.path == "/api/v1/play-session" -> handlePlaySession(request)

            request.method == "POST" && request.path == "/api/v1/page-token:refresh" -> handleRefresh(request)

            request.method == "POST" && request.path == "/api/v1/telemetry/batch" -> handleTelemetry(request)

            request.method == "GET" && request.path == "/live.flv" -> handleFlv(request)

            request.method == "GET" && request.path == "/stream.mjpg" -> handleMjpeg(request)

            else -> HttpResult.Short(404, null, ByteArray(0))
        }
    }

    private fun handlePage(request: HttpRequest): HttpResult {
        val token = request.path.removePrefix("/s/")
        if (token.isEmpty()) return pageError(3001)
        try {
            tokensOrThrow().verifyPageToken(token)
        } catch (e: LanException) {
            return pageError(e.code)
        }
        val html = loadPlayerHtml() ?: return HttpResult.Short(
            503,
            "text/html; charset=utf-8",
            placeholderPage().toByteArray(StandardCharsets.UTF_8),
            pageHeaders(),
        )
        return HttpResult.Short(200, "text/html; charset=utf-8", html, pageHeaders())
    }

    /** `GET /s/{pageToken}/logs`：返回文本诊断日志，浏览器直接下载保存。 */
    private fun handleLogs(request: HttpRequest): HttpResult {
        val token = request.path.removePrefix("/s/").removeSuffix("/logs").trimEnd('/')
        if (token.isEmpty()) throw HttpError(3001)
        try {
            tokensOrThrow().verifyPageToken(token)
        } catch (e: LanException) {
            throw HttpError(e.code, userHintOverride = e.userHint)
        }
        val provider = config.logProvider
        val text = if (provider != null) {
            runCatching { provider() }.getOrElse { "log provider failed: ${it.message}" }
        } else {
            "diagnostic log not available"
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return HttpResult.Short(
            200,
            "text/plain; charset=utf-8",
            bytes,
            mapOf(
                "Content-Disposition" to "attachment; filename=\"campush-log-$stamp.txt\"",
            ),
        )
    }

    private fun handleStatus(request: HttpRequest): HttpResult {
        verifyPageToken(request)
        val snapshot = status()
        val payload = LinkedHashMap<String, Any?>()
        payload["state"] = stateWire(snapshot.state)
        payload["lanIp"] = snapshot.lanIp
        payload["port"] = snapshot.port
        payload["viewers"] = snapshot.viewers
        payload["maxViewers"] = snapshot.maxViewers
        payload["pinRequired"] = snapshot.pinRequired
        payload["mediaProtocols"] = snapshot.mediaProtocols
        payload["uptimeSec"] = snapshot.uptimeSeconds
        payload["bitrateKbps"] = snapshot.bitrateBps / 1000L
        payload["fps"] = snapshot.fps
        payload["rtmpFrames"] = snapshot.rtmpFrames
        return json(200, payload)
    }

    private fun handlePlaySession(request: HttpRequest): HttpResult {
        verifyPageToken(request)
        val body = parseJsonObject(request.body)
        val requestedSessionId = body["playSessionId"] as? String
        val providedPin = body["pin"] as? String

        if (pinRequired) {
            val guard = pinGuard ?: throw HttpError(2002)
            try {
                guard.verify(required = true, expectedPin = viewingPin, providedPin = providedPin, remoteIp = request.remoteIp)
            } catch (e: LanException) {
                val retryAfter = if (e.code == 3006 || e.code == 3008) guard.retryAfterSeconds(request.remoteIp) else null
                throw HttpError(e.code, retryAfterSec = retryAfter, userHintOverride = e.userHint)
            }
        }

        val existing = requestedSessionId?.takeIf { it.isNotEmpty() }?.let { sessions[it] }
        val playSessionId = if (existing != null) {
            existing.playSessionId
        } else {
            // play-session 预检并发（05 §9.2）：让用户在拿到 mediaToken 前就看到明确提示
            if (viewers.get() >= config.maxViewers) {
                emit(LanEvent.VIEWER_LIMITED, viewers = viewers.get(), code = 6005)
                throw HttpError(6005, userHintOverride = viewerLimitHint())
            }
            val newId = RandomIds.playSessionId()
            sessions[newId] = PlaySession(newId, clock())
            newId
        }

        val mediaToken = tokensOrThrow().issueMediaToken(playSessionId)
        val payload = LinkedHashMap<String, Any?>()
        payload["playSessionId"] = playSessionId
        payload["mediaToken"] = mediaToken
        payload["expiresIn"] = config.mediaTokenTtlSeconds
        payload["pinRequired"] = pinRequired
        payload["protocols"] = linkedMapOf(
            "flv" to linkedMapOf("url" to "/live.flv"),
            "mjpeg" to linkedMapOf("url" to "/stream.mjpg"),
        )
        return json(200, payload)
    }

    private fun handleRefresh(request: HttpRequest): HttpResult {
        val token = pageTokenFrom(request)
        if (token.isEmpty()) throw HttpError(3001)
        val refreshed = try {
            tokensOrThrow().refreshPageToken(token, pinRequired)
        } catch (e: LanException) {
            throw HttpError(e.code, userHintOverride = e.userHint)
        }
        val payload = LinkedHashMap<String, Any?>()
        payload["pageToken"] = refreshed
        payload["expiresIn"] = config.pageTokenTtlSeconds
        return json(200, payload)
    }

    private fun handleTelemetry(request: HttpRequest): HttpResult {
        var accepted = 0
        var dropped = 0
        try {
            val parsed = MiniJson.parse(String(request.body, StandardCharsets.UTF_8)) as? Map<*, *>
            val events = parsed?.get("events") as? List<*>
            events?.forEach { raw ->
                val entry = raw as? Map<*, *>
                val name = (entry?.get("eventName") ?: entry?.get("name"))?.toString()
                if (name != null && name in TELEMETRY_EVENTS) {
                    telemetryCounts.computeIfAbsent(name) { java.util.concurrent.atomic.AtomicLong() }
                        .incrementAndGet()
                    accepted++
                } else {
                    dropped++
                }
            }
        } catch (_: Exception) {
            dropped++
        }
        telemetryAccepted.addAndGet(accepted.toLong())
        telemetryDropped.addAndGet(dropped.toLong())
        // P1 端侧只做内存计数（断网/停止即丢弃，不落盘）。
        val payload = LinkedHashMap<String, Any?>()
        payload["accepted"] = accepted
        payload["dropped"] = dropped
        return json(202, payload)
    }

    fun telemetrySummary(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        telemetryCounts.entries.sortedBy { it.key }.forEach { (name, count) -> out[name] = count.get() }
        out["_accepted"] = telemetryAccepted.get()
        out["_dropped"] = telemetryDropped.get()
        return out
    }

    private fun handleFlv(request: HttpRequest): HttpResult {
        val mediaToken = request.queryParam("mt")?.takeIf { it.isNotEmpty() } ?: throw HttpError(3001)
        verifyMediaToken(mediaToken)
        acquireViewer()
        return HttpResult.Stream(
            contentType = "video/x-flv",
            body = { out, socket -> streamFlv(out, socket) },
            onFinally = { releaseViewer() },
        )
    }

    private fun handleMjpeg(request: HttpRequest): HttpResult {
        val mediaToken = request.queryParam("mt")?.takeIf { it.isNotEmpty() } ?: throw HttpError(3001)
        verifyMediaToken(mediaToken)
        acquireViewer()
        return HttpResult.Stream(
            contentType = "multipart/x-mixed-replace; boundary=--frame",
            body = { out, socket -> streamMjpeg(out, socket) },
            onFinally = { releaseViewer() },
        )
    }

    // ------------------------------------------------------------- 媒体流

    private fun streamFlv(out: OutputStream, socket: Socket) {
        val subscription = mediaHub.openFlv()
        try {
            out.write(subscription.initialBytes())
            out.flush()
            while (!stopRequested && !socket.isClosed) {
                val chunk = subscription.poll(1000L)
                if (chunk != null && chunk.isNotEmpty()) {
                    out.write(chunk)
                    out.flush()
                } else if (peerClosed(socket)) {
                    break
                }
            }
        } finally {
            subscription.close()
        }
    }

    private fun streamMjpeg(out: OutputStream, socket: Socket) {
        mediaHub.mjpegClientOpened()
        try {
            streamMjpegLoop(out, socket)
        } finally {
            mediaHub.mjpegClientClosed()
        }
    }

    private fun streamMjpegLoop(out: OutputStream, socket: Socket) {
        var lastVersion = -1L
        var lastPeerCheck = System.currentTimeMillis()
        while (!stopRequested && !socket.isClosed) {
            val latest = mediaHub.latestJpegVersioned()
            if (latest != null && latest.first != lastVersion) {
                lastVersion = latest.first
                val jpeg = latest.second
                val partHeader = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                out.write(partHeader.toByteArray(StandardCharsets.ISO_8859_1))
                out.write(jpeg)
                out.write(CRLF)
                out.flush()
            } else {
                try {
                    Thread.sleep(50L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                val now = System.currentTimeMillis()
                if (now - lastPeerCheck > 500L) {
                    lastPeerCheck = now
                    if (peerClosed(socket)) break
                }
            }
        }
    }

    /** 观看端只读不写：用读超时探测 FIN，及时释放 viewers（05 §9.1 计数口径）。 */
    private fun peerClosed(socket: Socket): Boolean = try {
        socket.soTimeout = 500
        socket.getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    // ------------------------------------------------------------- 鉴权/计数

    private fun tokensOrThrow(): Tokens = tokens ?: throw HttpError(2002)

    /**
     * 页面 token 提取：契约以 `Authorization: Bearer` 为主（00 §5.1/§18.4），
     * 同时兼容 `X-Page-Token`（05 §6）。
     */
    private fun pageTokenFrom(request: HttpRequest): String {
        val direct = request.header("x-page-token")?.trim().orEmpty()
        if (direct.isNotEmpty()) return direct
        val auth = request.header("authorization")?.trim().orEmpty()
        if (auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        return ""
    }

    private fun verifyPageToken(request: HttpRequest): LanClaims {
        val token = pageTokenFrom(request)
        if (token.isEmpty()) throw HttpError(3001)
        return try {
            tokensOrThrow().verifyPageToken(token)
        } catch (e: LanException) {
            throw HttpError(e.code, userHintOverride = e.userHint)
        }
    }

    private fun verifyMediaToken(token: String): LanClaims = try {
        tokensOrThrow().verifyMediaToken(token)
    } catch (e: LanException) {
        throw HttpError(e.code, userHintOverride = e.userHint)
    }

    private fun acquireViewer() {
        while (true) {
            val current = viewers.get()
            if (current >= config.maxViewers) {
                emit(LanEvent.VIEWER_LIMITED, viewers = current, code = 6005)
                throw HttpError(6005, userHintOverride = viewerLimitHint())
            }
            if (viewers.compareAndSet(current, current + 1)) {
                emit(LanEvent.VIEWER_JOINED, viewers = current + 1)
                return
            }
        }
    }

    private fun releaseViewer() {
        val remaining = viewers.updateAndGet { current -> if (current > 0) current - 1 else 0 }
        emit(LanEvent.VIEWER_LEFT, viewers = remaining)
    }

    private fun viewerLimitHint(): String = "局域网观看人数已达上限（最多 ${config.maxViewers} 人）"

    // ------------------------------------------------------------- 播放页资源

    private fun loadPlayerHtml(): ByteArray? {
        cachedPlayerHtml?.let { return it }
        synchronized(lock) {
            if (playerHtmlLoaded) return cachedPlayerHtml
            playerHtmlLoaded = true
            val bytes = try {
                LanServerImpl::class.java.getResourceAsStream("/com/campush/lan/player.html")?.use { it.readBytes() }
            } catch (_: IOException) {
                null
            }
            cachedPlayerHtml = bytes
            return bytes
        }
    }

    private fun pageHeaders(): Map<String, String> = mapOf(
        "Referrer-Policy" to "no-referrer",
        "X-Frame-Options" to "DENY",
        "Content-Security-Policy" to
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src 'self' data:; media-src blob: 'self'; connect-src 'self'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    )

    private fun placeholderPage(): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="campush-lan-protocol" content="${BuildInfo.LAN_PROTOCOL_VERSION}">
        <title>CamPush · 播放页未就绪</title></head>
        <body><main><h1>播放页资源未就绪</h1>
        <p>端侧服务已启动，但 player.html 资源缺失（code 6001）。</p></main></body></html>
    """.trimIndent()

    private fun pageError(code: Int): HttpResult {
        val spec = LanErrors.specFor(code)
        val html = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="campush-lan-protocol" content="${BuildInfo.LAN_PROTOCOL_VERSION}">
            <title>CamPush · 播放地址不可用</title></head>
            <body><main><h1>${escapeHtml(spec.userHint)}</h1>
            <p>错误码 ${code} · ${escapeHtml(spec.errorName)}</p></main></body></html>
        """.trimIndent()
        return HttpResult.Short(
            spec.httpStatus,
            "text/html; charset=utf-8",
            html.toByteArray(StandardCharsets.UTF_8),
            pageHeaders(),
        )
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ------------------------------------------------------------- 工具

    private fun json(status: Int, payload: Any): HttpResult.Short {
        val bytes = MiniJson.write(payload).toByteArray(StandardCharsets.UTF_8)
        return HttpResult.Short(status, HttpServer.JSON_CONTENT_TYPE, bytes)
    }

    private fun parseJsonObject(body: ByteArray): Map<String, Any?> {
        if (body.isEmpty()) return emptyMap()
        return try {
            MiniJson.parse(String(body, StandardCharsets.UTF_8)) as? Map<String, Any?> ?: emptyMap()
        } catch (_: Exception) {
            throw HttpError(2007, userHintOverride = "请求体格式不正确")
        }
    }

    private fun stateWire(value: LanRoomState): String = when (value) {
        LanRoomState.CREATED -> "created"
        LanRoomState.LIVE -> "live"
        LanRoomState.STOPPING -> "stopping"
        LanRoomState.CLOSED -> "closed"
        LanRoomState.FAILED -> "failed"
    }

    private fun emit(event: LanEvent, message: String? = null, viewers: Int = this.viewers.get(), code: Int? = null) {
        val target = listener ?: return
        try {
            target.onEvent(LanEventData(event = event, message = message, viewers = viewers, code = code))
        } catch (_: Exception) {
            // 监听器异常不得影响服务
        }
    }

    private companion object {
        /** 00 §11 的 24 个必埋事件名（P1 端侧只计数，不做业务处理）。 */
        val TELEMETRY_EVENTS: Set<String> = setOf(
            "app_open", "push_start_clicked", "push_start_success", "push_failed", "push_stop",
            "play_page_open", "play_first_frame", "play_fallback", "play_failed",
            "play_stall_start", "play_stall_end", "protocol_switch_start", "protocol_switch_end",
            "address_copied", "qr_shown", "share_clicked",
            "lan_viewer_join", "lan_viewer_leave", "ap_isolation_detected",
            "degrade_triggered", "thermal_sample", "latency_sample",
            "permission_denied", "permission_granted",
        )
        val CRLF = "\r\n".toByteArray(StandardCharsets.ISO_8859_1)
    }
}
