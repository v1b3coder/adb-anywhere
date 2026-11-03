package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.WirelessDebuggingGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WirelessDebuggingGuardTest {
    private class Fake(
        var enabled: Int = 0,
        var writeOk: Boolean = true,
        var writeApplies: Boolean = true,
        var tlsPort: Int? = null,
        var openReads: Int = 0,
    ) {
        var writes = 0
        var pauses = 0
        var probes = 0

        fun guard() = WirelessDebuggingGuard(
            readSetting = { enabled },
            writeDisabled = {
                writes++
                if (writeOk && writeApplies) enabled = 0
                writeOk
            },
            readTlsPort = { tlsPort },
            isPortOpen = {
                probes++
                openReads-- > 0
            },
            pause = { pauses++ },
        )
    }

    @Test
    fun rewritesTheSettingEvenWhenItAlreadyReadsOff() {
        val fake = Fake(enabled = 0)
        fake.guard().ensureOff()
        assertEquals(1, fake.writes)
    }

    @Test
    fun failsWhenTheSettingCannotBeWritten() {
        val fake = Fake(enabled = 1, writeOk = false)
        val error = assertThrows(IOException::class.java) { fake.guard().ensureOff() }
        assertTrue(error.message!!.contains("WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun failsWhenTheSettingStaysOn() {
        val fake = Fake(enabled = 1, writeApplies = false)
        val error = assertThrows(IOException::class.java) { fake.guard().ensureOff() }
        assertTrue(error.message!!.contains("adb_wifi_enabled=1"))
        assertEquals(WirelessDebuggingGuard.ATTEMPTS - 1, fake.pauses)
    }

    @Test
    fun failsWhileTheTlsListenerStaysOpen() {
        val fake = Fake(enabled = 0, tlsPort = 37123, openReads = Int.MAX_VALUE)
        val error = assertThrows(IOException::class.java) { fake.guard().ensureOff() }
        assertTrue(error.message!!.contains("service.adb.tls.port=37123"))
        assertTrue(error.message!!.contains("listening"))
        assertEquals(WirelessDebuggingGuard.ATTEMPTS - 1, fake.pauses)
    }

    @Test
    fun acceptsAStaleTlsPortWhenTheListenerIsClosed() {
        val fake = Fake(enabled = 0, tlsPort = 37123, openReads = 0)
        fake.guard().ensureOff()
        assertEquals(1, fake.probes)
    }

    @Test
    fun retriesUntilTheFrameworkClosesTheListener() {
        val fake = Fake(enabled = 0, tlsPort = 37123, openReads = 1)
        fake.guard().ensureOff()
        assertEquals(2, fake.probes)
        assertEquals(1, fake.pauses)
    }

    @Test
    fun doesNotProbeWithoutAPublishedPort() {
        val fake = Fake(enabled = 0, tlsPort = null, openReads = Int.MAX_VALUE)
        fake.guard().ensureOff()
        assertEquals(0, fake.probes)
    }

    @Test
    fun parsesOnlyUsableTlsPorts() {
        assertEquals(37123, WirelessDebuggingGuard.parseTlsPort("37123"))
        assertEquals(37123, WirelessDebuggingGuard.parseTlsPort(" 37123 "))
        assertNull(WirelessDebuggingGuard.parseTlsPort(""))
        assertNull(WirelessDebuggingGuard.parseTlsPort("0"))
        assertNull(WirelessDebuggingGuard.parseTlsPort("65536"))
        assertNull(WirelessDebuggingGuard.parseTlsPort("abc"))
        assertNull(WirelessDebuggingGuard.parseTlsPort(null))
    }
}
