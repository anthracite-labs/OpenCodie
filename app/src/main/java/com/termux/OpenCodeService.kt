package com.termux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the single OpenCode server process alive while the app is in the
 * background. It owns nothing else: [OpenCodeHost] supervises the process.
 */
class OpenCodeService : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        Thread { OpenCodeHost.prepare(this) }.apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        OpenCodeHost.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenCode server", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("OpenCode server running")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "opencode"
        const val NOTIFICATION_ID = 1
    }
}
