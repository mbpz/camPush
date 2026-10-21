package com.campush.app.streaming.rtmp

import android.util.Log
import com.campush.lan.AacAccessUnit
import com.campush.lan.H264AccessUnit
import com.campush.lan.LanMediaPublisher
import com.campush.lan.MediaStats
import com.campush.lan.RtmpPushClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RTMP 旁路异步发布器：连接/发送都在单线程执行，绝不阻塞采集主线程。
 * 队列满时丢弃旁路帧（LAN 主链路不受影响）。
 */
class AsyncRtmpPublisher(private val url: String) : LanMediaPublisher {

    private val queue = ArrayBlockingQueue<() -> Unit>(120)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rtmp-push").apply { isDaemon = true } }
    @Volatile private var client: RtmpPushClient? = null
    @Volatile private var closed = false

    fun start() {
        executor.execute {
            try {
                client = RtmpPushClient(url).also { it.connect() }
                Log.i(TAG, "RTMP connected: $url")
            } catch (t: Throwable) {
                Log.w(TAG, "RTMP connect failed: $url", t)
            }
            pump()
        }
    }

    private fun pump() {
        while (!closed) {
            val task = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            runCatching { task() }.onFailure { Log.w(TAG, "RTMP send failed", it) }
        }
    }

    private fun offer(task: () -> Unit) {
        if (closed) return
        if (!queue.offer(task)) {
            // 旁路拥塞：丢弃旧帧，保留最新一帧。
            queue.poll()
            queue.offer(task)
        }
    }

    override fun publishJpeg(jpeg: ByteArray) = Unit

    override fun publishH264(unit: H264AccessUnit) {
        offer { client?.sendVideo(unit, unit.ptsUs / 1000) }
    }

    override fun publishAac(unit: AacAccessUnit) {
        offer { client?.sendAudio(unit, unit.ptsUs / 1000) }
    }

    override fun stats(): MediaStats = MediaStats()
    override fun requestKeyFrame() = Unit

    fun close() {
        closed = true
        executor.execute {
            runCatching { client?.close() }
            client = null
        }
        executor.shutdown()
    }

    private companion object {
        const val TAG = "AsyncRtmpPublisher"
    }
}
