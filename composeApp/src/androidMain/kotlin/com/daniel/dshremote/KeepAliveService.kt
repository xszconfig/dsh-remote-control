package com.daniel.dshremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 按需前台服务：存在 running 代理时保活 WS 长连接。
 * 类型 connectedDevice（无 6h 上限，语义=「与需要网络连接的外部设备互动」，见决策文档 §6.2）。
 */
class KeepAliveService : Service() {
    companion object {
        const val ACTION_START = "com.daniel.dshremote.action.KEEP_ALIVE"
        const val EXTRA_MAIN = "keepalive_main"
        const val EXTRA_SUB = "keepalive_sub"
        const val NOTIFICATION_ID = 8202
        const val CHANNEL_ID = "dsh_keepalive"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val main = intent?.getIntExtra(EXTRA_MAIN, 0) ?: 0
        val sub = intent?.getIntExtra(EXTRA_SUB, 0) ?: 0
        ensureChannel()
        val notification = buildNotification(main, sub)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台保活", NotificationManager.IMPORTANCE_LOW).apply {
                description = "有代理运行时保持连接（低打扰，不响铃不震动）"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(main: Int, sub: Int): Notification {
        val body = keepAliveNotificationBody(main, sub)
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("代理运行中")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
