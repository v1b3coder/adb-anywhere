package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AdbStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbStatusTest {
    @Test
    fun reportsNoWarningsWhenWirelessDebuggingIsOff() {
        assertEquals(emptyList<String>(), AdbStatus(0, 0, false).warnings())
    }

    @Test
    fun warnsAboutTheWirelessDebuggingSetting() {
        val warnings = AdbStatus(0, 1, false).warnings()
        assertEquals(1, warnings.size)
        assertEquals("Wireless debugging is on.", warnings.single())
    }

    @Test
    fun warnsAboutTheOpenSystemListener() {
        val warnings = AdbStatus(0, 0, true).warnings()
        assertEquals(1, warnings.size)
        assertEquals("Port 5555 is open: the system adb is listening.", warnings.single())
    }

    @Test
    fun acceptsTheSystemAdbOnUsb() {
        assertEquals(emptyList<String>(), AdbStatus(1, 0, false).warnings())
    }
}
