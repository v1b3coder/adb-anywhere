/*
 * SystemAdb - connects to the system adbd for bootstrap. Tries the published
 * TLS port, the last known port, listening shell sockets, plain 5555 and mDNS.
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

class SystemAdb(
    private val context: Context,
    keys: AppKeyStore,
    private val prefs: Prefs,
) {
    val manager = AdbManager(context, keys)
    val shell: Shell get() = Shell(manager)

    suspend fun pair(code: PairingCode): Boolean = withContext(Dispatchers.IO) {
        manager.pairTo("127.0.0.1", code.port, code.code)
    }

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        if (manager.isConnected) return@withContext true

        // A previously authorized ADB channel can bootstrap the app when the
        // system wireless debugging UI is not reachable. This is the same path
        // as USB `adb tcpip` followed by `adb connect`, but the address is
        // supplied by the debug-only intent used in the end-to-end test.
        if (prefs.bootstrapHost.isNotBlank() && prefs.bootstrapPort > 0) {
            AppLog.info(
                "system",
                "bootstrap ${prefs.bootstrapHost}:${prefs.bootstrapPort}",
            )
            if (manager.connectTo(prefs.bootstrapHost, prefs.bootstrapPort)) {
                AppLog.info("system", "bootstrap connected")
                return@withContext true
            }
            AppLog.warn("system", "bootstrap failed")
        }

        val candidates = LinkedHashSet<Int>()
        readProp("service.adb.tls.port")?.toIntOrNull()?.let(candidates::add)
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
        }

        // Android 13+ no longer publishes service.adb.tls.port; the port is only
        // in the mDNS advertisement. Keep this as a fallback: the settings write
        // above is the primary bootstrap and refreshes the advertisement.
        for (port in discoverTlsPorts()) {
            if (port == prefs.loopbackPort || port == prefs.vpnPort) continue
            if (manager.connectTo("127.0.0.1", port)) {
                prefs.lastTlsPort = port
                AppLog.info("system", "connected via mDNS on 127.0.0.1:$port")
                return@withContext true
            }
        }
        AppLog.info("system", "no listener found on ports $candidates")
        false
    }

    suspend fun discoverTlsPorts(): List<Int> = withContext(Dispatchers.IO) {
        MdnsDiscovery(context).discoverAll("_adb-tls-connect._tcp")
    }

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

    /** The system wireless debugging TLS port; null when adbd does not listen. */
    fun tlsPort(): Int? = WirelessDebuggingGuard.parseTlsPort(readProp("service.adb.tls.port"))

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
}
