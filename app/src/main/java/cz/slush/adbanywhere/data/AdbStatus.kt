/*
 * AdbStatus - the system ADB state the Settings screen reports. The values come
 * from the platform and the 5555 probe, never from the daemon phase.
 */
package cz.slush.adbanywhere.data

data class AdbStatus(
    val adbEnabled: Int,
    val adbWifiEnabled: Int,
    val port5555Open: Boolean,
    /**
     * The system adbd's wireless-debugging port while it listens, else 0. It is
     * never one of the app's own ports: the Settings card must not present the
     * custom adb's listener as the system one.
     */
    val wirelessPort: Int = 0,
) {
    /** The warnings the settings UI shows next to the state. */
    fun warnings(): List<String> = buildList {
        if (adbWifiEnabled != 0) {
            add("Wireless debugging is on.")
        }
        if (port5555Open) {
            add("Port 5555 is open: the system adb is listening.")
        }
    }
}
