package com.campush.app.streaming.pipeline

import android.content.Context
import android.view.SurfaceView
import com.campush.app.camera.Camera2SurfacePipeline
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.MediaPipeline
import com.campush.app.core.MediaPipelineCallbacks
import com.campush.app.core.MediaPipelineKind
import com.campush.app.core.MediaPipelineSpec
import com.campush.app.core.PipelineDiagnostics
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
    private var previewView: SurfaceView? = null

    override fun start(spec: MediaPipelineSpec) {
        this.spec = spec
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
        )
        pipeline = p
        previewView?.let { runCatching { p.setPreviewSurface(it.holder.surface) } }
        p.start()
    }

    override fun stop() {
        pipeline?.let { runCatching { it.stopAwait() } }
        pipeline = null
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
        runCatching { pipeline?.setPreviewSurface(view.holder.surface) }
    }

    override fun unbindPreview() {
        previewView = null
        runCatching { pipeline?.setPreviewSurface(null) }
    }

    override fun requestKeyFrame() = Unit

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
