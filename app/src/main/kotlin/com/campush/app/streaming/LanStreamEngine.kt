package com.campush.app.streaming

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.campush.app.camera.CameraHost
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.StreamSettingsUi
import com.campush.app.network.NetworkMonitor
import com.campush.lan.LanConfig
import com.campush.lan.LanEvent
import com.campush.lan.LanEventData
import com.campush.lan.LanEventListener
import com.campush.lan.LanException
import com.campush.lan.LanMediaPublisher
import com.campush.lan.LanRoomState
import com.campush.lan.LanServer
import com.campush.lan.LanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 启动推流所需的全部参数（由 Controller 通过 Intent 传给前台服务）。 */
data class StreamStartOptions(
    val settings: StreamSettingsUi,
    val facing: CameraFacingUi,
    val pinRequired: Boolean,
    val pin: String?,
    val port: Int,
    val muted: Boolean,
)

/**
 * LAN 推流引擎：封装 LanServer 生命周期、CameraX 采集、视频/JPEG/AAC 编码与状态轮询。
 *
 * 启动顺序（docs/02 §15.8）：权限（由 Controller 前置检查）→ 网络检查 → LanServer.start
 * → 相机/编码启动 → publish。停止顺序：编码/相机 → server.stop（≤1s）→ 状态清理。
 */
class LanStreamEngine(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
) {
    /** 视频/音频共用时间原点（System.nanoTime），避免 A/V 时间戳不同源。 */
    private var originNs: Long = 0L


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val networkMonitor = NetworkMonitor(context.applicationContext)
    private val cameraLifecycleOwner: LifecycleOwner = lifecycleOwner

    @Volatile
    private var phase: EnginePhase = EnginePhase.IDLE

    @Volatile
    private var server: LanServer? = null

    @Volatile
    private var videoEncoder: VideoEncoder? = null

    @Volatile
    private var jpegEncoder: JpegEncoder? = null

    @Volatile
    private var audioEncoder: AudioEncoder? = null

    private var statsJob: Job? = null

    @Volatile
    private var lastKnownIp: String? = null

    fun isActive(): Boolean = phase == EnginePhase.STREAMING || phase == EnginePhase.STARTING

    fun start(options: StreamStartOptions) {
        if (phase == EnginePhase.STARTING || phase == EnginePhase.STREAMING) return
        phase = EnginePhase.STARTING
        StreamingBridge.engine = this

        val network = runCatching { networkMonitor.current() }.getOrDefault(
            NetworkMonitor.NetworkSnapshot("未知", null, false)
        )
        StreamingBridge.update {
            it.copy(
                phase = EnginePhase.STARTING,
                networkLabel = network.label,
                lanIp = network.lanIp,
                port = options.port,
                viewers = 0,
                bitrateKbps = 0,
                fps = 0,
            )
        }

        // 1) 网络检查：非 Wi-Fi/热点直接 6003。
        if (!network.isLan) {
            fail(6003)
            return
        }
        if (network.lanIp.isNullOrBlank()) {
            fail(6003, "未能获取局域网 IP 地址")
            return
        }
        lastKnownIp = network.lanIp
        runCatching { networkMonitor.start { snapshot -> onNetworkChanged(snapshot) } }

        // 2) LanServer 启动（绑定端口 + 生成播放地址）。
        val config = LanConfig(
            port = options.port,
            maxViewers = MAX_VIEWERS,
            pinRequired = options.pinRequired,
            pin = options.pin,
            bindAddress = "0.0.0.0",
        )
        val lanServer = LanServer(
            config = config,
            ipProvider = { networkMonitor.currentLanIp() },
            listener = object : LanEventListener {
                override fun onEvent(event: LanEventData) = onLanEvent(event)
            },
        )
        val playUrl: String = try {
            lanServer.start()
        } catch (e: LanException) {
            Log.w(TAG, "LanServer.start failed: ${e.code}", e)
            fail(e.code, e.message, e.userHint)
            return
        } catch (t: Throwable) {
            Log.e(TAG, "LanServer.start crashed", t)
            fail(6001)
            return
        }
        server = lanServer

        val publisher: LanMediaPublisher = try {
            lanServer.publisher
        } catch (t: Throwable) {
            Log.e(TAG, "LanMediaPublisher unavailable", t)
            fail(6001)
            return
        }

        originNs = System.nanoTime()

        // 3) 编码器：仅在拿到 publisher 后创建。
        val video = VideoEncoder(publisher, options.settings) { error ->
            fail(error.code, error.message, error.hint)
        }
        videoEncoder = video
        val jpeg = JpegEncoder(publisher)
        jpegEncoder = jpeg

        var micDenied = false
        if (options.settings.audioEnabled) {
            val audio = AudioEncoder(
                context.applicationContext,
                publisher,
                options.muted,
                basePtsUs = (System.nanoTime() - originNs) / 1_000L,
            )
            micDenied = !audio.start()
            audioEncoder = audio
        }

        // 4) 相机：绑定到 Service 生命周期；分析帧在单线程 executor 上编码。
        val camera = CameraHost.get(context)
        camera.startStreaming(
            lifecycleOwner = cameraLifecycleOwner,
            facing = options.facing,
            width = options.settings.width,
            height = options.settings.height,
            fps = options.settings.fps,
            frameCallback = { image, _ ->
                val nowUs = (System.nanoTime() - originNs) / 1_000L
                video.encode(image, nowUs)
                jpeg.onFrame(image)
            },
            errorCallback = { error -> fail(error.code, error.message, error.hint) },
        )

        // 5) 启动即强制 IDR（首帧预算）。
        video.requestKeyFrame()

        phase = EnginePhase.STREAMING
        StreamingBridge.update {
            it.copy(
                phase = EnginePhase.STREAMING,
                playUrl = playUrl,
                lanIp = network.lanIp,
                port = options.port,
                maxViewers = MAX_VIEWERS,
            )
        }
        if (micDenied) {
            StreamingBridge.emitWarning("未授予麦克风权限，将静音推流")
        }
        startStatsLoop()
    }

    fun stop() {
        if (phase == EnginePhase.IDLE) {
            StreamingBridge.engine = null
            return
        }
        phase = EnginePhase.STOPPING
        StreamingBridge.update { it.copy(phase = EnginePhase.STOPPING) }
        teardownResources()
        phase = EnginePhase.IDLE
        StreamingBridge.engine = null
        StreamingBridge.update {
            it.copy(phase = EnginePhase.IDLE, playUrl = null, viewers = 0, bitrateKbps = 0, fps = 0)
        }
    }

    fun setMuted(muted: Boolean) {
        audioEncoder?.setMuted(muted)
    }

    fun requestKeyFrame() {
        videoEncoder?.requestKeyFrame()
    }

    fun updatePin(required: Boolean, pin: String?) {
        runCatching { server?.updatePin(required, pin) }
            .onFailure { Log.w(TAG, "updatePin failed", it) }
    }

    /** 供 Controller 每秒刷新 UI 使用；未启动时返回 null。 */
    fun status(): LanStatus? = runCatching { server?.status() }.getOrNull()

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val lanServer = server ?: break
                val status = withContext(Dispatchers.Default) {
                    runCatching { lanServer.status() }.getOrNull()
                } ?: continue
                StreamingBridge.update { snapshot ->
                    snapshot.copy(
                        viewers = status.viewers,
                        maxViewers = status.maxViewers.coerceAtLeast(1),
                        bitrateKbps = (status.bitrateBps / 1000L).toInt(),
                        fps = status.fps,
                        lanIp = status.lanIp.ifBlank { snapshot.lanIp ?: networkMonitor.currentLanIp() },
                        port = status.port,
                    )
                }
                if (status.state == LanRoomState.FAILED || status.state == LanRoomState.CLOSED) {
                    fail(6006, "局域网服务已停止")
                    break
                }
            }
        }
    }

    private fun onLanEvent(event: LanEventData) {
        scope.launch {
            when (event.event) {
                LanEvent.VIEWER_JOINED -> {
                    // 新观看者从关键帧起播，立即请求 IDR。
                    videoEncoder?.requestKeyFrame()
                    StreamingBridge.update { it.copy(viewers = event.viewers) }
                }
                LanEvent.VIEWER_LEFT -> StreamingBridge.update { it.copy(viewers = event.viewers) }
                LanEvent.VIEWER_LIMITED -> StreamingBridge.emitWarning(
                    "局域网观看人数已达上限（${MAX_VIEWERS}），新的观看请求被拒绝"
                )
                LanEvent.PIN_LOCKED -> StreamingBridge.emitWarning("口令错误次数过多，已临时锁定，请稍后重试")
                LanEvent.AP_ISOLATION_SUSPECTED -> StreamingBridge.emitWarning(NetworkMonitor.AP_ISOLATION_HINT)
                LanEvent.ERROR -> fail(event.code ?: 6006, event.message)
                LanEvent.SERVER_STARTED, LanEvent.SERVER_STOPPED -> Unit
            }
        }
    }

    private fun onNetworkChanged(snapshot: NetworkMonitor.NetworkSnapshot) {
        scope.launch {
            StreamingBridge.update {
                it.copy(networkLabel = snapshot.label, lanIp = snapshot.lanIp ?: it.lanIp)
            }
            if (phase != EnginePhase.STREAMING) return@launch
            if (!snapshot.isLan) {
                StreamingBridge.emitWarning("已离开局域网，推流已停止")
                stopInternal()
                return@launch
            }
            val currentIp = snapshot.lanIp
            val previousIp = lastKnownIp
            if (!currentIp.isNullOrBlank() && !previousIp.isNullOrBlank() && currentIp != previousIp) {
                // 地址变化后旧播放地址必然失效，按 docs/02 §10 停止并提示重新开始。
                StreamingBridge.emitWarning("网络地址已变化，请重新开始")
                stopInternal()
            }
        }
    }

    private fun stopInternal() {
        if (phase == EnginePhase.IDLE) return
        phase = EnginePhase.STOPPING
        StreamingBridge.update { it.copy(phase = EnginePhase.STOPPING) }
        scope.launch {
            // server.stop() 允许 ≤1s，放到 IO 线程避免阻塞主线程。
            withContext(Dispatchers.IO) { teardownResources() }
            phase = EnginePhase.IDLE
            StreamingBridge.engine = null
            StreamingBridge.update {
                it.copy(phase = EnginePhase.IDLE, playUrl = null, viewers = 0, bitrateKbps = 0, fps = 0)
            }
        }
    }

    private fun fail(code: Int, message: String? = null, hint: String? = null) {
        scope.launch {
            if (phase == EnginePhase.IDLE || phase == EnginePhase.FAILED) return@launch
            phase = EnginePhase.FAILED
            withContext(Dispatchers.IO) { teardownResources() }
            StreamingBridge.engine = null
            StreamingBridge.update { it.copy(phase = EnginePhase.FAILED, playUrl = null) }
            StreamingBridge.emitError(StreamErrors.map(code, message, hint))
        }
    }

    /** 释放顺序固定：编码/相机先停，再关 server（stop() ≤ 1s），最后清状态。所有释放都容错。 */
    private fun teardownResources() {
        statsJob?.cancel()
        statsJob = null
        runCatching { networkMonitor.stop() }
        audioEncoder?.let { runCatching { it.stop() } }
        audioEncoder = null
        videoEncoder?.let { runCatching { it.close() } }
        videoEncoder = null
        jpegEncoder?.let { runCatching { it.close() } }
        jpegEncoder = null
        runCatching { CameraHost.current()?.stopStreaming() }
        val lanServer = server
        server = null
        if (lanServer != null) {
            runCatching { lanServer.stop() }
                .onFailure { Log.w(TAG, "LanServer.stop failed", it) }
        }
        lastKnownIp = null
    }

    private companion object {
        const val TAG = "LanStreamEngine"
        const val MAX_VIEWERS = 3
        const val STATS_INTERVAL_MS = 1_000L
    }
}
