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
