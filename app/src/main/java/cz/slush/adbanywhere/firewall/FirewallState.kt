/*
 * FirewallState - the relay state the UI observes. It is separate from the
 * daemon phase, so the UI can show a listener error while adbd is healthy.
 */
package cz.slush.adbanywhere.firewall

data class FirewallState(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    /**
     * The user paused the firewall: the mode stays on, the relay stays closed and
     * the custom adb keeps its loopback listener. Owned by the service, not by the
     * relay, so a relay snapshot never clears it.
     */
    val paused: Boolean = false,
    val listenAddress: String = "",
    val activeSessions: Int = 0,
    val pendingPrompts: Int = 0,
    val lastError: String = "",
) {
    /**
     * Applies the fields the relay owns. `pendingPrompts` belongs to the service,
     * so a relay snapshot must not overwrite it.
     */
    fun withRelayState(relay: FirewallState): FirewallState = copy(
        enabled = relay.enabled,
        listening = relay.listening,
        listenAddress = relay.listenAddress,
        activeSessions = relay.activeSessions,
        lastError = relay.lastError,
    )
}
