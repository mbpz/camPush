package com.campush.app.streaming.wan

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * WebRTC 自定义 H.264 编码器（MediaCodec 软件/硬编通用）。
 *
 * 模拟器没有硬件 H264 时，WebRTC 默认工厂不会提供 H264；此实现让 WHIP offer 包含 H264，
 * 用 Android MediaCodec 输出 Annex-B 码流。
 */
class MediaCodecH264Encoder : VideoEncoder {
    private var codec: MediaCodec? = null
    private var callback: VideoEncoder.Callback? = null
    private var width = 1280
    private var height = 720
    private var configData: ByteArray? = null
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback?): VideoCodecStatus {
        this.callback = callback
        width = settings.width
        height = settings.height
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, settings.startBitrate * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.maxFramerate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            colorFormat = when {
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            VideoCodecStatus.OK
        } catch (t: Throwable) {
            Log.w(TAG, "initEncode failed", t)
            VideoCodecStatus.ERROR
        }
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo?): VideoCodecStatus {
        val c = codec ?: return VideoCodecStatus.UNINITIALIZED
        return try {
            val i420 = frame.buffer.toI420() ?: return VideoCodecStatus.ERROR
            val w = i420.width
            val h = i420.height
            val bytes = toEncoderYuv(i420, w, h)
            val inputIndex = c.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val input = c.getInputBuffer(inputIndex)!!
                input.clear()
                input.put(bytes)
                val key = info?.frameTypes?.firstOrNull() == EncodedImage.FrameType.VideoFrameKey
                c.queueInputBuffer(inputIndex, 0, bytes.size, frame.timestampNs / 1000, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            drain(c)
            VideoCodecStatus.OK
        } catch (t: Throwable) {
            Log.w(TAG, "encode failed", t)
            VideoCodecStatus.ERROR
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index < 0) break
            val buffer = c.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                val data = ByteArray(info.size)
                buffer.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    configData = data
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val payload = if (key && configData != null) configData!! + data else data
                    val bb = ByteBuffer.allocateDirect(payload.size)
                    bb.put(payload); bb.flip()
                    val image = EncodedImage.builder()
                        .setBuffer(bb, Runnable {})
                        .setEncodedWidth(width)
                        .setEncodedHeight(height)
                        .setCaptureTimeNs(info.presentationTimeUs * 1000)
                        .setFrameType(if (key) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta)
                        .createEncodedImage()
                    callback?.onEncodedFrame(image, VideoEncoder.CodecSpecificInfo())
                }
            }
            c.releaseOutputBuffer(index, false)
        }
    }

    private fun toEncoderYuv(i420: VideoFrame.I420Buffer, w: Int, h: Int): ByteArray {
        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        val sy = i420.strideY
        val su = i420.strideU
        val sv = i420.strideV
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            val out = ByteArray(w * h * 3 / 2)
            var o = 0
            for (row in 0 until h) {
                y.position(row * sy)
                y.get(out, o, w); o += w
            }
            for (row in 0 until h / 2) {
                u.position(row * su); u.get(out, o, w / 2); o += w / 2
            }
            for (row in 0 until h / 2) {
                v.position(row * sv); v.get(out, o, w / 2); o += w / 2
            }
            return out
        }
        // NV12: Y plane + interleaved UV
        val out = ByteArray(w * h * 3 / 2)
        var o = 0
        for (row in 0 until h) {
            y.position(row * sy)
            y.get(out, o, w); o += w
        }
        for (row in 0 until h / 2) {
            u.position(row * su); v.position(row * sv)
            var i = o
            for (col in 0 until w / 2) {
                out[i++] = u.get()
                out[i++] = v.get()
            }
            o += w
        }
        return out
    }

    override fun release(): VideoCodecStatus {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        return VideoCodecStatus.OK
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation?, frameRate: Int): VideoCodecStatus =
        VideoCodecStatus.OK

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF

    override fun getImplementationName(): String = "MediaCodecH264"

    override fun isHardwareEncoder(): Boolean = false

    companion object { private const val TAG = "MediaCodecH264" }
}

/** WebRTC 编码器工厂：只声明 H264，交给 MediaCodecH264Encoder。 */
class MediaCodecH264EncoderFactory : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? =
        if (info?.name?.equals("H264", ignoreCase = true) == true) MediaCodecH264Encoder() else null

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        arrayOf(
            VideoCodecInfo(
                "H264",
                mapOf(
                    VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID to "42e01f",
                    VideoCodecInfo.H264_FMTP_LEVEL_ASYMMETRY_ALLOWED to "1",
                    VideoCodecInfo.H264_FMTP_PACKETIZATION_MODE to "1",
                ),
                emptyList(),
            ),
        )
}

/**
 * 优先硬件 H264（DefaultVideoEncoderFactory），设备无硬件 H264 时回退到 MediaCodec 软件编码。
 * 真机保持原有硬件编码，模拟器也能完成 WHIP。
 */
class HybridH264EncoderFactory(eglContext: org.webrtc.EglBase.Context) : VideoEncoderFactory {
    private val hardware = org.webrtc.DefaultVideoEncoderFactory(eglContext, true, true)
    private val software = MediaCodecH264EncoderFactory()
    private val useHardware = hardware.supportedCodecs.any { it.name.equals("H264", ignoreCase = true) }

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? =
        if (useHardware) hardware.createEncoder(info) else software.createEncoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        if (useHardware) hardware.supportedCodecs else software.getSupportedCodecs()
}
