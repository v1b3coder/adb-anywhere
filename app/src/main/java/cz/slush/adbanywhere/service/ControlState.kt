/*
 * ControlState - the single source of truth the UI observes.
 */
package cz.slush.adbanywhere.service

import cz.slush.adbanywhere.data.AdbStatus
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.firewall.AuditEvent
import cz.slush.adbanywhere.firewall.FirewallConfig
import cz.slush.adbanywhere.firewall.FirewallState
import cz.slush.adbanywhere.firewall.PromptRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { Idle, NeedsPairing, Pairing, Deploying, Running, Error, Stopped }

enum class StepStatus { Pending, Active, Done, Failed }

data class SetupStep(
    val title: String,
    val status: StepStatus = StepStatus.Pending,
    val detail: String = "",
)

data class ControlState(
    val phase: Phase = Phase.Idle,
    val endpoint: String = "",
    val vpnIp: String? = null,
    val loopbackPort: Int = Prefs.DEFAULT_LOOPBACK_PORT,
    val vpnPort: Int = Prefs.DEFAULT_VPN_PORT,
    val appFingerprint: String = "",
    val keyCount: Int = 0,
    val message: String = "",
    val error: String = "",
    val steps: List<SetupStep> = emptyList(),
    /**
     * D11: the platform ADB state, read by the service. It is not derived from
     * the daemon phase, so the UI cannot claim "off" while the system listener
     * is on. Null until the service has read it once.
     */
    val adbStatus: AdbStatus? = null,
    val firewall: FirewallState = FirewallState(),
    /**
     * D24's clock, mirrored for the status hero: the relay forwarded an
     * authenticated ADB frame within the last moment. Only the firewall relay
     * sees frames, so a direct VPN listener never sets it.
     */
    val adbTraffic: Boolean = false,
) {
    val running: Boolean get() = phase == Phase.Running

    /**
     * Pairing is its own phase. A generic failure must not present the pairing
     * steps: the VPN, the relay and the ports fail for reasons that a new code
     * cannot fix, and the user would pair again for nothing.
     */
    val needsSetup: Boolean get() = phase == Phase.NeedsPairing

    /**
     * The pairing steps are instructions for a step that is still ahead. Once the
     * app is deploying or running, Android's pairing dialog is gone and the same
     * steps would read as if nothing had happened. A failed pairing stays in
     * [Phase.NeedsPairing], so its steps stay visible: only a new dialog, a new
     * code and a new reply can fix it.
     */
    val pairingInstructionsVisible: Boolean
        get() = phase == Phase.Idle || phase == Phase.NeedsPairing || phase == Phase.Pairing

    /**
     * The custom adb can be healthy while the firewall relay is down: the loopback
     * channel works, but developer machines cannot reach the VPN endpoint.  The Home
     * status must not claim the endpoint is reachable in that state. A paused
     * firewall is closed on purpose, not down (§6 Listener modes 6).
     */
    val firewallDown: Boolean
        get() = phase == Phase.Running && firewall.enabled && !firewall.paused &&
            !firewall.listening

    /**
     * Running with the firewall paused: the daemon is up, the VPN listener is
     * closed, and Resume is the one action that reopens it.
     */
    val paused: Boolean get() = phase == Phase.Running && firewall.paused
}

object ControlBus {
    val state = MutableStateFlow(ControlState())
    val logs = MutableStateFlow("")
    var audit: StateFlow<List<AuditEvent>> = MutableStateFlow(emptyList())
    val pendingPrompt = MutableStateFlow<PromptRequest?>(null)
    val firewallConfig = MutableStateFlow(FirewallConfig())

    fun update(block: (ControlState) -> ControlState) {
        state.update(block)
    }
}
