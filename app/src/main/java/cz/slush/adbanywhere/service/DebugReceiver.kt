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

class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            ACTION_PAIR -> {
                val code = intent.getStringExtra("code") ?: return
                AppLog.info("debug", "pair intent received")
                ControlService.start(context, ControlService.ACTION_PAIR, code)
            }
        }
    }

    companion object {
        const val ACTION_PAIR = "cz.slush.adbanywhere.DEBUG_PAIR"
    }
}
