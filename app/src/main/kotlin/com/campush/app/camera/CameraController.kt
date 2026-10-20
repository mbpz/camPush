package com.campush.app.camera

import android.content.Context
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.P1Error
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 采集：Preview + ImageAnalysis（YUV_420_888，KEEP_ONLY_LATEST）。
 *
 * - 推流中绑定到前台服务（LifecycleOwner = LanStreamingService），保证切后台不断流；
 * - 空闲时绑定到预览 View 所在的 Activity 生命周期，只开 Preview 供 UI 预览；
 * - 前后摄切换采用 unbind/rebind，编码管线与推流状态不受影响；
 * - 所有 CameraX 调用切到主线程执行，分析/编码回调跑在单线程 executor（docs/02 §4）。
 */
class CameraController internal constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var camera: Camera? = null

    private var previewView: PreviewView? = null
    private var previewOwner: LifecycleOwner? = null
    private var streamingOwner: LifecycleOwner? = null

    private var facing: CameraFacingUi = CameraFacingUi.BACK
    private var streaming: Boolean = false
    private var paused: Boolean = false
    private var torchOn: Boolean = false

    private var targetWidth: Int = 1280
    private var targetHeight: Int = 720
    private var targetFps: Int = 30

    private var onFrame: ((Image, Long) -> Unit)? = null
    private var onCameraError: ((P1Error) -> Unit)? = null
    private var onCameraState: ((Boolean) -> Unit)? = null

    /** 摄像头被其他应用占用时的自动恢复：每秒重试绑定，最多 30 次。 */
    private var recoveryScheduled: Boolean = false
    private var recoveryAttempts: Int = 0
    private var cameraLost: Boolean = false
    private var forceFpsRange: Boolean = true
    private var lastFrameAtMs: Long = 0L
    private val recoveryRunnable = Runnable { attemptRecovery() }
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            onFrameWatchdog()
            if (streaming) mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    fun isStreaming(): Boolean = streaming

    /** 绑定预览 View（由 app-ui 的 CameraPreview composable 调用）。 */
    fun bindPreview(view: PreviewView) {
        runOnMain {
            previewView = view
            view.post {
                runOnMain {
                    view.findViewTreeLifecycleOwner()?.let { previewOwner = it }
                    if (streaming) {
                        // 推流中旋转/重建 UI：只替换预览 surface，不打断采集与推流。
                        previewUseCase?.let { preview ->
                            if (paused) {
                                preview.setSurfaceProvider(null)
                            } else {
                                runCatching { preview.setSurfaceProvider(view.surfaceProvider) }
                            }
                        }
                    } else if (previewOwner != null) {
                        bindInternal(includeAnalysis = false)
                    }
                }
            }
        }
    }

    fun unbindPreview() {
        runOnMain {
            previewView = null
            if (streaming) {
                // 推流中 UI 销毁：仅摘掉预览 surface，采集/编码继续。
                runCatching { previewUseCase?.setSurfaceProvider(null) }
            } else {
                unbindAll()
                shutdownExecutor()
            }
        }
    }

    fun pausePreview() {
        runOnMain {
            paused = true
            // CameraX 1.3 的 UseCase 无 setEnabled；推流中仅解绑预览 surface，ImageAnalysis 继续采集。
            previewUseCase?.setSurfaceProvider(null)
        }
    }

    fun resumePreview() {
        runOnMain {
            paused = false
            val view = previewView
            val preview = previewUseCase
            if (view != null && preview != null) {
                runCatching { preview.setSurfaceProvider(view.surfaceProvider) }
            }
        }
    }

    /**
     * 开始推流采集：绑定到 [lifecycleOwner]（通常为 LanStreamingService），
     * 关闭仅预览模式并启用 Preview + ImageAnalysis。
     */
    fun startStreaming(
        lifecycleOwner: LifecycleOwner,
        facing: CameraFacingUi,
        width: Int,
        height: Int,
        fps: Int,
        frameCallback: (Image, Long) -> Unit,
        errorCallback: (P1Error) -> Unit,
        cameraStateCallback: ((Boolean) -> Unit)? = null,
    ) {
        runOnMain {
            this.streamingOwner = lifecycleOwner
            this.facing = facing
            this.targetWidth = width
            this.targetHeight = height
            this.targetFps = fps
            this.onFrame = frameCallback
            this.onCameraError = errorCallback
            this.onCameraState = cameraStateCallback
            this.recoveryAttempts = 0
            this.cameraLost = false
            this.forceFpsRange = true
            this.lastFrameAtMs = System.currentTimeMillis()
            this.streaming = true
            ensureExecutor()
            startWatchdog()
            bindInternal(includeAnalysis = true)
        }
    }

    /** 停止推流采集，回落到仅预览模式（若预览仍绑定）。 */
    fun stopStreaming() {
        runOnMain {
            if (!streaming) return@runOnMain
            streaming = false
            onFrame = null
            onCameraError = null
            onCameraState = null
            cancelRecovery()
            stopWatchdog()
            unbindAll()
            shutdownExecutor()
            if (previewView != null && previewOwner != null) {
                bindInternal(includeAnalysis = false)
            }
        }
    }

    fun switchCamera(newFacing: CameraFacingUi) {
        runOnMain {
            if (facing == newFacing) return@runOnMain
            facing = newFacing
            if (streaming || previewView != null) {
                bindInternal(includeAnalysis = streaming)
            }
        }
    }

    fun setTorch(on: Boolean) {
        runOnMain {
            torchOn = on
            val bound = camera ?: return@runOnMain
            if (!bound.cameraInfo.hasFlashUnit()) return@runOnMain
            runCatching {
                bound.cameraControl.enableTorch(on)
            }.onFailure { Log.w(TAG, "enableTorch failed", it) }
        }
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    private fun bindInternal(includeAnalysis: Boolean) {
        val owner = if (streaming) streamingOwner else previewOwner
        if (owner == null) return
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val tryBind: (ProcessCameraProvider) -> Unit = { cameraProvider ->
            try {
                cameraProvider.unbindAll()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (facing == CameraFacingUi.FRONT) CameraSelector.LENS_FACING_FRONT
                        else CameraSelector.LENS_FACING_BACK
                    )
                    .build()

                val preview = Preview.Builder().build()
                previewView?.let { view ->
                    runCatching { preview.setSurfaceProvider(view.surfaceProvider) }
                        .onFailure { Log.w(TAG, "set preview surfaceProvider failed", it) }
                }
                val useCases = mutableListOf<UseCase>(preview)

                var analysis: ImageAnalysis? = null
                if (includeAnalysis && onFrame != null) {
                    // 16:9 优先 + 目标分辨率：避免 target 1920x1080 时回落到 1920x1440(4:3)，
                    // 导致码率/像素数增加、UI 标称分辨率与实际不符。
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(even(targetWidth), even(targetHeight)),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    if (forceFpsRange && targetFps >= 30) {
                        // 部分设备/低照度下 AE 会把采集降到 15fps；局域网推流优先保证帧率。
                        runCatching {
                            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(30, 30),
                            )
                        }
                    }
                    analysis = analysisBuilder.build()
                    analysis.setAnalyzer(ensureExecutor()) { proxy -> deliverFrame(proxy) }
                    useCases.add(analysis)
                }

                val bound = cameraProvider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
                observeCameraState(bound, owner)
                analysis?.let {
                    runCatching {
                        val res = it.resolutionInfo?.resolution
                        Log.i(TAG, "analysis resolution=$res target=${targetWidth}x$targetHeight")
                    }
                }
                camera = bound
                previewUseCase = preview
                analysisUseCase = analysis
                if (paused) preview.setSurfaceProvider(null)
                if (torchOn && bound.cameraInfo.hasFlashUnit()) {
                    runCatching { bound.cameraControl.enableTorch(true) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bindToLifecycle failed", t)
                previewUseCase = null
                analysisUseCase = null
                camera = null
                if (forceFpsRange && streaming) {
                    // 设备不支持强制 30fps AE range：退回默认，重新绑定一次。
                    Log.w(TAG, "bind with forced 30fps failed, retry without it", t)
                    forceFpsRange = false
                    mainHandler.post { bindInternal(includeAnalysis = true) }
                } else if (streaming && recoveryAttempts > 0) {
                    // 其他应用仍占用摄像头：继续等待，不把端侧服务打掉。
                    scheduleRecovery()
                } else {
                    reportError(t)
                }
            }
        }

        val ready = provider
        if (ready != null) {
            tryBind(ready)
            return
        }

        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            val resolved = runCatching { future.get() }.getOrElse { error ->
                Log.w(TAG, "ProcessCameraProvider unavailable", error)
                reportError(error)
                return@addListener
            }
            provider = resolved
            tryBind(resolved)
        }, mainExecutor)
    }

    private fun deliverFrame(proxy: ImageProxy) {
        lastFrameAtMs = System.currentTimeMillis()
        if (cameraLost) {
            cameraLost = false
            cancelRecovery()
            onCameraState?.invoke(true)
        }
        try {
            val callback = onFrame ?: return
            val image = proxy.image ?: return
            val ptsUs = proxy.imageInfo.timestamp / 1_000L
            callback(image, ptsUs)
        } catch (t: Throwable) {
            Log.w(TAG, "frame delivery failed", t)
        } finally {
            runCatching { proxy.close() }
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    /**
     * 帧流看门狗：部分 ROM 在相机被抢占时只发 CLOSED(error=null)，不报错也不回调，
     * 表现为“预览黑屏但仍显示推流中”。超过 2s 没有分析帧即视为相机丢失并进入恢复重试。
     */
    private fun onFrameWatchdog() {
        if (!streaming || cameraLost) return
        val now = System.currentTimeMillis()
        if (lastFrameAtMs > 0L && now - lastFrameAtMs > FRAME_STALL_MS) {
            Log.w(TAG, "no camera frames for ${now - lastFrameAtMs} ms, try recovery")
            cameraLost = true
            onCameraState?.invoke(false)
            scheduleRecovery()
        }
    }

    /** 监听 CameraX 相机状态：被其他应用抢占时保持端侧服务并自动重试恢复。 */
    private fun observeCameraState(camera: Camera, owner: LifecycleOwner) {
        runCatching {
            camera.cameraInfo.cameraState.observe(owner) { state ->
                when (state?.type) {
                    CameraState.Type.OPEN -> {
                        recoveryAttempts = 0
                        cancelRecovery()
                        onCameraState?.invoke(true)
                    }
                    CameraState.Type.CLOSED -> {
                        val error = state.error
                        if (streaming && error != null) {
                            Log.w(TAG, "camera closed by system: ${error.code}", error.cause)
                            onCameraState?.invoke(false)
                            scheduleRecovery()
                        }
                    }
                    else -> Unit
                }
            }
        }.onFailure { Log.w(TAG, "observe camera state failed", it) }
    }

    private fun scheduleRecovery() {
        if (recoveryScheduled || !streaming) return
        recoveryScheduled = true
        mainHandler.removeCallbacks(recoveryRunnable)
        mainHandler.postDelayed(recoveryRunnable, RECOVERY_INTERVAL_MS)
    }

    private fun cancelRecovery() {
        recoveryScheduled = false
        recoveryAttempts = 0
        mainHandler.removeCallbacks(recoveryRunnable)
    }

    private fun attemptRecovery() {
        recoveryScheduled = false
        if (!streaming) return
        recoveryAttempts++
        if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            Log.w(TAG, "camera recovery failed after $MAX_RECOVERY_ATTEMPTS attempts")
            onCameraError?.invoke(
                P1Error(1003, "摄像头被其他应用占用，推流已停止", "请关闭其他使用摄像头的应用后重新开始推流")
            )
            return
        }
        Log.i(TAG, "camera recovery attempt $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS")
        bindInternal(includeAnalysis = true)
        scheduleRecovery()
    }

    private fun reportError(t: Throwable) {
        val callback = onCameraError ?: return
        val code = when (t) {
            is SecurityException -> 1001
            is IllegalArgumentException -> 1003
            else -> 1003
        }
        val error = if (code == 1001) {
            P1Error(1001, "未授予摄像头权限，无法预览与推流", "请在系统设置中允许相机权限后重试")
        } else {
            P1Error(1003, "摄像头不可用，可能被其他应用占用", "请关闭其他使用摄像头的应用后重试")
        }
        callback(error)
    }

    private fun unbindAll() {
        runCatching { provider?.unbindAll() }
        previewUseCase = null
        analysisUseCase = null
        camera = null
    }

    private fun ensureExecutor(): ExecutorService {
        val existing = analysisExecutor
        if (existing != null && !existing.isShutdown) return existing
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "campush-camera").apply { isDaemon = true }
        }
        analysisExecutor = executor
        return executor
    }

    private fun shutdownExecutor() {
        val executor = analysisExecutor ?: return
        analysisExecutor = null
        runCatching { executor.shutdown() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private companion object {
        const val TAG = "CameraController"
        const val RECOVERY_INTERVAL_MS = 1_000L
        const val MAX_RECOVERY_ATTEMPTS = 30
        const val WATCHDOG_INTERVAL_MS = 1_000L
        const val FRAME_STALL_MS = 2_000L
    }
}

/**
 * 进程内单例：App UI 与前台服务共享同一个 CameraX 管线，以便推流启动时把绑定生命周期
 * 从 Activity 切到 Service，停止后回落到仅预览。
 */
object CameraHost {
    @Volatile
    private var instance: CameraController? = null

    fun get(context: Context): CameraController {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: CameraController(context.applicationContext).also { instance = it }
        }
    }

    fun current(): CameraController? = instance
}
