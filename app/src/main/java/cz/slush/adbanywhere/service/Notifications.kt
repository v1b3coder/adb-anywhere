/*
 * Notifications - channel and id constants and the relay line shared by the app
 * and the service.
 */
package cz.slush.adbanywhere.service

import cz.slush.adbanywhere.firewall.FirewallState

object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_PAIRING = "pairing"
    const val CHANNEL_FIREWALL = "firewall"
    const val SERVICE_ID = 1001
    const val PAIRING_ID = 1002
    const val PROMPT_ID = 1003
    const val GROUP_SERVICE = "service"
    const val GROUP_PAIRING = "pairing"
}

/** The relay line in the ongoing notification. */
internal fun firewallStatusLine(firewall: FirewallState): String =
    if (firewall.listening) {
        val sessions = firewall.activeSessions
        "Firewall: $sessions ${if (sessions == 1) "session" else "sessions"}"
    } else {
        "Firewall: ${firewall.lastError.ifBlank { "not listening" }}"
    }
