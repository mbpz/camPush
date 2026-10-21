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
        val s = CloudServer(CloudServer.Config(accountToken = "acct_test"))
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

        val status = get(base, "/api/v1/rooms/$roomId/status", mapOf("X-Page-Token" to pageToken))
        assertEquals(200, status.statusCode())
        assertTrue(status.body().contains("\"viewers\":1"))
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
