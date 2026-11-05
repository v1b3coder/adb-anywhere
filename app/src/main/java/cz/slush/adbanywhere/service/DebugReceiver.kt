/*
 * DebugReceiver - debug-build-only hooks for the end-to-end tests. It lets the
 * harness drive pairing without bringing the activity to the front, which would
 * dismiss Android's pairing dialog.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.slush.adbanywhere.BuildConfig
import cz.slush.adbanywhere.data.PairingCode

class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            ACTION_PAIR -> {
                val pairing = intent.getStringExtra("code")?.let { PairingCode.parse(it) } ?: return
                AppLog.info("debug", "pair intent received")
                ControlService.startPair(context, pairing.code, pairing.port.toString())
            }
        }
    }

    companion object {
        const val ACTION_PAIR = "cz.slush.adbanywhere.DEBUG_PAIR"
    }
}
