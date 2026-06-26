package com.portal.wake.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The foreground-service notification, factored out of [WakeService].
 *
 * The app has NO UI — no Activity, no launcher icon, no overlay. The *only* thing it can put on screen is
 * this notification, which Android (API 26+) hard-requires for a foreground service. It is made as invisible
 * as the platform permits (min importance, no badge, secret on the lock screen). Pure construction with no
 * service state, so it lives on its own.
 */
internal object WakeNotification {

    /** Foreground-service notification id (`startForeground`). */
    const val ID = 1001

    private const val CHANNEL_ID = "portal_wake_listening"

    fun build(context: Context): Notification {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Background listening", NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        mgr.createNotificationChannel(channel)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }
}
