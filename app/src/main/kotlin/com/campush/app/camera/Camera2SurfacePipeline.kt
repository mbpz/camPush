package com.campush.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.campush.app.core.CameraFacingUi
import com.campush.app.diag.DiagLog
import com.campush.app.streaming.TsDemuxer
import java.io.InputStream

/**
 * 零拷贝推流管线：Camera2 -> MediaRecorder(SURFACE) -> MPEG-TS -> TsDemuxer -> MediaHub。
 *
 * 相机帧直接进硬件编码器（不再经过 CameraX ImageAnalysis 的 YUV 转换），
 * 1080p30 由设备硬件保证；预览 SurfaceView 直接由相机会话输出，预览与推流互不抢带宽。
 */
class Camera2SurfacePipeline(
    private val context: Context,
    private val facing: CameraFacingUi,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val onAccessUnit: (ptsUs: Long, isKey: Boolean, nalUnits: List<ByteArray>) -> Unit,
    private val onActualSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onStarted: () -> Unit,
    /** 小尺寸 JPEG（MJPEG 降级用），由 ImageReader 输出 YUV 后交给上层编码。 */
    private val onJpegImage: ((Image) -> Unit)? = null,
    private val watermark: Boolean = false,
) {

    private val thread = HandlerThread("campush-camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val jpegThread = HandlerThread("campush-jpeg").apply { start() }
    private val jpegHandler = Handler(jpegThread.looper)

    private var manager: CameraManager? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var recorderSurface: Surface? = null
    private var cameraOutputSurface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecInputSurface: Surface? = null
    private var codecThread: Thread? = null
    private var codecSps: ByteArray? = null
    private var codecPps: ByteArray? = null
    private var codecDrainLogs = 0
    private var watermarkRenderer: com.campush.app.streaming.watermark.GlWatermarkRenderer? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var jpegWidth = 0
    private var jpegHeight = 0
    private var actualWidth = width
    private var actualHeight = height
    private var torch = false
    private var running = false
    private var started = false
    private var readerThread: Thread? = null
    private var errorSent = false

    fun start() {
        handler.post {
            running = true
            try {
                val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager = mgr
                val cameraId = findCameraId(mgr) ?: throw IllegalStateException("未找到可用摄像头")
                val chars = mgr.getCameraCharacteristics(cameraId)
                chooseVideoSize(chars)
                mgr.openCamera(cameraId, deviceCallback, handler)
            } catch (t: Throwable) {
                reportError("打开相机失败：${t.message}")
            }
        }
    }

    fun stop() {
        handler.post { cleanup() }
    }

    /** 同步停止：等待相机/编码器/会话释放完成，避免切换摄像头时新旧相机会话竞争。 */
    fun stopAwait(timeoutMs: Long = 2_000L) {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            cleanup()
            latch.countDown()
        }
        runCatching { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private fun cleanup() {
        running = false
        try { readerThread?.interrupt() } catch (_: Throwable) {}
        readerThread = null
        runCatching { session?.close() }
        session = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecThread?.interrupt()
        codecThread = null
        runCatching { codecInputSurface?.release() }
        codecInputSurface = null
        runCatching { recorder?.let { if (started) it.stop(); it.reset(); it.release() } }
        recorder = null
        started = false
        runCatching { watermarkRenderer?.release() }
        watermarkRenderer = null
        cameraOutputSurface = null
        runCatching { recorderSurface?.release() }
        recorderSurface = null
        runCatching { imageReader?.close() }
        imageReader = null
        jpegThread.quitSafely()
        runCatching { pipeRead?.close() }
        pipeRead = null
        runCatching { pipeWrite?.close() }
        pipeWrite = null
        runCatching { device?.close() }
        device = null
        thread.quitSafely()
    }

    fun setPreviewSurface(surface: Surface?) {
        handler.post {
            if (previewSurface == surface) return@post
            previewSurface = surface
            if (device != null && cameraOutputSurface != null) recreateSession()
        }
    }

    fun setTorch(on: Boolean) {
        handler.post {
            torch = on
            session?.let { s ->
                runCatching { s.setRepeatingRequest(buildRequest(), null, handler) }
            }
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!running) {
                runCatching { camera.close() }
                return
            }
            device = camera
            try {
                setupRecorder()
                createSession()
            } catch (t: Throwable) {
                Log.w(TAG, "setup recorder failed", t)
                reportError("零拷贝编码初始化失败：${t.message}")
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            runCatching { camera.close() }
            reportError("相机已断开")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            runCatching { camera.close() }
            reportError("相机错误 code=$error")
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, actualWidth, actualHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, (bitrateKbps.coerceIn(500, 20_000)) * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        codecInputSurface = input
        val renderer = com.campush.app.streaming.watermark.GlWatermarkRenderer(
            input, actualWidth, actualHeight, "CamPush", context,
        )
        cameraOutputSurface = renderer.start()
        watermarkRenderer = renderer
        started = true
        Log.i(TAG, "surfaces codecInput=${input} cameraOut=$cameraOutputSurface same=${cameraOutputSurface === input}")
        DiagLog.i("Surface", "watermark MediaCodec enabled ${actualWidth}x$actualHeight")
        codecThread = Thread({ drainEncoder(encoder) }, "campush-encoder-drain").apply { start() }
    }

    private fun drainEncoder(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = runCatching { encoder.dequeueOutputBuffer(info, 10_000L) }.getOrElse { -1 }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue
            runCatching {
                val buffer = encoder.getOutputBuffer(index) ?: return@runCatching
                if (info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parseConfig(data)
                    } else {
                        val nals = splitAnnexB(data)
                        if (nals.isNotEmpty()) {
                            if (codecDrainLogs++ < 3) Log.i(TAG, "codec AU nals=${nals.size} key=${info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0} bytes=${data.size}")
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val withConfig = if (key) {
                                listOfNotNull(codecSps, codecPps) + nals
                            } else nals
                            onAccessUnit(info.presentationTimeUs, key, withConfig)
                        }
                    }
                }
            }
            runCatching { encoder.releaseOutputBuffer(index, false) }
        }
    }

    private fun parseConfig(data: ByteArray) {
        for (nal in splitAnnexB(data)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> codecSps = nal
                8 -> codecPps = nal
            }
        }
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 3 <= data.size) {
            val four = i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val three = !four && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (four || three) {
                if (start >= 0) out.add(data.copyOfRange(start, i))
                start = i + if (four) 4 else 3
                i = start
            } else {
                i++
            }
        }
        if (start >= 0 && start < data.size) out.add(data.copyOfRange(start, data.size))
        return out
    }

    private fun setupRecorder() {
        if (watermark) {
            setupEncoder()
            setupJpegReader()
            onActualSize(actualWidth, actualHeight)
            return
        }
        val pipe = ParcelFileDescriptor.createPipe()
        pipeRead = pipe[0]
        pipeWrite = pipe[1]
        val rec = newMediaRecorder()
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(actualWidth, actualHeight)
        rec.setVideoFrameRate(fps)
        rec.setVideoEncodingBitRate((bitrateKbps.coerceIn(500, 20_000)) * 1000)
        // 1080p 清晰度：优先 High Profile（同码率压缩效率更高）；设备不支持时保持默认。
        runCatching {
            rec.setVideoEncodingProfileLevel(
                android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            )
        }
        runCatching { rec.setOrientationHint(90) }
        rec.setOutputFile(pipeWrite!!.fileDescriptor)
        rec.prepare()
        recorder = rec
        // 先启动编码器，再取输入 Surface，最后创建 GL 输出。
        runCatching { rec.start(); started = true }
        recorderSurface = rec.surface
        if (watermark) {
            val renderer = com.campush.app.streaming.watermark.GlWatermarkRenderer(
                recorderSurface!!, actualWidth, actualHeight, "CamPush", context,
            )
            cameraOutputSurface = renderer.start()
            watermarkRenderer = renderer
            DiagLog.i("Surface", "watermark GL enabled ${actualWidth}x$actualHeight")
        } else {
            cameraOutputSurface = rec.surface
        }
        Log.i(TAG, "recorder ready ${actualWidth}x$actualHeight@$fps ${bitrateKbps}kbps")
        DiagLog.i("Surface", "recorder ready ${actualWidth}x$actualHeight@$fps ${bitrateKbps}kbps facing=$facing")
        setupJpegReader()
        onActualSize(actualWidth, actualHeight)
    }

    /** 额外开一路小尺寸 YUV 输出用于 MJPEG（iOS Safari 无 MSE，必须有 JPEG 降级）。 */
    private fun setupJpegReader() {
        if (onJpegImage == null) return
        runCatching {
            val mgr = manager ?: return
            val id = device?.id ?: return
            val map = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return
            if (sizes.isEmpty()) return
            val chosen = sizes
                .filter { it.width <= 960 && it.width >= 320 && kotlin.math.abs(it.width.toDouble() / it.height - 16.0 / 9.0) < 0.12 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { kotlin.math.abs(it.width - 640) }
                ?: return
            jpegWidth = chosen.width
            jpegHeight = chosen.height
            val reader = ImageReader.newInstance(chosen.width, chosen.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    onJpegImage?.invoke(image)
                } catch (t: Throwable) {
                    Log.w(TAG, "jpeg encode failed", t)
                } finally {
                    runCatching { image.close() }
                }
            }, jpegHandler)
            imageReader = reader
            DiagLog.i("Surface", "jpeg reader ${chosen.width}x${chosen.height} for MJPEG fallback")
        }.onFailure { Log.w(TAG, "setup jpeg reader failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val dev = device ?: return
        val recSurface = cameraOutputSurface ?: return
        val targets = ArrayList<Surface>(3)
        targets.add(recSurface)
        previewSurface?.let { targets.add(it) }
        imageReader?.surface?.let { targets.add(it) }
        try {
            dev.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running) return
                    this@Camera2SurfacePipeline.session = session
                    try {
                        recorder?.start()
                        started = true
                    } catch (t: Throwable) {
                        reportError("编码器启动失败：${t.message}")
                        return
                    }
                    runCatching { session.setRepeatingRequest(buildRequest(), null, handler) }
                        .onFailure { reportError("启动相机会话失败：${it.message}") }
                    startReader()
                    onStarted()
                    Log.i(TAG, "surface pipeline started")
                    DiagLog.i("Surface", "pipeline started facing=$facing ${actualWidth}x$actualHeight")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (imageReader != null) {
                        // 部分机型不支持 3 路输出：关闭 JPEG 输出后重试 2 路，MJPEG 降级不可用。
                        DiagLog.w("Surface", "3-stream session failed, retry without jpeg reader")
                        runCatching { imageReader?.close() }
                        imageReader = null
                        if (running) createSession()
                    } else {
                        reportError("相机会话配置失败")
                    }
                }
            }, handler)
        } catch (t: Throwable) {
            if (running) reportError("创建相机会话失败：${t.message}")
        }
    }

    private fun recreateSession() {
        if (!running) return
        handler.post {
            if (!running) return@post
            runCatching { session?.close() }
            session = null
            val dev = device ?: return@post
            val recSurface = cameraOutputSurface ?: return@post
            val targets = ArrayList<Surface>(3)
            targets.add(recSurface)
            previewSurface?.let { targets.add(it) }
            imageReader?.surface?.let { targets.add(it) }
            try {
                @Suppress("DEPRECATION")
                dev.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running) return
                        this@Camera2SurfacePipeline.session = session
                        if (!started) {
                            runCatching { recorder?.start() }
                            started = true
                        }
                        runCatching { session.setRepeatingRequest(buildRequest(), null, handler) }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (imageReader != null) {
                            runCatching { imageReader?.close() }
                            imageReader = null
                            if (running) recreateSession()
                        } else if (running) {
                            Log.w(TAG, "recreate preview session failed")
                        }
                    }
                }, handler)
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "recreate preview session failed", t)
            }
        }
    }

    private fun buildRequest(): CaptureRequest {
        val dev = device ?: throw IllegalStateException("camera closed")
        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        previewSurface?.let { builder.addTarget(it) }
        imageReader?.surface?.let { builder.addTarget(it) }
        cameraOutputSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange(dev.id))
        builder.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        return builder.build()
    }

    private fun aeRange(cameraId: String): Range<Int> {
        val chars = manager?.getCameraCharacteristics(cameraId)
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (ranges != null && ranges.isNotEmpty()) {
            ranges.firstOrNull { it.upper == fps && it.lower == fps }?.let { return it }
            ranges.firstOrNull { it.upper == fps }?.let { return it }
            return ranges.maxByOrNull { it.upper } ?: Range(fps, fps)
        }
        return Range(fps, fps)
    }

    private fun chooseVideoSize(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
            ?: map.getOutputSizes(SurfaceTexture::class.java)
            ?: return
        if (sizes.isEmpty()) return
        val target = Size(width, height)
        val targetRatio = width.toDouble() / height
        val candidates = sizes.filter { size ->
            val r = size.width.toDouble() / size.height
            kotlin.math.abs(r - targetRatio) < 0.05
        }.ifEmpty { sizes.toList() }
        val chosen = candidates.minByOrNull { size ->
            val area = size.width.toLong() * size.height
            kotlin.math.abs(area - target.width.toLong() * target.height) +
                if (area > target.width.toLong() * target.height) 1_000_000_000L else 0L
        } ?: target
        actualWidth = chosen.width
        actualHeight = chosen.height
    }

    private fun findCameraId(mgr: CameraManager): String? {
        val want = if (facing == CameraFacingUi.FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == want
        } ?: mgr.cameraIdList.firstOrNull()
    }

    private fun startReader() {
        Log.i(TAG, "startReader pipeRead=$pipeRead watermark=$watermark")
        if (readerThread != null) return
        val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pipeRead ?: return)
        val demux = TsDemuxer { ptsUs, isKey, nals ->
            if (nals.isNotEmpty()) onAccessUnit(ptsUs, isKey, nals)
        }
        val thread = Thread({
            val buffer = ByteArray(64 * 1024)
            try {
                while (running) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    demux.feed(buffer, 0, n)
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "ts reader stopped", t)
            } finally {
                runCatching { input.close() }
            }
        }, "campush-ts-reader")
        readerThread = thread
        thread.start()
    }

    private fun reportError(message: String) {
        if (errorSent) return
        errorSent = true
        Log.w(TAG, "surface pipeline error: $message")
        DiagLog.e("Surface", "pipeline error: $message")
        onError(message)
    }

    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    companion object {
        private const val TAG = "Camera2SurfacePipeline"
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
