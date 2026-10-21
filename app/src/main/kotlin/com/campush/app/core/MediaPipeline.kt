package com.campush.app.core

import android.view.SurfaceView
import com.campush.lan.LanMediaPublisher

/**
 * 采集/编码管线抽象（架构红线：引擎只依赖本契约，不依赖 Camera2/CameraX 具体实现）。
 *
 * 新增管线（Camera2+MediaRecorder Surface、CameraX 兼容、后续 WebRTC/GL）：
 * 实现 [MediaPipeline] + 在 [MediaPipelineFactory] 中装配适配器，禁止在 UI/引擎里写具体分支。
 */
enum class MediaPipelineKind { SURFACE, LEGACY }

data class MediaPipelineSpec(
    val facing: CameraFacingUi,
    val settings: StreamSettingsUi,
    val muted: Boolean,
)

data class PipelineDiagnostics(
    val codecName: String,
    val hardware: Boolean,
    val colorFormat: Int,
    val width: Int,
    val height: Int,
    val inFps: Int,
    val outFps: Int,
    val avgEncodeMs: Double,
)

/** 管线向引擎汇报状态；管线内部自行 publish H264（音频由引擎独立处理）。 */
interface MediaPipelineCallbacks {
    fun onActualSize(width: Int, height: Int)
    fun onStarted(kind: MediaPipelineKind)
    fun onError(message: String)
    fun onCameraAvailability(available: Boolean)
    fun onPreviewJpeg(bytes: ByteArray?)
}

interface MediaPipeline {
    val kind: MediaPipelineKind

    fun start(spec: MediaPipelineSpec)

    fun stop()

    /** 以新分辨率/朝向重启；播放地址由上层保持，不走这里。 */
    fun update(spec: MediaPipelineSpec)

    fun switchCamera(facing: CameraFacingUi)

    fun setTorch(on: Boolean)

    /** 仅 Surface 管线使用；Legacy 管线通过 [MediaPipelineCallbacks.onPreviewJpeg] 预览。 */
    fun bindPreview(view: SurfaceView)

    fun unbindPreview()

    fun requestKeyFrame()

    fun diagnostics(): PipelineDiagnostics?

    /** 播放端需要旋转的角度（0/90/180/270）；由管线根据相机传感器/屏幕方向计算。 */
    val playerRotationDegrees: Int get() = 0
}

interface MediaPipelineFactory {
    fun isSurfaceSupported(): Boolean

    fun createSurface(
        publisher: LanMediaPublisher,
        callbacks: MediaPipelineCallbacks,
        nowUs: () -> Long,
    ): MediaPipeline?

    fun createLegacy(
        publisher: LanMediaPublisher,
        callbacks: MediaPipelineCallbacks,
        nowUs: () -> Long,
    ): MediaPipeline
}
