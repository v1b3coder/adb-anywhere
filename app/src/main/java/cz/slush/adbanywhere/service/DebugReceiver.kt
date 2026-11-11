/*
 * DebugReceiver - debug-build-only hooks for the end-to-end tests. It is exported
 * so `adb shell am broadcast` can reach it, and protected by
 * android.permission.DUMP: the shell user holds it and a third-party app cannot,
 * so no installed app can drive these hooks (they run shell commands with the
 * daemon key and edit the key file and the firewall). The release manifest
 * disables the receiver and onReceive re-checks the build type.
 */
package cz.slush.adbanywhere.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import cz.slush.adbanywhere.AppViewModel
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
            ACTION_DEBUG -> command(context, intent)
        }
    }

    /** The e2e harness presses the same buttons the user does. */
    private fun command(context: Context, intent: Intent) {
        val model = AppViewModel(context.applicationContext as Application)
        intent.getIntExtra("tlsPort", 0).takeIf { it > 0 }?.let(model::rememberTlsPort)
        if (intent.getBooleanExtra("start", false)) model.start()
        if (intent.getBooleanExtra("stop", false)) model.stop()
        if (intent.hasExtra("firewall")) model.setFirewallEnabled(intent.getBooleanExtra("firewall", false))
        intent.getStringExtra("firewallIp")?.let { ip ->
            AppLog.info("debug", "firewall ip result=${model.addIp(ip) ?: "ok"}")
        }
        intent.getStringExtra("vpnIp")?.let(model::setDebugVpnIp)
        if (intent.hasExtra("firewallIpAllowlist")) {
            model.setIpAllowlistEnabled(intent.getBooleanExtra("firewallIpAllowlist", false))
        }
        intent.getStringExtra("firewallApp")?.let(model::addApp)
        if (intent.hasExtra("firewallAppAllowlist")) {
            model.setAppAllowlistEnabled(intent.getBooleanExtra("firewallAppAllowlist", false))
        }
        if (intent.hasExtra("firewallCallPolicy")) {
            model.setCallPolicyEnabled(intent.getBooleanExtra("firewallCallPolicy", false))
        }
        intent.getStringExtra("addkey")?.let { key ->
            val error = model.addDeveloperKey(key, intent.getStringExtra("addlabel") ?: "test")
            AppLog.info("debug", "addkey result=${error ?: "ok"}")
        }
        intent.getStringExtra("shellcmd")?.let(model::runDebugShell)
    }

    companion object {
        const val ACTION_PAIR = "cz.slush.adbanywhere.DEBUG_PAIR"
        const val ACTION_NOTIFY_PAIR = "cz.slush.adbanywhere.DEBUG_NOTIFY_PAIR"
        const val ACTION_PAIR_REPLY = "cz.slush.adbanywhere.DEBUG_PAIR_REPLY"
        const val ACTION_DEBUG = "cz.slush.adbanywhere.DEBUG"
    }
}
