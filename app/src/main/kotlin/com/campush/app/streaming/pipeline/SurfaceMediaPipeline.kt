package com.campush.app.streaming.pipeline

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import com.campush.app.camera.Camera2SurfacePipeline
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.MediaPipeline
import com.campush.app.core.MediaPipelineCallbacks
import com.campush.app.core.MediaPipelineKind
import com.campush.app.core.MediaPipelineSpec
import com.campush.app.core.PipelineDiagnostics
import com.campush.app.streaming.JpegEncoder
import com.campush.lan.H264AccessUnit
import com.campush.lan.LanMediaPublisher

/** Camera2 + MediaRecorder(TS) 零拷贝管线适配器。 */
class SurfaceMediaPipeline(
    private val context: Context,
    private val publisher: LanMediaPublisher,
    private val callbacks: MediaPipelineCallbacks,
    private val nowUs: () -> Long,
) : MediaPipeline {

    override val kind: MediaPipelineKind = MediaPipelineKind.SURFACE
    private var spec: MediaPipelineSpec? = null
    private var pipeline: Camera2SurfacePipeline? = null
    private var jpegEncoder: JpegEncoder? = null
    private var previewView: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSpec: MediaPipelineSpec? = null
    private var started = false
    private var startChecks = 0
    private var rotationDegrees = 0
    private val startCheck = Runnable { tryStart() }

    override fun start(spec: MediaPipelineSpec) {
        this.spec = spec
        rotationDegrees = computeRotation(spec.facing)
        pendingSpec = spec
        started = false
        startChecks = 0
        tryStart()
    }

    /** 等 UI 的 SurfaceView 就绪再创建相机会话，避免启动后重建会话导致黑屏。 */
    private fun tryStart() {
        if (started) return
        val target = pendingSpec ?: return
        val surface = previewView?.holder?.surface
        if (surface?.isValid == true || startChecks >= 15) {
            pendingSpec = null
            startChecks = 0
            doStart(target)
        } else {
            startChecks++
            handler.postDelayed(startCheck, 100L)
        }
    }

    private fun doStart(spec: MediaPipelineSpec) {
        started = true
        val jpeg = JpegEncoder(publisher) { bytes -> callbacks.onPreviewJpeg(bytes) }
        jpegEncoder = jpeg
        val base = nowUs()
        val p = Camera2SurfacePipeline(
            context = context,
            facing = spec.facing,
            width = spec.settings.width,
            height = spec.settings.height,
            fps = spec.settings.fps,
            bitrateKbps = spec.settings.bitrateKbps,
            onAccessUnit = { ptsUs, isKey, nals ->
                runCatching { publisher.publishH264(H264AccessUnit(nals, base + ptsUs, isKey)) }
            },
            onActualSize = { w, h -> callbacks.onActualSize(w, h) },
            onError = { message -> callbacks.onError(message) },
            onStarted = { callbacks.onStarted(MediaPipelineKind.SURFACE) },
            onJpegImage = { image -> runCatching { jpeg.onFrame(image) } },
            watermark = spec.settings.watermarkEnabled,
        )
        pipeline = p
        previewView?.let { runCatching { p.setPreviewSurface(it.holder.surface) } }
        p.start()
    }

    override fun stop() {
        started = false
        pendingSpec = null
        handler.removeCallbacks(startCheck)
        pipeline?.let { runCatching { it.stopAwait() } }
        pipeline = null
        jpegEncoder?.let { runCatching { it.close() } }
        jpegEncoder = null
    }

    override fun update(spec: MediaPipelineSpec) {
        stop()
        start(spec)
    }

    override fun switchCamera(facing: CameraFacingUi) {
        val current = spec ?: return
        update(current.copy(facing = facing))
    }

    override fun setTorch(on: Boolean) {
        pipeline?.setTorch(on)
    }

    override fun bindPreview(view: SurfaceView) {
        previewView = view
        if (pendingSpec != null) {
            tryStart()
        } else {
            runCatching { pipeline?.setPreviewSurface(view.holder.surface) }
        }
    }

    override fun unbindPreview() {
        previewView = null
        runCatching { pipeline?.setPreviewSurface(null) }
    }

    override fun requestKeyFrame() = Unit

    override val playerRotationDegrees: Int
        get() = rotationDegrees

    private fun computeRotation(facing: CameraFacingUi): Int {
        return runCatching {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (facing == CameraFacingUi.FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val id = mgr.cameraIdList.firstOrNull { cameraId ->
                mgr.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == want
            } ?: mgr.cameraIdList.firstOrNull() ?: return 0
            val sensor = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val display = when (
                (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            ) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val raw = if (facing == CameraFacingUi.FRONT) {
                (sensor + display) % 360
            } else {
                (360 - sensor + display) % 360
            }
            ((raw % 360) + 360) % 360
        }.getOrDefault(0)
    }

    override fun diagnostics(): PipelineDiagnostics = PipelineDiagnostics(
        codecName = "MediaRecorder/H.264",
        hardware = true,
        colorFormat = 0,
        width = spec?.settings?.width ?: 0,
        height = spec?.settings?.height ?: 0,
        inFps = 0,
        outFps = 0,
        avgEncodeMs = 0.0,
    )
}
