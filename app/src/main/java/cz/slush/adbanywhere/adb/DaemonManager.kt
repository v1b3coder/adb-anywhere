/*
 * DaemonManager - deploys the AOSP adbd, writes its key files, starts it
 * with the exact listener list and verifies the listeners.
 *
 * The daemon's files (docs/DESIGN.md D20):
 * - adb_keys: the daemon key and the developer keys (--keys); ADB key auth.
 * - tls_clients: the daemon key only (--tls-clients); the only client allowed
 *   to finish the TLS handshake on the loopback listener.
 * - tls_key.pem: the TLS server key (--tls-key) whose public key the app pins.
 * Every file is written atomically with mode 600.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import android.os.Build
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.DaemonTlsKey
import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.KeyFiles
import cz.slush.adbanywhere.service.AppLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DaemonManager(private val context: Context, private val prefs: cz.slush.adbanywhere.data.Prefs) {
    val remoteDir: String get() = prefs.remoteDir
    val binaryPath: String get() = "$remoteDir/adbd"
    val keysPath: String get() = "$remoteDir/adb_keys"
    val tlsClientsPath: String get() = "$remoteDir/tls_clients"
    val tlsKeyPath: String get() = "$remoteDir/tls_key.pem"
    val logPath: String get() = "$remoteDir/adbd.log"
    val pidPath: String get() = "$remoteDir/adbd.pid"

    fun abi(): String? {
        val supported = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supported }
    }

    fun hasAsset(): Boolean {
        val abi = abi() ?: return false
        return runCatching { context.assets.open("adbd/$abi/adbd").close() }.isSuccess
    }

    fun writeBinary(shell: Shell) {
        val abi = abi() ?: throw IOException("unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val bytes = context.assets.open("adbd/$abi/adbd").use { it.readBytes() }
        shell.mkdirs(remoteDir)
        val remoteHash = shell.sha256(binaryPath)
        if (remoteHash == sha256(bytes)) {
            AppLog.info("adb", "binary already current (abi=$abi)")
            return
        }
        // Replace through a new inode so a running daemon never sees ETXTBSY.
        val temporary = "$binaryPath.new"
        shell.writeFile(temporary, bytes)
        shell.chmod(temporary, "755")
        shell.exec("mv -f ${shell.quote(temporary)} ${shell.quote(binaryPath)}")
            .requireOk("install daemon")
        val installedHash = shell.sha256(binaryPath)
        if (installedHash != sha256(bytes)) {
            throw IOException("daemon checksum mismatch on device")
        }
        AppLog.info("adb", "binary installed (abi=$abi, ${bytes.size} bytes)")
    }

    /** What the daemon must be started with; built by the service from its keys. */
    class Material(
        val daemonKey: AndroidPubKey.Key,
        val developers: List<DeveloperKey>,
        val tlsKey: DaemonTlsKey,
    ) {
        val keysFile: String get() = KeyFiles.keysFile(daemonKey, developers)
        val tlsClientsFile: String get() = KeyFiles.tlsClientsFile(daemonKey)
    }

    fun writeKeys(shell: Shell, material: Material) {
        shell.mkdirs(remoteDir)
        shell.writeTextFile(keysPath, material.keysFile)
        shell.writeTextFile(tlsClientsPath, material.tlsClientsFile)
        shell.writeTextFile(tlsKeyPath, material.tlsKey.pem)
        AppLog.info("adb", "keys written (daemon key + ${material.developers.size} developer keys)")
    }

    /**
     * True when the key files on the device are exactly what the app wrote. Any
     * process running as shell can edit them; the watchdog rewrites them and
     * reports the tampering instead of trusting the device copy.
     */
    fun keysIntact(shell: Shell, material: Material): Boolean =
        shell.sha256(keysPath) == sha256(material.keysFile.toByteArray()) &&
            shell.sha256(tlsClientsPath) == sha256(material.tlsClientsFile.toByteArray()) &&
            shell.sha256(tlsKeyPath) == sha256(material.tlsKey.pem.toByteArray())

    fun deployAndStart(
        shell: Shell,
        material: Material,
        vpnIp: String?,
        loopbackPort: Int,
        vpnPort: Int,
        exposeVpn: Boolean = true,
    ) {
        writeBinary(shell)
        writeKeys(shell, material)
        stop(shell)
        // Every vendored daemon is gone now, so a listener left on the loopback
        // port belongs to another process. The daemon would refuse to bind it;
        // say so instead of retrying a start that cannot succeed (§9).
        if (shell.probe(Endpoint.LOOPBACK, loopbackPort)) throw DaemonPortHeldException(loopbackPort)
        trimLogs(shell)
        val command = startCommand(shell, vpnIp, loopbackPort, vpnPort, exposeVpn)
        AppLog.info("adb", "starting ${Endpoint.listeners(loopbackPort, vpnIp, vpnPort, exposeVpn)}")
        val result = shell.exec(command, 10_000)
        if (!result.ok) {
            throw IOException("cannot start daemon: ${result.stderr.ifBlank { result.stdout }.trim()}")
        }
        waitForPort(shell, loopbackPort)
        val pid = shell.exec("cat ${shell.quote(pidPath)} 2>/dev/null").stdout.trim()
        if (pid.toLongOrNull() == null) {
            throw IOException("daemon did not write a pid file")
        }
        AppLog.info("adb", "started (pid=$pid)")
        if (exposeVpn && !vpnIp.isNullOrBlank() && !shell.probe(vpnIp, vpnPort)) {
            throw IOException("daemon did not bind $vpnIp:$vpnPort")
        }
    }

    /**
     * Restarts the daemon from its own shell. The kill and the new start run in a
     * detached script so the shell served by the old daemon can die safely.
     */
    fun restartDetached(
        shell: Shell,
        material: Material,
        vpnIp: String?,
        loopbackPort: Int,
        vpnPort: Int,
        exposeVpn: Boolean = true,
    ) {
        awaitRestartScripts(shell)
        writeBinary(shell)
        writeKeys(shell, material)
        trimLogs(shell)
        Thread.sleep(200)
        val start = startCommand(shell, vpnIp, loopbackPort, vpnPort, exposeVpn)
        AppLog.info(
            "adb",
            "restarting detached on ${Endpoint.listeners(loopbackPort, vpnIp, vpnPort, exposeVpn)}",
        )
        val pids = daemonPids(shell)
        val scriptPath = "$remoteDir/adbd.restart.${System.nanoTime()}.sh"
        val markerPath = scriptPath.removeSuffix(".sh") + ".pid"
        if (pids == null || pids.isEmpty()) {
            AppLog.warn("adb", "ps could not identify the daemon; using the /proc restart")
            shell.writeTextFile(scriptPath, StaleDaemon.restartScript(start))
        } else {
            AppLog.info("adb", "restarting pids=$pids")
            shell.writeTextFile(scriptPath, restartScript(pids, start))
        }
        shell.chmod(scriptPath, "700")
        val launch = "echo \$\$ > ${shell.quote(markerPath)}; exec sh ${shell.quote(scriptPath)}"
        val result = shell.exec(
            "setsid sh -c ${shell.quote(launch)} </dev/null >/dev/null 2>&1 & echo \$!",
            10_000,
        )
        if (!result.ok) {
            throw IOException("cannot restart daemon: ${result.stderr.ifBlank { result.stdout }.trim()}")
        }
        waitForRestart(shell, loopbackPort)
        if (exposeVpn && !vpnIp.isNullOrBlank() && !shell.probe(vpnIp, vpnPort)) {
            throw IOException("daemon did not bind $vpnIp:$vpnPort")
        }
    }

    /**
     * Waits for the old daemon to release the loopback port and the new one to
     * accept on it. Waiting only for an open port would return while the old
     * daemon still listens, and the caller would verify a process that is about to
     * die.
     */
    private fun waitForRestart(shell: Shell, port: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && shell.probe("127.0.0.1", port)) {
            Thread.sleep(50)
        }
        if (shell.probe("127.0.0.1", port)) {
            throw IOException("old adbd still listens on 127.0.0.1:$port")
        }
        waitForPort(shell, port, timeoutMs)
    }

    private fun startCommand(
        shell: Shell,
        vpnIp: String?,
        loopbackPort: Int,
        vpnPort: Int,
        exposeVpn: Boolean,
    ): String {
        val listeners = Endpoint.listeners(loopbackPort, vpnIp, vpnPort, exposeVpn)
        // The daemon refuses to start unless every listener binds, so a port held
        // by another process is an explicit error, never a partial start.
        val stderr = shell.quote("$remoteDir/adbd.stderr")
        // A file left by an older build keeps its mode across the redirect.
        return "umask 077; rm -f $stderr; setsid ${shell.quote(binaryPath)}" +
            " --tls-listen ${shell.quote(listeners.tls)}" +
            (listeners.plain?.let { " --listen ${shell.quote(it)}" } ?: "") +
            " --tls-key ${shell.quote(tlsKeyPath)}" +
            " --tls-clients ${shell.quote(tlsClientsPath)}" +
            " --keys ${shell.quote(keysPath)}" +
            " --log ${shell.quote(logPath)}" +
            " --pid-file ${shell.quote(pidPath)}" +
            " </dev/null >/dev/null 2>$stderr &"
    }

    fun stop(shell: Shell) {
        awaitRestartScripts(shell)
        val pids = daemonPids(shell)
        if (pids == null) {
            AppLog.warn("adb", "ps unavailable; using the /proc scan")
            shell.exec(StaleDaemon.stopScript(), 10_000).requireOk("stop vendored adbd")
        } else if (pids.isEmpty()) {
            AppLog.info("adb", "no vendored adbd found")
        } else {
            AppLog.info("adb", "stopping pids=$pids")
            for (pid in pids) shell.exec("kill -9 $pid 2>/dev/null || true")
            waitForPids(shell, pids)
        }
        shell.exec("rm -f ${shell.quote(pidPath)}")
    }

    /**
     * Bounds the daemon's log and stderr files before the daemon opens them.
     * The daemon appends through one descriptor, so the trim only works while
     * it is stopped; the daemon also rotates the log at startup.
     */
    fun trimLogs(shell: Shell) {
        for (path in listOf(logPath, "$remoteDir/adbd.stderr")) {
            val quoted = shell.quote(path)
            shell.exec(
                "test -f $quoted && [ \$(wc -c < $quoted) -gt $MAX_LOG_BYTES ] && " +
                    "tail -c $MAX_LOG_BYTES $quoted > $quoted.tmp && " +
                    "mv -f $quoted.tmp $quoted || true",
            )
        }
    }

    /**
     * Finds the vendored daemon through ps instead of scanning every /proc entry.
     * The app always starts it with --listen, and the system adbd does not accept
     * that option. Returns null when ps is unavailable, so the caller can fall
     * back to the /proc scan.
     */
    private fun daemonPids(shell: Shell): List<Int>? {
        val result = shell.exec("ps -A -o PID,NAME,ARGS", 10_000)
        if (!result.ok) return null
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size == 3 && parts[1] == "adbd" && StaleDaemon.isOurs(parts[2])) {
                parts[0].toIntOrNull()
            } else {
                null
            }
        }.distinct().toList()
    }

    private fun waitForPids(shell: Shell, pids: List<Int>, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var alive = pids
        while (System.currentTimeMillis() < deadline && alive.isNotEmpty()) {
            alive = alive.filter { pid ->
                shell.exec("test -s /proc/$pid/cmdline && echo yes || echo no").stdout.trim() == "yes"
            }
            if (alive.isNotEmpty()) Thread.sleep(50)
        }
        if (alive.isNotEmpty()) {
            AppLog.warn("adb", "pids $alive survived kill -9; using the /proc scan")
            shell.exec(StaleDaemon.stopScript(), 10_000).requireOk("stop vendored adbd")
        }
    }

    private fun restartScript(pids: List<Int>, start: String): String = """
        pids="${pids.joinToString(" ")}"
        kill -9 ${'$'}pids 2>/dev/null || true
        i=0
        while [ ${'$'}i -lt 30 ]; do
          alive=0
          for p in ${'$'}pids; do [ -s /proc/${'$'}p/cmdline ] && alive=1; done
          [ ${'$'}alive = 0 ] && break
          sleep 0.1
          i=${'$'}((i+1))
        done
        [ ${'$'}alive = 1 ] && exit 1
        sleep 0.2
        $start
        rm -f "${'$'}0" "${'$'}{0%.sh}.pid"
    """.trimIndent()

    fun isRunning(shell: Shell, loopbackPort: Int): Boolean =
        shell.probe("127.0.0.1", loopbackPort)

    /**
     * Waits for a detached restart from an earlier process to finish. The script
     * writes its PID next to itself before it kills, so a crash during a restart
     * is recovered instead of racing the next start. This is coordination, not a
     * lock: two simultaneous starts are still last-writer-wins.
     */
    private fun awaitRestartScripts(shell: Shell, timeoutMs: Long = 10_000) {
        val dir = shell.quote(remoteDir)
        val marker = "$dir/adbd.restart.*.pid"
        val script = "$dir/adbd.restart.*.sh"
        val alive = """
            for m in $marker; do
              [ -f "${'$'}m" ] || continue
              p=${'$'}(cat "${'$'}m" 2>/dev/null)
              [ -n "${'$'}p" ] && [ -d "/proc/${'$'}p" ] && exit 0
            done
            exit 1
        """.trimIndent()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!shell.exec(alive).ok) {
                shell.exec("rm -f $marker $script")
                return
            }
            Thread.sleep(100)
        }
        throw IOException("a previous adbd restart is still running")
    }

    fun logTail(shell: Shell, lines: Int = 120): String =
        runCatching { shell.exec("tail -n $lines ${shell.quote(logPath)}").stdout }.getOrDefault("")

    private fun waitForPort(shell: Shell, port: Int, timeoutMs: Long = 6_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (shell.probe("127.0.0.1", port)) return
            Thread.sleep(100)
        }
        val log = logTail(shell, 5)
        throw IOException("daemon did not listen on 127.0.0.1:$port${if (log.isBlank()) "" else ": ${log.trim()}"}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_LOG_BYTES = 256 * 1024
    }
}

/**
 * Another process holds a daemon port. Nothing is signed for or sent to it, and
 * retrying cannot help until the user changes the ports or reboots, so the
 * failure waits for the user instead of looping through the bootstrap (§9).
 */
class DaemonPortHeldException(port: Int) : java.io.IOException(
    "Another process holds 127.0.0.1:$port, the custom adb's port. Change the ports in " +
        "Settings or reboot, then press Start again.",
)
