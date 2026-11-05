/*
 * DaemonManager - deploys the AOSP adbd, writes its key file, starts it
 * with the exact listener list and verifies the listeners.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import android.os.Build
import cz.slush.adbanywhere.data.AndroidPubKey
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

    fun writeKeys(
        shell: Shell,
        appKey: AndroidPubKey.Key,
        developers: List<DeveloperKey>,
    ) {
        shell.mkdirs(remoteDir)
        shell.writeTextFile(keysPath, KeyFiles.keysFile(appKey, developers))
        shell.chmod(keysPath, "600")
        AppLog.info("adb", "keys written (app key + ${developers.size} developer keys)")
    }

    fun deployAndStart(
        shell: Shell,
        appKey: AndroidPubKey.Key,
        developers: List<DeveloperKey>,
        vpnIp: String?,
        loopbackPort: Int,
        vpnPort: Int,
        exposeVpn: Boolean = true,
    ) {
        writeBinary(shell)
        writeKeys(shell, appKey, developers)
        stop(shell)
        val command = startCommand(shell, vpnIp, loopbackPort, vpnPort, exposeVpn)
        AppLog.info("adb", "starting ${Endpoint.listenSpec(loopbackPort, vpnIp, vpnPort, exposeVpn)}")
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
        appKey: AndroidPubKey.Key,
        developers: List<DeveloperKey>,
        vpnIp: String?,
        loopbackPort: Int,
        vpnPort: Int,
        exposeVpn: Boolean = true,
    ) {
        awaitRestartScripts(shell)
        writeBinary(shell)
        writeKeys(shell, appKey, developers)
        Thread.sleep(200)
        val start = startCommand(shell, vpnIp, loopbackPort, vpnPort, exposeVpn)
        AppLog.info(
            "adb",
            "restarting detached on ${Endpoint.listenSpec(loopbackPort, vpnIp, vpnPort, exposeVpn)}",
        )
        val scriptPath = "$remoteDir/adbd.restart.${System.nanoTime()}.sh"
        shell.writeTextFile(scriptPath, StaleDaemon.restartScript(start))
        shell.chmod(scriptPath, "700")
        val result = shell.exec(
            "setsid sh ${shell.quote(scriptPath)} </dev/null >/dev/null 2>&1 & echo \$!",
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
        val spec = Endpoint.listenSpec(loopbackPort, vpnIp, vpnPort, exposeVpn)
        return "setsid ${shell.quote(binaryPath)}" +
            " --listen ${shell.quote(spec)}" +
            " --keys ${shell.quote(keysPath)}" +
            " --log ${shell.quote(logPath)}" +
            " --pid-file ${shell.quote(pidPath)}" +
            " </dev/null >/dev/null 2>${shell.quote(remoteDir + "/adbd.stderr")} &"
    }

    fun stop(shell: Shell) {
        awaitRestartScripts(shell)
        // Kill every vendored adbd, not only the pid file PID: a process that
        // lost the bind race can have written the pid file last, and a daemon
        // started from another remote directory has a different pid file. The
        // /proc scan needs no ps/awk/xargs and no lock.
        AppLog.info("adb", "stopping every vendored adbd")
        shell.exec(StaleDaemon.stopScript(), 10_000).requireOk("stop vendored adbd")
        shell.exec("rm -f ${shell.quote(pidPath)}")
    }

    fun isRunning(shell: Shell, loopbackPort: Int): Boolean =
        shell.probe("127.0.0.1", loopbackPort)

    /**
     * Waits for a detached restart from an earlier process to finish. Its script
     * file exists while it runs, so the next start does not race its kill/start
     * pair. This is coordination, not a lock: it covers an app crash during a
     * restart, not two simultaneous starts.
     */
    private fun awaitRestartScripts(shell: Shell, timeoutMs: Long = 10_000) {
        val pattern = "${shell.quote(remoteDir)}/adbd.restart.*.sh"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && restartScriptRunning(shell, pattern)) {
            Thread.sleep(100)
        }
        if (restartScriptRunning(shell, pattern)) {
            throw IOException("a previous adbd restart is still running")
        }
    }

    private fun restartScriptRunning(shell: Shell, pattern: String): Boolean =
        shell.exec("ls $pattern 2>/dev/null").stdout.isNotBlank()

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
}
