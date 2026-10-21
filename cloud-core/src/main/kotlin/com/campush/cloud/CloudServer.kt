package com.campush.cloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * P2 T1-S1 房间 REST 骨架（本地/契约联调用，媒体面 WHIP/WHEP 由 SRS/Mediasoup 承担）。
 *
 * 已实现：创建/查询/停止房间、pageToken/mediaToken/ingestToken、play-session、status。
 * 未实现：账号体系、计费/额度、SSE、WHIP/WHEP 代理（后续任务）。
 */
class CloudServer(
    private val config: Config,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val apiHost: String = "http://127.0.0.1:18090",
        val playHost: String = "http://127.0.0.1:18090",
        val whipHost: String = "http://srs.m0-wan.orb.local",
        val whipApiPort: Int = 1985,
        val turnUrls: List<String> = listOf("turn:127.0.0.1:3478?transport=udp"),
        val turnSecret: String = "dev-turn-secret",
        val accountToken: String = "acct_dev",
        val maxViewers: Int = 100,
        val maxDurationSec: Int = 43_200,
        val idleRoomTtlMs: Long = 10 * 60 * 1000L,
    )

    data class Room(
        val id: String,
        val createdAt: Long,
        val expireAt: Long,
        val pageToken: String,
        val ingestToken: String,
        val pinRequired: Boolean,
        var state: String = "created",
        var viewers: Int = 0,
        var lastActiveAt: Long = createdAt,
    )

    private val tokens = Tokens(UUID.randomUUID().toString().replace("-", "").toByteArray())
    private val rooms = ConcurrentHashMap<String, Room>()
    private val idempotency = ConcurrentHashMap<String, String>()
    private var server: HttpServer? = null
    private val cleaner = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cloud-room-cleaner").apply { isDaemon = true }
    }

    val port: Int get() = server?.address?.port ?: -1

    fun start(port: Int = 0): Int {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
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
                method == "POST" && path == "/api/v1/rooms" -> createRoom(ex)
                method == "GET" && Regex("^/api/v1/rooms/[^/]+$").matches(path) -> getRoom(ex, path.removePrefix("/api/v1/rooms/"))
                method == "DELETE" && Regex("^/api/v1/rooms/[^/]+$").matches(path) -> deleteRoom(ex, path.removePrefix("/api/v1/rooms/"))
                method == "POST" && path.endsWith("/play-session") ->
                    playSession(ex, path.removePrefix("/api/v1/rooms/").removeSuffix("/play-session"))
                method == "GET" && path.endsWith("/status") ->
                    status(ex, path.removePrefix("/api/v1/rooms/").removeSuffix("/status"))
                else -> send(ex, 404, error(2001, "not_found", "room or endpoint not found"))
            }
        } catch (t: Throwable) {
            send(ex, 500, error(7001, "internal_error", t.message ?: "internal"))
        }
    }

    private fun createRoom(ex: HttpExchange) {
        if (!isAccount(ex)) return send(ex, 401, error(3001, "token_missing", "account bearer required"))
        val body = ex.requestBody.bufferedReader().readText()
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
        val pinRequired = body.contains("\"pinRequired\"") && body.contains("true")
        val pageToken = tokens.issue(id, "page", 3600, listOf("play"), pinRequired, now)
        val ingestToken = tokens.issue(id, "ingest", maxDuration.toLong(), listOf("ingest"), false, now)
        val room = Room(id, now, now + maxDuration * 1000L, pageToken, ingestToken, pinRequired)
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
        send(ex, 202, """{"roomId":"${room.id}","state":"closed"}""")
    }

    private fun playSession(ex: HttpExchange, id: String) {
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        if (room.state != "created" && room.state != "live") {
            return send(ex, 410, error(2002, "room_closed", "room closed"))
        }
        val pageToken = ex.requestHeaders.getFirst("X-Page-Token")
        val claims = pageToken?.let { tokens.verify(it) }
        if (claims == null || claims.aud != "page" || claims.sub != id) {
            return send(ex, 401, error(3002, "token_invalid", "page token invalid"))
        }
        if (room.viewers >= config.maxViewers) return send(ex, 429, error(2008, "max_viewers", "viewer limit"))
        room.viewers++
        room.lastActiveAt = clock()
        val media = tokens.issue(id, "media", 300, listOf("play"), false)
        val session = "ps_" + UUID.randomUUID().toString().replace("-", "").take(12)
        send(
            ex,
            201,
            """{"playSessionId":"$session","pageToken":"${room.pageToken}","mediaToken":"$media","protocols":{"webrtc":{"url":"${config.apiHost}/whep/$id"},"flv":{"url":"${config.playHost}/live/$id.flv"},"hls":{"url":"${config.playHost}/live/$id.m3u8"}},"maxViewers":${config.maxViewers}}""",
        )
    }

    private fun status(ex: HttpExchange, id: String) {
        val room = rooms[id] ?: return send(ex, 404, error(2001, "not_found", "room not found"))
        val pageToken = ex.requestHeaders.getFirst("X-Page-Token")
        val claims = pageToken?.let { tokens.verify(it) }
        if (claims == null || claims.sub != id) return send(ex, 401, error(3002, "token_invalid", "page token invalid"))
        send(ex, 200, """{"roomId":"${room.id}","state":"${room.state}","viewers":${room.viewers},"maxViewers":${config.maxViewers}}""")
    }

    private fun cleanupIdle() {
        val now = clock()
        rooms.values.forEach { room ->
            if (room.state != "closed" && now - room.lastActiveAt > config.idleRoomTtlMs) {
                room.state = "closed"
            }
        }
    }

    private fun roomJson(room: Room): String {
        val turnUser = "${(clock() + 3600_000) / 1000}:${room.id}"
        val credential = Tokens.sha256("${config.turnSecret}$turnUser".toByteArray())
        return """{"roomId":"${room.id}","state":"${room.state}","createdAt":"${Instant.ofEpochMilli(room.createdAt)}","expireAt":"${Instant.ofEpochMilli(room.expireAt)}","playUrl":"${config.playHost}/s/${room.id}?token=${room.pageToken}","ingest":{"protocol":"whip","whipUrl":"${config.whipHost.replace("http://","http://")}:${config.whipApiPort}/rtc/v1/whip/?app=live&stream=${room.id}","rtmpUrl":"rtmp://localhost:1935/live/${room.id}","srtUrl":"srt://localhost:10080?streamid=#!::r=${room.id}","token":"${room.ingestToken}"},"turn":{"urls":${config.turnUrls.joinToString(",", "[", "]") { "\"$it\"" }},"username":"$turnUser","credential":"$credential"},"limits":{"maxViewers":${config.maxViewers},"maxDurationSec":${config.maxDurationSec}}}"""
    }

    private fun isAccount(ex: HttpExchange): Boolean = bearer(ex) == config.accountToken || bearer(ex)?.startsWith("acct_") == true

    private fun bearer(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    private fun send(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
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
