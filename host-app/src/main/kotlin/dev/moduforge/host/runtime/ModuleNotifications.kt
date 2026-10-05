package dev.moduforge.host.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.core.module.ModuleNotifier
import dev.moduforge.host.MainActivity
import dev.moduforge.host.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts notifications for modules. The module's name is always shown by the host next to the
 * module's own text, and each module owns a single notification slot that it can only replace.
 */
@Singleton
class ModuleNotifications @Inject constructor(@ApplicationContext private val context: Context) : ModuleNotifier {

    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun notify(moduleId: String, moduleName: String, title: String, text: String): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notifications_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setSubText(moduleName)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // The tag keeps one slot per module; id 2 stays clear of the foreground service notification.
        manager.notify(moduleId, NOTIFICATION_ID, notification)
        return true
    }

    private companion object {
        const val CHANNEL_ID = "module-notifications"
        const val NOTIFICATION_ID = 2
    }
}
