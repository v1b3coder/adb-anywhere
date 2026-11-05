/*
 * PairReplyReceiver - receives the replies to the two pairing notifications:
 * the six-digit code first, then the port.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

class PairReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        // The port step carries the code in the intent because the user already
        // entered it; the code step only has the reply.
        val code = results.getCharSequence(ControlService.INPUT_PAIR_CODE)?.toString()
            ?: intent.getStringExtra(ControlService.EXTRA_CODE)
        val port = results.getCharSequence(ControlService.INPUT_PAIR_PORT)?.toString()
        if (code == null) return
        AppLog.info("pair", if (port == null) "pairing code received" else "pairing port received")
        ControlService.startPair(context, code, port)
    }
}
