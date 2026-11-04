/*
 * WatchdogReceiver - wakes the service while the phone is in Doze.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.slush.adbanywhere.data.Prefs

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A stop cancels the alarm; a stale one must not bring the service back.
        if (!Prefs(context).userWantsRunning) {
            AppLog.info("alarm", "watchdog alarm ignored; the user stopped adb")
            return
        }
        AppLog.info("alarm", "watchdog alarm received")
        ControlService.start(context, ControlService.ACTION_WATCHDOG)
    }
}
