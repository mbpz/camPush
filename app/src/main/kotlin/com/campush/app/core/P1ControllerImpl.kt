package com.campush.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.campush.app.camera.CameraHost
import com.campush.app.diag.DiagLog
import com.campush.app.diag.Telemetry
import com.campush.lan.DiagnosticLogServer
import java.util.UUID
import com.campush.app.network.NetworkMonitor
import com.campush.app.service.LanStreamingService
import com.campush.app.streaming.EnginePhase
import com.campush.app.streaming.EngineSnapshot
import com.campush.app.streaming.StreamStartOptions
import com.campush.app.streaming.StreamingBridge
import com.campush.app.network.AccountClient
import com.campush.app.streaming.wan.WanRoomClient
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
import kotlinx.coroutines.withContext

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

    /** 帧率优先自适应：连续低帧率采样、降档级别（PRD §8.4 性能保护）。 */
    private var lowFpsSamples: Int = 0
    private var fpsWarningShown: Boolean = false
    private var fpsFallbackDone: Boolean = false

    private val _state = MutableStateFlow(restoreState())
    override val previewJpeg = StreamingBridge.previewJpeg
    private val wanClient = WanRoomClient(appContext)
    private val accountClient = AccountClient()
    private var accountToken: String = prefs.getString(KEY_ACCOUNT_TOKEN, "") ?: ""
    private val _wanTest = MutableStateFlow(WanTestUi())
    override val wanTest: StateFlow<WanTestUi> = _wanTest.asStateFlow()
    override val state: StateFlow<P1UiState> = _state.asStateFlow()

    private var logServer: DiagnosticLogServer? = null
    private var logServerIp: String? = null

    init {
        observeBridge()
        observeNetworkLabel()
        startLogServiceLoop()
        probeCameraCapabilities()
        wanClient.setAccountToken(accountToken)
        if (accountToken.isNotBlank()) refreshByo()
        scope.launch {
            wanClient.state.collect { st ->
                _wanTest.value = WanTestUi(st.running, st.status, st.roomId, st.playUrl, st.error)
                if (_state.value.accessScope == AccessScopeUi.WAN) {
                    _state.update { cur ->
                        cur.copy(
                            starting = cur.starting && !st.running && st.error == null && st.roomId == null,
                            streaming = st.running,
                            playUrl = st.playUrl ?: cur.playUrl,
                            error = st.error?.let { P1Error(7001, it) } ?: cur.error,
                        )
                    }
                }
            }
        }
    }

    /**
     * 独立诊断日志服务：应用打开即启动，与推流/LanServer 解耦；Wi-Fi IP 变化时自动重启。
     * 同一局域网电脑浏览器打开 `http://{ip}:18089/logs/{token}` 即可下载累计日志。
     */
    private fun startLogServiceLoop() {
        scope.launch {
            while (isActive) {
                val ip = runCatching { networkMonitor.currentLanIp() }.getOrNull()
                val current = logServer
                if (ip != null) {
                    if (current == null || !current.isRunning() || logServerIp != ip) {
                        runCatching { current?.stop() }
                        val server = DiagnosticLogServer(
                            ipProvider = { runCatching { networkMonitor.currentLanIp() }.getOrNull() },
                            logProvider = {
                                val st = _state.value
                                DiagLog.downloadText(
                                    "streaming=${st.streaming} surface=${st.surfacePipeline} " +
                                        "network=${st.networkLabel} settings=${st.settings}",
                                )
                            },
                            token = UUID.randomUUID().toString().replace("-", "").take(20),
                        )
                        runCatching { server.start() }
                            .onSuccess { url ->
                                logServer = server
                                logServerIp = ip
                                _state.update { it.copy(logServiceUrl = url, logServiceRunning = true) }
                            }
                            .onFailure {
                                _state.update { it.copy(logServiceRunning = false) }
                            }
                    }
                } else if (_state.value.logServiceRunning || _state.value.logServiceUrl != null) {
                    _state.update { it.copy(logServiceUrl = null, logServiceRunning = false) }
                }
                delay(10_000)
            }
        }
    }

    // ---------------------------------------------------------------- UI 操作

    override fun start() {
        val requestedAtMs = System.currentTimeMillis()
        val current = _state.value
        if (current.starting || current.streaming) return
        _state.update { it.copy(starting = true, error = null, warning = null) }

        // 相机权限缺失：1001，阻止推流。
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Telemetry.event("permission_denied", mapOf("permission" to "camera"))
            showError(
                P1Error(
                    1001,
                    "未授予摄像头权限，无法预览与推流",
                    "请在系统设置中允许相机权限后重试",
                )
            )
            return
        }

        if (current.accessScope == AccessScopeUi.WAN) {
            val api = _state.value.cloudApiHost.trim()
            if (api.isEmpty()) {
                _state.update { it.copy(starting = false) }
                showError(P1Error(2009, "请先配置 Cloud API", "设置 → WAN 房间联调填写 Mac 局域网 IP"))
                return
            }
            Telemetry.event("push_start_clicked", mapOf("accessScope" to "wan"))
            _state.update { it.copy(streaming = false, playUrl = null) }
            pausePreview()
            wanClient.start(api)
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
        // 1080p 低于 8 Mbps 会明显发糊：启动时自动抬高到 8 Mbps（用户可在设置中调更高）。
        val rawSettings = _state.value.settings
        if (rawSettings.height >= 1080 && rawSettings.bitrateKbps < 12000) {
            val sharper = rawSettings.copy(bitrateKbps = 12000)
            _state.update { it.copy(settings = sharper) }
            persistSettings(sharper)
        }

        // 麦克风权限缺失：1002 提示后静音继续，不阻断。
        val micGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!micGranted) {
            Telemetry.event("permission_denied", mapOf("permission" to "microphone"))
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
            requestedAtMs = requestedAtMs,
            rtmpUrl = currentAfterUpdate.settings.rtmpUrl,
        )
        Telemetry.event(
            "push_start_clicked",
            mapOf(
                "accessScope" to "lan",
                "resolution" to "${currentAfterUpdate.settings.width}x${currentAfterUpdate.settings.height}",
                "fps" to currentAfterUpdate.settings.fps,
                "bitrateKbps" to currentAfterUpdate.settings.bitrateKbps,
                "pinRequired" to currentAfterUpdate.pinRequired,
                "cameraFacing" to currentAfterUpdate.cameraFacing.name.lowercase(),
            ),
        )
        try {
            LanStreamingService.start(appContext, options)
        } catch (t: Throwable) {
            Telemetry.event("push_failed", mapOf("code" to 1007))
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
        if (_state.value.accessScope == AccessScopeUi.WAN) {
            Telemetry.event("push_stop", mapOf("reason" to "wan"))
            wanClient.stop()
            resumePreview()
            _state.update {
                it.copy(starting = false, streaming = false, playUrl = null, viewers = 0, bitrateKbps = 0, fps = 0)
            }
            return
        }
        Telemetry.event("push_stop", mapOf("reason" to "user"))
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
            if (_state.value.streaming) StreamingBridge.engine?.switchCameraSurface(next)
            else CameraHost.get(appContext).switchCamera(next)
        }
    }

    override fun setTorch(on: Boolean) {
        if (_state.value.cameraFacing == CameraFacingUi.FRONT) {
            _state.update { it.copy(warning = "前置摄像头不支持闪光灯，已切换后置后再试") }
            return
        }
        _state.update { it.copy(torchOn = on) }
        if (hasPermission(Manifest.permission.CAMERA)) {
            if (_state.value.streaming) StreamingBridge.engine?.setTorchSurface(on)
            else CameraHost.get(appContext).setTorch(on)
        }
    }

    override fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
        StreamingBridge.engine?.setMuted(muted)
    }

    override fun selectAccessScope(scope: AccessScopeUi) {
        if (_state.value.streaming || _state.value.starting) {
            _state.update { it.copy(warning = "推流中不可切换访问范围，请先停止推流") }
            return
        }
        if (scope == AccessScopeUi.WAN && _state.value.cloudApiHost.isBlank()) {
            _state.update { it.copy(warning = "请先在设置中填写 WAN Cloud API") }
            return
        }
        _state.update {
            it.copy(
                accessScope = scope,
                wanLocked = scope == AccessScopeUi.WAN && it.cloudApiHost.isBlank(),
                warning = null,
                playUrl = null,
            )
        }
    }

    override fun setCloudApiHost(host: String) {
        val normalized = host.trim().trimEnd('/')
        _state.update {
            it.copy(
                cloudApiHost = normalized,
                wanLocked = normalized.isBlank(),
            )
        }
        prefs.edit().putString(KEY_CLOUD_API, normalized).apply()
    }

    override fun login(email: String, password: String, register: Boolean) {
        if (_state.value.accountBusy) return
        val api = _state.value.cloudApiHost.trim()
        if (api.isEmpty()) {
            _state.update { it.copy(warning = "请先填写 Cloud API 地址") }
            return
        }
        _state.update { it.copy(accountBusy = true, error = null) }
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    accountClient.login(api, email.trim(), password, register)
                }
                accountToken = session.token
                prefs.edit()
                    .putString(KEY_ACCOUNT_TOKEN, session.token)
                    .putString(KEY_ACCOUNT_EMAIL, session.email)
                    .putString(KEY_ACCOUNT_PLAN, session.plan)
                    .apply()
                wanClient.setAccountToken(session.token)
                _state.update {
                    it.copy(
                        accountBusy = false,
                        accountLoggedIn = true,
                        accountEmail = session.email,
                        accountPlan = session.plan,
                        warning = "账号已登录：${session.email}（${session.plan}）",
                    )
                }
                refreshByo()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        accountBusy = false,
                        error = P1Error(3010, "账号登录失败", t.message ?: "请检查 Cloud API/邮箱/密码"),
                    )
                }
            }
        }
    }

    override fun refreshByo() {
        val api = _state.value.cloudApiHost
        val token = accountToken
        if (api.isBlank() || token.isBlank()) return
        scope.launch {
            try {
                val byo = withContext(Dispatchers.IO) { accountClient.getByo(api, token) }
                _state.update {
                    it.copy(
                        byoWhipHost = byo.whipHost,
                        byoPlayHost = byo.playHost,
                        byoRtmpHost = byo.rtmpHost,
                        byoMode = byo.mode,
                    )
                }
            } catch (_: Throwable) {
                // BYO 拉取失败不阻塞账号/推流；状态在保存/测试时反馈。
            }
        }
    }

    override fun saveByo(whipHost: String, playHost: String, rtmpHost: String) {
        val api = _state.value.cloudApiHost
        val token = accountToken
        if (api.isBlank() || token.isBlank()) {
            _state.update { it.copy(byoStatus = "请先登录账号") }
            return
        }
        _state.update { it.copy(byoBusy = true, byoStatus = "保存中…") }
        scope.launch {
            try {
                val byo = withContext(Dispatchers.IO) { accountClient.saveByo(api, token, whipHost, playHost, rtmpHost) }
                _state.update {
                    it.copy(
                        byoBusy = false,
                        byoWhipHost = byo.whipHost,
                        byoPlayHost = byo.playHost,
                        byoRtmpHost = byo.rtmpHost,
                        byoMode = byo.mode,
                        byoStatus = "已保存（${byo.mode}）",
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(byoBusy = false, byoStatus = "保存失败：${t.message}") }
            }
        }
    }

    override fun testByo() {
        val hosts = listOf(
            "WHIP" to _state.value.byoWhipHost,
            "Play" to _state.value.byoPlayHost,
            "RTMP" to _state.value.byoRtmpHost,
        )
        if (hosts.all { it.second.isBlank() }) {
            _state.update { it.copy(byoStatus = "请先配置至少一个 BYO 地址") }
            return
        }
        _state.update { it.copy(byoBusy = true, byoStatus = "连通性测试中…") }
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                hosts.joinToString(" · ") { (name, url) ->
                    if (url.isBlank()) "$name=未配置"
                    else "$name=${if (accountClient.testReachable(url)) "OK" else "FAIL"}"
                }
            }
            _state.update { it.copy(byoBusy = false, byoStatus = results) }
        }
    }

    override fun openAudience() {
        val api = _state.value.cloudApiHost.trim().trimEnd('/')
        if (api.isEmpty()) {
            _state.update { it.copy(warning = "请先填写 Cloud API 地址") }
            return
        }
        runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, com.campush.app.ui.AudienceActivity::class.java)
                    .putExtra(com.campush.app.ui.AudienceActivity.EXTRA_URL, "$api/rooms")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun exportDiagnostics(): String = DiagLog.downloadTextRedacted()

    override fun logout() {
        accountToken = ""
        prefs.edit()
            .remove(KEY_ACCOUNT_TOKEN)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_PLAN)
            .apply()
        wanClient.setAccountToken(null)
        _state.update {
            it.copy(
                accountLoggedIn = false,
                accountEmail = null,
                accountPlan = null,
                accountBusy = false,
                byoWhipHost = "",
                byoPlayHost = "",
                byoRtmpHost = "",
                byoMode = "managed",
                byoStatus = null,
            )
        }
    }

    override fun updateSettings(settings: StreamSettingsUi) {
        val sanitized = settings.copy(
            width = settings.width.coerceIn(320, 1920) and 1.inv(),
            height = settings.height.coerceIn(240, 1080) and 1.inv(),
            fps = settings.fps.coerceIn(15, 30),
            bitrateKbps = settings.bitrateKbps.coerceIn(800, 12000),
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

    /** T0-A1：探测逻辑多摄 / 并发相机组合，供 D-3 支持率统计。 */
    private fun probeCameraCapabilities() {
        runCatching {
            val manager = appContext.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val ids = manager.cameraIdList
            var physical = 0
            var logicalDual = false
            ids.forEach { id ->
                val chars = manager.getCameraCharacteristics(id)
                val caps = chars.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (caps.contains(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                    logicalDual = true
                    physical = maxOf(physical, chars.physicalCameraIds.size)
                }
            }
            val concurrentPairs = if (android.os.Build.VERSION.SDK_INT >= 30) {
                runCatching { manager.concurrentCameraIds.size }.getOrDefault(0)
            } else 0
            _state.value = _state.value.copy(
                dualCameraSupported = logicalDual || concurrentPairs > 0,
                physicalCameraCount = physical,
                concurrentCameraPairs = concurrentPairs,
            )
            DiagLog.i("CameraProbe", "dual=${logicalDual || concurrentPairs > 0} physical=$physical concurrent=$concurrentPairs ids=${ids.size}")
        }.onFailure { DiagLog.w("CameraProbe", "probe failed: ${it.message}") }
    }

    override fun startWanRoom(apiBase: String) {
        // WAN WHIP 需要独占相机；先释放主界面预览，避免 Camera2 session 被抢。
        pausePreview()
        wanClient.start(apiBase)
    }

    override fun stopWanRoom() {
        wanClient.stop()
        resumePreview()
    }

    override fun openDualPipPreview() {
        runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, com.campush.app.camera.dual.DualPipActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onAddressCopied() {
        Telemetry.event("address_copied", mapOf("accessScope" to "lan"))
    }

    override fun onQrShown() {
        Telemetry.event("qr_shown", mapOf("accessScope" to "lan"))
    }

    override fun onShareClicked() {
        Telemetry.event("share_clicked", mapOf("accessScope" to "lan"))
    }

    override fun bindPreview(previewView: PreviewView) {
        CameraHost.get(appContext).bindPreview(previewView)
    }

    override fun unbindPreview() {
        CameraHost.current()?.unbindPreview()
    }

    override fun bindStreamPreview(view: android.view.SurfaceView) {
        StreamingBridge.pendingStreamPreview = view
        StreamingBridge.engine?.bindStreamPreview(view)
    }

    override fun unbindStreamPreview() {
        StreamingBridge.pendingStreamPreview = null
        StreamingBridge.engine?.unbindStreamPreview()
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
            val fps = snapshot.fps
            // 画质优先：不再自动降分辨率（避免“画质漂移”）；仅在持续低于 30fps 时提示。
            if (snapshot.cameraAvailable && fps in 1..4) {
                lowFpsSamples++
            } else {
                lowFpsSamples = 0
            }
            // 1080p 只有个位数帧率时先救可用性：自动切 720p，但只重启媒体子管线，播放地址不变。
            if (lowFpsSamples >= 3 && !fpsFallbackDone && _state.value.settings.height > 720 &&
                snapshot.cameraAvailable
            ) {
                fpsFallbackDone = true
                lowFpsSamples = 0
                val fallback = _state.value.settings.copy(
                    width = 1280,
                    height = 720,
                    bitrateKbps = minOf(_state.value.settings.bitrateKbps, 2500),
                )
                StreamingBridge.emitWarning("1080p 编码帧率过低，已自动切换到 720p（播放地址不变）；可在设置中改回")
                StreamingBridge.engine?.updateMediaConfig(fallback, _state.value.cameraFacing)
            } else if (snapshot.cameraAvailable && fps in 5..29) {
                if (!fpsWarningShown) {
                    lowFpsSamples++
                    if (lowFpsSamples >= 5) {
                        fpsWarningShown = true
                        StreamingBridge.emitWarning(
                            "当前约 ${snapshot.fps} fps，低于 30fps；最高画质受设备性能限制，可在设置中选 720p"
                        )
                    }
                }
            } else if (fps >= 30) {
                lowFpsSamples = 0
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
                    surfacePipeline = snapshot.surfacePipeline,
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
            rtmpUrl = prefs.getString(KEY_RTMP_URL, "") ?: "",
        )
        val facing = runCatching {
            CameraFacingUi.valueOf(prefs.getString(KEY_FACING, CameraFacingUi.BACK.name) ?: "BACK")
        }.getOrDefault(CameraFacingUi.BACK)
        val network = runCatching { networkMonitor.current() }.getOrNull()
        val cloudApi = prefs.getString(KEY_CLOUD_API, "") ?: ""
        return P1UiState(
            accessScope = AccessScopeUi.LAN,
            wanLocked = cloudApi.isBlank(),
            cloudApiHost = cloudApi,
            accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null),
            accountPlan = prefs.getString(KEY_ACCOUNT_PLAN, null),
            accountLoggedIn = accountToken.isNotBlank(),
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
            .putString(KEY_RTMP_URL, settings.rtmpUrl)
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
        const val KEY_RTMP_URL = "settings_rtmp_url"
        const val KEY_CLOUD_API = "settings_cloud_api"
        const val KEY_ACCOUNT_TOKEN = "account_token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_ACCOUNT_PLAN = "account_plan"
        const val KEY_FACING = "camera_facing"
        const val KEY_MUTED = "muted"
        const val KEY_PORT = "lan_port"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_PIN = "pin_value"

        val PORT_RANGE = 8089..8099
        val PIN_REGEX = Regex("^\\d{4,6}$")
        const val NETWORK_LABEL_REFRESH_MS = 5_000L
        const val ADAPT_COOLDOWN_MS = 8_000L
        const val STARTUP_GRACE_MS = 6_000L
    }
}
