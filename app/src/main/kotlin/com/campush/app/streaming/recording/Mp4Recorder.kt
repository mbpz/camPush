package com.campush.app.streaming.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.campush.lan.AacAccessUnit
import com.campush.lan.H264AccessUnit
import java.io.File
import java.nio.ByteBuffer

/**
 * P3 T2-A7：把已编码的 H264/AAC access unit 写入本地 MP4，不额外占用相机/编码器。
 * 录制失败只记录日志，不影响推流主链路。
 */
class Mp4Recorder(
    private val output: File,
    private val audioExpected: Boolean = true,
    private val width: Int = 1280,
    private val height: Int = 720,
) {
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false
    private var samples = 0L
    private var firstVideoPtsUs = -1L
    private var pendingAudioConfig: AacAccessUnit? = null

    @Synchronized
    fun onVideo(unit: H264AccessUnit) {
        if (stopped) return
        runCatching {
            if (videoTrack < 0) {
                var inlineSps: ByteArray? = null
                var inlinePps: ByteArray? = null
                for (nal in unit.nalUnits) {
                    if (nal.isEmpty()) continue
                    when (nal[0].toInt() and 0x1F) {
                        7 -> inlineSps = nal
                        8 -> inlinePps = nal
                    }
                }
                val sps = unit.sps ?: inlineSps ?: return
                val pps = unit.pps ?: inlinePps ?: return
                val mux = muxer ?: MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width.coerceAtLeast(2),
                    height.coerceAtLeast(2),
                ).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
                    setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
                }
                videoTrack = mux.addTrack(format)
                pendingAudioConfig?.let { audio -> addAudioTrack(audio) }
            }
            val mux = muxer ?: return
            if (!started) {
                if (firstVideoPtsUs < 0) firstVideoPtsUs = unit.ptsUs
                val audioTimedOut = audioExpected && pendingAudioConfig == null &&
                    unit.ptsUs - firstVideoPtsUs > 3_000_000L
                if (audioTrack < 0 && !audioTimedOut && (audioExpected && pendingAudioConfig == null)) return
                mux.start(); started = true
            }
            val data = unit.nalUnits.fold(ByteArray(0)) { acc, nal -> acc + withStartCode(nal) }
            if (data.isEmpty()) return
            val buffer = ByteBuffer.allocateDirect(data.size).apply { put(data); position(0) }
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = data.size
                presentationTimeUs = unit.ptsUs.coerceAtLeast(0)
                flags = if (unit.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            mux.writeSampleData(videoTrack, buffer, info)
            samples++
        }.onFailure { Log.w(TAG, "record video failed: ${it.message}") }
    }

    @Synchronized
    fun onAudio(unit: AacAccessUnit) {
        if (stopped) return
        runCatching {
            if (audioTrack < 0) {
                if (!unit.isSequenceHeader) return
                if (videoTrack < 0) {
                    pendingAudioConfig = unit
                    return
                }
                addAudioTrack(unit)
            }
            val mux = muxer ?: return
            if (!started) {
                if (videoTrack < 0) return
                mux.start(); started = true
            }
            val buffer = ByteBuffer.allocateDirect(unit.frame.size).apply { put(unit.frame); position(0) }
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = unit.frame.size
                presentationTimeUs = unit.ptsUs.coerceAtLeast(0)
                flags = if (unit.isSequenceHeader) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            }
            mux.writeSampleData(audioTrack, buffer, info)
            samples++
        }.onFailure { Log.w(TAG, "record audio failed: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching {
            if (started) muxer?.stop()
            muxer?.release()
            if (samples == 0L) output.delete()
            Log.i(TAG, "record saved=${output.absolutePath} samples=$samples")
        }.onFailure { Log.w(TAG, "record stop failed", it) }
        muxer = null
    }

    private fun addAudioTrack(unit: AacAccessUnit) {
        val mux = muxer ?: return
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, unit.sampleRate, unit.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.AAC_LC)
            if (unit.isSequenceHeader) setByteBuffer("csd-0", ByteBuffer.wrap(unit.frame))
        }
        audioTrack = mux.addTrack(format)
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) return nal
        if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) return nal
        return byteArrayOf(0, 0, 0, 1) + nal
    }

    private object MediaCodecInfo {
        const val AAC_LC = 2
    }

    companion object {
        private const val TAG = "Mp4Recorder"
    }
}
