package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogServerTest {

    @Test
    fun servesLogsWithTokenAndHealthz() {
        val server = DiagnosticLogServer(
            ipProvider = { "192.0.2.10" },
            logProvider = { "CAMPLOG\nline-1\nline-2" },
            token = "tok1234567890123456",
            portRange = 18200..18210,
        )
        try {
            val url = server.start()
            assertTrue(url.contains("/logs/tok1234567890123456"))
            val port = server.let { url.substringAfterLast(':').substringBefore('/').toInt() }
            println("log-server url=$url port=$port")

            val ok = TestHttp.request(port, "GET", "/logs/tok1234567890123456")
            println("status=${ok.status} text=${ok.text} headers=${ok.headers}")
            assertEquals(200, ok.status)
            assertTrue(ok.text.contains("CAMPLOG"))
            assertTrue(ok.headers["content-disposition"]?.contains("attachment") == true)

            val health = TestHttp.request(port, "GET", "/healthz")
            assertEquals(200, health.status)
            assertEquals("ok", health.text)

            val bad = TestHttp.request(port, "GET", "/logs/wrong-token")
            assertEquals(401, bad.status)
        } finally {
            server.stop()
        }
    }
}
