package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AdbStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue(warnings.single().contains("adb_wifi_enabled=1"))
    }

    @Test
    fun warnsAboutTheOpenSystemListener() {
        val warnings = AdbStatus(0, 0, true).warnings(keepSystemAdbOff = true)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("127.0.0.1:5555"))
    }

    @Test
    fun warnsWhenKeepOffDoesNotHoldAdbEnabledDown() {
        val warnings = AdbStatus(1, 0, false).warnings(keepSystemAdbOff = true)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("adb_enabled=1"))
    }

    @Test
    fun acceptsAdbEnabledWhenKeepOffIsOff() {
        assertEquals(emptyList<String>(), AdbStatus(1, 0, false).warnings(keepSystemAdbOff = false))
    }
}
