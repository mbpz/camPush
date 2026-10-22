package com.campush.cloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import java.net.URLDecoder

/**
 * P2 T1-S1 房间 REST 骨架（本地/契约联调用，媒体面 WHIP/WHEP 由 SRS/Mediasoup 承担）。
 *
 * 已实现：创建/查询/停止房间、pageToken/mediaToken/ingestToken、play-session、status。
 * 未实现：账号体系、计费/额度、SSE、WHIP/WHEP 代理（后续任务）。
 */
class CloudServer(
    initialConfig: CloudConfig = CloudConfig.fromEnv(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var config: CloudConfig = initialConfig
    data class Room(
        val id: String,
        val accountId: String = "dev",
        val createdAt: Long,
        val expireAt: Long,
        val pageToken: String,
        val ingestToken: String,
        val pinRequired: Boolean,
        val pin: String? = null,
        var pinFailures: Int = 0,
        var pinLockedUntil: Long = 0L,
        var state: String = "created",
        var viewers: Int = 0,
        var lastActiveAt: Long = createdAt,
    )

    private val tokens = Tokens(UUID.randomUUID().toString().replace("-", "").toByteArray())
    private val rooms = ConcurrentHashMap<String, Room>()
    private val accounts = AccountStore()
    private val httpClient = HttpClient.newHttpClient()
    private val idempotency = ConcurrentHashMap<String, String>()
    private val playRates = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val telemetryCounts = ConcurrentHashMap<String, Long>()
    private val firstFrameMs = ConcurrentHashMap<String, MutableList<Long>>()
    private var server: HttpServer? = null
    private val cleaner = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cloud-room-cleaner").apply { isDaemon = true }
    }

    val port: Int get() = server?.address?.port ?: -1

    fun start(port: Int = 0): Int {
        val s = HttpServer.create(InetSocketAddress(config.bindHost, port), 0)
        s.createContext("/") { ex -> handle(ex) }
        s.executor = Executors.newFixedThreadPool(4)
        s.start()
        server = s
        cleaner.scheduleAtFixedRate({ cleanupIdle() }, 30, 30, TimeUnit.SECONDS)
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
        cleaner.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val method = ex.requestMethod
            when {
                method == "GET" && path == "/healthz" -> send(ex, 200, """{"ok":true}""")
                method == "GET" && path == "/rooms" -> staticAsset(ex, "audience.html", "text/html; charset=utf-8")
                method == "GET" && path == "/api/v1/rooms" -> listRooms(ex)
                method == "OPTIONS" -> corsOptions(ex)
                method == "GET" && path == "/assets/hls.min.js" -> staticAsset(ex, "hls.min.js", "application/javascript; charset=utf-8")
                method == "GET" && path == "/assets/mpegts.min.js" -> staticAsset(ex, "mpegts.min.js", "application/javascript; charset=utf-8")
                method == "GET" && Regex("^/live/[^/]+$").matches(path) -> proxyLive(ex, path.removePrefix("/live/"))
                method == "GET" && Regex("^/hls/[^/]+$").matches(path) -> proxyHls(ex, path.removePrefix("/hls/"))
                method == "GET" && Regex("^/s/[^/]+$").matches(path) -> cloudPlayer(ex, path.removePrefix("/s/"))
                method == "POST" && Regex("^/(whep|whip)/[^/]+$").matches(path) ->
                    proxyMedia(ex, path)
                method == "GET" && path == "/config" -> send(ex, 200, configPage(), "text/html; charset=utf-8")
                method == "GET" && path == "/api/v1/config" -> send(ex, 200, configJson())
                method == "POST" && path == "/api/v1/config" -> updateConfig(ex)
                method == "POST" && path == "/api/v1/telemetry" -> ingestTelemetry(ex)
                method == "GET" && path == "/api/v1/metrics" -> metrics(ex)
                method == "POST" && path == "/api/v1/auth/register" -> register(ex)
                method == "POST" && path == "/api/v1/auth/login" -> login(ex)
                method == "POST" && path == "/api/v1/devices/bind" -> bindDevice(ex)
                method == "GET" && path == "/api/v1/account" -> accountInfo(ex)
                method == "POST" && path == "/api/v1/account/plan" -> updatePlan(ex)
                method == "GET" && path == "/api/v1/account/usage" -> accountUsage(ex)
                method == "POST" && path == "/api/v1/rooms" -> createRoom(ex)
                method == "GET" && Regex("^/api/v1/rooms/[^/]+$").matches(path) -> getRoom(ex, path.removePrefix("/api/v1/rooms/"))
                method == "DELETE" && Regex("^/api/v1/rooms/[^/]+$").matches(path) -> deleteRoom(ex, path.removePrefix("/api/v1/rooms/"))
                method == "POST" && path.endsWith("/play-session") ->
                    playSession(ex, path.removePrefix("/api/v1/rooms/").removeSuffix("/play-session"))
                method == "GET" && path.endsWith("/status") ->
                    status(ex, path.removePrefix("/api/v1/rooms/").removeSuffix("/status"))
                method == "GET" && path.endsWith("/events") ->
                    events(ex, path.removePrefix("/api/v1/rooms/").removeSuffix("/events"))
                else -> send(ex, 404, error(2001, "not_found", "room or endpoint not found"))
            }
        } catch (t: Throwable) {
            send(ex, 500, error(7001, "internal_error", t.message ?: "internal"))
        }
    }

    private fun accountId(ex: HttpExchange): String? {
        val token = bearer(ex) ?: return null
        if (token == config.accountToken || token.startsWith("acct_")) return "dev"
        val claims = tokens.verify(token) ?: return null
        if (claims.aud != "account") return null
        return claims.sub.takeIf { accounts.byId(it) != null }
    }

    private fun register(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().readText()
        val account = accounts.register(field(body, "email") ?: "", field(body, "password") ?: "", field(body, "deviceId"))
            ?: return send(ex, 400, error(2007, "invalid_argument", "email/password/duplicate invalid"))
        val token = tokens.issue(account.id, "account", 7 * 24 * 3600, listOf("account"), false)
        send(ex, 201, accountJson(account, token))
    }

    private fun login(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().readText()
        val account = accounts.login(field(body, "email") ?: "", field(body, "password") ?: "", field(body, "deviceId"))
            ?: return send(ex, 401, error(3002, "token_invalid", "email/password/device invalid"))
        val token = tokens.issue(account.id, "account", 7 * 24 * 3600, listOf("account"), false)
        send(ex, 200, accountJson(account, token))
    }

    private fun accountInfo(ex: HttpExchange) {
        val id = accountId(ex) ?: return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val account = accounts.byId(id) ?: return send(ex, 404, error(2001, "not_found", "account not found"))
        send(ex, 200, accountJson(account, null))
    }

    private fun updatePlan(ex: HttpExchange) {
        val id = accountId(ex) ?: return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val body = ex.requestBody.bufferedReader().readText()
        val plan = field(body, "plan") ?: return send(ex, 400, error(2007, "invalid_argument", "plan required"))
        val account = accounts.setPlan(id, plan) ?: return send(ex, 400, error(2007, "invalid_argument", "unknown plan"))
        send(ex, 200, accountJson(account, null))
    }

    private fun accountUsage(ex: HttpExchange) {
        val id = accountId(ex) ?: return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val account = accounts.byId(id) ?: return send(ex, 404, error(2001, "not_found", "account not found"))
        send(ex, 200, """{"accountId":"${account.id}","plan":"${account.plan}","usedSeconds":${account.usedSeconds},"monthlyMinutes":${accounts.monthlyMinutes(account.plan)},"roomLimit":${accounts.roomLimit(account.plan)}}""")
    }

    private fun bindDevice(ex: HttpExchange) {
        val id = accountId(ex) ?: return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val body = ex.requestBody.bufferedReader().readText()
        val deviceId = field(body, "deviceId") ?: return send(ex, 400, error(2007, "invalid_argument", "deviceId required"))
        if (!accounts.bind(id, deviceId)) return send(ex, 409, error(2009, "device_limit", "device bind limit"))
        send(ex, 200, """{"bound":true,"deviceId":"$deviceId"}""")
    }

    private fun accountJson(account: AccountStore.Account, token: String?): String {
        val tokenPart = if (token == null) "" else ",\"token\":\"$token\""
        return """{"accountId":"${account.id}","email":"${account.email}","plan":"${account.plan}","devices":${account.devices.size},"roomLimit":${accounts.roomLimit(account.plan)},"usedSeconds":${account.usedSeconds},"monthlyMinutes":${accounts.monthlyMinutes(account.plan)}$tokenPart}"""
    }

    /** T3-S5：公开房间列表（观众端）。 */
    private fun listRooms(ex: HttpExchange) {
        val publicRooms = rooms.values
            .filter { it.state != "closed" && it.state != "failed" }
            .sortedByDescending { it.createdAt }
            .map { room ->
                """{"roomId":"${room.id}","state":"${room.state}","viewers":${room.viewers},"pinRequired":${room.pinRequired},"createdAt":${room.createdAt},"playUrl":"${config.playHost}/s/${room.id}?token=${room.pageToken}"}"""
            }
        send(ex, 200, """{"rooms":[${publicRooms.joinToString(",")}]}""")
    }

    private fun createRoom(ex: HttpExchange) {
        val accountId = accountId(ex) ?: return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val account = accounts.byId(accountId)
        val limit = if (account == null) config.maxRooms else minOf(config.maxRooms, accounts.roomLimit(account.plan))
        val body = ex.requestBody.bufferedReader().readText()
        val activeRooms = rooms.values.count { it.accountId == accountId && it.state != "closed" && it.state != "failed" }
        if (activeRooms >= limit) {
            return send(ex, 429, error(2004, "room_quota_exceeded", "max rooms $limit"))
        }
        val scope = field(body, "accessScope")
        if (scope != null && scope != "wan") {
            return send(ex, 400, error(2007, "invalid_argument", "accessScope must be wan"))
        }
        val idem = ex.requestHeaders.getFirst("Idempotency-Key")
        if (idem != null) {
            idempotency[idem]?.let { roomId ->
                val room = rooms[roomId]
                if (room != null) return send(ex, 201, roomJson(room))
            }
        }
        val now = clock()
        val id = "r" + (1..9).map { CROCKFORD[Random.nextInt(CROCKFORD.length)] }.joinToString("")
        val maxDuration = field(body, "maxDurationSec")?.toIntOrNull()?.coerceIn(60, config.maxDurationSec) ?: config.maxDurationSec
        if (account != null && accounts.usageExceeded(accountId, 0L)) {
            return send(ex, 429, error(2004, "room_quota_exceeded", "monthly minutes exceeded"))
        }
        val pinRequired = body.contains("\"pinRequired\"") && body.contains("true")
        val pin = field(body, "pin")
        if (pinRequired && (pin == null || !Regex("^\\d{4,6}$").matches(pin))) {
            return send(ex, 400, error(3004, "pin_required", "pin 4-6 digits required"))
        }
        val pageToken = tokens.issue(id, "page", 3600, listOf("play"), pinRequired, now)
        val ingestToken = tokens.issue(id, "ingest", maxDuration.toLong(), listOf("ingest"), false, now)
        val room = Room(id, accountId, now, now + maxDuration * 1000L, pageToken, ingestToken, pinRequired, pin)
        rooms[id] = room
        if (idem != null) idempotency[idem] = id
        send(ex, 201, roomJson(room))
    }

    private fun getRoom(ex: HttpExchange, id: String) {
        if (!isAccount(ex)) return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        send(ex, 200, roomJson(room))
    }

    private fun deleteRoom(ex: HttpExchange, id: String) {
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        val token = bearer(ex)
        val claims = token?.let { tokens.verify(it) }
        if (claims == null || claims.aud != "ingest" || claims.sub != id) {
            return send(ex, 401, error(3002, "token_invalid", "ingest token required"))
        }
        room.state = "stopping"
        room.state = "closed"
        if (room.accountId != "dev") {
            accounts.recordUsage(room.accountId, (clock() - room.createdAt) / 1000)
        }
        send(ex, 202, """{"roomId":"${room.id}","state":"closed"}""")
    }

    private fun playSession(ex: HttpExchange, id: String) {
        val ip = ex.remoteAddress?.address?.hostAddress ?: "unknown"
        if (!allowPlay(ip)) {
            return send(ex, 429, error(3008, "rate_limited", "play-session rate limit"))
        }
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        if (room.state != "created" && room.state != "live") {
            return send(ex, 410, error(2002, "room_closed", "room closed"))
        }
        val pageToken = ex.requestHeaders.getFirst("X-Page-Token")
        val claims = pageToken?.let { tokens.verify(it) }
        if (claims == null || claims.aud != "page" || claims.sub != id) {
            return send(ex, 401, error(3002, "token_invalid", "page token invalid"))
        }
        val body = ex.requestBody.bufferedReader().readText()
        if (room.pinRequired) {
            if (room.pinLockedUntil > clock()) {
                return send(ex, 429, error(3006, "pin_locked", "too many PIN failures"))
            }
            val supplied = field(body, "pin")
            if (supplied == null || supplied != room.pin) {
                room.pinFailures++
                if (room.pinFailures >= config.pinFailuresBeforeLock) {
                    room.pinLockedUntil = clock() + config.pinLockMs
                    return send(ex, 429, error(3006, "pin_locked", "too many PIN failures"))
                }
                return send(ex, 401, error(3005, "pin_invalid", "PIN required or incorrect"))
            }
            room.pinFailures = 0
        }
        if (room.viewers >= config.maxViewers) return send(ex, 429, error(2008, "max_viewers", "viewer limit"))
        room.viewers++
        room.lastActiveAt = clock()
        val media = tokens.issue(id, "media", 300, listOf("play"), false)
        val session = "ps_" + UUID.randomUUID().toString().replace("-", "").take(12)
        send(
            ex,
            201,
            """{"playSessionId":"$session","pageToken":"${room.pageToken}","mediaToken":"$media","protocols":{"webrtc":{"url":"${config.apiHost}/whep/$id"},"flv":{"url":"${config.apiHost}/live/$id.flv?token=$media"},"hls":{"url":"${config.apiHost}/hls/$id.m3u8?token=$media"}},"turn":${turnJson(room)},"maxViewers":${config.maxViewers}}""",
        )
    }

    private fun status(ex: HttpExchange, id: String) {
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        val pageToken = ex.requestHeaders.getFirst("X-Page-Token")
        val claims = pageToken?.let { tokens.verify(it) }
        if (claims == null || claims.sub != id) return send(ex, 401, error(3002, "token_invalid", "page token invalid"))
        send(ex, 200, """{"roomId":"${room.id}","state":"${room.state}","viewers":${room.viewers},"maxViewers":${config.maxViewers}}""")
    }

    /** 房间状态 SSE（骨架：state + heartbeat；viewer-count/ended 后续接入）。 */
    private fun events(ex: HttpExchange, id: String) {
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        val pageToken = ex.requestHeaders.getFirst("X-Page-Token")
        val claims = pageToken?.let { tokens.verify(it) }
        if (claims == null || claims.sub != id) return send(ex, 401, error(3002, "token_invalid", "page token invalid"))
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        try {
            fun emit(event: String, data: String) {
                out.write("retry: 3000\nevent: $event\ndata: $data\n\n".toByteArray())
                out.flush()
            }
            var lastViewers = room.viewers
            var lastState = room.state
            emit("state", """{"roomId":"${room.id}","state":"${room.state}","viewers":${room.viewers}}""")
            var lastHeartbeat = System.currentTimeMillis()
            while (server != null) {
                Thread.sleep(1_000)
                if (room.viewers != lastViewers) {
                    lastViewers = room.viewers
                    emit("viewer-count", """{"viewers":$lastViewers}""")
                }
                if (room.state != lastState) {
                    lastState = room.state
                    emit("state", """{"roomId":"${room.id}","state":"${room.state}","viewers":${room.viewers}}""")
                    if (lastState == "closed" || lastState == "failed") {
                        emit("ended", """{"reason":"room_closed"}""")
                        break
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= 15_000) {
                    room.lastActiveAt = clock()
                    lastHeartbeat = now
                    emit("heartbeat", "{}")
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { out.close() }
        }
    }

    private fun ingestTelemetry(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().readText()
        val event = field(body, "event")?.takeIf { it.isNotBlank() } ?: return send(ex, 400, error(2007, "invalid_argument", "event required"))
        telemetryCounts.merge(event, 1L, Long::plus)
        val protocol = field(body, "protocol")
        val ttff = Regex("\"timeToFirstFrameMs\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
        if (event == "play_first_frame" && protocol != null && ttff != null) {
            firstFrameMs.getOrPut(protocol) { java.util.Collections.synchronizedList(mutableListOf()) }.add(ttff)
        }
        send(ex, 202, """{"accepted":true}""")
    }

    private fun metrics(ex: HttpExchange) {
        fun summarize(values: List<Long>): String {
            if (values.isEmpty()) return """{"count":0}"""
            val sorted = values.sorted()
            fun p(q: Double): Long = sorted[(Math.ceil(sorted.size * q).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return """{"count":${sorted.size},"p50":${p(0.50)},"p95":${p(0.95)},"max":${sorted.last()}}"""
        }
        val protocols = firstFrameMs.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${summarize(v.toList())}"
        }
        val counts = telemetryCounts.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
        send(ex, 200, """{"counts":$counts,"firstFrame":$protocols}""")
    }

    private fun allowPlay(ip: String): Boolean {
        val now = clock()
        val dq = playRates.getOrPut(ip) { ArrayDeque() }
        synchronized(dq) {
            while (dq.isNotEmpty() && now - dq.first() > 60_000) dq.removeFirst()
            if (dq.size >= config.playSessionPerMinute) return false
            dq.addLast(now)
            return true
        }
    }

    private fun cleanupIdle() {
        val now = clock()
        rooms.values.forEach { room ->
            if (room.state != "closed" && now - room.lastActiveAt > config.idleRoomTtlMs) {
                room.state = "closed"
            }
        }
    }

    private fun turnJson(room: Room): String {
        val turnUser = "${(clock() + 3600_000) / 1000}:${room.id}"
        val credential = Tokens.turnCredential(config.turnSecret, turnUser)
        return """{"urls":${config.turnUrls.joinToString(",", "[", "]") { "\"$it\"" }},"username":"$turnUser","credential":"$credential"}"""
    }

    private fun roomJson(room: Room): String {
        val turnUser = "${(clock() + 3600_000) / 1000}:${room.id}"
        val credential = Tokens.turnCredential(config.turnSecret, turnUser)
        return """{"roomId":"${room.id}","state":"${room.state}","createdAt":"${Instant.ofEpochMilli(room.createdAt)}","expireAt":"${Instant.ofEpochMilli(room.expireAt)}","playUrl":"${config.playHost}/s/${room.id}?token=${room.pageToken}","ingest":{"protocol":"whip","whipUrl":"${config.whipHost}:${config.whipApiPort}/rtc/v1/whip/?app=live&stream=${room.id}","rtmpUrl":"rtmp://localhost:1935/live/${room.id}","srtUrl":"srt://localhost:10080?streamid=#!::r=${room.id}","token":"${room.ingestToken}"},"turn":{"urls":${config.turnUrls.joinToString(",", "[", "]") { "\"$it\"" }},"username":"$turnUser","credential":"$credential"},"limits":{"maxViewers":${config.maxViewers},"maxDurationSec":${config.maxDurationSec}}}"""
    }

    /**
     * WHIP/WHEP 反向代理：云端对外统一 apiHost，媒体后端仍是 SRS。
     * 客户端只看到 `/whep/{roomId}` / `/whip/{roomId}`，不暴露 SRS 端口。
     */
    private fun proxyMedia(ex: HttpExchange, path: String) {
        val parts = path.trim('/').split('/')
        if (parts.size != 2) return send(ex, 404, error(2001, "not_found", "bad media path"))
        val kind = parts[0]
        val roomId = parts[1]
        val room = rooms[roomId] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        if (room.state == "closed" || room.state == "failed") {
            return sendCors(ex, 410, error(2002, "room_closed", "room closed"))
        }
        val token = bearer(ex)
        val claims = token?.let { tokens.verify(it) }
        val aud = if (kind == "whep") "media" else "ingest"
        if (claims == null || claims.aud != aud || claims.sub != roomId) {
            return sendCors(ex, 401, error(3002, "token_invalid", "media token required"))
        }
        val body = ex.requestBody.readBytes()
        val target = "${config.whipHost}:${config.whipApiPort}/rtc/v1/$kind/?app=live&stream=$roomId"
        val request = HttpRequest.newBuilder(URI.create(target))
            .header("Content-Type", ex.requestHeaders.getFirst("Content-Type") ?: "application/sdp")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val contentType = response.headers().firstValue("Content-Type").orElse("application/sdp")
            sendCors(ex, response.statusCode(), String(response.body()), contentType)
        } catch (t: Throwable) {
            sendCors(ex, 502, error(7001, "media_unavailable", t.message ?: "SRS unavailable"))
        }
    }

    private fun staticAsset(ex: HttpExchange, name: String, contentType: String) {
        val bytes = javaClass.classLoader.getResourceAsStream("com/campush/cloud/$name")?.readBytes()
            ?: return send(ex, 404, error(2001, "not_found", "asset missing"))
        send(ex, 200, String(bytes), contentType)
    }

    /** 云端播放页：token 在 query，页面内用 X-Page-Token 换 play-session。 */
    private fun cloudPlayer(ex: HttpExchange, roomId: String) {
        val room = rooms[roomId] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        val query = ex.requestURI.rawQuery ?: ""
        val token = query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "token") URLDecoder.decode(kv[1], "UTF-8") else null
        }.firstOrNull()
        val claims = token?.let { tokens.verify(it) }
        if (claims == null || claims.sub != roomId) return send(ex, 401, error(3002, "token_invalid", "page token invalid"))
        staticAsset(ex, "player.html", "text/html; charset=utf-8")
    }

    private fun corsOptions(ex: HttpExchange) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS,PATCH,DELETE")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Page-Token,Idempotency-Key")
        ex.sendResponseHeaders(204, -1)
        ex.close()
    }

    private fun sendCors(ex: HttpExchange, status: Int, body: String, contentType: String = "application/json; charset=utf-8") {
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        send(ex, status, body, contentType)
    }

    /** 同源 FLV 代理：播放页只访问 cloud-core，避免 SRS CORS/混合内容问题。 */
    private fun proxyLive(ex: HttpExchange, file: String) {
        val roomId = file.substringBefore('.')
        val room = rooms[roomId]
        if (room == null || room.state == "closed" || room.state == "failed") {
            return send(ex, 410, error(2002, "room_closed", "room closed"))
        }
        val token = (ex.requestURI.rawQuery ?: "").split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "token") URLDecoder.decode(kv[1], "UTF-8") else null
        }.firstOrNull()
        val claims = token?.let { tokens.verify(it) }
        val valid = claims != null && claims.aud == "media" &&
            (if (file.endsWith(".m3u8")) claims.sub == roomId else true)
        if (!valid) {
            return send(ex, 401, error(3002, "token_invalid", "media token required"))
        }
        val extraQuery = (ex.requestURI.rawQuery ?: "").split('&')
            .filter { it.isNotEmpty() && !it.startsWith("token=") }
            .joinToString("&")
        val target = "${config.playbackHost}:${config.playbackPort}/live/$file" +
            if (extraQuery.isNotEmpty()) "?$extraQuery" else ""
        try {
            val upstream = httpClient.send(
                HttpRequest.newBuilder(URI.create(target)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            if (upstream.statusCode() !in 200..299) {
                upstream.body().close()
                return send(ex, upstream.statusCode(), error(7001, "media_unavailable", "upstream ${upstream.statusCode()}"))
            }
            ex.responseHeaders.add("Content-Type", upstream.headers().firstValue("Content-Type").orElse("video/x-flv"))
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.sendResponseHeaders(200, 0)
            upstream.body().use { input -> ex.responseBody.use { output -> input.copyTo(output) } }
        } catch (t: Throwable) {
            send(ex, 502, error(7001, "media_unavailable", t.message ?: "SRS unavailable"))
        }
    }

    /** HLS 同源代理：playlist 内 .ts 相对地址改写为带 token 的 /hls 路径。 */
    private fun proxyHls(ex: HttpExchange, file: String) {
        val query = ex.requestURI.rawQuery ?: ""
        val token = query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "token") URLDecoder.decode(kv[1], "UTF-8") else null
        }.firstOrNull()
        val claims = token?.let { tokens.verify(it) }
        if (claims == null || claims.aud != "media") {
            return send(ex, 401, error(3002, "token_invalid", "media token required"))
        }
        val room = rooms[claims.sub]
        if (room == null || room.state == "closed" || room.state == "failed") {
            return send(ex, 410, error(2002, "room_closed", "room closed"))
        }
        val extraQuery = (ex.requestURI.rawQuery ?: "").split('&')
            .filter { it.isNotEmpty() && !it.startsWith("token=") }
            .joinToString("&")
        val target = "${config.playbackHost}:${config.playbackPort}/live/$file" +
            if (extraQuery.isNotEmpty()) "?$extraQuery" else ""
        try {
            val upstream = httpClient.send(
                HttpRequest.newBuilder(URI.create(target)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (upstream.statusCode() !in 200..299) {
                return send(ex, upstream.statusCode(), error(7001, "media_unavailable", "upstream ${upstream.statusCode()}"))
            }
            val contentType = upstream.headers().firstValue("Content-Type").orElse("application/vnd.apple.mpegurl")
            val body = String(upstream.body())
            if (file.endsWith(".m3u8")) {
                val baseDir = file.substringBeforeLast('/', "")
                val prefix = if (baseDir.isEmpty()) "" else "$baseDir/"
                val rewritten = body.lineSequence().joinToString("\n") { line ->
                    val t = line.trim()
                    when {
                        t.isEmpty() || t.startsWith("#") -> line
                        t.startsWith("http://") || t.startsWith("https://") -> line
                        t.startsWith("/live/") -> {
                            val rel = t.removePrefix("/live/")
                            val sep = if (rel.contains('?')) '&' else '?'
                            "/hls/$rel${sep}token=$token"
                        }
                        t.startsWith("/") -> t
                        else -> {
                            val sep = if (t.contains('?')) '&' else '?'
                            "/hls/$prefix$t${sep}token=$token"
                        }
                    }
                }
                send(ex, 200, rewritten, contentType)
            } else {
                sedBytes(ex, upstream.body(), contentType)
            }
        } catch (t: Throwable) {
            send(ex, 502, error(7001, "media_unavailable", t.message ?: "SRS unavailable"))
        }
    }

    private fun sedBytes(ex: HttpExchange, bytes: ByteArray, contentType: String) {
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun configJson(): String =
        """{"apiHost":"${config.apiHost}","playHost":"${config.playHost}","whipHost":"${config.whipHost}","whipApiPort":${config.whipApiPort},"playbackHost":"${config.playbackHost}","playbackPort":${config.playbackPort},"turnUrls":${config.turnUrls.joinToString(",", "[", "]") { "\"$it\"" }}}"""

    private fun updateConfig(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().readText()
        val contentType = ex.requestHeaders.getFirst("Content-Type") ?: ""
        fun value(name: String): String? =
            if (contentType.startsWith("application/x-www-form-urlencoded")) {
                body.split('&').mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    if (kv.size == 2 && kv[0] == name) java.net.URLDecoder.decode(kv[1], "UTF-8") else null
                }.firstOrNull()
            } else {
                field(body, name)
            }
        val whipHost = value("whipHost")?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return send(ex, 400, error(2007, "invalid_argument", "whipHost must be http(s) URL"))
        val apiHost = value("apiHost")?.trim()?.takeIf { it.isNotBlank() } ?: config.apiHost
        val playHost = value("playHost")?.trim()?.takeIf { it.isNotBlank() } ?: config.playHost
        val whipPort = value("whipApiPort")?.toIntOrNull()
            ?: Regex("\"whipApiPort\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: config.whipApiPort
        val turnUrls = value("turnUrls")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val playbackHost = value("playbackHost")?.trim()?.takeIf { it.isNotBlank() } ?: config.playbackHost
        val playbackPort = value("playbackPort")?.toIntOrNull()
            ?: Regex("\"playbackPort\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: config.playbackPort
        config = config.copy(
            apiHost = apiHost,
            playHost = playHost,
            whipHost = whipHost.removeSuffix("/"),
            whipApiPort = whipPort,
            playbackHost = playbackHost.removeSuffix("/"),
            playbackPort = playbackPort,
            turnUrls = turnUrls ?: config.turnUrls,
        )
        send(ex, 200, configJson())
    }

    private fun configPage(): String = """<!doctype html><meta charset="utf-8">
<title>CamPush Cloud 配置</title>
<style>body{font:14px system-ui;background:#0b0f14;color:#e8eef6;padding:24px;max-width:720px}label{display:block;margin:10px 0 4px}input{width:100%;padding:8px;background:#121a24;color:#e8eef6;border:1px solid #1e2a38;border-radius:6px}button{margin-top:14px;padding:10px 18px;background:#34d399;border:0;border-radius:8px;font-weight:600}</style>
<h2>CamPush Cloud 地址配置（本地开发）</h2>
<p>默认值来自本地 Docker：SRS <code>srs.m0-wan.orb.local</code>（页面 8080 / 信令 1985）。保存后创建房间立即生效。</p>
<form method="post" action="/api/v1/config">
<label>API Host（房间 REST）</label><input name="apiHost" value="${config.apiHost}">
<label>Play Host（播放地址/FLV/HLS）</label><input name="playHost" value="${config.playHost}">
<label>WHIP Host（WebRTC 推流）</label><input name="whipHost" value="${config.whipHost}">
<label>WHIP API Port</label><input name="whipApiPort" value="${config.whipApiPort}">
<label>Playback Host（FLV/HLS）</label><input name="playbackHost" value="${config.playbackHost}">
<label>Playback Port（FLV/HLS）</label><input name="playbackPort" value="${config.playbackPort}">
<label>TURN URLs（逗号分隔）</label><input name="turnUrls" value="${config.turnUrls.joinToString(",")}">
<button type="submit">保存配置</button>
</form>
<p><a style="color:#34d399" href="/api/v1/config">查看 JSON</a></p>"""

    private fun isAccount(ex: HttpExchange): Boolean = bearer(ex) == config.accountToken || bearer(ex)?.startsWith("acct_") == true

    private fun bearer(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    private fun send(ex: HttpExchange, status: Int, body: String, contentType: String = "application/json; charset=utf-8") {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    private fun error(code: Int, error: String, message: String) =
        """{"code":$code,"error":"$error","message":"$message","retryable":false}"""

    companion object {
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
