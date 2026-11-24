package app.loadout.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The setup guide's voice while the user is away in Android's settings. Loadout cannot draw
 * on Android's screens, so each step it sends the user to is repeated in a notification that
 * also leads back to Loadout.
 */
object GuideNotice {
    private const val CHANNEL = "guide"
    private const val ID = 42

    /** Opens Loadout exactly as it was left, the way tapping its icon does. */
    fun returnIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 7, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, title: String, text: String) {
        val app = context.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Setup guide", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Repeats the current setup step while you are in Android's settings."
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(returnIntent(app))
            .build()
        try {
            manager.notify(ID, notification)
        } catch (_: SecurityException) {
            // Notifications were switched off in the meantime.
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
