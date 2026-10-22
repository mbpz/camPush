package com.campush.app.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** P3 T3-A3：cloud-core 账号 API（注册/登录），只负责 HTTP + 解析。 */
class AccountClient {

    data class Session(
        val token: String,
        val email: String,
        val plan: String,
        val roomLimit: Int,
    )

    data class Byo(
        val whipHost: String = "",
        val playHost: String = "",
        val rtmpHost: String = "",
        val mode: String = "managed",
    )

    fun getByo(apiBase: String, token: String): Byo = byoRequest(apiBase, token, "GET", null)

    fun saveByo(apiBase: String, token: String, whipHost: String, playHost: String, rtmpHost: String): Byo =
        byoRequest(
            apiBase, token, "PUT",
            JSONObject()
                .put("whipHost", whipHost.trim())
                .put("playHost", playHost.trim())
                .put("rtmpHost", rtmpHost.trim())
                .toString(),
        )

    private fun byoRequest(apiBase: String, token: String, method: String, body: String?): Byo {
        val base = apiBase.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "Cloud API 地址无效" }
        val connection = URL("$base/api/v1/account/byo").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray()
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $code: ${message?.takeIf { it.isNotBlank() } ?: text.take(160)}")
            }
            val json = JSONObject(text)
            return Byo(
                whipHost = json.optString("whipHost"),
                playHost = json.optString("playHost"),
                rtmpHost = json.optString("rtmpHost"),
                mode = json.optString("mode", "managed"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** T3-A4 连通性：HTTP(S) 用 GET，RTMP 用 TCP 连接 host:port。 */
    fun testReachable(url: String, timeoutMs: Int = 3_000): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            if (url.startsWith("rtmp://")) {
                val uri = java.net.URI(url)
                val port = if (uri.port > 0) uri.port else 1935
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(uri.host, port), timeoutMs) }
                true
            } else {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = false
                try {
                    connection.responseCode in 200..499
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrDefault(false)
    }

    fun login(apiBase: String, email: String, password: String, register: Boolean): Session {
        val base = apiBase.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "Cloud API 地址无效" }
        val path = if (register) "/api/v1/auth/register" else "/api/v1/auth/login"
        val connection = URL("$base$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("email", email.trim())
                .put("password", password)
                .toString()
                .toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $code: ${message?.takeIf { it.isNotBlank() } ?: text.take(160)}")
            }
            val json = JSONObject(text)
            val token = json.optString("token")
            if (token.isBlank()) throw IllegalStateException("响应缺少 token")
            return Session(
                token = token,
                email = json.optString("email", email.trim()),
                plan = json.optString("plan", "free"),
                roomLimit = json.optInt("roomLimit", 1),
            )
        } finally {
            connection.disconnect()
        }
    }
}
