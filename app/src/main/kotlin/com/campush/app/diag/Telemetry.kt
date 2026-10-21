package com.campush.app.diag

import android.util.Log
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1 局域网埋点：队列 + 批量上报到端侧服务 `/api/v1/telemetry/batch`。
 *
 * - 事件名严格使用 00 §11 白名单（P1 子集）；属性只放脱敏后的数值/枚举；
 * - 局域网断网/服务停止时直接丢弃（P1 不依赖云端，不落盘补报）；
 * - 每 10s 或 10 条 flush 一次，避免影响推流。
 */
object Telemetry {

    private const val TAG = "Telemetry"
    private const val MAX_QUEUE = 200
    private const val BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 10_000L

    private val queue = ArrayDeque<Map<String, Any?>>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "campush-telemetry").apply { isDaemon = true }
    }
    private val flushing = AtomicBoolean(false)
    private val lock = Any()

    @Volatile private var port: Int = -1
    @Volatile private var lastFlushAt = 0L
    @Volatile private var dropped = 0L
    @Volatile private var sent = 0L

    fun configure(port: Int) {
        this.port = port
    }

    fun reset() {
        port = -1
        synchronized(lock) { queue.clear() }
    }

    fun event(name: String, props: Map<String, Any?> = emptyMap()) {
        val entry = linkedMapOf<String, Any?>(
            "eventName" to name,
            "eventId" to "evt_" + UUID.randomUUID().toString().replace("-", "").take(16),
            "clientTs" to System.currentTimeMillis(),
            "props" to props,
        )
        val shouldFlush: Boolean
        synchronized(lock) {
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(entry)
            shouldFlush = queue.size >= BATCH_SIZE ||
                System.currentTimeMillis() - lastFlushAt >= FLUSH_INTERVAL_MS
        }
        if (shouldFlush) flush()
    }

    fun flush() {
        if (port <= 0) return
        if (!flushing.compareAndSet(false, true)) return
        val batch = synchronized(lock) {
            if (queue.isEmpty()) return@synchronized emptyList()
            val size = minOf(queue.size, BATCH_SIZE)
            val out = ArrayList<Map<String, Any?>>(size)
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
            try {
                post(targetPort, batch)
                sent += batch.size
            } catch (t: Throwable) {
                // P1 局域网：服务已停止/断网时直接丢弃，不阻塞推流。
                Log.d(TAG, "telemetry flush dropped: ${t.message}")
            } finally {
                flushing.set(false)
            }
        }
    }

    fun summaryLine(): String = "telemetry sent=$sent dropped=$dropped pending=${synchronized(lock) { queue.size }}"

    private fun post(port: Int, batch: List<Map<String, Any?>>) {
        val connection = (URL("http://127.0.0.1:$port/api/v1/telemetry/batch").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 800
            connection.readTimeout = 800
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = "{${jsonString("events")}:[${batch.joinToString(",") { jsonObject(it) }}]}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            val out: OutputStream = connection.outputStream
            out.write(bytes)
            out.flush()
            connection.responseCode // 触发请求
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun jsonObject(map: Map<String, Any?>): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
        jsonString(k) + ":" + jsonValue(v)
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> jsonObject(value.entries.associate { it.key.toString() to it.value })
        is List<*> -> value.joinToString(",", "[", "]") { jsonValue(it) }
        else -> jsonString(value.toString())
    }
}
