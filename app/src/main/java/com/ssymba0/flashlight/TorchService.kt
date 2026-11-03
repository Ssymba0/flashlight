package com.ssymba0.flashlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Runs while the light is on. Android switches the flash off if the app that turned it on
 * is killed, so this keeps the app alive and shows a notification with a "Turn off" button.
 */
class TorchService : Service() {

    companion object {
        private const val ACTION_TURN_OFF = "com.ssymba0.flashlight.TURN_OFF"
        private const val CHANNEL_ID = "flashlight"
        private const val NOTIFICATION_ID = 1

        var running = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TorchService::class.java))
            } catch (_: Exception) {
                // Android can refuse when we're in the background. The light still works.
            }
        }
    }

    private var lastPercent = -1

    private val listener: () -> Unit = {
        if (!Torch.isOn) stopSelf() else updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        Torch.init(this)
        running = true
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        Torch.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (intent?.action == ACTION_TURN_OFF) Torch.turnOff()
        if (!Torch.isOn) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Torch.removeListener(listener)
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        if (Torch.percent == lastPercent) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        lastPercent = Torch.percent
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val off = PendingIntent.getService(
            this, 1, Intent(this, TorchService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (Torch.supportsLevels) getString(R.string.notification_text, Torch.percent) else null
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flashlight)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_power), getString(R.string.turn_off), off
                ).build()
            )
            .build()
    }
}
