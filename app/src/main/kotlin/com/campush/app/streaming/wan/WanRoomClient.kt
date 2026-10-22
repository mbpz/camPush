package com.campush.app.streaming.wan

import android.content.Context
import android.util.Log
import com.campush.app.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * P2 WAN 房间联调：cloud-core 创建房间 → 取 ingest.whipUrl → WHIP 推流。
 * 仅原型入口；正式 WAN 流程接入客户端房间/计费/SSE 后替换。
 */
class WanRoomClient(
    context: Context,
    private val accountToken: String = "acct_dev",
) {
    data class State(
        val running: Boolean = false,
        val status: String = "idle",
        val roomId: String? = null,
        val playUrl: String? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val whip = WanWhipClient(context)
    private var apiBase: String? = null
    private var ingestToken: String? = null

    init {
        scope.launch {
            whip.state.collect { s ->
                if (_state.value.roomId != null) {
                    _state.value = _state.value.copy(
                        running = s.running,
                        status = s.status,
                        error = s.error ?: _state.value.error,
                    )
                }
            }
        }
    }

    fun start(apiBaseUrl: String) {
        if (_state.value.running && _state.value.roomId != null) return
        val base = apiBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            _state.value = State(status = "失败", error = "API 地址需以 http:// 或 https:// 开头")
            return
        }
        apiBase = base
        _state.value = State(status = "创建房间…")
        scope.launch {
            try {
                val room = withContext(Dispatchers.IO) { createRoom(base) }
                _state.value = State(
                    running = true,
                    status = "房间已创建，WHIP 连接中…",
                    roomId = room.optString("roomId"),
                    playUrl = room.optString("playUrl"),
                )
                ingestToken = room.optJSONObject("ingest")?.optString("token")
                val whipUrl = room.optJSONObject("ingest")?.optString("whipUrl")
                    ?: throw IllegalStateException("room has no ingest.whipUrl")
                val turn = room.optJSONObject("turn")
                val turnUrls = turn?.optJSONArray("urls")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                }.orEmpty()
                val turnUser = turn?.optString("username")
                val turnCred = turn?.optString("credential")
                DiagLog.i("WanRoom", "room=${room.optString("roomId")} whip=$whipUrl turn=${turnUrls.size}")
                whip.start(whipUrl, turnUrls, turnUser, turnCred)
            } catch (t: Throwable) {
                Log.w(TAG, "create room failed", t)
                DiagLog.e("WanRoom", "create failed: ${t.message}")
                _state.value = State(status = "失败", error = t.message)
            }
        }
    }

    fun stop() {
        val base = apiBase
        val roomId = _state.value.roomId
        val token = ingestToken
        whip.stop()
        _state.value = State(status = "已停止")
        if (base != null && roomId != null && token != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { deleteRoom(base, roomId, token) }
            }
        }
    }

    private fun createRoom(base: String): JSONObject {
        val connection = URL("$base/api/v1/rooms").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accountToken")
            val body = """{"accessScope":"wan","captureMode":"single_back","pushProtocol":"whip"}""".toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("create room HTTP $code: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteRoom(base: String, roomId: String, token: String) {
        val connection = URL("$base/api/v1/rooms/$roomId").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            runCatching { connection.responseCode }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "WanRoomClient"
    }
}
