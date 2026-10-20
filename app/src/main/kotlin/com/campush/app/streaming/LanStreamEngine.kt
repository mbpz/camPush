package com.campush.app.streaming

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import androidx.lifecycle.LifecycleOwner
import com.campush.app.core.MediaPipeline
import com.campush.app.core.MediaPipelineCallbacks
import com.campush.app.core.MediaPipelineFactory
import com.campush.app.core.MediaPipelineKind
import com.campush.app.core.MediaPipelineSpec
import com.campush.app.diag.DiagLog
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
    private val pipelineFactory: MediaPipelineFactory,
) {
    /** 视频/音频共用时间原点（System.nanoTime），避免 A/V 时间戳不同源。 */
    private var originNs: Long = 0L

    private var lastActualWidth: Int = 0
    private var lastActualHeight: Int = 0
    private var publisherRef: LanMediaPublisher? = null
    private var streamMuted: Boolean = false
    private var pipelineSettings: StreamSettingsUi? = null
    private var pipelineFacing: CameraFacingUi = CameraFacingUi.BACK
    private var pipelineJob: Job? = null
    private var mediaPipeline: MediaPipeline? = null
    private var surfaceDisabled: Boolean = false
    private var streamPreviewView: SurfaceView? = null
    private var lowFpsStreak: Int = 0
    private var lowFpsWarned: Boolean = false
    private var cameraAvailable: Boolean = true


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val networkMonitor = NetworkMonitor(context.applicationContext)
    private val cameraLifecycleOwner: LifecycleOwner = lifecycleOwner

    @Volatile
    private var phase: EnginePhase = EnginePhase.IDLE

    @Volatile
    private var server: LanServer? = null

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
            logProvider = {
                DiagLog.snapshot(
                    "phase=$phase surface=${mediaPipeline?.kind == MediaPipelineKind.SURFACE} " +
                        "facing=$pipelineFacing settings=${pipelineSettings ?: options.settings} " +
                        "actual=${lastActualWidth}x${lastActualHeight} " +
                        "serverFps=${serverFpsForLog()} bitrate=${serverBitrateForLog()} " +
                        "streamingOwner=${cameraLifecycleOwner != null}",
                )
            },
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
        surfaceDisabled = false

        // 省电模式：强制后置单摄 + 854×480 + 24 fps + 码率上限 1 Mbps（docs/02 §12.3）。
        val baseSettings = options.settings
        val activeSettings = if (baseSettings.powerSave) {
            baseSettings.copy(
                width = 854,
                height = 480,
                fps = 24,
                bitrateKbps = minOf(baseSettings.bitrateKbps, 1000),
            )
        } else {
            baseSettings
        }
        val activeFacing = if (baseSettings.powerSave) CameraFacingUi.BACK else options.facing

        publisherRef = publisher
        streamMuted = options.muted
        pipelineSettings = activeSettings
        pipelineFacing = activeFacing
        val micDenied = startMediaPipeline(activeSettings, activeFacing, publisher)

        phase = EnginePhase.STREAMING
        DiagLog.i("Engine", "streaming started: surface=${mediaPipeline?.kind == MediaPipelineKind.SURFACE} settings=${pipelineSettings} facing=$pipelineFacing")
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
        if (baseSettings.powerSave) {
            StreamingBridge.emitWarning("省电模式已开启：后置单摄 · 854×480 · 24 fps · ≤1 Mbps")
        }
        startStatsLoop()
    }

    /** 摄像头被系统回收/被其他应用占用时的恢复状态回调。 */
    private fun onCameraAvailability(available: Boolean) {
        DiagLog.i("Engine", "camera availability=$available phase=$phase")
        val wasUnavailable = !cameraAvailable
        cameraAvailable = available
        if (phase != EnginePhase.STREAMING) return
        StreamingBridge.update { it.copy(cameraAvailable = available) }
        if (available) {
            if (!wasUnavailable) return
            mediaPipeline?.requestKeyFrame()
            StreamingBridge.emitWarning("摄像头已恢复，画面继续")
            Log.i(TAG, "camera recovered")
        } else {
            StreamingBridge.emitWarning("摄像头被其他应用占用，端侧服务保持运行，正在等待摄像头恢复…")
            Log.w(TAG, "camera unavailable, keep LAN server alive and retry")
        }
    }

    fun stop() {
        DiagLog.i("Engine", "stop requested phase=$phase")
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
            it.copy(
                phase = EnginePhase.IDLE,
                playUrl = null,
                viewers = 0,
                bitrateKbps = 0,
                fps = 0,
                cameraAvailable = true,
            )
        }
    }

    fun setMuted(muted: Boolean) {
        audioEncoder?.setMuted(muted)
    }

    fun requestKeyFrame() {
        mediaPipeline?.requestKeyFrame()
    }

    fun updatePin(required: Boolean, pin: String?) {
        runCatching { server?.updatePin(required, pin) }
            .onFailure { Log.w(TAG, "updatePin failed", it) }
    }

    /** 供 Controller 每秒刷新 UI 使用；未启动时返回 null。 */
    fun status(): LanStatus? = runCatching { server?.status() }.getOrNull()

    /**
     * 启动/重启媒体子管线（相机+编码），不触碰 LanServer。
     * 用于画质自适应降级：保持播放地址不变。
     */
    private fun nowUs(): Long = (System.nanoTime() - originNs) / 1_000L

    private fun startAudio(settings: StreamSettingsUi, publisher: LanMediaPublisher): Boolean {
        if (!settings.audioEnabled) return false
        val audio = AudioEncoder(
            context.applicationContext,
            publisher,
            streamMuted,
            basePtsUs = nowUs(),
        )
        val denied = !audio.start()
        audioEncoder = audio
        return denied
    }

    private fun startMediaPipeline(
        settings: StreamSettingsUi,
        facing: CameraFacingUi,
        publisher: LanMediaPublisher,
    ): Boolean {
        val micDenied = startAudio(settings, publisher)
        pipelineSettings = settings
        pipelineFacing = facing
        val spec = MediaPipelineSpec(facing, settings, streamMuted)
        val surfaceWanted = !surfaceDisabled && pipelineFactory.isSurfaceSupported()
        val pipeline = if (surfaceWanted) {
            pipelineFactory.createSurface(publisher, pipelineCallbacks(), ::nowUs)
        } else {
            pipelineFactory.createLegacy(publisher, pipelineCallbacks(), ::nowUs)
        }
        if (pipeline == null) {
            startLegacyPipeline(settings, facing, publisher)
        } else {
            mediaPipeline = pipeline
            streamPreviewView?.let { runCatching { pipeline.bindPreview(it) } }
            pipeline.start(spec)
        }
        return micDenied
    }

    private fun pipelineCallbacks(): MediaPipelineCallbacks = object : MediaPipelineCallbacks {
        override fun onActualSize(width: Int, height: Int) {
            if (width != lastActualWidth || height != lastActualHeight) {
                lastActualWidth = width
                lastActualHeight = height
                StreamingBridge.update { it.copy(actualWidth = width, actualHeight = height) }
            }
        }

        override fun onStarted(kind: MediaPipelineKind) {
            StreamingBridge.update { it.copy(surfacePipeline = kind == MediaPipelineKind.SURFACE) }
            DiagLog.i("Engine", "pipeline started kind=$kind")
        }

        override fun onError(message: String) = onPipelineError(message)

        override fun onCameraAvailability(available: Boolean) =
            this@LanStreamEngine.onCameraAvailability(available)

        override fun onPreviewJpeg(bytes: ByteArray?) = StreamingBridge.publishPreviewJpeg(bytes)
    }

    private fun startLegacyPipeline(
        settings: StreamSettingsUi,
        facing: CameraFacingUi,
        publisher: LanMediaPublisher,
    ) {
        StreamingBridge.update { it.copy(surfacePipeline = false) }
        val spec = MediaPipelineSpec(facing, settings, streamMuted)
        val pipeline = pipelineFactory.createLegacy(publisher, pipelineCallbacks(), ::nowUs)
        mediaPipeline = pipeline
        streamPreviewView?.let { runCatching { pipeline.bindPreview(it) } }
        pipeline.start(spec)
    }

    /** Surface 管线初始化失败：回退 CameraX 兼容管线，播放地址/服务不变。 */
    private fun onPipelineError(message: String) {
        val current = mediaPipeline
        if (current != null && current.kind == MediaPipelineKind.SURFACE && !surfaceDisabled) {
            DiagLog.w("Engine", "surface pipeline failed: $message, fallback legacy")
            surfaceDisabled = true
            runCatching { current.stop() }
            mediaPipeline = null
            StreamingBridge.update { it.copy(surfacePipeline = false) }
            StreamingBridge.emitWarning("零拷贝编码不可用，已回退兼容模式：$message")
            val settings = pipelineSettings ?: return
            val publisher = publisherRef ?: return
            stopAudio()
            startAudio(settings, publisher)
            startLegacyPipeline(settings, pipelineFacing, publisher)
        } else {
            fail(1003, "相机/编码失败：$message", "请检查相机是否被其他应用占用后重试")
        }
    }

    private fun stopMediaPipeline() {
        mediaPipeline?.let { runCatching { it.stop() } }
        mediaPipeline = null
        stopAudio()
        StreamingBridge.update { it.copy(surfacePipeline = false) }
        StreamingBridge.publishPreviewJpeg(null)
    }

    private fun stopAudio() {
        audioEncoder?.let { runCatching { it.stop() } }
        audioEncoder = null
    }

    fun bindStreamPreview(view: SurfaceView) {
        streamPreviewView = view
        runCatching { mediaPipeline?.bindPreview(view) }
    }

    fun unbindStreamPreview() {
        streamPreviewView = null
        runCatching { mediaPipeline?.unbindPreview() }
    }

    fun switchCameraSurface(target: CameraFacingUi) {
        pipelineFacing = target
        runCatching { mediaPipeline?.switchCamera(target) }
    }

    fun setTorchSurface(on: Boolean) {
        runCatching { mediaPipeline?.setTorch(on) }
    }

    /** 重启媒体子管线（保持 LanServer/播放地址不变）。 */
    fun updateMediaConfig(settings: StreamSettingsUi, facing: CameraFacingUi) {
        if (phase != EnginePhase.STREAMING) return
        pipelineSettings = settings
        pipelineFacing = facing
        val pipeline = mediaPipeline ?: return
        pipelineJob?.cancel()
        pipelineJob = scope.launch {
            withContext(Dispatchers.Default) {
                pipeline.update(MediaPipelineSpec(facing, settings, streamMuted))
            }
            lastActualWidth = 0
            lastActualHeight = 0
            StreamingBridge.update { it.copy(actualWidth = 0, actualHeight = 0) }
            Log.i(TAG, "media pipeline updated to ${settings.width}x${settings.height}@${settings.fps}")
        }
    }

    private fun serverFpsForLog(): Int = runCatching { server?.status()?.fps ?: 0 }.getOrDefault(0)

    private fun serverBitrateForLog(): Long = runCatching { server?.status()?.bitrateBps ?: 0L }.getOrDefault(0L)

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val lanServer = server ?: break
                val status = withContext(Dispatchers.Default) {
                    runCatching { lanServer.status() }.getOrNull()
                } ?: continue
                if (cameraAvailable && status.fps in 1..4 && status.bitrateBps < 300_000L) {
                    lowFpsStreak++
                } else {
                    lowFpsStreak = 0
                }
                if (lowFpsStreak >= 5 && !lowFpsWarned && phase == EnginePhase.STREAMING && cameraAvailable) {
                    lowFpsWarned = true
                    Log.w(TAG, "low fps detected: fps=${status.fps} bitrate=${status.bitrateBps}")
                    DiagLog.w("Engine", "low fps: fps=${status.fps} bitrate=${status.bitrateBps} actual=${lastActualWidth}x$lastActualHeight")
                    val diag = mediaPipeline?.diagnostics()
                    val codecInfo = diag?.takeIf { it.codecName.isNotBlank() }?.let {
                        "（编码器 ${it.codecName.let { n -> if (it.hardware) "$n/硬编" else "$n/软编" }}，" +
                            "in=${it.inFps}fps out=${it.outFps}fps ${it.width}x${it.height}）"
                    }.orEmpty()
                    StreamingBridge.emitWarning(
                        "编码帧率过低（<5 fps）：正在尝试自动调整分辨率$codecInfo；" +
                            "若仍卡顿请切换 720p 或检查相机占用"
                    )
                }
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
                    mediaPipeline?.requestKeyFrame()
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
                it.copy(
                phase = EnginePhase.IDLE,
                playUrl = null,
                viewers = 0,
                bitrateKbps = 0,
                fps = 0,
                cameraAvailable = true,
            )
            }
        }
    }

    private fun fail(code: Int, message: String? = null, hint: String? = null) {
        DiagLog.e("Engine", "fail code=$code message=$message hint=$hint phase=$phase")
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
        StreamingBridge.publishPreviewJpeg(null)
        pipelineJob?.cancel()
        pipelineJob = null
        stopMediaPipeline()
        publisherRef = null
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
