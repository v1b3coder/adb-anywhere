/*
 * WatchdogReceiver - wakes the service while the phone is in Doze.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ControlService.start(context, ControlService.ACTION_WATCHDOG)
    }
}
