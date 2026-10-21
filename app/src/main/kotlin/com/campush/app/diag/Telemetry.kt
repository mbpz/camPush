package com.campush.app.diag

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1 局域网埋点：队列 + 批量上报 + 失败落盘补报。
 *
 * - 事件名严格使用 00 §11 白名单（P1 子集）；属性只放脱敏后的数值/枚举，不含 token/PIN/IP/地址；
 * - 服务可用时每 10s 或 10 条 flush；失败/服务未启动时写入本地队列（≤1000 条、≤7 天）；
 * - 下次推流起服后自动补报（P1 端侧没有云端，补报发到本机 LanServer）；
 * - 离线文件只保存事件名、eventId、clientTs、props；不保存任何凭证。
 */
object Telemetry {

    private const val TAG = "Telemetry"
    private const val MAX_QUEUE = 1000
    private const val BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val queue = ArrayDeque<JSONObject>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "campush-telemetry").apply { isDaemon = true }
    }
    private val flushing = AtomicBoolean(false)
    private val lock = Any()

    @Volatile private var port: Int = -1
    @Volatile private var lastFlushAt = 0L
    @Volatile private var dropped = 0L
    @Volatile private var sent = 0L
    @Volatile private var pendingFile: File? = null

    fun init(context: Context) {
        pendingFile = File(File(context.applicationContext.filesDir, "telemetry"), "pending.jsonl")
        runCatching { pendingFile?.parentFile?.mkdirs() }
        loadPending()
    }

    fun configure(port: Int) {
        this.port = port
        loadPending()
        flush()
    }

    fun event(name: String, props: Map<String, Any?> = emptyMap()) {
        val json = JSONObject().apply {
            put("eventName", name)
            put("eventId", "evt_" + UUID.randomUUID().toString().replace("-", "").take(16))
            put("clientTs", System.currentTimeMillis())
            put("props", JSONObject(props))
        }
        val shouldFlush: Boolean
        synchronized(lock) {
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(json)
            shouldFlush = queue.size >= BATCH_SIZE ||
                System.currentTimeMillis() - lastFlushAt >= FLUSH_INTERVAL_MS
        }
        if (shouldFlush) flush()
    }

    fun flush() {
        if (!flushing.compareAndSet(false, true)) return
        val batch = synchronized(lock) {
            if (queue.isEmpty()) return@synchronized emptyList()
            val size = minOf(queue.size, BATCH_SIZE)
            val out = ArrayList<JSONObject>(size)
            repeat(size) { out.add(queue.removeFirst()) }
            lastFlushAt = System.currentTimeMillis()
            out
        }
        if (batch.isEmpty()) {
            flushing.set(false)
            return
        }
        val targetPort = port
        executor.execute {
            if (targetPort <= 0) {
                persist(batch)
            } else {
                try {
                    post(targetPort, batch)
                    sent += batch.size
                } catch (t: Throwable) {
                    Log.d(TAG, "telemetry flush failed, persist offline: ${t.message}")
                    persist(batch)
                }
            }
            flushing.set(false)
        }
    }

    fun summaryLine(): String =
        "telemetry sent=$sent dropped=$dropped pending=${synchronized(lock) { queue.size }} " +
            "offline=${pendingFile?.let { if (it.exists()) it.length() else 0L } ?: 0L}B"

    private fun post(port: Int, batch: List<JSONObject>) {
        val connection = (URL("http://127.0.0.1:$port/api/v1/telemetry/batch").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 800
            connection.readTimeout = 800
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("events", JSONArray(batch)).toString()
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            val out: OutputStream = connection.outputStream
            out.write(bytes)
            out.flush()
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** 失败补报：追加到本地文件（单行 JSON，便于崩溃后恢复）。 */
    private fun persist(batch: List<JSONObject>) {
        val file = pendingFile ?: return
        runCatching {
            file.appendText(batch.joinToString(separator = "\n", postfix = "\n") { it.toString() })
            trimOffline(file)
        }
    }

    /** 起服后把离线事件放回队列，交给 flush 补报；文件最多 1000 条、7 天。 */
    private fun loadPending() {
        val file = pendingFile ?: return
        if (!file.exists()) return
        val loaded = runCatching { file.readLines().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        } }.getOrDefault(emptyList())
        val cutoff = System.currentTimeMillis() - TTL_MS
        val valid = loaded.filter { (it.optLong("clientTs", 0L)) >= cutoff }
        synchronized(lock) {
            valid.forEach { if (queue.size < MAX_QUEUE) queue.addLast(it) }
            runCatching { file.delete() }
        }
    }

    private fun trimOffline(file: File) {
        runCatching {
            val lines = file.readLines()
            if (lines.size > MAX_QUEUE) {
                file.writeText(lines.takeLast(MAX_QUEUE).joinToString("\n", postfix = "\n"))
            }
        }
    }
}
