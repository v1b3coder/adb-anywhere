/*
 * ControlState - the single source of truth the UI observes.
 */
package cz.slush.adbanywhere.service

import cz.slush.adbanywhere.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    val running: Boolean get() = phase == Phase.Running
    val needsSetup: Boolean get() = phase == Phase.NeedsPairing || phase == Phase.Error
}

object ControlBus {
    val state = MutableStateFlow(ControlState())
    val logs = MutableStateFlow("")

    fun update(block: (ControlState) -> ControlState) {
        state.update(block)
    }
}
