/*
 * AdbAnywhereApp - notification channels. Everything the service needs is in
 * device-protected storage so it can run before the first unlock.
 */
package cz.slush.adbanywhere

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import cz.slush.adbanywhere.service.Notifications

class AdbAnywhereApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                Notifications.CHANNEL_SERVICE,
                "ADB Anywhere service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the ADB endpoint alive" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                Notifications.CHANNEL_PAIRING,
                "Pairing",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Pairing with Android's wireless debugging" },
        )
    }
}
