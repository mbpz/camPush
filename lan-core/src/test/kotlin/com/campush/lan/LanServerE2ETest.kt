package com.campush.lan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class LanServerE2ETest {

    private var now = 1_700_000_000_000L
    private var server: LanServer? = null
    private var url: String = ""
    private val events = CopyOnWriteArrayList<LanEventData>()
    private val clock: () -> Long = { now }

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    // ------------------------------------------------------------- 用例

    @Test
    fun startPageStatusHealthzAndAuth() {
        val s = start()
        val port = s.status().port
        val token = token()

        assertTrue("url=$url", url.startsWith("http://192.0.2.10:$port/s/"))
        assertEquals(LanRoomState.LIVE, s.status().state)

        val health = TestHttp.request(port, "GET", "/healthz")
        assertEquals(200, health.status)
        assertEquals("""{"ok":true}""", health.text)
        assertEquals("headers=${health.headers}", "CamPush-LAN/1.0", health.headers["server"])

        val badPage = TestHttp.request(port, "GET", "/s/tampered-token")
        assertEquals(401, badPage.status)
        assertTrue(badPage.headers["content-type"]!!.startsWith("text/html"))

        val page = TestHttp.request(port, "GET", "/s/$token")
        assertEquals(200, page.status)
        assertTrue(page.headers["content-type"]!!.startsWith("text/html"))
        assertEquals("no-store", page.headers["cache-control"])
        assertEquals("DENY", page.headers["x-frame-options"])
        assertTrue("播放页应包含脚本", page.text.contains("<script"))

        val noAuth = TestHttp.request(port, "GET", "/api/v1/status")
        assertEquals(401, noAuth.status)
        assertEquals(3001, jsonCode(noAuth.text))

        val status = TestHttp.request(port, "GET", "/api/v1/status", mapOf("X-Page-Token" to token))
        assertEquals(200, status.status)
        assertEquals("application/json; charset=utf-8", status.headers["content-type"])
        assertEquals("live", jsonField(status.text, "state"))
        assertEquals("192.0.2.10", jsonField(status.text, "lanIp"))
        assertEquals(port, (jsonField(status.text, "port") as Number).toInt())
        assertEquals(0, (jsonField(status.text, "viewers") as Number).toInt())
        assertEquals(3, (jsonField(status.text, "maxViewers") as Number).toInt())
        assertEquals(false, jsonField(status.text, "pinRequired"))
        assertEquals(listOf("flv", "mjpeg"), jsonField(status.text, "mediaProtocols"))
        assertEquals(0, (jsonField(status.text, "uptimeSec") as Number).toInt())
        assertNotNull(jsonField(status.text, "bitrateKbps"))
        assertNotNull(jsonField(status.text, "fps"))

        val options = TestHttp.request(port, "OPTIONS", "/api/v1/status")
        assertEquals(204, options.status)
        assertEquals(null, options.headers["access-control-allow-origin"])
        assertEquals(0, options.body.size)

        val unknown = TestHttp.request(port, "GET", "/nope")
        assertEquals(404, unknown.status)
        assertTrue(events.any { it.event == LanEvent.SERVER_STARTED })
    }

    @Test
    fun playSessionRefreshTelemetryAndExpiredMediaToken() {
        val s = start()
        val port = s.status().port
        val token = token()

        val play = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
        assertEquals(200, play.status)
        val playSessionId = jsonField(play.text, "playSessionId") as String
        val mediaToken = jsonField(play.text, "mediaToken") as String
        assertTrue(playSessionId.isNotEmpty())
        assertTrue(mediaToken.isNotEmpty())
        assertEquals(300, (jsonField(play.text, "expiresIn") as Number).toInt())
        assertEquals(false, jsonField(play.text, "pinRequired"))
        val protocols = jsonField(play.text, "protocols") as Map<*, *>
        assertEquals("/live.flv", (protocols["flv"] as Map<*, *>)["url"])
        assertEquals("/stream.mjpg", (protocols["mjpeg"] as Map<*, *>)["url"])

        val refresh = TestHttp.postJson(port, "/api/v1/page-token:refresh", mapOf("X-Page-Token" to token), "{}")
        assertEquals(200, refresh.status)
        val refreshed = jsonField(refresh.text, "pageToken") as String
        assertNotEquals(token, refreshed)
        assertEquals(3600, (jsonField(refresh.text, "expiresIn") as Number).toInt())
        val statusWithNew = TestHttp.request(port, "GET", "/api/v1/status", mapOf("X-Page-Token" to refreshed))
        assertEquals(200, statusWithNew.status)
        // 旧 token 在 exp 前仍有效
        assertEquals(200, TestHttp.request(port, "GET", "/api/v1/status", mapOf("X-Page-Token" to token)).status)

        val telemetry = TestHttp.postJson(
            port,
            "/api/v1/telemetry/batch",
            emptyMap(),
            """{"events":[{"name":"app_open"},{"name":"push_start_clicked"},{"name":"play_first_frame"}]}""",
        )
        assertEquals(202, telemetry.status)
        assertEquals(3, (jsonField(telemetry.text, "accepted") as Number).toInt())
        assertEquals(0, (jsonField(telemetry.text, "dropped") as Number).toInt())
        // 00 §11 白名单外的名字丢弃，但计入 dropped；摘要按事件名计数。
        val invalid = TestHttp.postJson(
            port,
            "/api/v1/telemetry/batch",
            emptyMap(),
            """{"events":[{"name":"not_allowed_event"}]}""",
        )
        assertEquals(202, invalid.status)
        assertEquals(0, (jsonField(invalid.text, "accepted") as Number).toInt())
        assertEquals(1, (jsonField(invalid.text, "dropped") as Number).toInt())
        val summary = s.telemetrySummary()
        assertEquals(1L, summary["app_open"]?.toLong() ?: 0L)
        assertEquals(1L, summary["push_start_clicked"]?.toLong() ?: 0L)
        assertEquals(1L, summary["play_first_frame"]?.toLong() ?: 0L)

        // mediaToken 过期 -> 401 + 3003
        now += 301_000L
        val expired = TestHttp.request(port, "GET", "/live.flv?mt=$mediaToken")
        assertEquals(401, expired.status)
        assertEquals(3003, jsonCode(expired.text))

        // pageToken 作 mt 使用 -> 类型不匹配 3002
        val wrongType = TestHttp.request(port, "GET", "/live.flv?mt=$token")
        assertEquals(401, wrongType.status)
        assertEquals(3002, jsonCode(wrongType.text))

        val missing = TestHttp.request(port, "GET", "/live.flv")
        assertEquals(401, missing.status)
        assertEquals(3001, jsonCode(missing.text))
    }

    @Test
    fun pinFlow() {
        val s = start(pinConfig())
        val port = s.status().port
        val token = token()
        assertTrue(s.status().pinRequired)

        val missing = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
        assertEquals(401, missing.status)
        assertEquals(3004, jsonCode(missing.text))

        val wrong = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), """{"pin":"0000"}""")
        assertEquals(401, wrong.status)
        assertEquals(3005, jsonCode(wrong.text))

        val correct = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), """{"pin":"1234"}""")
        assertEquals(200, correct.status)
        assertEquals(true, jsonField(correct.text, "pinRequired"))
        assertTrue((jsonField(correct.text, "mediaToken") as String).isNotEmpty())
    }

    @Test
    fun flvStreamStartsWithHeaderMetadataAndKeyframe() {
        val s = start()
        val port = s.status().port
        publishSampleMedia(s)
        val mediaToken = issueMediaToken(port)

        val stream = TestHttp.openStream(port, "/live.flv?mt=$mediaToken")
        try {
            assertEquals(200, stream.status)
            assertTrue(stream.headers["content-type"]!!.startsWith("video/x-flv"))
            assertEquals("no-store", stream.headers["cache-control"])
            assertEquals("close", stream.headers["connection"])

            val bytes = stream.readAtLeast(256, timeoutMs = 2_000)
            assertTrue("FLV 字节不足: ${bytes.size}", bytes.size >= 64)
            assertEquals('F'.code.toByte(), bytes[0])
            assertEquals('L'.code.toByte(), bytes[1])
            assertEquals('V'.code.toByte(), bytes[2])
            assertEquals(1, bytes[3].toInt())
            assertEquals(9, bytes[8].toInt())

            val tags = FlvParser.parseTags(bytes)
            assertTrue(tags.isNotEmpty())
            assertTrue("首 tag 应为 onMetaData", tags[0].isScript())
            assertTrue("缺少 AVC sequence header", tags.any { it.isVideo() && it.avcPacketType() == 0 })
            assertTrue("缺少 AAC sequence header", tags.any { it.isAudio() && it.aacPacketType() == 0 })
            val firstVideoFrame = tags.firstOrNull { it.isVideo() && it.avcPacketType() == 1 }
            assertNotNull(firstVideoFrame)
            assertEquals("起播首个视频帧必须是关键帧", 1, firstVideoFrame!!.videoFrameType())
            assertTrue(tags.any { it.isAudio() && it.aacPacketType() == 1 })
            assertTrue("时间戳应单调", tags.filter { it.isVideo() }.zipWithNext().all { (a, b) -> a.timestamp <= b.timestamp })
        } finally {
            stream.close()
        }
    }

    @Test
    fun mjpegMultipartStream() {
        val s = start()
        val port = s.status().port
        val mediaToken = issueMediaToken(port)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { it.toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        s.publisher.publishJpeg(jpeg)

        val stream = TestHttp.openStream(port, "/stream.mjpg?mt=$mediaToken")
        try {
            assertEquals(200, stream.status)
            val contentType = stream.headers["content-type"]!!
            assertTrue(contentType.startsWith("multipart/x-mixed-replace"))
            assertTrue(contentType.contains("boundary=--frame"))

            val bytes = stream.readAtLeast(jpeg.size + 90, timeoutMs = 2_000)
            val head = String(bytes.copyOfRange(0, minOf(bytes.size, 120)), StandardCharsets.ISO_8859_1)
            assertTrue("MPEG part 头缺失: $head", head.contains("--frame"))
            assertTrue(head.contains("Content-Type: image/jpeg"))
            assertTrue("JPEG 帧未出现在 multipart 中", FlvParser.indexOf(bytes, jpeg) >= 0)
        } finally {
            stream.close()
        }
    }

    @Test
    fun viewerLimitReturns429AndCountsConnections() {
        val s = start(defaultConfig(maxViewers = 1))
        val port = s.status().port
        val token = token()

        val first = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
        assertEquals(200, first.status)
        val firstSession = jsonField(first.text, "playSessionId") as String
        val firstToken = jsonField(first.text, "mediaToken") as String

        val second = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
        assertEquals(200, second.status)
        val secondToken = jsonField(second.text, "mediaToken") as String

        val stream = TestHttp.openStream(port, "/live.flv?mt=$firstToken")
        try {
            assertEquals(200, stream.status)
            waitUntil(2_000L) { s.status().viewers == 1 }

            val rejected = TestHttp.request(port, "GET", "/live.flv?mt=$secondToken")
            assertEquals(429, rejected.status)
            assertEquals(6005, jsonCode(rejected.text))
            assertTrue(rejected.headers["retry-after"] == null)

            val playRejected = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
            assertEquals(429, playRejected.status)
            assertEquals(6005, jsonCode(playRejected.text))

            // 复用 playSessionId 续期：不重复计观看者，允许在满员时续期
            val renewal = TestHttp.postJson(
                port,
                "/api/v1/play-session",
                mapOf("X-Page-Token" to token),
                """{"playSessionId":"$firstSession"}""",
            )
            assertEquals(200, renewal.status)
            assertEquals(firstSession, jsonField(renewal.text, "playSessionId"))
        } finally {
            stream.close()
        }

        waitUntil(3_000L) { s.status().viewers == 0 }
        val third = TestHttp.openStream(port, "/live.flv?mt=$secondToken")
        try {
            assertEquals(200, third.status)
        } finally {
            third.close()
        }
        assertTrue(events.any { it.event == LanEvent.VIEWER_JOINED })
        assertTrue(events.any { it.event == LanEvent.VIEWER_LEFT })
        assertTrue(events.any { it.event == LanEvent.VIEWER_LIMITED })
    }

    @Test
    fun pinRateLimitAndLockout() {
        val s = start(pinConfig())
        val port = s.status().port
        val token = token()
        fun wrong(): HttpResp = TestHttp.postJson(
            port,
            "/api/v1/play-session",
            mapOf("X-Page-Token" to token),
            """{"pin":"0000"}""",
        )

        // 单 IP 5 次/分钟：第 6 次 -> 3008。
        repeat(5) { assertEquals(3005, jsonCode(wrong().text)) }
        assertEquals(3008, jsonCode(wrong().text))

        // 窗口重置后继续失败，连续 10 次 -> 3006 锁定。
        now += 61_000L
        repeat(4) { assertEquals(3005, jsonCode(wrong().text)) }
        assertEquals(3006, jsonCode(wrong().text))
        // 锁定期内即使 PIN 正确也拒绝。
        val lockedCorrect = TestHttp.postJson(
            port,
            "/api/v1/play-session",
            mapOf("X-Page-Token" to token),
            """{"pin":"1234"}""",
        )
        assertEquals(3006, jsonCode(lockedCorrect.text))
        assertTrue(s.telemetrySummary().isNotEmpty())
    }

    @Test
    fun logDownloadRequiresValidPageToken() {
        val s = start(defaultConfig().copy(logProvider = { "CAMPLOG\nline-1\nline-2" }))
        val port = s.status().port
        val token = token()

        val ok = TestHttp.request(port, "GET", "/s/$token/logs")
        assertEquals(200, ok.status)
        assertTrue(ok.text.contains("CAMPLOG"))
        assertTrue("headers=${ok.headers}", ok.headers["content-disposition"]?.contains("attachment") == true)

        val bad = TestHttp.request(port, "GET", "/s/tampered/logs")
        assertEquals(401, bad.status)
    }

    @Test
    fun stopInvalidatesPortAndIsFast() {
        val s = start()
        val port = s.status().port
        publishSampleMedia(s)
        val mediaToken = issueMediaToken(port)
        val stream = TestHttp.openStream(port, "/live.flv?mt=$mediaToken")
        try {
            assertEquals(200, stream.status)
            val startedNs = System.nanoTime()
            s.stop()
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            assertTrue("stop 耗时 ${elapsedMs}ms 超过 1s", elapsedMs <= 1_000L)
        } finally {
            stream.close()
        }

        assertEquals(LanRoomState.CLOSED, s.status().state)
        assertEquals(0, s.status().viewers)
        assertTrue(events.any { it.event == LanEvent.SERVER_STOPPED })

        // 可重复 stop
        s.stop()
        assertEquals(LanRoomState.CLOSED, s.status().state)

        try {
            TestHttp.request(port, "GET", "/healthz", timeoutMs = 1_000)
            fail("停止后端口必须 connection refused")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun ipProviderNullReturns6003() {
        val s = LanServer(defaultConfig(), { null }, { event -> events.add(event) }, clock)
        server = s
        val error = assertThrows(LanException::class.java) { s.start() }
        assertEquals(6003, error.code)
        assertEquals(LanRoomState.FAILED, s.status().state)
        assertTrue(events.any { it.event == LanEvent.ERROR })
        s.stop()
        assertEquals(LanRoomState.CLOSED, s.status().state)
    }

    @Test
    fun allPortsBusyReturns6002() {
        val blockers = (18090..18099).map { port ->
            java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("0.0.0.0", port))
            }
        }
        try {
            val s = LanServer(defaultConfig(), { "192.0.2.10" }, { event -> events.add(event) }, clock)
            server = s
            val error = assertThrows(LanException::class.java) { s.start() }
            assertEquals(6002, error.code)
        } finally {
            blockers.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun updatePinAppliesToSubsequentPlaySessions() {
        val s = start()
        val port = s.status().port
        val token = token()
        s.updatePin(required = true, pin = "4321")
        assertTrue(s.status().pinRequired)

        val missing = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
        assertEquals(3004, jsonCode(missing.text))
        val ok = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), """{"pin":"4321"}""")
        assertEquals(200, ok.status)

        s.updatePin(required = false, pin = null)
        assertFalse(s.status().pinRequired)
        val withoutPin = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token), "{}")
        assertEquals(200, withoutPin.status)
    }

    // ------------------------------------------------------------- helpers

    private fun defaultConfig(maxViewers: Int = 3): LanConfig = LanConfig(
        // 18089 被 adb host 转发占用；18090 被本地 cloud-core 占用。
        // 仍取契约区间 18089–18099 的子集 18092–18099，避免与外部监听器抢路由。
        port = 18092,
        portRange = 18092..18099,
        maxViewers = maxViewers,
        pageTokenTtlSeconds = 3600,
        mediaTokenTtlSeconds = 300,
    )

    private fun pinConfig(): LanConfig = defaultConfig().copy(pinRequired = true, pin = "1234")

    private fun start(config: LanConfig = defaultConfig()): LanServer {
        val s = LanServer(config, { "192.0.2.10" }, { event -> events.add(event) }, clock)
        server = s
        url = s.start()
        return s
    }

    private fun token(): String {
        val value = url.substringAfter("/s/")
        assertTrue(value.isNotEmpty())
        return value
    }

    private fun issueMediaToken(port: Int): String {
        val response = TestHttp.postJson(port, "/api/v1/play-session", mapOf("X-Page-Token" to token()), "{}")
        assertEquals(200, response.status)
        return jsonField(response.text, "mediaToken") as String
    }

    private fun publishSampleMedia(s: LanServer) {
        s.publisher.publishH264(H264AccessUnit(listOf(byteArrayOf(0x65, 1, 2, 3)), 0L, true, sps, pps))
        s.publisher.publishAac(AacAccessUnit(byteArrayOf(0x11, 0x90.toByte()), 0L, isSequenceHeader = true))
        repeat(3) { index ->
            s.publisher.publishH264(
                H264AccessUnit(listOf(byteArrayOf(0x41, index.toByte())), (index + 1) * 33_000L, false),
            )
        }
        s.publisher.publishAac(AacAccessUnit(byteArrayOf(1, 2, 3), 33_000L))
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        fail("condition not met within ${timeoutMs}ms")
    }
}
