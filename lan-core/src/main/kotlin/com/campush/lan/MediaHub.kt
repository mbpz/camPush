package com.campush.lan

import java.io.ByteArrayOutputStream

/**
 * LanMediaPublisher 的纯内存实现（契约：05 §8）。
 *
 * - 发布侧永不阻塞：只做内存追加 + 按 GOP 整段淘汰，不等待观看者（05 §8.8）；
 * - GOP 环形缓冲：约 6 s 或 16 MiB，先到者为准，以关键帧整组淘汰；
 * - FLV 订阅者：从最近关键帧起播；游标落后到被淘汰区域时跳到最新关键帧并重发序列头；
 * - MJPEG：只取最新一帧；
 * - stats()：最近 3 s fps、最近 5 s 码率、lastVideo/lastAudio 时间。
 *
 * 线程模型：单写多读；所有状态由一把短临界区锁保护，临界区内不做 IO。
 */
internal class MediaHub(private val clock: () -> Long) : LanMediaPublisher {

    private class Entry(
        val seq: Long,
        val gop: Int,
        val type: Int,
        val timestampMs: Long,
        val bytes: ByteArray,
        val keyframe: Boolean,
    )

    private val lock = Object()
    private val mjpegClients = java.util.concurrent.atomic.AtomicInteger(0)

    // 序列头快照（完整 FLV tag 字节；新连接/追帧时随关键帧重发）
    private var videoSequenceHeader: ByteArray? = null
    private var audioSequenceHeader: ByteArray? = null
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var cachedAudioConfig: ByteArray? = null

    // GOP 环形缓冲
    private val ring = ArrayDeque<Entry>()
    private val gopStarts = ArrayDeque<Long>()
    private var nextSeq = 0L
    private var currentGop = -1
    private var bytesInRing = 0L
    private var lastTagBytes: ByteArray? = null

    // JPEG 最新帧
    private var latestJpeg: ByteArray? = null
    private var jpegVersion = 0L

    // 统计
    private val videoTimestamps = ArrayDeque<Long>()
    private val byteSamples = ArrayDeque<LongArray>() // [timestampMs, bytes]
    private var lastVideoAtMillis = 0L
    private var lastAudioAtMillis = 0L

    private var closed = false

    private val maxDurationMs = 6_000L
    private val maxBytes = 16L * 1024L * 1024L
    private val keepAliveMs = 5_000L

    // ---------------------------------------------------------------- 发布侧

    override fun publishJpeg(jpeg: ByteArray) {
        synchronized(lock) {
            if (closed) return
            latestJpeg = jpeg
            jpegVersion++
        }
    }

    override fun publishH264(unit: H264AccessUnit) {
        val frameNals = ArrayList<ByteArray>(unit.nalUnits.size)

        var inlineSps: ByteArray? = null
        var inlinePps: ByteArray? = null
        for (raw in unit.nalUnits) {
            val nal = stripStartCode(raw)
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> inlineSps = nal
                8 -> inlinePps = nal
                else -> frameNals.add(nal)
            }
        }
        val timestampMs = (unit.ptsUs / 1000L).coerceAtLeast(0L)

        synchronized(lock) {
            if (closed) return
            val finalSps = unit.sps ?: inlineSps ?: cachedSps
            val finalPps = unit.pps ?: inlinePps ?: cachedPps
            if (finalSps != null && finalPps != null) {
                val changed = cachedSps == null || cachedPps == null ||
                    !cachedSps!!.contentEquals(finalSps) || !cachedPps!!.contentEquals(finalPps)
                cachedSps = finalSps
                cachedPps = finalPps
                if (changed || videoSequenceHeader == null) {
                    videoSequenceHeader = FlvMuxer.avcSequenceHeaderTag(finalSps, finalPps)
                }
            }

            if (frameNals.isNotEmpty()) {
                val tagBytes = FlvMuxer.videoFrameTag(frameNals, unit.isKeyFrame, timestampMs)
                appendTag(FlvMuxer.TAG_VIDEO, timestampMs, tagBytes, keyframe = unit.isKeyFrame)
                val now = clock()
                videoTimestamps.addLast(now)
                byteSamples.addLast(longArrayOf(now, tagBytes.size.toLong()))
                lastVideoAtMillis = now
                trimStats(now)
            }
            lock.notifyAll()
        }
    }

    override fun publishAac(unit: AacAccessUnit) {
        val timestampMs = (unit.ptsUs / 1000L).coerceAtLeast(0L)
        if (unit.isSequenceHeader) {
            synchronized(lock) {
                if (closed) return
                if (cachedAudioConfig == null || !cachedAudioConfig!!.contentEquals(unit.frame)) {
                    cachedAudioConfig = unit.frame.copyOf()
                    audioSequenceHeader = FlvMuxer.aacSequenceHeaderTag(unit.frame)
                }
                lock.notifyAll()
            }
            return
        }
        synchronized(lock) {
            if (closed) return
            val tagBytes = FlvMuxer.aacFrameTag(unit.frame, timestampMs)
            appendTag(FlvMuxer.TAG_AUDIO, timestampMs, tagBytes, keyframe = false)
            val now = clock()
            byteSamples.addLast(longArrayOf(now, tagBytes.size.toLong()))
            lastAudioAtMillis = now
            trimStats(now)
            lock.notifyAll()
        }
    }

    /** 无法通过冻结 API 反控编码器，这里只记录请求（见回报中的 API 缺口）。 */
    @Volatile
    var keyFrameRequests: Int = 0
        private set

    override fun requestKeyFrame() {
        keyFrameRequests++
    }

    override fun stats(): MediaStats = synchronized(lock) {
        val now = clock()
        trimStats(now)
        val fps = if (videoTimestamps.isEmpty()) {
            0
        } else {
            Math.round(videoTimestamps.size / 3.0).toInt()
        }
        var bytes = 0L
        for (sample in byteSamples) bytes += sample[1]
        val bitrate = if (byteSamples.isEmpty()) 0L else bytes * 8L / 5L
        MediaStats(
            fps = fps,
            bitrateBps = bitrate,
            lastVideoAtMillis = lastVideoAtMillis,
            lastAudioAtMillis = lastAudioAtMillis,
        )
    }

    // ---------------------------------------------------------------- FLV 订阅

    fun openFlv(): FlvSubscription = FlvSubscription()

    fun mjpegClientOpened() {
        mjpegClients.incrementAndGet()
    }

    fun mjpegClientClosed() {
        mjpegClients.decrementAndGet()
    }

    override fun isMjpegWanted(): Boolean = mjpegClients.get() > 0

    fun latestJpegVersioned(): Pair<Long, ByteArray>? = synchronized(lock) {
        val jpeg = latestJpeg ?: return null
        jpegVersion to jpeg
    }

    /** 停止时清空媒体状态（服务重启后复用同一 publisher 实例）。 */
    fun reset() {
        synchronized(lock) {
            ring.clear()
            gopStarts.clear()
            nextSeq = 0L
            currentGop = -1
            bytesInRing = 0L
            videoSequenceHeader = null
            audioSequenceHeader = null
            cachedSps = null
            cachedPps = null
            cachedAudioConfig = null
            latestJpeg = null
            jpegVersion = 0L
            videoTimestamps.clear()
            byteSamples.clear()
            lastVideoAtMillis = 0L
            lastAudioAtMillis = 0L
            lastTagBytes = null
            lock.notifyAll()
        }
    }

    /** 标记 hub 关闭：所有等待中的订阅者立即返回。 */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun reopen() {
        synchronized(lock) {
            closed = false
        }
    }

    /**
     * 一个 FLV 观看连接：独立游标；不持有锁做 IO。
     */
    inner class FlvSubscription internal constructor() : AutoCloseable {
        private var cursor = 0L
        private var needKeyframe = true
        private var lastSendAtMs = 0L
        private var lastSent: ByteArray? = null

        /** FLV header + onMetaData + 序列头 + 最近关键帧起播的已有 tag。 */
        fun initialBytes(): ByteArray = synchronized(lock) {
            val out = ByteArrayOutputStream(64 * 1024)
            val hasAudio = audioSequenceHeader != null
            out.write(FlvMuxer.header(hasAudio = hasAudio, hasVideo = true))
            out.write(FlvMuxer.scriptTag(hasAudio = hasAudio, hasVideo = true))
            val keyframe = gopStarts.lastOrNull()
            if (keyframe != null) {
                cursor = keyframe
                needKeyframe = false
                videoSequenceHeader?.let { out.write(it) }
                audioSequenceHeader?.let { out.write(it) }
                drainAvailable(out)
            } else {
                cursor = nextSeq
                needKeyframe = true
            }
            lastSendAtMs = clock()
            out.toByteArray()
        }

        /**
         * 等待并返回下一批字节；timeout 内无新 tag 返回 null。
         * 空闲 ≥5 s 时重复最近一个 tag 保活（05 §8.4）。
         */
        fun poll(timeoutMs: Long): ByteArray? {
            synchronized(lock) {
                if (closed) return null
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val out = ByteArrayOutputStream(32 * 1024)
                while (true) {
                    if (closed) return null
                    drainAvailable(out)
                    if (out.size() > 0) {
                        lastSendAtMs = clock()
                        return out.toByteArray()
                    }
                    val remainingNanos = deadline - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        val idle = clock() - lastSendAtMs
                        val keepAlive = lastSent
                        if (keepAlive != null && idle >= keepAliveMs) {
                            lastSendAtMs = clock()
                            return keepAlive
                        }
                        return null
                    }
                    try {
                        lock.wait((remainingNanos / 1_000_000L).coerceAtLeast(1L))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }

        override fun close() {
            // 订阅者只是游标，无需额外资源。
        }

        private fun drainAvailable(out: ByteArrayOutputStream) {
            if (ring.isEmpty()) {
                cursor = nextSeq
                return
            }
            if (needKeyframe) {
                val keyframe = gopStarts.firstOrNull { it >= ring.first().seq }
                if (keyframe == null) {
                    cursor = nextSeq
                    return
                }
                cursor = keyframe
                needKeyframe = false
                videoSequenceHeader?.let { out.write(it) }
                audioSequenceHeader?.let { out.write(it) }
            }
            if (cursor < ring.first().seq) {
                // 游标已落后到被淘汰区域：跳到最新关键帧并重发序列头（05 §8.7 追帧）
                val keyframe = gopStarts.lastOrNull()
                if (keyframe == null) {
                    needKeyframe = true
                    cursor = nextSeq
                    return
                }
                cursor = keyframe
                videoSequenceHeader?.let { out.write(it) }
                audioSequenceHeader?.let { out.write(it) }
            }
            while (true) {
                val entry = entryAt(cursor) ?: break
                out.write(entry.bytes)
                lastSent = entry.bytes
                cursor = entry.seq + 1
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun appendTag(type: Int, timestampMs: Long, bytes: ByteArray, keyframe: Boolean) {
        val seq = nextSeq++
        if (keyframe && type == FlvMuxer.TAG_VIDEO) {
            currentGop++
            gopStarts.addLast(seq)
        }
        val entry = Entry(seq, currentGop, type, timestampMs, bytes, keyframe)
        ring.addLast(entry)
        bytesInRing += bytes.size
        lastTagBytes = bytes
        purge()
    }

    private fun entryAt(seq: Long): Entry? {
        if (ring.isEmpty()) return null
        val index = seq - ring.first().seq
        if (index < 0 || index >= ring.size) return null
        return ring[index.toInt()]
    }

    private fun overBudget(): Boolean {
        if (ring.size <= 1) return false
        if (bytesInRing > maxBytes) return true
        return ring.last().timestampMs - ring.first().timestampMs > maxDurationMs
    }

    /** 以 GOP 为单位整段淘汰；至少保留最近一个 GOP（05 §8.3）。 */
    private fun purge() {
        while (overBudget()) {
            if (gopStarts.isEmpty()) {
                removeHead()
                continue
            }
            val firstKeyframe = gopStarts.first()
            if (ring.first().seq < firstKeyframe) {
                removeHead()
                continue
            }
            if (gopStarts.size >= 2) {
                val secondGopStart = gopStarts.elementAt(1)
                while (ring.isNotEmpty() && ring.first().seq < secondGopStart) removeHead()
                gopStarts.removeFirst()
                continue
            }
            break
        }
    }

    private fun removeHead() {
        val removed = ring.removeFirst()
        bytesInRing -= removed.bytes.size
    }

    private fun trimStats(now: Long) {
        while (videoTimestamps.isNotEmpty() && now - videoTimestamps.first() > 3_000L) {
            videoTimestamps.removeFirst()
        }
        while (byteSamples.isNotEmpty() && now - byteSamples.first()[0] > 5_000L) {
            byteSamples.removeFirst()
        }
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        var offset = 0
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
            offset = 4
        } else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
            offset = 3
        }
        return if (offset == 0) nal else nal.copyOfRange(offset, nal.size)
    }
}
