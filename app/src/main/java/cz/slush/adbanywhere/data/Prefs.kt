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

    /** Records the app's own event log. Off by default. */
    var diagnosticLog: Boolean
        get() = prefs.getBoolean("diagnostic_log", false)
        set(value) = prefs.edit().putBoolean("diagnostic_log", value).apply()

    var bootstrapHost: String
        get() = prefs.getString("bootstrap_host", "") ?: ""
        set(value) = prefs.edit().putString("bootstrap_host", value).apply()

    var bootstrapPort: Int
        get() = prefs.getInt("bootstrap_port", 0)
        set(value) = prefs.edit().putInt("bootstrap_port", value).apply()

    var remoteDir: String
        get() = prefs.getString("remote_dir", "/data/local/tmp/adb-anywhere") ?: "/data/local/tmp/adb-anywhere"
        set(value) = prefs.edit().putString("remote_dir", value.trim()).apply()

    var firewallEnabled: Boolean
        get() = prefs.getBoolean("firewall_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_enabled", value).apply()

    var firewallIpAllowlistEnabled: Boolean
        get() = prefs.getBoolean("firewall_ip_allowlist_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_ip_allowlist_enabled", value).apply()

    var firewallIpAllowlistJson: String
        get() = prefs.getString("firewall_ip_allowlist", "[]") ?: "[]"
        set(value) = prefs.edit().putString("firewall_ip_allowlist", value).apply()

    var firewallAppAllowlistEnabled: Boolean
        get() = prefs.getBoolean("firewall_app_allowlist_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_app_allowlist_enabled", value).apply()

    var firewallAppAllowlistJson: String
        get() = prefs.getString("firewall_app_allowlist", "[]") ?: "[]"
        set(value) = prefs.edit().putString("firewall_app_allowlist", value).apply()

    var firewallAppGraceMs: Long
        get() = prefs.getLong("firewall_app_grace_ms", 15_000L)
        set(value) = prefs.edit().putLong("firewall_app_grace_ms", value).apply()

    var firewallCallPolicyEnabled: Boolean
        get() = prefs.getBoolean("firewall_call_policy_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_call_policy_enabled", value).apply()

    var firewallCallRulesJson: String
        get() = prefs.getString("firewall_call_rules", "[]") ?: "[]"
        set(value) = prefs.edit().putString("firewall_call_rules", value).apply()

    var firewallPromptTimeoutMs: Long
        get() = prefs.getLong("firewall_prompt_timeout_ms", 30_000L)
        set(value) = prefs.edit().putLong("firewall_prompt_timeout_ms", value).apply()

    var firewallRedactAudit: Boolean
        get() = prefs.getBoolean("firewall_redact_audit", false)
        set(value) = prefs.edit().putBoolean("firewall_redact_audit", value).apply()

    companion object {
        const val DEFAULT_LOOPBACK_PORT = 15037
        const val DEFAULT_VPN_PORT = 15038
    }
}
