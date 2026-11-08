/*
 * SystemAdb - connects to the system adbd for bootstrap. Tries the published
 * TLS port, the last known port, listening shell sockets, plain 5555 and mDNS.
 * The Settings status finds the TLS port live from the socket when the release
 * does not publish it.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.data.WirelessDebuggingGuard
import cz.slush.adbanywhere.service.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class SystemAdb(
    private val context: Context,
    keys: AppKeyStore,
    private val prefs: Prefs,
) {
    val manager = AdbManager(context, keys)
    val shell: Shell get() = Shell(manager)

    /** True when the last [ensureConnected] reached a listener that needs a pair. */
    @Volatile
    var pairingRequired = false
        private set

    suspend fun pair(code: PairingCode): Boolean = withContext(Dispatchers.IO) {
        manager.pairTo("127.0.0.1", code.port, code.code)
    }

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        pairingRequired = false
        if (manager.isConnected) return@withContext true

        // A previously authorized ADB channel can bootstrap the app when the
        // system wireless debugging UI is not reachable. This is the same path
        // as USB `adb tcpip` followed by `adb connect`, but the address is
        // supplied by the debug-only intent used in the end-to-end test. It is
        // one-shot: a stale forward must not be retried on every later setup.
        if (prefs.bootstrapHost.isNotBlank() && prefs.bootstrapPort > 0) {
            val host = prefs.bootstrapHost
            val port = prefs.bootstrapPort
            prefs.bootstrapHost = ""
            prefs.bootstrapPort = 0
            AppLog.info("system", "bootstrap $host:$port")
            if (manager.connectTo(host, port)) {
                AppLog.info("system", "bootstrap connected")
                return@withContext true
            }
            pairingRequired = pairingRequired || manager.pairingRequired
            AppLog.warn("system", "bootstrap failed")
        }

        val candidates = LinkedHashSet<Int>()
        systemTlsPort()?.let(candidates::add)
        prefs.lastTlsPort.takeIf { it > 0 }?.let(candidates::add)
        candidates += listeningShellPorts()
        candidates += 5555

        for (port in candidates) {
            if (port == prefs.loopbackPort || port == prefs.vpnPort) continue
            if (manager.connectTo("127.0.0.1", port)) {
                prefs.lastTlsPort = port
                AppLog.info("system", "connected on 127.0.0.1:$port")
                return@withContext true
            }
            pairingRequired = pairingRequired || manager.pairingRequired
        }

        // Android 11-16 publish service.adb.tls.port; AOSP main after Android 16
        // delivers the port to the framework over the auth socket instead. Keep
        // mDNS as a fallback: the settings write above is the primary bootstrap
        // and refreshes the advertisement.
        for (port in discoverTlsPorts()) {
            if (port == prefs.loopbackPort || port == prefs.vpnPort) continue
            if (manager.connectTo("127.0.0.1", port)) {
                prefs.lastTlsPort = port
                AppLog.info("system", "connected via mDNS on 127.0.0.1:$port")
                return@withContext true
            }
            pairingRequired = pairingRequired || manager.pairingRequired
        }
        AppLog.info("system", "no listener found on ports $candidates")
        false
    }

    suspend fun discoverTlsPorts(timeoutMs: Long = MDNS_TIMEOUT_MS): List<Int> =
        discoverTlsPorts(context, timeoutMs)

    /**
     * Closes the legacy plain `adb tcpip` listener on the system adbd. `usb:`
     * restarts the system adbd in USB mode and clears service.adb.tcp.port; the
     * app reconnects to the system adbd afterwards. The custom adbd is untouched
     * except that the restart may kill it, so the caller re-probes it.
     */
    suspend fun closePlainListener(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val stream = manager.openStream("usb:")
            stream.close()
            true
        }.getOrDefault(false)
    }

    fun settingsGet(key: String): String? {
        val result = shell.exec("settings get global $key")
        val value = result.stdout.trim()
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }

    fun settingsPut(key: String, value: String): Boolean =
        shell.exec("settings put global $key $value").ok

    fun grantSelfPermission(): Boolean {
        val result = shell.exec(
            "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
        )
        // A second grant is harmless; the permission check below is authoritative.
        if (!result.ok) {
            val check = shell.exec(
                "dumpsys package ${context.packageName} | grep WRITE_SECURE_SETTINGS",
            )
            return check.stdout.contains("granted=true")
        }
        return true
    }

    companion object {
        /**
         * The system wireless debugging TLS port, read from the property adbd
         * publishes on Android 11-16; null when the release does not publish it
         * or when adbd does not listen.
         */
        fun systemTlsPort(): Int? =
            WirelessDebuggingGuard.parseTlsPort(readProp("service.adb.tls.port"))

        /**
         * The system adbd's TLS port, live. The client path is mDNS, the same
         * advertisement the host `adb` uses. A release that does not advertise
         * it is probed on the socket: every shell-owned listening port is probed
         * with the app key, and the system adbd's TLS listener answers (or asks
         * to pair). The app's own ports and the plain 5555 are excluded by
         * port, so a plain listener is never reported as the system one.
         */
        suspend fun detectTlsPort(
            context: Context,
            keys: AppKeyStore,
            prefs: Prefs,
        ): Int? = withContext(Dispatchers.IO) {
            // mDNS is the client discovery path, the property is the cheap
            // candidate a relaxed build still publishes, and the shell-owned
            // listening ports are the socket fallback. Every candidate is
            // verified with the TLS handshake, so a stale advertisement or a
            // plain listener cannot be reported as the system TLS port.
            val candidates = LinkedHashSet<Int>()
            candidates += discoverTlsPorts(context, STATUS_MDNS_TIMEOUT_MS)
            systemTlsPort()?.let(candidates::add)
            candidates += listeningShellPorts()
            candidates.remove(prefs.loopbackPort)
            candidates.remove(prefs.vpnPort)
            // The plain 5555 listener is not the TLS server, and a probe of it
            // succeeds without TLS. Reporting it as the wireless port would show
            // the app's own endpoint as the system one.
            candidates.remove(Prefs.PLAIN_ADB_PORT)
            val manager = AdbManager(context, keys)
            manager.setTimeout(2, TimeUnit.SECONDS)
            for (port in candidates) {
                if (manager.connectTo("127.0.0.1", port) || manager.pairingRequired) {
                    manager.disconnectQuietly()
                    AppLog.info("system", "wireless port $port")
                    return@withContext port
                }
            }
            AppLog.info("system", "wireless port not found")
            null
        }

        suspend fun discoverTlsPorts(context: Context, timeoutMs: Long = MDNS_TIMEOUT_MS): List<Int> =
            withContext(Dispatchers.IO) {
                MdnsDiscovery(context).discoverAll("_adb-tls-connect._tcp", timeoutMs)
            }

        private const val MDNS_TIMEOUT_MS = 4_000L
        private const val STATUS_MDNS_TIMEOUT_MS = 2_000L

        private fun listeningShellPorts(): List<Int> {
            val ports = mutableListOf<Int>()
            for (file in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
                val lines = runCatching { File(file).readLines() }.getOrNull() ?: continue
                for (line in lines.drop(1)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 10 || parts[3] != "0A") continue
                    val port = parts[1].substringAfterLast(':').toIntOrNull(16) ?: continue
                    val uid = parts[7].toIntOrNull() ?: continue
                    if (uid != 2000) continue
                    if (port in 1024..65535) ports += port
                }
            }
            return ports.distinct()
        }

        private fun readProp(name: String): String? {
            val fromBinary = runCatching {
                val process = ProcessBuilder("/system/bin/getprop", name)
                    .redirectErrorStream(true)
                    .start()
                val value = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                value
            }.getOrNull()
            if (!fromBinary.isNullOrEmpty()) return fromBinary

            return runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getMethod("get", String::class.java)
                method.invoke(null, name) as? String
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}
