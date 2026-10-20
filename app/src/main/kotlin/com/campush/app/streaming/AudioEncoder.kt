package com.campush.app.streaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.campush.lan.AacAccessUnit
import com.campush.lan.LanMediaPublisher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集 + AAC 编码：AudioRecord（48 kHz 单声道 PCM16） -> MediaCodec audio/mp4a-latm（64 kbps）。
 *
 * - 首个 csd-0 作为 AAC sequence header 发布（isSequenceHeader = true）；
 * - muted 时读掉麦克风数据但填写静音帧，保证音轨连续、播放端不卡顿；
 * - 无 RECORD_AUDIO 权限（1002）时不上报错误、不阻断推流，持续发布静音 AAC 帧。
 */
class AudioEncoder(
    private val context: Context,
    private val publisher: LanMediaPublisher,
    mutedInitially: Boolean,
    /** 与视频共用同一时间原点（System.nanoTime 微秒），保证 A/V 时间戳同源。 */
    private val basePtsUs: Long = 0L,
) {

    @Volatile
    private var muted: Boolean = mutedInitially

    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    /** 是否有真实麦克风采集能力（无权限时为 false，走静音帧）。 */
    @Volatile
    private var hasMicPermission: Boolean = false

    fun setMuted(value: Boolean) {
        muted = value
    }

    /**
     * 启动采集线程。返回是否拥有麦克风权限；无论如何都会持续产出静音 AAC 帧。
     */
    fun start(): Boolean {
        if (running.get()) return hasMicPermission
        hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        running.set(true)
        worker = Thread({ runLoop() }, "campush-audio").apply {
            isDaemon = true
            start()
        }
        return hasMicPermission
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        worker?.interrupt()
        runCatching { worker?.join(1_000) }
        worker = null
    }

    private fun runLoop() {
        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val encoder = createAacEncoder() ?: return
            codec = encoder
            val record = createAudioRecord()
            audioRecord = record
            if (record != null) {
                runCatching { record.startRecording() }
                    .onFailure {
                        Log.w(TAG, "AudioRecord.startRecording failed, fallback to silence", it)
                        runCatching { record.release() }
                        audioRecord = null
                        hasMicPermission = false
                    }
            }

            val pcm = ShortArray(SAMPLES_PER_FRAME)
            var totalSamples = 0L
            while (running.get()) {
                val record = audioRecord
                if (record != null && hasMicPermission) {
                    val read = runCatching { record.read(pcm, 0, pcm.size) }.getOrDefault(-1)
                    if (read > 0) {
                        if (muted) java.util.Arrays.fill(pcm, 0, read, 0.toShort())
                        encodePcm(encoder, pcm, read, totalSamples)
                        totalSamples += read
                        drain(encoder)
                    } else {
                        Thread.sleep(FRAME_DURATION_MS)
                    }
                } else {
                    java.util.Arrays.fill(pcm, 0.toShort())
                    encodePcm(encoder, pcm, pcm.size, totalSamples)
                    totalSamples += pcm.size
                    drain(encoder)
                    Thread.sleep(FRAME_DURATION_MS)
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "audio loop failed", t)
        } finally {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            codec?.let { releaseCodec(it) }
        }
    }

    private fun createAacEncoder(): MediaCodec? {
        return try {
            val format = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }
            val codec = MediaCodec.createEncoderByType(MIME_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec
        } catch (t: Throwable) {
            Log.e(TAG, "AAC encoder init failed", t)
            null
        }
    }

    private fun createAudioRecord(): AudioRecord? {
        if (!hasMicPermission) return null
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = maxOf(minBuffer, SAMPLES_PER_FRAME * 2 * 4)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init failed, fallback to silence", t)
            hasMicPermission = false
            null
        }
    }

    private fun encodePcm(codec: MediaCodec, pcm: ShortArray, count: Int, startSample: Long) {
        if (count <= 0) return
        var offset = 0
        val byteCount = count * 2
        while (offset < byteCount && running.get()) {
            val inputIndex = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
            if (inputIndex < 0) return
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            val chunk = minOf(byteCount - offset, input.remaining() and 1.inv())
            if (chunk <= 0) return
            for (i in 0 until chunk / 2) {
                val sample = pcm[offset / 2 + i].toInt()
                input.put((sample and 0xFF).toByte())
                input.put(((sample shr 8) and 0xFF).toByte())
            }
            val ptsUs = basePtsUs + (startSample + offset / 2) * 1_000_000L / SAMPLE_RATE
            codec.queueInputBuffer(inputIndex, 0, chunk, ptsUs, 0)
            offset += chunk
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = runCatching { codec.dequeueOutputBuffer(info, 0L) }.getOrDefault(-1)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> publishSequenceHeaderFromFormat(codec)
                index >= 0 -> {
                    try {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null) {
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(data)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    publishSequenceHeader(data)
                                } else if (data.isNotEmpty()) {
                                    publishFrame(data, info.presentationTimeUs)
                                }
                            }
                        }
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }
                else -> return
            }
        }
    }

    private fun publishSequenceHeaderFromFormat(codec: MediaCodec) {
        runCatching {
            codec.outputFormat.getByteBuffer("csd-0")?.let { buffer ->
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                publishSequenceHeader(data)
            }
        }
    }

    private fun publishSequenceHeader(data: ByteArray) {
        if (data.isEmpty() || sequenceHeaderPublished) return
        sequenceHeaderPublished = true
        runCatching {
            publisher.publishAac(
                AacAccessUnit(
                    frame = data,
                    ptsUs = 0L,
                    isSequenceHeader = true,
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS,
                )
            )
        }.onFailure { Log.w(TAG, "publishAac(sequence header) failed", it) }
    }

    private fun publishFrame(data: ByteArray, ptsUs: Long) {
        runCatching {
            publisher.publishAac(
                AacAccessUnit(
                    frame = data,
                    ptsUs = ptsUs,
                    isSequenceHeader = false,
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS,
                )
            )
        }.onFailure { Log.w(TAG, "publishAac failed", it) }
    }

    private fun releaseCodec(codec: MediaCodec) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    @Volatile
    private var sequenceHeaderPublished: Boolean = false

    private companion object {
        const val TAG = "AudioEncoder"
        const val MIME_AAC = "audio/mp4a-latm"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val BITRATE = 64_000
        const val MAX_INPUT_SIZE = 16 * 1024
        const val SAMPLES_PER_FRAME = 960 // 20 ms @ 48 kHz
        const val FRAME_DURATION_MS = 20L
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
