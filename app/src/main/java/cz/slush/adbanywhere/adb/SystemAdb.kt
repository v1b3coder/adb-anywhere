/*
 * SystemAdb - connects to the system adbd for bootstrap with the app's system
 * key (AppKeyStore "app"), which the patched daemon never trusts.
 *
 * The app signs only where a signature cannot be replayed (docs/DESIGN.md I15):
 * - Every candidate port except 5555 must speak TLS. A plain AUTH token there is
 *   refused unsigned, because a local app can advertise or squat such a port and
 *   relay the token from a real plain listener.
 * - 5555 is plain ADB and the last resort. It is not owned by construction:
 *   with no `adb tcpip` listener any app can bind it, and a plain signature
 *   there can be replayed to any plain listener that trusts the system key. The
 *   caller forbids it while such a listener exists (a pre-TLS daemon).
 *
 * A connected endpoint is trusted only after [ShellProof] shows that it runs
 * commands as the shell user; a squatter cannot deliver that broadcast.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.data.Prefs
import cz.slush.adbanywhere.data.WirelessDebuggingGuard
import cz.slush.adbanywhere.service.AppLog
import cz.slush.adbanywhere.service.ShellProof
import cz.slush.adbanywhere.service.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class SystemAdb(
    private val context: Context,
    private val keys: AppKeyStore,
    private val prefs: Prefs,
) {
    // libadb only pairs: SPAKE2 with the code the user read from Settings
    // authenticates that exchange. Every connection goes through AdbClient.
    private val pairing = AdbManager(context, keys)

    @Volatile
    private var client: AdbClient? = null

    /** The verified system shell. Throws when [ensureConnected] did not succeed. */
    val shell: Shell
        get() = Shell(client?.takeIf { it.isConnected } ?: throw IOException("Android's adb is not connected"))

    /** True when the verified system connection runs over the TLS listener. */
    val connectedOverTls: Boolean get() = client?.takeIf { it.isConnected }?.tls == true

    /** True when the last [ensureConnected] reached a listener that needs a pair. */
    @Volatile
    var pairingRequired = false
        private set

    suspend fun pair(code: PairingCode): Boolean = withContext(Dispatchers.IO) {
        pairing.pairTo("127.0.0.1", code.port, code.code)
    }

    fun disconnect() {
        client?.close()
        client = null
    }

    /**
     * Connects to the system adbd and proves it (I16). TLS candidates come first,
     * mDNS included: a TLS client signature cannot be replayed. The plain 5555
     * listener is the last resort and only with [allowPlain]: in the default
     * state (`is_usb=true`, no `adb tcpip`) 5555 is free and any app can bind
     * it, so a plain signature there goes to whoever holds it and can be
     * replayed to any plain listener that trusts the system key (I15).
     */
    suspend fun ensureConnected(allowPlain: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        pairingRequired = false
        if (client?.isConnected == true) return@withContext true
        val known = listOfNotNull(systemTlsPort(), prefs.lastTlsPort.takeIf { it > 0 })
        // Android 11-16 publish service.adb.tls.port to coredomain only; AOSP main
        // after Android 16 does not publish it at all. mDNS is the client path,
        // and it runs only when no known port connects.
        val discover = { kotlinx.coroutines.runBlocking { discoverTlsPorts() } }
        for ((port, mode) in attempts(known, discover, allowPlain)) {
            if (tryConnect(port, mode)) return@withContext true
        }
        if (!allowPlain && SystemSettings.isLocalPortOpen(Prefs.PLAIN_ADB_PORT)) {
            AppLog.warn("system", "not signing on 127.0.0.1:5555: a plain listener trusts the system key")
        }
        AppLog.info("system", "no verified listener found")
        false
    }

    private fun tryConnect(port: Int, mode: AdbMode): Boolean {
        if (port == prefs.loopbackPort || port == prefs.vpnPort) return false
        val connected = try {
            AdbClient.connect("127.0.0.1", port, keys.adbIdentity, mode)
        } catch (e: AdbPairingRequiredException) {
            pairingRequired = true
            AppLog.warn("system", "127.0.0.1:$port requires pairing")
            return false
        } catch (e: Exception) {
            AppLog.warn("system", "connect 127.0.0.1:$port failed: ${e.message}")
            return false
        }
        if (!ShellProof.verify(context, Shell(connected))) {
            AppLog.warn("system", "127.0.0.1:$port is not Android's adb; ignored")
            connected.close()
            return false
        }
        client = connected
        if (mode is AdbMode.Tls) prefs.lastTlsPort = port
        AppLog.info("system", "verified Android's adb on 127.0.0.1:$port")
        return true
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
        val current = client ?: return@withContext false
        runCatching {
            current.openStream("usb:").close()
            true
        }.getOrDefault(false).also { disconnect() }
    }

    /** The system adbd's own plain TCP port, read through the verified shell. */
    fun plainTcpPort(): Int? =
        runCatching { shell.exec("getprop service.adb.tcp.port").stdout.trim().toIntOrNull() }
            .getOrNull()?.takeIf { it in 1..65535 }

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
         * publishes on Android 11-16. The property is coredomain-only, so an app
         * usually reads null; it stays a cheap candidate on relaxed builds.
         */
        fun systemTlsPort(): Int? =
            WirelessDebuggingGuard.parseTlsPort(readProp("service.adb.tls.port"))

        /**
         * The system adbd's TLS port for the status card. mDNS is the client path;
         * every candidate must offer TLS. The probe never signs anything: it
         * sends CNXN and only looks for A_STLS. The result is display-only.
         */
        suspend fun detectTlsPort(
            context: Context,
            prefs: Prefs,
        ): Int? = withContext(Dispatchers.IO) {
            val candidates = LinkedHashSet<Int>()
            candidates += discoverTlsPorts(context, STATUS_MDNS_TIMEOUT_MS)
            systemTlsPort()?.let(candidates::add)
            candidates.remove(prefs.loopbackPort)
            candidates.remove(prefs.vpnPort)
            candidates.remove(Prefs.PLAIN_ADB_PORT)
            for (port in candidates) {
                if (AdbClient.offersTls("127.0.0.1", port)) {
                    AppLog.info("system", "wireless port $port")
                    return@withContext port
                }
            }
            null
        }

        suspend fun discoverTlsPorts(context: Context, timeoutMs: Long = MDNS_TIMEOUT_MS): List<Int> =
            withContext(Dispatchers.IO) {
                MdnsDiscovery(context).discoverAll("_adb-tls-connect._tcp", timeoutMs)
            }

        /**
         * The order the bootstrap tries the system adbd in. Every TLS candidate
         * comes first, the known ports and then the mDNS ones ([discover] runs
         * only when the known ones are exhausted): a TLS client signature cannot
         * be replayed. The plain 5555 listener is last and only with
         * [allowPlain] (I15). 5555 is never a TLS candidate.
         */
        internal fun attempts(
            known: List<Int>,
            discover: () -> List<Int>,
            allowPlain: Boolean,
        ): Sequence<Pair<Int, AdbMode>> = sequence {
            val tried = LinkedHashSet<Int>()
            for (port in known) {
                if (port != Prefs.PLAIN_ADB_PORT && tried.add(port)) yield(port to AdbMode.Tls())
            }
            for (port in discover()) {
                if (port != Prefs.PLAIN_ADB_PORT && tried.add(port)) yield(port to AdbMode.Tls())
            }
            if (allowPlain) yield(Prefs.PLAIN_ADB_PORT to AdbMode.Plain)
        }

        private const val MDNS_TIMEOUT_MS = 4_000L
        private const val STATUS_MDNS_TIMEOUT_MS = 2_000L

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
