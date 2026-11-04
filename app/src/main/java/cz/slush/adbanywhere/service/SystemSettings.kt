/*
 * SystemSettings - the app holds WRITE_SECURE_SETTINGS after the one-time ADB
 * grant, so it can re-assert the bootstrap state directly and does not need a
 * reachable shell to turn system adbd off again.
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

    fun setAdbEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, if (enabled) 1 else 0)
    }.getOrDefault(false)

    fun setAdbWifiEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        Settings.Global.putInt(
            context.contentResolver,
            "adb_wifi_enabled",
            if (enabled) 1 else 0,
        )
    }.getOrDefault(false)

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

    fun isWifiConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        }
        return false
    }
}
