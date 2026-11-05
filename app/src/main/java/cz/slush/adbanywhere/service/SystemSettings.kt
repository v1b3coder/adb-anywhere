/*
 * SystemSettings - the app holds WRITE_SECURE_SETTINGS after the one-time ADB
 * grant, so it can re-assert the wireless-debugging bootstrap and hardening
 * settings directly. It reads ADB_ENABLED but never writes it: stopping the system
 * adbd would also kill the patched adbd on devices that share its cgroup.
 */
package cz.slush.adbanywhere.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import cz.slush.adbanywhere.data.AdbStatus
import java.net.InetSocketAddress
import java.net.Socket

object SystemSettings {
    fun adbEnabled(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)

    fun adbWifiEnabled(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)

    fun setAdbWifiEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        Settings.Global.putInt(
            context.contentResolver,
            "adb_wifi_enabled",
            if (enabled) 1 else 0,
        )
    }.getOrDefault(false)

    /**
     * DESIGN §7: 0 removes the framework's wireless-debugging timeout, so the
     * bootstrap path survives while the app is in the background. The key is
     * @hide, so it is addressed by name like adb_wifi_enabled; the read-back
     * rejects a write an OEM ignored.
     */
    fun ensureNoAdbConnectionTimeout(context: Context): Boolean {
        val resolver = context.contentResolver
        fun current(): Int =
            runCatching { Settings.Global.getInt(resolver, ADB_ALLOWED_CONNECTION_TIME, -1) }
                .getOrDefault(-1)
        if (current() == 0) return true
        runCatching { Settings.Global.putInt(resolver, ADB_ALLOWED_CONNECTION_TIME, 0) }
        return current() == 0
    }

    /** D11: the state the Settings screen reports, read from the platform. */
    fun status(context: Context): AdbStatus = AdbStatus(
        adbEnabled = adbEnabled(context),
        adbWifiEnabled = adbWifiEnabled(context),
        port5555Open = isLocalPortOpen(5555),
    )

    /** A connect is enough: adbd listens on 5555 and never answers a probe. */
    fun isLocalPortOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    private const val PROBE_TIMEOUT_MS = 300
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    fun isWifiConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        }
        return false
    }
}
