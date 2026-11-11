package cz.slush.adbanywhere

import cz.slush.adbanywhere.adb.AdbKeyRejectedException
import cz.slush.adbanywhere.adb.AdbMode
import cz.slush.adbanywhere.adb.AdbPlainTokenRefusedException
import cz.slush.adbanywhere.adb.AdbProtocolException
import cz.slush.adbanywhere.adb.AdbServerIdentityException
import cz.slush.adbanywhere.adb.DaemonPortHeldException
import cz.slush.adbanywhere.adb.SystemAdb
import cz.slush.adbanywhere.service.DaemonProbe
import cz.slush.adbanywhere.service.SetupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/** The setup decisions that keep I15/I16 and the §9 failure policy. */
class SetupPolicyTest {
    @Test
    fun theBootstrapTriesEveryTlsCandidateBeforeThePlainListener() {
        var discovered = false
        val order = SystemAdb.attempts(listOf(40000, 5555, 40000), { discovered = true; listOf(41000, 40000, 5555) }, true)
            .map { (port, mode) -> port to (mode is AdbMode.Plain) }
            .toList()
        assertEquals(listOf(40000 to false, 41000 to false, 5555 to true), order)
        assertTrue(discovered)
    }

    @Test
    fun mdnsRunsOnlyWhenTheKnownPortsAreExhausted() {
        var discovered = false
        val first = SystemAdb.attempts(listOf(40000), { discovered = true; listOf(41000) }, true).first()
        assertEquals(40000, first.first)
        assertFalse(discovered)
    }

    @Test
    fun noPlainSignatureWhileAPreTlsDaemonTrustsTheSystemKey() {
        // A free 5555 can be bound by any app, which would relay the token to the
        // pre-TLS daemon: with a Legacy probe the plain listener is never tried.
        val allowPlain = SetupPolicy.plainSigningAllowed(DaemonProbe.Legacy)
        assertFalse(allowPlain)
        val modes = SystemAdb.attempts(emptyList(), { listOf(41000) }, allowPlain).map { it.second }.toList()
        assertTrue(modes.none { it is AdbMode.Plain })
        for (probe in DaemonProbe.entries - DaemonProbe.Legacy) assertTrue(SetupPolicy.plainSigningAllowed(probe))
    }

    @Test
    fun onlyAProvenOtherIdentityIsForeign() {
        val pin = SSLHandshakeException("pin").apply { initCause(AdbServerIdentityException("other key")) }
        assertEquals(DaemonProbe.Foreign, SetupPolicy.classify(pin, tlsDaemonDeployed = true))
        assertEquals(DaemonProbe.Foreign, SetupPolicy.classify(AdbProtocolException("no STLS"), true))
        assertEquals(DaemonProbe.Foreign, SetupPolicy.classify(AdbPlainTokenRefusedException(), true))
        assertEquals(DaemonProbe.Legacy, SetupPolicy.classify(AdbPlainTokenRefusedException(), false))
        // Behind the pin it is the app's own daemon: redeploy, do not give up.
        assertEquals(DaemonProbe.Unreachable, SetupPolicy.classify(AdbKeyRejectedException(), true))
        assertEquals(DaemonProbe.Unreachable, SetupPolicy.classify(EOFException("closed"), true))
        assertEquals(DaemonProbe.Unreachable, SetupPolicy.classify(java.net.SocketTimeoutException(), true))
    }

    @Test
    fun aForeignHolderStopsEveryAutomaticSetupBeforeTheBootstrap() {
        assertTrue(SetupPolicy.stopsBeforeBootstrap(DaemonProbe.Foreign, userInitiated = false))
        assertFalse(SetupPolicy.stopsBeforeBootstrap(DaemonProbe.Foreign, userInitiated = true))
        for (probe in DaemonProbe.entries - DaemonProbe.Foreign) {
            assertFalse(SetupPolicy.stopsBeforeBootstrap(probe, userInitiated = false))
        }
    }

    @Test
    fun aHeldPortWaitsForTheUser() {
        assertFalse(SetupPolicy.retryable(DaemonPortHeldException(27042)))
        assertTrue(SetupPolicy.retryable(IOException("daemon did not listen")))
    }

    @Test
    fun aPairingReplyCountsOnlyForACurrentNotification() {
        val now = 1_000_000_000L
        assertTrue(SetupPolicy.pairingReplyAccepted(now - 60_000, now))
        assertFalse(SetupPolicy.pairingReplyAccepted(0, now))
        assertFalse(SetupPolicy.pairingReplyAccepted(now - SetupPolicy.PAIRING_REPLY_WINDOW_MS - 1, now))
        assertFalse(SetupPolicy.pairingReplyAccepted(now + 60_000, now))
    }
}
