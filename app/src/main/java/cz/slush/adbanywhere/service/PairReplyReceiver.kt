/*
 * PairReplyReceiver - receives the "code:port" reply from the pairing notification.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

class PairReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(ControlService.EXTRA_PAIR_INPUT)?.toString() ?: return
        AppLog.info("pair", "pairing reply received")
        ControlService.startPair(context, text, null, fromNotification = true)
        NotificationManagerCompat.from(context).cancel(Notifications.PAIRING_ID)
    }
}
