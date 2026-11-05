/*
 * AdbLifecycle - the Start/Stop ownership policy for the system adbd.
 */
package cz.slush.adbanywhere.data

data class AdbState(val adbEnabled: Int, val adbWifiEnabled: Int)

enum class AdbSetting { ADB_ENABLED, ADB_WIFI_ENABLED }

object AdbLifecycle {
    /**
     * Start leaves ADB_ENABLED alone when the patched adbd already runs. A start
     * with the patched adbd down enables the system adbd it needs as a shell.
     */
    fun start(
        current: AdbState,
        patchedAdbdAlive: Boolean,
        wifiConnected: Boolean,
    ): AdbState =
        if (patchedAdbdAlive) {
            current.copy(adbWifiEnabled = 0)
        } else {
            AdbState(adbEnabled = 1, adbWifiEnabled = if (wifiConnected) 1 else 0)
        }

    fun stop(): AdbState = AdbState(adbEnabled = 0, adbWifiEnabled = 0)

    fun boot(userWantsRunning: Boolean, wifiConnected: Boolean): AdbState =
        if (userWantsRunning) {
            AdbState(adbEnabled = 1, adbWifiEnabled = if (wifiConnected) 1 else 0)
        } else {
            stop()
        }

    /** Only settings whose value differs are written. */
    fun changes(current: AdbState, desired: AdbState): List<AdbSetting> = buildList {
        if (current.adbEnabled != desired.adbEnabled) add(AdbSetting.ADB_ENABLED)
        if (current.adbWifiEnabled != desired.adbWifiEnabled) add(AdbSetting.ADB_WIFI_ENABLED)
    }
}
