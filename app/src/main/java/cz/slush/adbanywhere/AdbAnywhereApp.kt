/*
 * AdbAnywhereApp - notification channels and the persisted control state.
 * Everything the service needs is in device-protected storage so it can run before
 * the first unlock.
 */
package cz.slush.adbanywhere

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.firewall.FirewallConfigStore
import cz.slush.adbanywhere.service.AppLog
import cz.slush.adbanywhere.service.ControlBus
import cz.slush.adbanywhere.service.Notifications
import cz.slush.adbanywhere.service.Phase

class AdbAnywhereApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // A new process has no service and no daemon, so restore the last intent:
        // an app the user stopped comes back as stopped, not as "not set up".
        val prefs = Prefs(this)
        AppLog.init(this, prefs.diagnosticLog)
        AppLog.info("app", "process started; diagnostics=${prefs.diagnosticLog}")
        ControlBus.firewallConfig.value = FirewallConfigStore(prefs).load()
        if (prefs.setupComplete && !prefs.userWantsRunning) {
            ControlBus.update { it.copy(phase = Phase.Stopped) }
        }

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
        manager.createNotificationChannel(
            NotificationChannel(
                Notifications.CHANNEL_FIREWALL,
                "Firewall",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Approval of ADB calls" },
        )
    }
}
