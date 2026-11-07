/*
 * SystemSettings - the app holds WRITE_SECURE_SETTINGS after the one-time ADB
 * grant, so it can re-assert the wireless-debugging bootstrap and hardening
 * settings directly. It writes ADB_ENABLED only when the user opts into the
 * Start/Stop ownership; the default never touches it, so Android keeps the paired
 * key and the USB recovery path.
 */
package cz.slush.adbanywhere.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import cz.slush.adbanywhere.adb.SystemAdb
import cz.slush.adbanywhere.data.AdbStatus
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.Prefs
import java.net.InetSocketAddress
import java.net.Socket

object SystemSettings {
    /** The two platform settings a setup attempt is allowed to change. */
    data class AdbState(val adbEnabled: Int, val adbWifiEnabled: Int)

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

    /** The platform state a failed setup restores. */
    fun state(context: Context): AdbState =
        AdbState(adbEnabled(context), adbWifiEnabled(context))

    /** Only the opt-in D5 ownership writes ADB_ENABLED; the default never does. */
    fun setAdbEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        Settings.Global.putInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED,
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
    suspend fun status(context: Context): AdbStatus {
        val prefs = Prefs(context)
        return AdbStatus(
            adbEnabled = adbEnabled(context),
            adbWifiEnabled = adbWifiEnabled(context),
            port5555Open = isLocalPortOpen(Prefs.PLAIN_ADB_PORT),
            wirelessPort = systemWirelessPort(context, prefs),
        )
    }

    /**
     * The system adbd's wireless-debugging port while it listens. Android 11-16
     * publish service.adb.tls.port. AOSP main after Android 16 stopped
     * publishing it, so the port is found live from the socket: only the system
     * adbd's TLS listener answers the app key. The port remembered from the last
     * bootstrap is the last fallback. A port that belongs to the custom adb is
     * never reported: it is our listener, not the system one.
     */
    private suspend fun systemWirelessPort(context: Context, prefs: Prefs): Int {
        val port = SystemAdb.detectTlsPort(context, AppKeyStore(context), prefs)
            ?: prefs.lastTlsPort
        if (port <= 0 || port == prefs.loopbackPort || port == prefs.vpnPort) return 0
        return if (isLocalPortOpen(port)) port else 0
    }

    /** A connect is enough: adbd listens on 5555 and never answers a probe. */
    fun isLocalPortOpen(port: Int): Boolean = isPortOpen("127.0.0.1", port)

    fun isPortOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
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
