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
        AppLog.info("boot", "${intent.action} received")
        // The user's last explicit stop outlives the reboot: a boot only resumes
        // the daemon when the user last asked for it to run. The system adbd and
        // its paired keys are left alone; only the patched daemon is ours.
        if (!Prefs(context).userWantsRunning) {
            AppLog.info("boot", "ignored; the user stopped adb")
            return
        }
        AppLog.info("boot", "starting the control service")
        // A boot or an update is not a Start: it never turns the system adb on.
        ControlService.start(context, ControlService.ACTION_RECHECK)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
