/*
 * BootReceiver - brings the control plane back after a reboot. The service is
 * direct-boot aware and reads only device-protected state.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.slush.adbanywhere.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is not exported and the system still reaches it, so only a
        // same-app intent could get here with another action.
        if (intent.action !in ACTIONS) return
        // The user's last explicit stop outlives the reboot: a boot only resumes
        // the daemon when the user last asked for it to run.
        if (!Prefs(context).userWantsRunning) return
        ControlService.start(context, ControlService.ACTION_START)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
