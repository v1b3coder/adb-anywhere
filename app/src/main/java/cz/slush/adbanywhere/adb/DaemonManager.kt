/*
 * DaemonManager - deploys the minimal adbd, writes keys and policy, starts it
 * with the exact listener list and verifies the listeners.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import android.os.Build
import android.util.Log
import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.KeyFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DaemonManager(private val context: Context) {
    val remoteDir = "/data/local/tmp/adb-anywhere"
    val binaryPath = "$remoteDir/adbd"
    val keysPath = "$remoteDir/adb_keys"
    val policyPath = "$remoteDir/policy.tsv"
    val logPath = "$remoteDir/adbd.log"
    val pidPath = "$remoteDir/adbd.pid"

    fun abi(): String? {
        val supported = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supported }
    }

    fun hasAsset(): Boolean {
        val abi = abi() ?: return false
        return runCatching { context.assets.open("adbd/$abi/adbd").close() }.isSuccess
    }

    fun deployAndStart(
        shell: Shell,
        appKey: AndroidPubKey.Key,
        developers: List<DeveloperKey>,
        vpnIp: String?,
        loopbackPort: Int,
        vpnPort: Int,
    ) {
        val abi = abi() ?: throw IOException("unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val bytes = context.assets.open("adbd/$abi/adbd").use { it.readBytes() }

        shell.mkdirs(remoteDir)
        shell.writeFile(binaryPath, bytes)
        shell.chmod(binaryPath, "755")
        shell.writeFile(keysPath, KeyFiles.keysFile(appKey, developers).toByteArray())
        shell.chmod(keysPath, "600")
        shell.writeFile(policyPath, KeyFiles.policyFile(appKey, developers).toByteArray())
        shell.chmod(policyPath, "600")

        Log.i("AdbAnywhere", "verify checksum")
        val remoteHash = shell.sha256(binaryPath)
        if (remoteHash != sha256(bytes)) {
            throw IOException("daemon checksum mismatch on device")
        }

        Log.i("AdbAnywhere", "stop old daemon")
        stop(shell)
        loopbackPortForStop = loopbackPort
        val spec = Endpoint.listenSpec(loopbackPort, vpnIp, vpnPort)
        val command = "setsid ${shell.quote(binaryPath)}" +
            " --listen ${shell.quote(spec)}" +
            " --keys ${shell.quote(keysPath)}" +
            " --policy ${shell.quote(policyPath)}" +
            " --log ${shell.quote(logPath)}" +
            " --pid-file ${shell.quote(pidPath)}" +
            " </dev/null >/dev/null 2>${shell.quote(remoteDir + "/adbd.stderr")} & echo \$!"
        Log.i("AdbAnywhere", "start daemon: $command")
        val result = shell.exec(command, 10_000)
        Log.i("AdbAnywhere", "start result rc=${result.exitCode} out=${result.stdout.trim()} err=${result.stderr.trim()}")
        if (!result.ok) {
            throw IOException("cannot start daemon: ${result.stderr.ifBlank { result.stdout }.trim()}")
        }
        Log.i("AdbAnywhere", "wait for loopback")
        waitForPort(shell, loopbackPort)
        val pid = shell.exec("cat ${shell.quote(pidPath)} 2>/dev/null").stdout.trim()
        if (pid.toLongOrNull() == null) {
            throw IOException("daemon did not write a pid file")
        }
        Log.i("AdbAnywhere", "daemon pid=$pid")
        if (!vpnIp.isNullOrBlank() && !shell.probe(vpnIp, vpnPort)) {
            throw IOException("daemon did not bind $vpnIp:$vpnPort")
        }
    }

    fun stop(shell: Shell) {
        val pid = shell.exec("cat ${shell.quote(pidPath)} 2>/dev/null").stdout.trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() }) {
            shell.exec("kill $pid 2>/dev/null || true")
        }
        // Also stop a stale instance that lost its pid file. Filter on the
        // process name so the shell running this command is never matched.
        shell.exec(
            "ps -A -o PID,NAME,ARGS | awk '\$2==\"adbd\" && /--listen/ {print \$1}' | " +
                "xargs -r kill 2>/dev/null || true",
        )
        shell.exec("rm -f ${shell.quote(pidPath)}")
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && shell.probe("127.0.0.1", loopbackPortForStop)) {
            Thread.sleep(50)
        }
    }

    fun isRunning(shell: Shell, loopbackPort: Int): Boolean =
        shell.probe("127.0.0.1", loopbackPort)

    private var loopbackPortForStop: Int = 15037

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

    companion object {
        private const val TAG = "AdbAnywhere"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
