/*
 * BootReceiver - brings the control plane back after a reboot. The service is
 * direct-boot aware and reads only device-protected state.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ControlService.start(context, ControlService.ACTION_START)
    }
}
