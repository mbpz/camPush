package com.campush.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.campush.app.MainActivity
import com.campush.app.R
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.P1Error
import com.campush.app.core.StreamSettingsUi
import com.campush.app.streaming.EnginePhase
import com.campush.app.streaming.EngineSnapshot
import com.campush.app.streaming.LanStreamEngine
import com.campush.app.streaming.StreamStartOptions
import com.campush.app.streaming.StreamingBridge
import com.campush.app.streaming.pipeline.CameraMediaPipelineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 推流前台服务：常驻通知 + 推流引擎宿主。
 *
 * - 相机/编码绑定 Service 生命周期（CameraX bindToLifecycle(this, ...)）；
 * - 启动顺序：先 startForeground（≤5 s），再启动媒体管线（docs/02 §11.1）；
 * - foregroundServiceType = camera|microphone（Manifest 已声明），START_NOT_STICKY；
 * - 通知「停止推流」action 等价 StopPush，UI 已销毁时仍可停止。
 */
class LanStreamingService : LifecycleService() {

    private lateinit var engine: LanStreamEngine
    private var snapshotJob: Job? = null
    private var lastNotifiedAt: Long = 0L
    private var lastPhase: EnginePhase? = null

    override fun onCreate() {
        super.onCreate()
        instanceAlive = true
        // 组合根：Service 负责把具体管线适配器装配给引擎；引擎只依赖 MediaPipeline 契约。
        engine = LanStreamEngine(this, this, CameraMediaPipelineFactory(this, this))
        StreamingBridge.engine = engine
        StreamingBridge.pendingStreamPreview?.let { engine.bindStreamPreview(it) }
        createNotificationChannel()
        snapshotJob = lifecycleScope.launch {
            StreamingBridge.snapshot.collect { snapshot ->
                if (snapshot.phase == EnginePhase.STREAMING || snapshot.phase == EnginePhase.STARTING) {
                    val now = System.currentTimeMillis()
                    if (snapshot.phase != lastPhase || now - lastNotifiedAt >= NOTIFICATION_REFRESH_MS) {
                        lastNotifiedAt = now
                        lastPhase = snapshot.phase
                        runCatching { notify(buildNotification(snapshot)) }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val options = intent.toOptions()
                try {
                    startForegroundCompat(buildNotification(StreamingBridge.snapshot.value))
                } catch (t: Throwable) {
                    StreamingBridge.emitError(
                        P1Error(
                            1007,
                            "无法启动后台推流服务",
                            "请检查系统通知/后台运行权限后重试",
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                engine.start(options)
            }
            ACTION_STOP -> stopStreamingAndSelf()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        snapshotJob?.cancel()
        snapshotJob = null
        runCatching { engine.stop() }
        if (StreamingBridge.engine === engine) StreamingBridge.engine = null
        instanceAlive = false
        super.onDestroy()
    }

    private fun stopStreamingAndSelf() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { engine.stop() } }
            runCatching { stopForegroundCompat() }
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        // camera/microphone 两个类型常量自 API 30 起生效；API 29 传 0 避免未知 type 校验失败。
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var flags = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasMicPermission()) {
                flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            flags
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(snapshot: EngineSnapshot): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, LanStreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = when (snapshot.phase) {
            EnginePhase.STARTING -> "正在启动局域网推流…"
            EnginePhase.STREAMING -> "推流中 · 观看 ${snapshot.viewers}/${snapshot.maxViewers}"
            EnginePhase.STOPPING -> "正在停止…"
            EnginePhase.FAILED -> "推流已中断"
            EnginePhase.IDLE -> "已停止"
        }
        val line = if (snapshot.phase == EnginePhase.STREAMING) {
            "$statusText · ${snapshot.bitrateKbps} kbps · ${snapshot.fps} fps"
        } else {
            statusText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("${getString(R.string.app_name)} · 局域网推流")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "停止推流", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "推流会话",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "局域网推流进行中的常驻通知"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun Intent.toOptions(): StreamStartOptions {
        return StreamStartOptions(
            settings = StreamSettingsUi(
                width = getIntExtra(EXTRA_WIDTH, 1280),
                height = getIntExtra(EXTRA_HEIGHT, 720),
                fps = getIntExtra(EXTRA_FPS, 30),
                bitrateKbps = getIntExtra(EXTRA_BITRATE, 2500),
                audioEnabled = getBooleanExtra(EXTRA_AUDIO_ENABLED, true),
                powerSave = getBooleanExtra(EXTRA_POWER_SAVE, false),
                adaptiveBitrate = getBooleanExtra(EXTRA_ADAPTIVE_BITRATE, true),
            ),
            facing = if (getBooleanExtra(EXTRA_FACING_FRONT, false)) {
                CameraFacingUi.FRONT
            } else {
                CameraFacingUi.BACK
            },
            pinRequired = getBooleanExtra(EXTRA_PIN_REQUIRED, false),
            pin = getStringExtra(EXTRA_PIN),
            port = getIntExtra(EXTRA_PORT, 8089),
            muted = getBooleanExtra(EXTRA_MUTED, false),
            requestedAtMs = getLongExtra(EXTRA_REQUESTED_AT, System.currentTimeMillis()),
        )
    }

    companion object {
        private const val TAG = "LanStreamingService"
        private const val CHANNEL_ID = "push_session"
        private const val NOTIFICATION_ID = 1201
        private const val REQUEST_OPEN = 11
        private const val REQUEST_STOP = 12
        private const val NOTIFICATION_REFRESH_MS = 5_000L

        const val ACTION_START = "com.campush.app.action.START_STREAMING"
        const val ACTION_STOP = "com.campush.app.action.STOP_STREAMING"

        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE = "bitrateKbps"
        private const val EXTRA_AUDIO_ENABLED = "audioEnabled"
        private const val EXTRA_POWER_SAVE = "powerSave"
        private const val EXTRA_ADAPTIVE_BITRATE = "adaptiveBitrate"
        private const val EXTRA_FACING_FRONT = "facingFront"
        private const val EXTRA_PIN_REQUIRED = "pinRequired"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_MUTED = "muted"
        private const val EXTRA_REQUESTED_AT = "requestedAtMs"

        @Volatile
        private var instanceAlive: Boolean = false

        fun start(context: Context, options: StreamStartOptions) {
            val intent = Intent(context, LanStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WIDTH, options.settings.width)
                putExtra(EXTRA_HEIGHT, options.settings.height)
                putExtra(EXTRA_FPS, options.settings.fps)
                putExtra(EXTRA_BITRATE, options.settings.bitrateKbps)
                putExtra(EXTRA_AUDIO_ENABLED, options.settings.audioEnabled)
                putExtra(EXTRA_POWER_SAVE, options.settings.powerSave)
                putExtra(EXTRA_ADAPTIVE_BITRATE, options.settings.adaptiveBitrate)
                putExtra(EXTRA_FACING_FRONT, options.facing == CameraFacingUi.FRONT)
                putExtra(EXTRA_PIN_REQUIRED, options.pinRequired)
                putStringExtraCompat(EXTRA_PIN, options.pin)
                putExtra(EXTRA_PORT, options.port)
                putExtra(EXTRA_MUTED, options.muted)
                putExtra(EXTRA_REQUESTED_AT, options.requestedAtMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (!instanceAlive) return
            val intent = Intent(context, LanStreamingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun isRunning(): Boolean = instanceAlive

        private fun Intent.putStringExtraCompat(key: String, value: String?) {
            if (value != null) putExtra(key, value)
        }
    }
}
