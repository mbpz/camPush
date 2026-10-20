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
    private var lastOutputPtsUs: Long = 0L
    private var pendingW: Int = 0
    private var pendingH: Int = 0
    private var pendingCount: Int = 0
    private var restartCount: Int = 0
    private var inputFrames: Long = 0L
    private var outputFrames: Long = 0L
    private var lastDiagAtMs: Long = 0L
    private var lastDiagInput: Long = 0L
    private var lastDiagOutput: Long = 0L
    private var lastLatencyResetMs: Long = 0L
    private var closed: Boolean = false

    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun encode(image: Image, ptsUs: Long) {
        if (closed) return
        val frameWidth = image.width and 1.inv()
        val frameHeight = image.height and 1.inv()
        if (frameWidth <= 0 || frameHeight <= 0) return

        inputFrames++
        val current = codec
        if (current == null) {
            restartCodec(frameWidth, frameHeight)
            val created = codec ?: return
            if (!shouldFeed(ptsUs)) return
            feedFrame(created, image, ptsUs)
            return
        }
        if (frameWidth != width || frameHeight != height) {
            // 相机输出尺寸偶尔抖动（1080/1088 对齐等）：先缩放喂入现有编码器，持续 2s 才重建，
            // 避免每帧 restart 导致 1 fps / 极低码率。
            if (frameWidth == pendingW && frameHeight == pendingH) {
                pendingCount++
            } else {
                pendingW = frameWidth
                pendingH = frameHeight
                pendingCount = 1
            }
            if (pendingCount >= RESTART_DEBOUNCE_FRAMES) {
                Log.w(TAG, "camera size changed ${width}x$height -> ${frameWidth}x$frameHeight, restarting encoder")
                restartCount++
                restartCodec(frameWidth, frameHeight)
                pendingCount = 0
                val recreated = codec ?: return
                if (!shouldFeed(ptsUs)) return
                feedFrame(recreated, image, ptsUs)
                return
            }
            if (handleLatency(current, ptsUs)) return
            if (!shouldFeed(ptsUs)) return
            feedFrameScaled(current, image, ptsUs)
            diagnose()
            return
        }
        if (handleLatency(current, ptsUs)) return
        if (!shouldFeed(ptsUs)) return
        feedFrame(current, image, ptsUs)
        diagnose()
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

    /**
     * 延迟保护：编码器输出落后采集超过 2.5 s 时 flush 重建流水线（3 s 冷却），
     * 避免慢编码设备上队列越积越长导致端到端延迟持续增大。
     */
    private fun handleLatency(codec: MediaCodec, ptsUs: Long): Boolean {
        val last = lastOutputPtsUs
        if (last <= 0L) return false
        val lag = ptsUs - last
        if (lag <= MAX_PIPELINE_LAG_US) return false
        val now = System.currentTimeMillis()
        if (now - lastLatencyResetMs < LATENCY_RESET_COOLDOWN_MS) {
            droppedInWindow++
            return true
        }
        lastLatencyResetMs = now
        Log.w(TAG, "encoder lag ${lag / 1000} ms, flush pipeline")
        runCatching {
            codec.flush()
            codec.start()
        }.onFailure { Log.w(TAG, "flush encoder failed", it) }
        lastOutputPtsUs = 0L
        lastFedAtUs = 0L
        pendingKeyFrame = true
        requestKeyFrame()
        droppedInWindow++
        return true
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

    private fun feedFrameScaled(codec: MediaCodec, image: Image, ptsUs: Long) {
        val inputIndex = runCatching { codec.dequeueInputBuffer(0L) }.getOrDefault(-1)
        if (inputIndex < 0) {
            droppedInWindow++
            drain(codec)
            return
        }
        val dstImage = codec.getInputImage(inputIndex)
        if (dstImage == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
            droppedInWindow++
            drain(codec)
            return
        }
        YuvUtils.copyImageToImageScaled(image, dstImage, width, height)
        codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
        framesInWindow++
        lastFedAtUs = ptsUs
        drain(codec)
    }

    /** 每 5s 输出一次编码诊断，便于真机远程定位低帧率/低码率。 */
    private fun diagnose() {
        val now = System.currentTimeMillis()
        if (now - lastDiagAtMs < 5_000) return
        val elapsedSec = ((now - lastDiagAtMs).coerceAtLeast(1L)) / 1000.0
        val inFps = ((inputFrames - lastDiagInput) / elapsedSec).toInt()
        val outFps = ((outputFrames - lastDiagOutput) / elapsedSec).toInt()
        lastDiagAtMs = now
        lastDiagInput = inputFrames
        lastDiagOutput = outputFrames
        Log.i(
            TAG,
            "diag: inFps=$inFps outFps=$outFps drops=$droppedInWindow restarts=$restartCount " +
                "size=${width}x$height target=${targetBitrate} avgEncodeMs=${"%.1f".format(averageEncodeMs)}",
        )
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
        outputFrames++
        lastOutputPtsUs = info.presentationTimeUs
        diagnose()
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
        // 仅按“实际丢帧”判定拥塞：编码耗时高只影响帧率，不应把用户设定的码率打到地板。
        // 局域网内带宽充足，码率优先保证画质；最低不低于配置值的一半（且 ≥800 kbps）。
        val floor = maxOf(MIN_BITRATE, baseBitrate / 2)
        val congested = dropRatio > 0.15

        windowStartMs = now
        framesInWindow = 0
        droppedInWindow = 0

        if (congested && now - lastAdjustMs > DOWN_COOLDOWN_MS) {
            val next = (targetBitrate * 0.8).toInt().coerceAtLeast(floor)
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

    private fun avcLevelFor(w: Int, h: Int): Int = when {
        w * h >= 1920 * 1080 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
        w * h >= 1280 * 720 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
        else -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
    }

    private fun restartCodec(frameWidth: Int, frameHeight: Int) {
        releaseCodec()
        try {
            var candidate: MediaCodec? = MediaCodec.createEncoderByType(MIME_VIDEO)
            val cbrSupported = runCatching {
                candidate!!.codecInfo.getCapabilitiesForType(MIME_VIDEO).encoderCapabilities
                    .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }.getOrDefault(false)
            val preferredProfile = if (frameWidth * frameHeight >= 1280 * 720) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            } else {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            }
            try {
                candidate!!.configure(
                    buildFormat(frameWidth, frameHeight, profile = preferredProfile, cbr = cbrSupported),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "configure with profile=$preferredProfile failed, retry baseline", t)
                runCatching { candidate?.release() }
                candidate = MediaCodec.createEncoderByType(MIME_VIDEO)
                try {
                    candidate!!.configure(
                        buildFormat(
                            frameWidth,
                            frameHeight,
                            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                            cbr = cbrSupported,
                        ),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                } catch (t2: Throwable) {
                    Log.w(TAG, "configure baseline failed, retry default", t2)
                    runCatching { candidate?.release() }
                    candidate = MediaCodec.createEncoderByType(MIME_VIDEO)
                    candidate!!.configure(
                        buildFormat(frameWidth, frameHeight, profile = null, cbr = cbrSupported),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                }
            }
            val encoder = candidate ?: error("MediaCodec unavailable")
            encoder.start()
            Log.i(
                TAG,
                "encoder started: ${frameWidth}x$frameHeight ${encoder.name} " +
                    "level=${avcLevelFor(frameWidth, frameHeight)} fps=${settings.fps} " +
                    "bitrate=$targetBitrate mode=${if (cbrSupported) "CBR" else "VBR"}",
            )
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

    private fun buildFormat(width: Int, height: Int, profile: Int?, cbr: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps.coerceIn(15, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SECONDS)
            // 1080p30 需要 AVC Level 4.0；Level 3.1 仅覆盖到 720p30，错配会导致部分编码器降速/异常。
            setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(width, height))
            runCatching { setInteger(MediaFormat.KEY_OPERATING_RATE, settings.fps.coerceIn(15, 60)) }
            runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            if (profile != null) {
                runCatching { setInteger(MediaFormat.KEY_PROFILE, profile) }
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
        const val MAX_PIPELINE_LAG_US = 2_500_000L
        const val LATENCY_RESET_COOLDOWN_MS = 3_000L
        const val RESTART_DEBOUNCE_FRAMES = 60
        const val MAX_BITRATE = 8_000_000
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
