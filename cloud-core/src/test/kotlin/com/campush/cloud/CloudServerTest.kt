package com.campush.cloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class CloudServerTest {

    private var server: CloudServer? = null
    private val http = HttpClient.newHttpClient()

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun start(): Pair<CloudServer, String> {
        val s = CloudServer(CloudConfig(accountToken = "acct_test"))
        val port = s.start(0)
        server = s
        return s to "http://127.0.0.1:$port"
    }

    private fun post(base: String, path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun put(base: String, path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(base: String, path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun createPlaySessionAndStatus() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back","pushProtocol":"whip"}""", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(201, created.statusCode())
        assertTrue(created.body().contains("\"roomId\":\"r"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]

        val session = post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken))
        assertEquals(201, session.statusCode())
        assertTrue(session.body().contains("\"mediaToken\""))
        assertTrue(session.body().contains("\"turn\""))
        assertTrue(session.body().contains("\"urls\""))

        val status = get(base, "/api/v1/rooms/$roomId/status", mapOf("X-Page-Token" to pageToken))
        assertEquals(200, status.statusCode())
        assertTrue(status.body().contains("\"viewers\":1"))
    }

    @Test
    fun configPageDefaultsAndUpdate() {
        val (_, base) = start()
        val page = get(base, "/config")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("srs.m0-wan.orb.local"))
        val current = get(base, "/api/v1/config")
        assertEquals(200, current.statusCode())
        assertTrue(current.body().contains("1985"))

        val updated = post(
            base,
            "/api/v1/config",
            """{"whipHost":"http://127.0.0.1","whipApiPort":9999}""",
        )
        assertEquals(200, updated.statusCode())
        assertTrue(updated.body().contains("9999"))
    }

    @Test
    fun pinFailuresLockRoomAndRateLimit() {
        val s = CloudServer(CloudConfig(accountToken = "acct_test", pinFailuresBeforeLock = 3, playSessionPerMinute = 100))
        val port = s.start(0)
        server = s
        val base = "http://127.0.0.1:$port"
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back","pinRequired":true,"pin":"1234"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]
        repeat(2) {
            val r = post(base, "/api/v1/rooms/$roomId/play-session", """{"pin":"0000"}""", mapOf("X-Page-Token" to pageToken))
            assertEquals(401, r.statusCode())
        }
        val locked = post(base, "/api/v1/rooms/$roomId/play-session", """{"pin":"0000"}""", mapOf("X-Page-Token" to pageToken))
        assertEquals(429, locked.statusCode())
        assertTrue(locked.body().contains("3006"))
    }

    @Test
    fun playSessionRateLimit() {
        val s = CloudServer(CloudConfig(accountToken = "acct_test", playSessionPerMinute = 2))
        val port = s.start(0)
        server = s
        val base = "http://127.0.0.1:$port"
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]
        repeat(2) {
            assertEquals(201, post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken)).statusCode())
        }
        val limited = post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken))
        assertEquals(429, limited.statusCode())
        assertTrue(limited.body().contains("3008"))
    }

    @Test
    fun closedRoomReturns410ForMediaAndPlaySession() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]
        val ingestToken = Regex("\"ingest\":\\{[^}]*\"token\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val del = java.net.URI.create("$base/api/v1/rooms/$roomId").toURL().openConnection() as java.net.HttpURLConnection
        del.requestMethod = "DELETE"; del.setRequestProperty("Authorization", "Bearer $ingestToken")
        assertEquals(202, del.responseCode); del.disconnect()

        val play = post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken))
        assertEquals(410, play.statusCode())
        assertTrue(play.body().contains("2002"))
    }

    @Test
    fun accountRegisterLoginAndPlanQuota() {
        val (_, base) = start()
        val reg = post(base, "/api/v1/auth/register", """{"email":"a@example.com","password":"secret123","deviceId":"dev-1"}""")
        assertEquals(201, reg.statusCode())
        val token = Regex("\"token\":\"([^\"]+)\"").find(reg.body())!!.groupValues[1]

        val dup = post(base, "/api/v1/auth/register", """{"email":"a@example.com","password":"secret123"}""")
        assertEquals(400, dup.statusCode())

        val bad = post(base, "/api/v1/auth/login", """{"email":"a@example.com","password":"wrong"}""")
        assertEquals(401, bad.statusCode())

        val login = post(base, "/api/v1/auth/login", """{"email":"a@example.com","password":"secret123","deviceId":"dev-1"}""")
        assertEquals(200, login.statusCode())

        val info = get(base, "/api/v1/account", mapOf("Authorization" to "Bearer $token"))
        assertEquals(200, info.statusCode())
        assertTrue(info.body().contains("\"plan\":\"free\""))

        val first = post(base, "/api/v1/rooms", """{"accessScope":"wan"}""", mapOf("Authorization" to "Bearer $token"))
        assertEquals(201, first.statusCode())
        val second = post(base, "/api/v1/rooms", """{"accessScope":"wan"}""", mapOf("Authorization" to "Bearer $token"))
        assertEquals(429, second.statusCode())
        assertTrue(second.body().contains("2004"))
    }

    @Test
    fun roomExposesMultiIngestAddresses() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val addresses = get(base, "/api/v1/rooms/$roomId/addresses", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(200, addresses.statusCode())
        assertTrue(addresses.body().contains("\"protocol\":\"whip\""))
        assertTrue(addresses.body().contains("\"protocol\":\"rtmp\""))
        assertTrue(addresses.body().contains("\"protocol\":\"srt\""))
        assertTrue(addresses.body().contains("\"status\":\"idle\""))
    }

    @Test
    fun byoHostsOverrideRoomUrls() {
        val (_, base) = start()
        val reg = post(base, "/api/v1/auth/register", """{"email":"byo@example.com","password":"secret123"}""")
        val token = Regex("\"token\":\"([^\"]+)\"").find(reg.body())!!.groupValues[1]
        val auth = mapOf("Authorization" to "Bearer $token")
        val bad = put(base, "/api/v1/account/byo", """{"whipHost":"ftp://bad","playHost":"http://p.example.com","rtmpHost":"rtmp://r.example.com/live"}""", auth)
        assertEquals(400, bad.statusCode())
        val ok = put(base, "/api/v1/account/byo", """{"whipHost":"http://w.example.com","playHost":"http://p.example.com","rtmpHost":"rtmp://r.example.com/live"}""", auth)
        assertEquals(200, ok.statusCode())
        assertTrue(ok.body().contains("\"mode\":\"byo\""))
        val room = post(base, "/api/v1/rooms", """{"accessScope":"wan"}""", auth)
        assertEquals(201, room.statusCode())
        assertTrue(room.body().contains("http://p.example.com/s/"))
        assertTrue(room.body().contains("http://w.example.com:1985/rtc/v1/whip/"))
        assertTrue(room.body().contains("rtmp://r.example.com/live/live/"))
    }

    @Test
    fun accountPageServes() {
        val (_, base) = start()
        val page = get(base, "/account")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("CamPush 账号与套餐"))
    }

    @Test
    fun audienceRoomListAndPage() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val list = get(base, "/api/v1/rooms")
        assertEquals(200, list.statusCode())
        assertTrue(list.body().contains(roomId))
        assertTrue(list.body().contains("playUrl"))
        val page = get(base, "/rooms")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("CamPush 观众端"))
    }

    @Test
    fun planUpgradeAndUsage() {
        val (_, base) = start()
        val reg = post(base, "/api/v1/auth/register", """{"email":"b@example.com","password":"secret123"}""")
        val token = Regex("\"token\":\"([^\"]+)\"").find(reg.body())!!.groupValues[1]
        val auth = mapOf("Authorization" to "Bearer $token")

        val upgrade = post(base, "/api/v1/account/plan", """{"plan":"pro"}""", auth)
        assertEquals(200, upgrade.statusCode())
        assertTrue(upgrade.body().contains("\"roomLimit\":10"))

        val first = post(base, "/api/v1/rooms", """{"accessScope":"wan"}""", auth)
        assertEquals(201, first.statusCode())
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(first.body())!!.groupValues[1]
        val ingestToken = Regex("\"ingest\":\\{[^}]*\"token\":\"([^\"]+)\"").find(first.body())!!.groupValues[1]
        Thread.sleep(1_100)
        val del = java.net.URI.create("$base/api/v1/rooms/$roomId").toURL().openConnection() as java.net.HttpURLConnection
        del.requestMethod = "DELETE"; del.setRequestProperty("Authorization", "Bearer $ingestToken")
        assertEquals(202, del.responseCode); del.disconnect()

        val usage = get(base, "/api/v1/account/usage", auth)
        assertEquals(200, usage.statusCode())
        assertTrue(usage.body().contains("\"usedSeconds\":1") || usage.body().contains("\"usedSeconds\":2"))
    }

    @Test
    fun telemetryFirstFrameMetrics() {
        val (_, base) = start()
        repeat(3) { i ->
            val r = post(base, "/api/v1/telemetry", """{"event":"play_first_frame","protocol":"whep","timeToFirstFrameMs":${100 + i * 100}}""")
            assertEquals(202, r.statusCode())
        }
        post(base, "/api/v1/telemetry", """{"event":"play_fallback","protocol":"hls"}""")
        val metrics = get(base, "/api/v1/metrics")
        assertEquals(200, metrics.statusCode())
        assertTrue(metrics.body().contains("\"play_first_frame\":3"))
        assertTrue(metrics.body().contains("\"whep\""))
        assertTrue(metrics.body().contains("\"p95\":300"))
    }

    @Test
    fun roomQuotaIsEnforced() {
        val s = CloudServer(CloudConfig(accountToken = "acct_test", maxRooms = 1))
        val port = s.start(0)
        server = s
        val base = "http://127.0.0.1:$port"
        val first = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(201, first.statusCode())
        val second = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(429, second.statusCode())
        assertTrue(second.body().contains("2004"))
    }

    @Test
    fun sseEmitsViewerCountAndEnded() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]
        val ingestToken = Regex("\"ingest\":\\{[^}]*\"token\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]

        val conn = java.net.URL("$base/api/v1/rooms/$roomId/events").openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("X-Page-Token", pageToken)
        conn.connectTimeout = 2000
        conn.readTimeout = 6000
        try {
            assertEquals(200, conn.responseCode)
            val reader = conn.inputStream.bufferedReader()
            assertEquals("retry: 3000", reader.readLine())
            assertEquals("event: state", reader.readLine())
            assertTrue(reader.readLine()!!.startsWith("data: "))
            reader.readLine() // blank line

            val ps = post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken))
            assertEquals(201, ps.statusCode())
            val events = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && !events.contains("event: viewer-count")) {
                if (reader.ready()) events.add(reader.readLine() ?: break) else Thread.sleep(50)
            }
            assertTrue("events=$events", events.contains("event: viewer-count"))

            // 停止房间应触发 ended（ingest token）
            val connDel = java.net.URL("$base/api/v1/rooms/$roomId").openConnection() as java.net.HttpURLConnection
            connDel.requestMethod = "DELETE"
            connDel.setRequestProperty("Authorization", "Bearer $ingestToken")
            connDel.connectTimeout = 2000
            connDel.readTimeout = 3000
            connDel.responseCode
            connDel.disconnect()

            val endedEvents = mutableListOf<String>()
            val deadline2 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline2 && !endedEvents.contains("event: ended")) {
                if (reader.ready()) endedEvents.add(reader.readLine() ?: break) else Thread.sleep(50)
            }
            assertTrue("ended=$endedEvents", endedEvents.contains("event: ended"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun hlsProxyRewritesSegmentsAndServesTs() {
        val mock = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        mock.createContext("/live/") { ex ->
            val bytes = if (ex.requestURI.path.endsWith(".m3u8")) {
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\nseg1.ts\n".toByteArray()
            } else {
                byteArrayOf(0x47, 0x40, 0x00, 0x10)
            }
            ex.responseHeaders.add("Content-Type", if (ex.requestURI.path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        mock.start()
        val config = CloudConfig(accountToken = "acct_test", playbackHost = "http://127.0.0.1", playbackPort = mock.address.port)
        val s = CloudServer(config)
        val port = s.start(0)
        server = s
        val base = "http://127.0.0.1:$port"
        try {
            val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
            val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
            val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]
            val session = post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken))
            val hlsPath = Regex("/hls/[^\"]+").find(session.body())!!.value
            val playlist = get(base, hlsPath)
            assertEquals(200, playlist.statusCode())
            assertTrue(playlist.body().contains("/hls/seg1.ts?token="))

            val mediaToken = Regex("token=([^&\"]+)").find(hlsPath)!!.groupValues[1]
            val ts = get(base, "/hls/seg1.ts?token=$mediaToken")
            assertEquals(200, ts.statusCode())
            assertTrue(ts.body().isNotEmpty())
        } finally {
            mock.stop(0)
        }
    }

    @Test
    fun pinRequiredRoomEnforcesPin() {
        val (_, base) = start()
        val missing = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back","pinRequired":true}""", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(400, missing.statusCode())
        assertTrue(missing.body().contains("3004"))

        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back","pinRequired":true,"pin":"1234"}""", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(201, created.statusCode())
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]

        val noPin = post(base, "/api/v1/rooms/$roomId/play-session", "{}", mapOf("X-Page-Token" to pageToken))
        assertEquals(401, noPin.statusCode())
        assertTrue(noPin.body().contains("3005"))

        val wrong = post(base, "/api/v1/rooms/$roomId/play-session", """{"pin":"0000"}""", mapOf("X-Page-Token" to pageToken))
        assertEquals(401, wrong.statusCode())

        val ok = post(base, "/api/v1/rooms/$roomId/play-session", """{"pin":"1234"}""", mapOf("X-Page-Token" to pageToken))
        assertEquals(201, ok.statusCode())
    }

    @Test
    fun cloudPlayerPageServesAndRejectsBadToken() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]

        val page = get(base, "/s/$roomId?token=$pageToken")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("CamPush 云端观看"))
        assertTrue(get(base, "/assets/hls.min.js").body().length > 100_000)
        assertTrue(get(base, "/assets/mpegts.min.js").body().length > 100_000)
        assertEquals(401, get(base, "/s/$roomId?token=bad").statusCode())
        assertEquals(401, get(base, "/live/$roomId.flv?token=bad").statusCode())
    }

    @Test
    fun mediaProxyRequiresTokenAndAllowsCorsPreflight() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]

        val unauthorized = post(base, "/whep/$roomId", "v=0", mapOf("Content-Type" to "application/sdp"))
        assertEquals(401, unauthorized.statusCode())
        assertTrue(unauthorized.body().contains("3002"))

        val options = HttpRequest.newBuilder(URI.create(base + "/whep/$roomId"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build()
        val preflight = http.send(options, HttpResponse.BodyHandlers.ofString())
        assertEquals(204, preflight.statusCode())
    }

    @Test
    fun roomEventsSseStartsAndHeartbeats() {
        val (_, base) = start()
        val created = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        val pageToken = Regex("\"playUrl\":\"[^\"]*token=([^\"]+)\"").find(created.body())!!.groupValues[1]
        val conn = java.net.URL("$base/api/v1/rooms/$roomId/events").openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("X-Page-Token", pageToken)
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        try {
            assertEquals(200, conn.responseCode)
            val reader = conn.inputStream.bufferedReader()
            val first = reader.readLine()
            assertEquals("retry: 3000", first)
            assertEquals("event: state", reader.readLine())
            assertTrue(reader.readLine()!!.startsWith("data: "))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun rejectsLanScopeAndBadToken() {
        val (_, base) = start()
        val lan = post(base, "/api/v1/rooms", """{"accessScope":"lan","captureMode":"single_back"}""", mapOf("Authorization" to "Bearer acct_test"))
        assertEquals(400, lan.statusCode())
        assertTrue(lan.body().contains("2007"))

        val noAuth = post(base, "/api/v1/rooms", """{"accessScope":"wan","captureMode":"single_back"}""")
        assertEquals(401, noAuth.statusCode())
        assertTrue(noAuth.body().contains("3001"))

        val bad = post(base, "/api/v1/rooms/rBAD/play-session", "{}", mapOf("X-Page-Token" to "bad"))
        assertEquals(404, bad.statusCode())
    }
}
