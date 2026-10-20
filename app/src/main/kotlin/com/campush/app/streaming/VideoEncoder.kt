package com.campush.app.streaming

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import com.campush.app.core.P1Error
import com.campush.app.core.StreamSettingsUi
import com.campush.lan.H264AccessUnit
import com.campush.lan.LanMediaPublisher

/**
 * H.264 硬编：CameraX ImageAnalysis 的 YUV_420_888 帧 -> MediaCodec（COLOR_FormatYUV420Flexible）
 * -> Annex-B NALU 解析 -> publisher.publishH264(H264AccessUnit)。
 *
 * 约束（docs/02 §7）：GOP 2s、默认 2.5 Mbps、ABR 800 kbps–4 Mbps、启动即请求 IDR。
 * 所有方法只在相机单线程 executor 上调用；requestKeyFrame 可能来自服务线程，故加锁。
 */
class VideoEncoder(
    private val publisher: LanMediaPublisher,
    private val settings: StreamSettingsUi,
    private val onFatalError: ((P1Error) -> Unit)? = null,
) {

    private var codec: MediaCodec? = null
    private var width: Int = 0
    private var height: Int = 0

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var pendingKeyFrame: Boolean = true

    private val baseBitrate: Int = clampBitrate(settings.bitrateKbps * 1000)
    private var targetBitrate: Int = baseBitrate

    private var windowStartMs: Long = 0L
    private var framesInWindow: Int = 0
    private var droppedInWindow: Int = 0
    private var lastAdjustMs: Long = 0L
    private var averageEncodeMs: Double = 0.0
    private var lastFedAtUs: Long = 0L
    private var closed: Boolean = false

    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun encode(image: Image, ptsUs: Long) {
        if (closed) return
        val frameWidth = image.width and 1.inv()
        val frameHeight = image.height and 1.inv()
        if (frameWidth <= 0 || frameHeight <= 0) return

        if (codec == null || frameWidth != width || frameHeight != height) {
            restartCodec(frameWidth, frameHeight)
            val current = codec ?: return
            if (!shouldFeed(ptsUs)) return
            feedFrame(current, image, ptsUs)
            return
        }
        val current = codec ?: return
        if (!shouldFeed(ptsUs)) return
        feedFrame(current, image, ptsUs)
    }

    /** 请求编码器立刻输出 IDR（播放端加入 / 网络恢复 / 启动首帧）。 */
    @Synchronized
    fun requestKeyFrame() {
        val current = codec
        if (current == null) {
            pendingKeyFrame = true
            return
        }
        val bundle = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        runCatching { current.setParameters(bundle) }
            .onFailure {
                pendingKeyFrame = true
                Log.w(TAG, "request sync frame failed", it)
            }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        releaseCodec()
    }

    private fun feedFrame(codec: MediaCodec, image: Image, ptsUs: Long) {
        val startNs = System.nanoTime()
        try {
            val inputIndex = codec.dequeueInputBuffer(0L)
            if (inputIndex < 0) {
                droppedInWindow++
                drain(codec)
                maybeAdjustBitrate()
                return
            }
            val dstImage = codec.getInputImage(inputIndex)
            if (dstImage == null) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                droppedInWindow++
                drain(codec)
                return
            }
            YuvUtils.copyImageToImage(image, dstImage, width, height)
            codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
            framesInWindow++
            lastFedAtUs = ptsUs
            drain(codec)
        } catch (t: Throwable) {
            Log.w(TAG, "encode frame failed", t)
        } finally {
            val costMs = (System.nanoTime() - startNs) / 1_000_000.0
            averageEncodeMs = if (averageEncodeMs == 0.0) costMs else averageEncodeMs * 0.9 + costMs * 0.1
            maybeAdjustBitrate()
        }
    }

    /** 按 settings.fps 做简单抽帧，避免相机输出帧率高于编码目标。 */
    private fun shouldFeed(ptsUs: Long): Boolean {
        val frameIntervalUs = 1_000_000L / settings.fps.coerceIn(10, 60)
        if (lastFedAtUs != 0L && ptsUs - lastFedAtUs < frameIntervalUs * 0.7) return false
        return true
    }

    private fun drain(codec: MediaCodec) {
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0L)
            } catch (t: Throwable) {
                Log.w(TAG, "dequeueOutputBuffer failed", t)
                return
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> captureCsd(codec)
                index >= 0 -> {
                    try {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null) {
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(data)
                                publishOutput(data, bufferInfo)
                            }
                        }
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun publishOutput(data: ByteArray, info: MediaCodec.BufferInfo) {
        val nalUnits = AnnexB.split(data)
        if (nalUnits.isEmpty()) return
        var keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        var containsVcl = false
        for (nalu in nalUnits) {
            if (nalu.isEmpty()) continue
            when (nalu[0].toInt() and 0x1F) {
                7 -> sps = nalu
                8 -> pps = nalu
                5 -> {
                    keyFrame = true
                    containsVcl = true
                }
                1 -> containsVcl = true
            }
        }
        if (!containsVcl) return
        runCatching {
            publisher.publishH264(
                H264AccessUnit(
                    nalUnits = nalUnits,
                    ptsUs = info.presentationTimeUs,
                    isKeyFrame = keyFrame,
                    sps = sps,
                    pps = pps,
                )
            )
        }.onFailure { Log.w(TAG, "publishH264 failed", it) }
    }

    private fun captureCsd(codec: MediaCodec) {
        runCatching {
            val format = codec.outputFormat
            val csd0 = format.getByteBuffer("csd-0")?.let { buffer ->
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            val csd1 = format.getByteBuffer("csd-1")?.let { buffer ->
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            csd0?.let { bytes ->
                AnnexB.split(bytes).firstOrNull { (it[0].toInt() and 0x1F) == 7 }?.let { sps = it }
            }
            csd1?.let { bytes ->
                AnnexB.split(bytes).firstOrNull { (it[0].toInt() and 0x1F) == 8 }?.let { pps = it }
            }
        }.onFailure { Log.w(TAG, "read csd failed", it) }
    }

    private fun maybeAdjustBitrate() {
        if (!settings.adaptiveBitrate) return
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) {
            windowStartMs = now
            return
        }
        if (now - windowStartMs < ABR_WINDOW_MS) return

        val total = framesInWindow + droppedInWindow
        val dropRatio = if (total > 0) droppedInWindow.toDouble() / total else 0.0
        val frameBudgetMs = 1000.0 / settings.fps.coerceAtLeast(10)
        val tooSlow = averageEncodeMs > frameBudgetMs * 0.9
        val congested = dropRatio > 0.1 || tooSlow

        windowStartMs = now
        framesInWindow = 0
        droppedInWindow = 0

        if (congested && now - lastAdjustMs > DOWN_COOLDOWN_MS) {
            val next = (targetBitrate * 0.8).toInt().coerceAtLeast(MIN_BITRATE)
            if (next != targetBitrate) {
                targetBitrate = next
                lastAdjustMs = now
                applyBitrate()
            }
        } else if (!congested && now - lastAdjustMs > UP_COOLDOWN_MS) {
            val ceiling = minOf(baseBitrate, MAX_BITRATE)
            val next = (targetBitrate * 1.2).toInt().coerceAtMost(ceiling)
            if (next > targetBitrate) {
                targetBitrate = next
                lastAdjustMs = now
                applyBitrate()
            }
        }
    }

    private fun applyBitrate() {
        val current = codec ?: return
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetBitrate)
        }
        runCatching { current.setParameters(params) }
            .onFailure { Log.w(TAG, "setParameters(bitrate) failed", it) }
    }

    private fun restartCodec(frameWidth: Int, frameHeight: Int) {
        releaseCodec()
        try {
            var candidate: MediaCodec? = null
            try {
                candidate = MediaCodec.createEncoderByType(MIME_VIDEO)
                candidate.configure(
                    buildFormat(frameWidth, frameHeight, includeProfile = true),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "configure baseline failed, retry without profile", t)
                runCatching { candidate?.release() }
                candidate = MediaCodec.createEncoderByType(MIME_VIDEO)
                candidate.configure(
                    buildFormat(frameWidth, frameHeight, includeProfile = false),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            }
            val encoder = candidate ?: error("MediaCodec unavailable")
            encoder.start()
            codec = encoder
            width = frameWidth
            height = frameHeight
            sps = null
            pps = null
            pendingKeyFrame = false
            windowStartMs = System.currentTimeMillis()
            framesInWindow = 0
            droppedInWindow = 0
            // 启动即强制 IDR（docs/02 §7.2、首帧预算 00 §10.2）。
            runCatching {
                encoder.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VideoEncoder init failed", t)
            releaseCodec()
            onFatalError?.invoke(P1Error(1005, "视频编码器初始化失败", "请重试，或降低分辨率后重新开始推流"))
        }
    }

    private fun buildFormat(width: Int, height: Int, includeProfile: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps.coerceIn(15, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SECONDS)
            runCatching {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }
            if (includeProfile) {
                runCatching {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
        }
    }

    private fun releaseCodec() {
        val current = codec ?: return
        codec = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private companion object {
        const val TAG = "VideoEncoder"
        const val MIME_VIDEO = "video/avc"
        const val KEY_FRAME_INTERVAL_SECONDS = 2
        const val MIN_BITRATE = 800_000
        const val MAX_BITRATE = 4_000_000
        const val ABR_WINDOW_MS = 1_000L
        const val DOWN_COOLDOWN_MS = 5_000L
        const val UP_COOLDOWN_MS = 10_000L

        fun clampBitrate(bitrate: Int): Int = bitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)
    }
}

/** Annex-B（00 00 01 / 00 00 00 01 起始码）NALU 拆分。 */
internal object AnnexB {

    fun split(data: ByteArray): List<ByteArray> {
        // naluStart: 起始码之后 NALU 首字节位置；codeStart: 起始码首字节位置（用于确定上一个 NALU 的结束）。
        val naluStarts = mutableListOf<Int>()
        val codeStarts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    codeStarts.add(i)
                    naluStarts.add(i + 3)
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    codeStarts.add(i)
                    naluStarts.add(i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (naluStarts.isEmpty()) {
            return if (data.isNotEmpty()) listOf(data) else emptyList()
        }
        val result = ArrayList<ByteArray>(naluStarts.size)
        for (index in naluStarts.indices) {
            val begin = naluStarts[index]
            val end = if (index + 1 < codeStarts.size) codeStarts[index + 1] else data.size
            val safeEnd = end.coerceIn(begin, data.size)
            if (safeEnd > begin) {
                result.add(data.copyOfRange(begin, safeEnd))
            }
        }
        return result
    }
}
