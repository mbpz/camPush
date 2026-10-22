package com.campush.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** P1：原生观众端房间列表；点击进入 WebView 播放页。 */
class AudienceListActivity : Activity() {
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private var apiBase: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apiBase = intent.getStringExtra(EXTRA_API)?.trim()?.trimEnd('/').orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(11, 15, 20))
        }
        val title = TextView(this).apply {
            text = "CamPush 观众端"
            textSize = 20f
            setTextColor(Color.rgb(232, 238, 246))
        }
        status = TextView(this).apply {
            text = "加载中…"
            textSize = 13f
            setTextColor(Color.rgb(147, 164, 184))
            setPadding(0, 12, 0, 12)
        }
        val refresh = Button(this).apply {
            text = "刷新"
            setOnClickListener { loadRooms() }
        }
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(title)
        root.addView(status)
        root.addView(refresh)
        root.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadRooms()
    }

    private fun loadRooms() {
        if (apiBase.isBlank()) {
            status.text = "未配置 Cloud API"
            return
        }
        status.text = "加载中…"
        Thread {
            val result = runCatching { fetchRooms() }
            runOnUiThread {
                result.onSuccess { rooms ->
                    container.removeAllViews()
                    status.text = if (rooms.isEmpty()) "暂无公开房间" else "${rooms.size} 个房间"
                    rooms.forEach { room ->
                        container.addView(roomView(room))
                    }
                }.onFailure { e ->
                    status.text = "加载失败：${e.message}"
                }
            }
        }.start()
    }

    private fun fetchRooms(): List<JSONObject> {
        val connection = URL("$apiBase/api/v1/rooms").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val code = connection.responseCode
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val arr = JSONObject(text).optJSONArray("rooms") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun roomView(room: JSONObject): TextView {
        val playUrl = room.optString("playUrl")
        val state = room.optString("state")
        val live = state == "live" || state == "switching"
        return TextView(this).apply {
            text = "${room.optString("roomId")}\n${if (live) "直播中" else state} · 观看 ${room.optInt("viewers")}" +
                if (room.optBoolean("pinRequired")) " · 需要 PIN" else ""
            textSize = 15f
            setTextColor(Color.rgb(232, 238, 246))
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(18, 26, 36))
            gravity = Gravity.START
            setOnClickListener {
                startActivity(
                    Intent(this@AudienceListActivity, AudienceActivity::class.java)
                        .putExtra(AudienceActivity.EXTRA_URL, playUrl),
                )
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = 18
            layoutParams = lp
        }
    }

    companion object {
        const val EXTRA_API = "audience_api"
    }
}
