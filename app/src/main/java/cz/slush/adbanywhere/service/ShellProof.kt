/*
 * ShellProof - proves that an ADB endpoint runs commands as the shell user
 * before the app trusts it (docs/DESIGN.md I16).
 *
 * The system adbd's TLS key is random per process, so it cannot be pinned, and
 * a local app can advertise or squat a port the app probes. The proof asks the
 * endpoint to run `am broadcast` to [ShellProofReceiver], which is protected by
 * android.permission.DUMP. The shell user holds DUMP; a third-party app cannot.
 * Only an endpoint that really executes commands as shell (or root) can deliver
 * the nonce, and a squatter has no real adbd behind it to forward the command
 * to: the TLS client signature and the TLS-pinned daemon cannot be relayed.
 */
package cz.slush.adbanywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.slush.adbanywhere.adb.Shell
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShellProof {
    const val ACTION = "cz.slush.adbanywhere.SHELL_PROOF"
    const val EXTRA_NONCE = "nonce"
    private const val TIMEOUT_MS = 8_000L

    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val random = SecureRandom()

    /** True when [shell] delivered a fresh nonce through the DUMP-protected receiver. */
    fun verify(context: Context, shell: Shell, timeoutMs: Long = TIMEOUT_MS): Boolean {
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val latch = CountDownLatch(1)
        pending[nonce] = latch
        return try {
            val component = "${context.packageName}/${ShellProofReceiver::class.java.name}"
            val result = runCatching {
                shell.exec(
                    "am broadcast -n ${shell.quote(component)} -a $ACTION " +
                        "--es $EXTRA_NONCE $nonce",
                    timeoutMs,
                )
            }.getOrNull()
            if (result == null) {
                AppLog.warn("proof", "the endpoint did not run the proof command")
                return false
            }
            val delivered = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!delivered) AppLog.warn("proof", "the endpoint did not prove it runs as shell")
            delivered
        } finally {
            pending.remove(nonce)
        }
    }

    internal fun deliver(nonce: String?) {
        if (nonce == null) return
        pending[nonce]?.countDown()
    }
}

/**
 * Receives the nonce. The manifest protects it with android.permission.DUMP,
 * so only the shell user (or the system) can deliver it.
 */
class ShellProofReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ShellProof.ACTION) return
        ShellProof.deliver(intent.getStringExtra(ShellProof.EXTRA_NONCE))
    }
}
