package com.campush.app.core

import androidx.camera.view.PreviewView
import kotlinx.coroutines.flow.StateFlow

/** Lead 冻结的 P1 UI/控制器契约：app-stream 实现 P1ControllerImpl，app-ui 只消费。 */

enum class AccessScopeUi { LAN, WAN }

enum class CameraFacingUi { BACK, FRONT }

enum class CameraModeUi { SINGLE_BACK, SINGLE_FRONT }

data class StreamSettingsUi(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 2500,
    val audioEnabled: Boolean = true,
    val powerSave: Boolean = false,
    val adaptiveBitrate: Boolean = true,
) {
    val label: String
        get() = "${width}x${height} · ${fps}fps · ${bitrateKbps / 1000.0} Mbps"
}

data class P1Error(val code: Int, val message: String, val hint: String? = null)

data class P1UiState(
    val accessScope: AccessScopeUi = AccessScopeUi.LAN,
    val wanLocked: Boolean = true,
    val streaming: Boolean = false,
    val starting: Boolean = false,
    val cameraFacing: CameraFacingUi = CameraFacingUi.BACK,
    val cameraMode: CameraModeUi = CameraModeUi.SINGLE_BACK,
    val settings: StreamSettingsUi = StreamSettingsUi(),
    val playUrl: String? = null,
    val lanIp: String? = null,
    val port: Int = 8089,
    val pinRequired: Boolean = false,
    val pin: String? = null,
    /** 实际编码分辨率（相机支持的分辨率可能与设置不同，0 表示尚未开始）。 */
    val actualWidth: Int = 0,
    val actualHeight: Int = 0,
    val viewers: Int = 0,
    val maxViewers: Int = 3,
    val bitrateKbps: Int = 0,
    val fps: Int = 0,
    val networkLabel: String = "未知",
    val torchOn: Boolean = false,
    val muted: Boolean = false,
    val warning: String? = null,
    val error: P1Error? = null,
    val copiedHint: String? = null,
)

interface P1Controller {
    val state: StateFlow<P1UiState>

    fun start()
    fun stop()
    fun switchCamera()
    fun setTorch(on: Boolean)
    fun setMuted(muted: Boolean)
    fun selectAccessScope(scope: AccessScopeUi)
    fun updateSettings(settings: StreamSettingsUi)
    fun setPin(required: Boolean, pin: String?)
    fun setPort(port: Int)
    fun dismissError()
    fun dismissWarning()

    /** 预览绑定由 app-ui 的 CameraPreview composable 调用。 */
    fun bindPreview(previewView: PreviewView)
    fun unbindPreview()
    fun pausePreview()
    fun resumePreview()
}
