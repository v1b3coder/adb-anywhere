/*
 * FirewallState - the relay state the UI observes. It is separate from the
 * daemon phase, so the UI can show a listener error while adbd is healthy.
 */
package cz.slush.adbanywhere.firewall

data class FirewallState(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val listenAddress: String = "",
    val activeSessions: Int = 0,
    val pendingPrompts: Int = 0,
    val lastError: String = "",
)
