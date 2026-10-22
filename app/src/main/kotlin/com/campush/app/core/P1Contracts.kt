package com.campush.app.core

import android.view.SurfaceView
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
    /** P2 D-4：可选 RTMP 旁路推流地址（rtmp://host/app/stream）；空 = 关闭。 */
    val rtmpUrl: String = "",
    /** P3 T2-A7：开始推流时同时本地录制 MP4（应用私有目录）。 */
    val recordEnabled: Boolean = false,
    /** P3：单摄直播流 GL 烧录水印（实验，失败回退无烧录）。 */
    val watermarkEnabled: Boolean = false,
) {
    val label: String
        get() = "${width}x${height} · ${fps}fps · ${bitrateKbps / 1000.0} Mbps"
}

data class RecordingUi(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class P1Error(val code: Int, val message: String, val hint: String? = null)

/** P2 WAN WHIP 联调状态（原型；正式 WAN 房间流程接入后替换）。 */
data class WanTestUi(
    val running: Boolean = false,
    val status: String = "idle",
    val roomId: String? = null,
    val playUrl: String? = null,
    val error: String? = null,
)

data class P1UiState(
    val accessScope: AccessScopeUi = AccessScopeUi.LAN,
    val wanLocked: Boolean = true,
    /** P2 WAN Cloud API 地址（设置页配置，主界面 WAN 模式使用）。 */
    val cloudApiHost: String = "",
    /** P3 账号会话（token 仅持久化，不在 UI 暴露）。 */
    val accountEmail: String? = null,
    val accountPlan: String? = null,
    val accountLoggedIn: Boolean = false,
    val accountBusy: Boolean = false,
    val byoWhipHost: String = "",
    val byoPlayHost: String = "",
    val byoRtmpHost: String = "",
    val byoMode: String = "managed",
    val byoStatus: String? = null,
    val byoBusy: Boolean = false,
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
    val cameraAvailable: Boolean = true,
    /** T0-A1 双摄能力探测（D-3 门槛数据）。 */
    val dualCameraSupported: Boolean = false,
    val physicalCameraCount: Int = 0,
    val concurrentCameraPairs: Int = 0,
    val surfacePipeline: Boolean = false,
    val networkLabel: String = "未知",
    /** 独立日志服务地址（应用打开即可用，不依赖推流）。 */
    val logServiceUrl: String? = null,
    val logServiceRunning: Boolean = false,
    val torchOn: Boolean = false,
    val muted: Boolean = false,
    val warning: String? = null,
    val error: P1Error? = null,
    val copiedHint: String? = null,
)

interface P1Controller {
    val state: StateFlow<P1UiState>

    /** 推流中 App 内预览 JPEG（CameraX Preview 已释放，避免与分析并发限帧）。 */
    val previewJpeg: StateFlow<ByteArray?>

    /** P2 T0-A2：WAN WHIP 联调（设置页开发入口）。 */
    val wanTest: StateFlow<WanTestUi>
    fun startWanRoom(apiBase: String)
    fun stopWanRoom()
    /** T0-A3 实验：打开双摄 PiP 预览。 */
    fun openDualPipPreview()

    fun start()
    fun stop()
    fun switchCamera()
    fun setTorch(on: Boolean)
    fun setMuted(muted: Boolean)
    fun selectAccessScope(scope: AccessScopeUi)
    fun setCloudApiHost(host: String)
    fun login(email: String, password: String, register: Boolean)
    fun logout()
    fun refreshByo()
    fun saveByo(whipHost: String, playHost: String, rtmpHost: String)
    fun testByo()
    /** P3 T3-A7：打开 App 内观众端。 */
    fun openAudience()
    /** T3-A5：导出脱敏诊断日志（token/PIN/roomId 已移除）。 */
    fun exportDiagnostics(): String

    fun listRecordings(): List<RecordingUi>
    fun shareRecording(path: String)
    fun deleteRecording(path: String)
    fun updateSettings(settings: StreamSettingsUi)
    fun setPin(required: Boolean, pin: String?)
    fun setPort(port: Int)
    fun dismissError()
    fun dismissWarning()

    /** P1 埋点：地址复制 / 二维码 / 分享（UI 只调契约，不直接依赖 telemetry 实现）。 */
    fun onAddressCopied()
    fun onQrShown()
    fun onShareClicked()

    /** 预览绑定由 app-ui 的 CameraPreview composable 调用。 */
    fun bindPreview(previewView: PreviewView)
    fun unbindPreview()

    /** 零拷贝推流模式下的原生相机预览 Surface。 */
    fun bindStreamPreview(view: SurfaceView)
    fun unbindStreamPreview()
    fun pausePreview()
    fun resumePreview()
}
