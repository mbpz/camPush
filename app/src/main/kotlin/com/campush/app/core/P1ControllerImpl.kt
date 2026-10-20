package com.campush.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.campush.app.camera.CameraHost
import com.campush.app.network.NetworkMonitor
import com.campush.app.service.LanStreamingService
import com.campush.app.streaming.EnginePhase
import com.campush.app.streaming.EngineSnapshot
import com.campush.app.streaming.StreamStartOptions
import com.campush.app.streaming.StreamingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * P1 UI 控制器实现：把 UI 操作映射到前台服务 + LAN 推流引擎，并汇总 StateFlow<P1UiState>。
 *
 * 状态更新全部在 Dispatchers.Main.immediate；相机/编码由引擎在专用线程执行，
 * Controller 本身不做任何编解码/IO 阻塞操作。
 */
class P1ControllerImpl(context: Context) : P1Controller {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val networkMonitor = NetworkMonitor(appContext)

    /** 连续低帧率采样与自动降级标记（PRD §8.4 性能保护）。 */
    private var lowFpsSamples: Int = 0
    private var autoFallbackDone: Boolean = false

    private val _state = MutableStateFlow(restoreState())
    override val state: StateFlow<P1UiState> = _state.asStateFlow()

    init {
        observeBridge()
        observeNetworkLabel()
    }

    // ---------------------------------------------------------------- UI 操作

    override fun start() {
        val current = _state.value
        if (current.starting || current.streaming) return
        _state.update { it.copy(starting = true, error = null, warning = null) }

        // 相机权限缺失：1001，阻止推流。
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showError(
                P1Error(
                    1001,
                    "未授予摄像头权限，无法预览与推流",
                    "请在系统设置中允许相机权限后重试",
                )
            )
            return
        }

        // 局域网检查：非 Wi-Fi/热点 -> 6003。
        val network = runCatching { networkMonitor.current() }.getOrNull()
        if (network == null || !network.isLan) {
            _state.update { it.copy(networkLabel = network?.label ?: "无网络") }
            showError(
                P1Error(
                    6003,
                    "仅局域网需要连接同一 Wi-Fi 或开启热点",
                    "请连接 Wi-Fi 或开启热点后重新开始推流",
                )
            )
            return
        }
        _state.update { it.copy(networkLabel = network.label, lanIp = network.lanIp) }

        // 麦克风权限缺失：1002 提示后静音继续，不阻断。
        val micGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!micGranted) {
            _state.update {
                it.copy(muted = true, warning = "未授予麦克风权限，将静音推流")
            }
        }
        // Android 13+ 通知权限缺失：推流不受影响，但通知可能不可见。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            _state.update {
                it.copy(
                    warning = it.warning
                        ?: "通知权限未授予，前台服务通知可能不可见（不影响推流）",
                )
            }
        }

        // 省电模式强制后置单摄（docs/02 §12.3），同步 UI 状态避免“前置”误导。
        if (_state.value.settings.powerSave && _state.value.cameraFacing == CameraFacingUi.FRONT) {
            _state.update {
                it.copy(
                    cameraFacing = CameraFacingUi.BACK,
                    cameraMode = CameraModeUi.SINGLE_BACK,
                    torchOn = false,
                )
            }
        }

        val currentAfterUpdate = _state.value
        val options = StreamStartOptions(
            settings = currentAfterUpdate.settings,
            facing = currentAfterUpdate.cameraFacing,
            pinRequired = currentAfterUpdate.pinRequired,
            pin = currentAfterUpdate.pin,
            port = currentAfterUpdate.port,
            muted = currentAfterUpdate.muted,
        )
        try {
            LanStreamingService.start(appContext, options)
        } catch (t: Throwable) {
            // 前台服务启动被拒 -> 1007。
            showError(
                P1Error(
                    1007,
                    "无法启动后台推流服务",
                    "请检查系统是否限制后台运行/通知后重试",
                )
            )
        }
    }

    override fun stop() {
        _state.update {
            it.copy(
                starting = false,
                streaming = false,
                playUrl = null,
                viewers = 0,
                bitrateKbps = 0,
                fps = 0,
            )
        }
        // 实际停止（编码/相机 -> LanServer.stop ≤1s）由 Service 在后台线程完成。
        LanStreamingService.stop(appContext)
    }

    override fun switchCamera() {
        val currentFacing = _state.value.cameraFacing
        val next = if (currentFacing == CameraFacingUi.BACK) CameraFacingUi.FRONT else CameraFacingUi.BACK
        _state.update {
            it.copy(
                cameraFacing = next,
                cameraMode = if (next == CameraFacingUi.BACK) CameraModeUi.SINGLE_BACK
                else CameraModeUi.SINGLE_FRONT,
                torchOn = if (next == CameraFacingUi.FRONT) false else it.torchOn,
            )
        }
        prefs.edit().putString(KEY_FACING, next.name).apply()
        if (hasPermission(Manifest.permission.CAMERA)) {
            CameraHost.get(appContext).switchCamera(next)
        }
    }

    override fun setTorch(on: Boolean) {
        if (_state.value.cameraFacing == CameraFacingUi.FRONT) {
            _state.update { it.copy(warning = "前置摄像头不支持闪光灯，已切换后置后再试") }
            return
        }
        _state.update { it.copy(torchOn = on) }
        if (hasPermission(Manifest.permission.CAMERA)) {
            CameraHost.get(appContext).setTorch(on)
        }
    }

    override fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
        StreamingBridge.engine?.setMuted(muted)
    }

    override fun selectAccessScope(scope: AccessScopeUi) {
        if (scope == AccessScopeUi.WAN) {
            // P1 仅局域网；WAN 阶段二开放，保持 LAN 并提示。
            _state.update {
                it.copy(
                    accessScope = AccessScopeUi.LAN,
                    wanLocked = true,
                    warning = "公网可访问将在阶段二开放，当前保持局域网模式",
                )
            }
        } else {
            _state.update { it.copy(accessScope = AccessScopeUi.LAN, wanLocked = true) }
        }
    }

    override fun updateSettings(settings: StreamSettingsUi) {
        val sanitized = settings.copy(
            width = settings.width.coerceIn(320, 1920) and 1.inv(),
            height = settings.height.coerceIn(240, 1080) and 1.inv(),
            fps = settings.fps.coerceIn(15, 30),
            bitrateKbps = settings.bitrateKbps.coerceIn(800, 4000),
        )
        _state.update { it.copy(settings = sanitized) }
        persistSettings(sanitized)
        if (_state.value.streaming || _state.value.starting) {
            _state.update { it.copy(warning = "设置已保存，将在下次开始推流后生效") }
        }
    }

    override fun setPin(required: Boolean, pin: String?) {
        val normalized = pin?.trim()?.takeIf { it.isNotEmpty() }
        if (required && (normalized == null || !PIN_REGEX.matches(normalized))) {
            _state.update { it.copy(warning = "观看口令需为 4–6 位数字") }
            return
        }
        val storedPin = normalized
        _state.update {
            it.copy(pinRequired = required, pin = storedPin)
        }
        prefs.edit()
            .putBoolean(KEY_PIN_REQUIRED, required)
            .putString(KEY_PIN, storedPin)
            .apply()
        StreamingBridge.engine?.updatePin(required, storedPin)
    }

    override fun setPort(port: Int) {
        if (port !in PORT_RANGE) {
            _state.update { it.copy(warning = "端口需在 ${PORT_RANGE.first}–${PORT_RANGE.last} 之间") }
            return
        }
        _state.update { it.copy(port = port) }
        prefs.edit().putInt(KEY_PORT, port).apply()
        if (_state.value.streaming || _state.value.starting) {
            _state.update { it.copy(warning = "端口修改将在下次开始推流后生效") }
        }
    }

    override fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    override fun dismissWarning() {
        _state.update { it.copy(warning = null) }
    }

    override fun bindPreview(previewView: PreviewView) {
        CameraHost.get(appContext).bindPreview(previewView)
    }

    override fun unbindPreview() {
        CameraHost.current()?.unbindPreview()
    }

    override fun pausePreview() {
        CameraHost.current()?.pausePreview()
    }

    override fun resumePreview() {
        CameraHost.current()?.resumePreview()
    }

    // ---------------------------------------------------------------- 内部

    private fun observeBridge() {
        scope.launch {
            StreamingBridge.snapshot.collect { snapshot -> applySnapshot(snapshot) }
        }
        scope.launch {
            StreamingBridge.errors.collect { error ->
                _state.update {
                    it.copy(
                        error = error,
                        starting = false,
                        streaming = false,
                        playUrl = null,
                        viewers = 0,
                        bitrateKbps = 0,
                        fps = 0,
                    )
                }
                // 引擎失败后清理前台服务通知；LanServer 已由引擎自行关闭。
                LanStreamingService.stop(appContext)
            }
        }
        scope.launch {
            StreamingBridge.warnings.collect { warning ->
                _state.update { it.copy(warning = warning) }
            }
        }
    }

    private fun applySnapshot(snapshot: EngineSnapshot) {
        if (snapshot.phase == EnginePhase.STREAMING) {
            if (snapshot.fps in 1..4 && snapshot.bitrateKbps < 300) {
                lowFpsSamples++
            } else {
                lowFpsSamples = 0
            }
            val currentSettings = _state.value.settings
            if (lowFpsSamples >= 5 && !autoFallbackDone && currentSettings.height > 720 &&
                _state.value.cameraAvailable
            ) {
                autoFallbackDone = true
                lowFpsSamples = 0
                val reduced = currentSettings.copy(
                    width = 1280,
                    height = 720,
                    bitrateKbps = minOf(currentSettings.bitrateKbps, 2500),
                )
                _state.update {
                    it.copy(
                        settings = reduced,
                        warning = "检测到编码帧率过低，已自动降至 720p 保证流畅；可在设置中改回 1080p",
                    )
                }
                persistSettings(reduced)
                stop()
                scope.launch {
                    delay(1_200)
                    start()
                }
                return
            }
        }
        _state.update { current ->
            when (snapshot.phase) {
                EnginePhase.STARTING -> current.copy(
                    starting = true,
                    streaming = false,
                    playUrl = null,
                    lanIp = snapshot.lanIp ?: current.lanIp,
                    port = snapshot.port,
                    actualWidth = snapshot.actualWidth.takeIf { it > 0 } ?: current.actualWidth,
                    actualHeight = snapshot.actualHeight.takeIf { it > 0 } ?: current.actualHeight,
                    networkLabel = snapshot.networkLabel,
                )
                EnginePhase.STREAMING -> current.copy(
                    starting = false,
                    streaming = true,
                    playUrl = snapshot.playUrl ?: current.playUrl,
                    lanIp = snapshot.lanIp ?: current.lanIp,
                    port = snapshot.port,
                    actualWidth = snapshot.actualWidth.takeIf { it > 0 } ?: current.actualWidth,
                    actualHeight = snapshot.actualHeight.takeIf { it > 0 } ?: current.actualHeight,
                    viewers = snapshot.viewers,
                    maxViewers = snapshot.maxViewers,
                    bitrateKbps = snapshot.bitrateKbps,
                    fps = snapshot.fps,
                    cameraAvailable = snapshot.cameraAvailable,
                    networkLabel = snapshot.networkLabel,
                )
                EnginePhase.STOPPING,
                EnginePhase.FAILED,
                EnginePhase.IDLE,
                -> current.copy(
                    starting = false,
                    streaming = false,
                    playUrl = null,
                    viewers = 0,
                    bitrateKbps = 0,
                    fps = 0,
                    cameraAvailable = true,
                )
            }
        }
    }

    /** 空闲时每 5 s 刷新一次网络标签（推流中由引擎快照提供）。 */
    private fun observeNetworkLabel() {
        scope.launch {
            while (isActive) {
                delay(NETWORK_LABEL_REFRESH_MS)
                if (_state.value.streaming) continue
                val network = runCatching { networkMonitor.current() }.getOrNull() ?: continue
                _state.update { it.copy(networkLabel = network.label) }
            }
        }
    }

    private fun showError(error: P1Error) {
        _state.update {
            it.copy(error = error, starting = false, streaming = false)
        }
    }

    private fun restoreState(): P1UiState {
        val settings = StreamSettingsUi(
            width = prefs.getInt(KEY_WIDTH, 1280).takeIf { it > 0 } ?: 1280,
            height = prefs.getInt(KEY_HEIGHT, 720).takeIf { it > 0 } ?: 720,
            fps = prefs.getInt(KEY_FPS, 30).takeIf { it in 15..30 } ?: 30,
            bitrateKbps = prefs.getInt(KEY_BITRATE, 2500).takeIf { it > 0 } ?: 2500,
            audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, true),
            powerSave = prefs.getBoolean(KEY_POWER_SAVE, false),
            adaptiveBitrate = prefs.getBoolean(KEY_ADAPTIVE_BITRATE, true),
        )
        val facing = runCatching {
            CameraFacingUi.valueOf(prefs.getString(KEY_FACING, CameraFacingUi.BACK.name) ?: "BACK")
        }.getOrDefault(CameraFacingUi.BACK)
        val network = runCatching { networkMonitor.current() }.getOrNull()
        return P1UiState(
            accessScope = AccessScopeUi.LAN,
            wanLocked = true,
            settings = settings,
            cameraFacing = facing,
            cameraMode = if (facing == CameraFacingUi.FRONT) CameraModeUi.SINGLE_FRONT
            else CameraModeUi.SINGLE_BACK,
            port = prefs.getInt(KEY_PORT, 8089).takeIf { it in PORT_RANGE } ?: 8089,
            pinRequired = prefs.getBoolean(KEY_PIN_REQUIRED, false),
            pin = prefs.getString(KEY_PIN, null),
            muted = prefs.getBoolean(KEY_MUTED, false),
            networkLabel = network?.label ?: "未知",
            lanIp = network?.lanIp,
        )
    }

    private fun persistSettings(settings: StreamSettingsUi) {
        prefs.edit()
            .putInt(KEY_WIDTH, settings.width)
            .putInt(KEY_HEIGHT, settings.height)
            .putInt(KEY_FPS, settings.fps)
            .putInt(KEY_BITRATE, settings.bitrateKbps)
            .putBoolean(KEY_AUDIO_ENABLED, settings.audioEnabled)
            .putBoolean(KEY_POWER_SAVE, settings.powerSave)
            .putBoolean(KEY_ADAPTIVE_BITRATE, settings.adaptiveBitrate)
            .apply()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS_NAME = "campush_p1_prefs"
        const val KEY_WIDTH = "settings_width"
        const val KEY_HEIGHT = "settings_height"
        const val KEY_FPS = "settings_fps"
        const val KEY_BITRATE = "settings_bitrate_kbps"
        const val KEY_AUDIO_ENABLED = "settings_audio_enabled"
        const val KEY_POWER_SAVE = "settings_power_save"
        const val KEY_ADAPTIVE_BITRATE = "settings_adaptive_bitrate"
        const val KEY_FACING = "camera_facing"
        const val KEY_MUTED = "muted"
        const val KEY_PORT = "lan_port"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_PIN = "pin_value"

        val PORT_RANGE = 8089..8099
        val PIN_REGEX = Regex("^\\d{4,6}$")
        const val NETWORK_LABEL_REFRESH_MS = 5_000L
    }
}
