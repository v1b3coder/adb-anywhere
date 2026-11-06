/*
 * DebugReceiver - debug-build-only hooks for the end-to-end tests. It lets the
 * harness drive pairing without bringing the activity to the front, which would
 * dismiss Android's pairing dialog.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
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
            ACTION_NOTIFY_PAIR -> {
                AppLog.info("debug", "pairing notification requested")
                ControlService.start(context, ControlService.ACTION_NOTIFY_PAIR)
            }
            ACTION_PAIR_REPLY -> {
                val text = intent.getStringExtra("code") ?: return
                val reply = Intent(context, PairReplyReceiver::class.java)
                val results = Bundle().apply {
                    putCharSequence(ControlService.EXTRA_PAIR_INPUT, text)
                }
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(ControlService.EXTRA_PAIR_INPUT).build()),
                    reply,
                    results,
                )
                AppLog.info("debug", "pairing reply injected")
                PairReplyReceiver().onReceive(context, reply)
            }
        }
    }

    companion object {
        const val ACTION_PAIR = "cz.slush.adbanywhere.DEBUG_PAIR"
        const val ACTION_NOTIFY_PAIR = "cz.slush.adbanywhere.DEBUG_NOTIFY_PAIR"
        const val ACTION_PAIR_REPLY = "cz.slush.adbanywhere.DEBUG_PAIR_REPLY"
    }
}
