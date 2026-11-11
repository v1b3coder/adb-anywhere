/*
 * SetupPolicy - the setup decisions that keep the trust rules, as pure
 * functions so each one is tested on its own (docs/DESIGN.md I14).
 */
package cz.slush.adbanywhere.service

import cz.slush.adbanywhere.adb.AdbKeyRejectedException
import cz.slush.adbanywhere.adb.AdbPlainTokenRefusedException
import cz.slush.adbanywhere.adb.AdbProtocolException
import cz.slush.adbanywhere.adb.AdbTls
import cz.slush.adbanywhere.adb.DaemonPortHeldException

/**
 * Who holds the loopback port. Verified: the pinned daemon key and ADB auth
 * inside it. Legacy: a plain-ADB daemon from a build before the TLS channel,
 * never seen since this build deployed one. Foreign: the holder proved another
 * identity (a pin mismatch, or a plain listener after the TLS daemon was
 * deployed); it is never trusted or signed for. Unreachable: the connection
 * failed without proving anything, such as a daemon that is exiting.
 */
enum class DaemonProbe { Verified, Legacy, Absent, Foreign, Unreachable }

object SetupPolicy {
    /** Classifies a failed connection to the loopback port. */
    fun classify(error: Throwable, tlsDaemonDeployed: Boolean): DaemonProbe = when {
        error is AdbPlainTokenRefusedException ->
            if (tlsDaemonDeployed) DaemonProbe.Foreign else DaemonProbe.Legacy
        AdbTls.identityFailure(error) != null -> DaemonProbe.Foreign
        // A key rejection behind the pin is the app's own daemon with a changed
        // key file: redeploying it is the recovery, not a foreign holder.
        error is AdbKeyRejectedException -> DaemonProbe.Unreachable
        error is AdbProtocolException -> DaemonProbe.Foreign
        else -> DaemonProbe.Unreachable
    }

    /**
     * Whether the bootstrap may sign a plain token on 5555. A pre-TLS daemon
     * trusts the system key over plain ADB, and with no `adb tcpip` listener
     * any app can bind 5555 and relay the token to it (I15).
     */
    fun plainSigningAllowed(probe: DaemonProbe): Boolean = probe != DaemonProbe.Legacy

    /**
     * True when setup must stop before the bootstrap: a holder with another
     * identity on the daemon's port. Only an explicit Start redeploys over it;
     * a boot, the watchdog or a network change must not reopen the system
     * wireless debugging for it on every retry (§9).
     */
    fun stopsBeforeBootstrap(probe: DaemonProbe, userInitiated: Boolean): Boolean =
        probe == DaemonProbe.Foreign && !userInitiated

    /** A port another process holds stays held; retrying cannot help (§9). */
    fun retryable(error: Throwable): Boolean = error !is DaemonPortHeldException

    /**
     * Whether a pairing reply from the notification is accepted. The reply
     * action is a mutable PendingIntent that any app with notification access
     * can fire, also after the notification is gone, so only a reply to a
     * notification posted within [windowMs] counts (D9).
     */
    fun pairingReplyAccepted(armedAt: Long, now: Long, windowMs: Long = PAIRING_REPLY_WINDOW_MS): Boolean =
        armedAt > 0 && now - armedAt in 0..windowMs

    const val PAIRING_REPLY_WINDOW_MS = 15 * 60_000L
}
