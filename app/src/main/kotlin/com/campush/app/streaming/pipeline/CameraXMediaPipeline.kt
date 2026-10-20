package com.campush.app.streaming.pipeline

import android.content.Context
import android.view.SurfaceView
import androidx.lifecycle.LifecycleOwner
import com.campush.app.camera.CameraHost
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.MediaPipeline
import com.campush.app.core.MediaPipelineCallbacks
import com.campush.app.core.MediaPipelineKind
import com.campush.app.core.MediaPipelineSpec
import com.campush.app.core.PipelineDiagnostics
import com.campush.app.streaming.JpegEncoder
import com.campush.app.streaming.VideoEncoder
import com.campush.lan.LanMediaPublisher

/** CameraX Preview + ImageAnalysis 兼容管线适配器（API < 26 或 Surface 管线失败时使用）。 */
class CameraXMediaPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val publisher: LanMediaPublisher,
    private val callbacks: MediaPipelineCallbacks,
    private val nowUs: () -> Long,
) : MediaPipeline {

    override val kind: MediaPipelineKind = MediaPipelineKind.LEGACY
    private var spec: MediaPipelineSpec? = null
    private var video: VideoEncoder? = null
    private var jpeg: JpegEncoder? = null

    override fun start(spec: MediaPipelineSpec) {
        this.spec = spec
        val v = VideoEncoder(publisher, spec.settings) { error -> callbacks.onError(error.message ?: "编码器错误") }
        video = v
        val j = JpegEncoder(publisher) { bytes -> callbacks.onPreviewJpeg(bytes) }
        jpeg = j
        CameraHost.get(context).startStreaming(
            lifecycleOwner = lifecycleOwner,
            facing = spec.facing,
            width = spec.settings.width,
            height = spec.settings.height,
            fps = spec.settings.fps,
            frameCallback = { image, _ ->
                callbacks.onActualSize(image.width, image.height)
                v.encode(image, nowUs())
                j.onFrame(image)
            },
            errorCallback = { error -> callbacks.onError(error.message ?: "相机不可用") },
            cameraStateCallback = { available -> callbacks.onCameraAvailability(available) },
        )
        v.requestKeyFrame()
        callbacks.onStarted(MediaPipelineKind.LEGACY)
    }

    override fun stop() {
        video?.let { runCatching { it.close() } }
        video = null
        jpeg?.let { runCatching { it.close() } }
        jpeg = null
        runCatching { CameraHost.current()?.stopStreaming() }
    }

    override fun update(spec: MediaPipelineSpec) {
        stop()
        start(spec)
    }

    override fun switchCamera(facing: CameraFacingUi) {
        spec = spec?.copy(facing = facing)
        CameraHost.get(context).switchCamera(facing)
    }

    override fun setTorch(on: Boolean) {
        CameraHost.get(context).setTorch(on)
    }

    override fun bindPreview(view: SurfaceView) = Unit

    override fun unbindPreview() = Unit

    override fun requestKeyFrame() {
        video?.requestKeyFrame()
    }

    override fun diagnostics(): PipelineDiagnostics? = video?.diagnostics()?.let { d ->
        PipelineDiagnostics(d.codecName, d.hardware, d.colorFormat, d.width, d.height, d.inFps, d.outFps, d.avgEncodeMs)
    }
}
