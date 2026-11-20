/*
 * Prefs - device-protected storage for the control plane. The service can run
 * before the user unlocks the phone, so everything it needs lives here.
 */
package cz.slush.adbanywhere.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The persisted lifecycle intent. It is the user's last explicit choice, not the
 * observed state: `Running` and `Paused` both keep the daemon up, `Paused` also
 * keeps the relay closed. Only an explicit start, restart, pairing, pause,
 * resume or stop changes it (D22).
 */
enum class Lifecycle {
    Stopped,
    Paused,
    Running,
    ;

    /**
     * An explicit Start or Restart. It keeps a pause: starting the app must not
     * reopen the VPN listener the user closed on purpose.
     */
    val afterRequestRun: Lifecycle get() = if (this == Paused) Paused else Running

    /** An explicit Pause. Only a running daemon can be paused. */
    val afterPause: Lifecycle get() = if (this == Running) Paused else this

    /** An explicit Resume, or a firewall mode change; a stop is not revived. */
    val afterResume: Lifecycle get() = if (this == Stopped) Stopped else Running

    /** An explicit Stop wins over everything and clears the pause. */
    val afterStop: Lifecycle get() = Stopped
}

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
     * The last explicit lifecycle choice: started, paused or stopped. A fresh
     * install waits for Start; an install that already completed setup keeps its
     * choice across an update, so a deploy does not leave an unsupervised daemon
     * behind. Nothing else changes it: not opening the app, not a reboot, not a
     * settings change and not a sticky restart. The write is synchronous: a stop
     * that is followed by a hard process kill must not be lost in an apply().
     *
     * `user_wants_running` is the pre-pause key; it is read, never written again.
     */
    var lifecycle: Lifecycle
        get() {
            val stored = prefs.getString(LIFECYCLE, null)
                ?.let { name -> Lifecycle.entries.firstOrNull { it.name == name } }
            if (stored != null) return stored
            return if (prefs.getBoolean(USER_WANTS_RUNNING, setupComplete)) {
                Lifecycle.Running
            } else {
                Lifecycle.Stopped
            }
        }
        set(value) {
            prefs.edit().putString(LIFECYCLE, value.name).commit()
        }

    /** The daemon is this process's responsibility: started or paused. */
    val userWantsRunning: Boolean get() = lifecycle != Lifecycle.Stopped

    /** The firewall mode is on and the relay must stay closed. */
    val firewallPaused: Boolean get() = lifecycle == Lifecycle.Paused

    /** An explicit Start or Restart; it never resumes a pause (D22). */
    fun requestRun() {
        lifecycle = lifecycle.afterRequestRun
    }

    /** An explicit Pause of a daemon that runs; a stop is not a pause. */
    fun pauseLifecycle() {
        lifecycle = lifecycle.afterPause
    }

    /** An explicit Resume, or a firewall mode change: clears the pause. */
    fun resumeLifecycle() {
        lifecycle = lifecycle.afterResume
    }

    /** An explicit Stop wins over everything and clears the pause. */
    fun stopLifecycle() {
        lifecycle = lifecycle.afterStop
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

    /**
     * D25: hold the display awake while the app is on screen. Off by default; it
     * only sets a window flag, so it ends when the window is no longer visible and
     * it never overrides a manual lock.
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    /**
     * True once this build deployed the TLS daemon. A plain-ADB listener on the
     * loopback port is then never treated as a pre-TLS daemon to migrate.
     */
    var tlsDaemonDeployed: Boolean
        get() = prefs.getBoolean("tls_daemon_deployed", false)
        set(value) {
            prefs.edit().putBoolean("tls_daemon_deployed", value).commit()
        }

    /**
     * When the app last posted the pairing notification, or 0. Its reply action
     * is a mutable PendingIntent (RemoteInput needs one), so any app with
     * notification access can fire it; a reply is accepted only while a
     * notification the app posted is current (D9).
     */
    var pairingArmedAt: Long
        get() = prefs.getLong("pairing_armed_at", 0L)
        set(value) {
            prefs.edit().putLong("pairing_armed_at", value).commit()
        }

    /** D6: the last failure needs the user; a process restart does not retry it. */
    var failureNeedsUser: Boolean
        get() = prefs.getBoolean("failure_needs_user", false)
        set(value) = prefs.edit().putBoolean("failure_needs_user", value).apply()

    var remoteDir: String
        get() = prefs.getString("remote_dir", "/data/local/tmp/adb-anywhere") ?: "/data/local/tmp/adb-anywhere"
        set(value) = prefs.edit().putString("remote_dir", value.trim()).apply()

    /**
     * D5: the opt-in Start/Stop ownership of the system adbd. Off is the default:
     * the app never writes ADB_ENABLED, so Android keeps the paired key and the
     * USB recovery path. On turns ADB off on Stop and back on on Start.
     */
    var turnSystemAdbOffOnStop: Boolean
        get() = prefs.getBoolean("turn_system_adb_off_on_stop", false)
        set(value) = prefs.edit().putBoolean("turn_system_adb_off_on_stop", value).apply()

    var daemonFirewallMode: Boolean
        get() = prefs.getBoolean("daemon_firewall_mode", false)
        set(value) = prefs.edit().putBoolean("daemon_firewall_mode", value).apply()

    /** Debug-only VPN address override for the emulator e2e. */
    var debugVpnIp: String
        get() = prefs.getString("debug_vpn_ip", "") ?: ""
        set(value) = prefs.edit().putString("debug_vpn_ip", value).apply()

    var firewallEnabled: Boolean
        get() = prefs.getBoolean("firewall_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_enabled", value).apply()

    var firewallIpAllowlistEnabled: Boolean
        get() = prefs.getBoolean("firewall_ip_allowlist_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_ip_allowlist_enabled", value).apply()

    var firewallIpAllowlistJson: String
        get() = prefs.getString("firewall_ip_allowlist", "[]") ?: "[]"
        set(value) = prefs.edit().putString("firewall_ip_allowlist", value).apply()

    var firewallIpLastUsedJson: String
        get() = prefs.getString("firewall_ip_last_used", "{}") ?: "{}"
        set(value) = prefs.edit().putString("firewall_ip_last_used", value).apply()

    var firewallAppAllowlistEnabled: Boolean
        get() = prefs.getBoolean("firewall_app_allowlist_enabled", false)
        set(value) = prefs.edit().putBoolean("firewall_app_allowlist_enabled", value).apply()

    var firewallAppAllowlistJson: String
        get() = prefs.getString("firewall_app_allowlist", "[]") ?: "[]"
        set(value) = prefs.edit().putString("firewall_app_allowlist", value).apply()

    var firewallAppLastUsedJson: String
        get() = prefs.getString("firewall_app_last_used", "{}") ?: "{}"
        set(value) = prefs.edit().putString("firewall_app_last_used", value).apply()

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

    /**
     * D24: close the relay after this long without an adb command. Off by default;
     * it is the same pause the button takes, so it exists in firewall mode only.
     */
    var autoPauseEnabled: Boolean
        get() = prefs.getBoolean("auto_pause_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_pause_enabled", value).apply()

    var autoPauseTimeoutMs: Long
        get() = prefs.getLong("auto_pause_timeout_ms", AutoPause.DEFAULT_MS)
        set(value) = prefs.edit().putLong("auto_pause_timeout_ms", AutoPause.clamp(value)).apply()

    companion object {
        private const val LIFECYCLE = "lifecycle"
        private const val USER_WANTS_RUNNING = "user_wants_running"

        const val DEFAULT_LOOPBACK_PORT = 15037

        /**
         * The VPN listener's default: one above the standard adb port, so it is
         * as easy to remember as 5555 and still never the system adbd's own.
         */
        const val DEFAULT_VPN_PORT = 5556

        /** The legacy plain `adb tcpip` listener that the system adbd binds. */
        const val PLAIN_ADB_PORT = 5555

        /**
         * Android's wireless-debugging TLS server binds port 0, so the kernel
         * picks from the ephemeral range.  A custom loopback or VPN port at or
         * above this value can collide with it; the defaults and the UI keep both
         * below it.
         */
        const val EPHEMERAL_PORT_START = 32768

        /**
         * The one policy for the two ports the custom adb binds. The plain 5555 is
         * refused: the system adbd can hold it, so the custom adb would fail to
         * bind while the 5555 probe reported the system listener as ours.
         */
        fun validatePorts(loopback: Int, vpn: Int): String? {
            if (loopback !in 1024..65535) return "The loopback port must be between 1024 and 65535."
            if (vpn !in 1024..65535) return "The VPN port must be between 1024 and 65535."
            if (loopback == vpn) return "The two ports must be different."
            if (loopback == PLAIN_ADB_PORT || vpn == PLAIN_ADB_PORT) {
                return "Port $PLAIN_ADB_PORT belongs to the system adb's plain listener. " +
                    "Use another port so the app can never reach the system adb by mistake."
            }
            if (loopback >= EPHEMERAL_PORT_START || vpn >= EPHEMERAL_PORT_START) {
                return "Use ports below $EPHEMERAL_PORT_START (for example " +
                    "$DEFAULT_LOOPBACK_PORT/$DEFAULT_VPN_PORT): Android's wireless " +
                    "debugging picks its port from the ephemeral range, so a higher port " +
                    "can collide with it."
            }
            return null
        }
    }
}
