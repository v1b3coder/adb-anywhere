/*
 * Prefs - device-protected storage for the control plane. The service can run
 * before the user unlocks the phone, so everything it needs lives here.
 */
package cz.slush.adbanywhere.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("adb-anywhere", Context.MODE_PRIVATE)

    var loopbackPort: Int
        get() = prefs.getInt("loopback_port", DEFAULT_LOOPBACK_PORT)
        set(value) = prefs.edit().putInt("loopback_port", value).apply()

    var vpnPort: Int
        get() = prefs.getInt("vpn_port", DEFAULT_VPN_PORT)
        set(value) = prefs.edit().putInt("vpn_port", value).apply()

    var keepSystemAdbOff: Boolean
        get() = prefs.getBoolean("keep_system_adb_off", false)
        set(value) = prefs.edit().putBoolean("keep_system_adb_off", value).apply()

    var setupComplete: Boolean
        get() = prefs.getBoolean("setup_complete", false)
        set(value) = prefs.edit().putBoolean("setup_complete", value).apply()

    /**
     * The last explicit start or stop. A fresh install waits for Start; an
     * install that already completed setup keeps running across an update, so a
     * deploy does not leave an unsupervised daemon behind. Nothing else brings
     * the daemon up without this intent: not opening the app, not a reboot and
     * not a sticky restart. The write is synchronous: a stop that is followed
     * by a hard process kill must not be lost in a pending apply().
     */
    var userWantsRunning: Boolean
        get() = prefs.getBoolean("user_wants_running", setupComplete)
        set(value) {
            prefs.edit().putBoolean("user_wants_running", value).commit()
        }

    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(value) = prefs.edit().putBoolean("paired", value).apply()

    var originalAdbEnabled: String
        get() = prefs.getString("original_adb_enabled", "") ?: ""
        set(value) = prefs.edit().putString("original_adb_enabled", value).apply()

    var lastVpnIp: String
        get() = prefs.getString("last_vpn_ip", "") ?: ""
        set(value) = prefs.edit().putString("last_vpn_ip", value).apply()

    var lastTlsPort: Int
        get() = prefs.getInt("last_tls_port", 0)
        set(value) = prefs.edit().putInt("last_tls_port", value).apply()

    var developerKeysJson: String
        get() = prefs.getString("developer_keys", "[]") ?: "[]"
        set(value) = prefs.edit().putString("developer_keys", value).apply()

    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(value) = prefs.edit().putString("last_error", value).apply()

    var bootstrapHost: String
        get() = prefs.getString("bootstrap_host", "") ?: ""
        set(value) = prefs.edit().putString("bootstrap_host", value).apply()

    var bootstrapPort: Int
        get() = prefs.getInt("bootstrap_port", 0)
        set(value) = prefs.edit().putInt("bootstrap_port", value).apply()

    var remoteDir: String
        get() = prefs.getString("remote_dir", "/data/local/tmp/adb-anywhere") ?: "/data/local/tmp/adb-anywhere"
        set(value) = prefs.edit().putString("remote_dir", value.trim()).apply()

    companion object {
        const val DEFAULT_LOOPBACK_PORT = 15037
        const val DEFAULT_VPN_PORT = 15038
    }
}
