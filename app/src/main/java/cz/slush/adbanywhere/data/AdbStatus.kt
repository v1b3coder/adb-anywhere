/*
 * AdbStatus - D11: the system ADB state the Settings screen reports. The values
 * come from the platform and the 5555 probe, never from the daemon phase.
 */
package cz.slush.adbanywhere.data

data class AdbStatus(
    val adbEnabled: Int,
    val adbWifiEnabled: Int,
    val port5555Open: Boolean,
) {
    /** D11: the warnings the settings UI shows next to the state. */
    fun warnings(keepSystemAdbOff: Boolean): List<String> = buildList {
        if (adbWifiEnabled != 0) {
            add("adb_wifi_enabled=$adbWifiEnabled: system wireless debugging is on.")
        }
        if (port5555Open) {
            add("127.0.0.1:5555 is open: the system adbd is listening.")
        }
        if (keepSystemAdbOff && adbEnabled != 0) {
            add("adb_enabled=$adbEnabled although \"Keep system adb off\" is on.")
        }
    }
}
