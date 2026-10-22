package com.campush.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** P0：WAN/WHIP 推流前台服务，保证退后台/锁屏时进程与相机采集存活。 */
class WanStreamingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WAN 推流", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "公网 WHIP 推流进行中"
                    setShowBadge(false)
                },
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("CamPush 公网推流中")
            .setContentText("WAN/WHIP 会话进行中，返回 App 可停止")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "wan_streaming"
        private const val NOTIFICATION_ID = 4202
        private const val ACTION_START = "com.campush.app.action.WAN_START"
        private const val ACTION_STOP = "com.campush.app.action.WAN_STOP"

        fun start(context: Context) {
            val intent = Intent(context, WanStreamingService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WanStreamingService::class.java)) }
        }
    }
}
