/*
 * SystemAdb - connects to the system adbd for bootstrap. Tries the published
 * TLS port, the last known port, listening shell sockets, plain 5555 and mDNS.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.data.Prefs
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

        val candidates = LinkedHashSet<Int>()
        readProp("service.adb.tls.port")?.toIntOrNull()?.let(candidates::add)
        prefs.lastTlsPort.takeIf { it > 0 }?.let(candidates::add)
        candidates += listeningShellPorts()
        candidates += 5555

        for (port in candidates) {
            if (port == prefs.loopbackPort || port == prefs.vpnPort) continue
            if (manager.connectTo("127.0.0.1", port)) {
                prefs.lastTlsPort = port
                return@withContext true
            }
        }

        val mdnsPort = MdnsDiscovery(context).discover("_adb-tls-connect._tcp")
        if (mdnsPort != null && manager.connectTo("127.0.0.1", mdnsPort)) {
            prefs.lastTlsPort = mdnsPort
            return@withContext true
        }
        false
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
