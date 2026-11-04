package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AdbStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbStatusTest {
    @Test
    fun reportsNoWarningsWhenEverythingIsOff() {
        assertEquals(emptyList<String>(), AdbStatus(0, 0, false).warnings(keepSystemAdbOff = true))
    }

    @Test
    fun warnsAboutTheWirelessDebuggingSetting() {
        val warnings = AdbStatus(0, 1, false).warnings(keepSystemAdbOff = true)
        assertEquals(1, warnings.size)
        assertEquals("Wireless debugging is on.", warnings.single())
    }

    @Test
    fun warnsAboutTheOpenSystemListener() {
        // The 5555 warning must survive either value of "Keep system adb off" (P0-6).
        for (keepOff in listOf(true, false)) {
            val warnings = AdbStatus(0, 0, true).warnings(keepSystemAdbOff = keepOff)
            assertEquals(1, warnings.size)
            assertEquals("Port 5555 is open: the system adb is listening.", warnings.single())
        }
    }

    @Test
    fun warnsWhenKeepOffDoesNotHoldAdbEnabledDown() {
        val warnings = AdbStatus(1, 0, false).warnings(keepSystemAdbOff = true)
        assertEquals(1, warnings.size)
        assertEquals(
            "The system adb is still on although \"Keep system adb off\" is enabled.",
            warnings.single(),
        )
    }

    @Test
    fun acceptsAdbEnabledWhenKeepOffIsOff() {
        assertEquals(emptyList<String>(), AdbStatus(1, 0, false).warnings(keepSystemAdbOff = false))
    }
}
