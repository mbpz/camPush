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
