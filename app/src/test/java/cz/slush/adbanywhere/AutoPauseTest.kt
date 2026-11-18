/*
 * The idle timeout is a duration the user stores, so its formatting and range are
 * part of the settings contract.
 */
package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AutoPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPauseTest {
    @Test
    fun labelsMinutesAndHours() {
        assertEquals("1 min", AutoPause.label(1))
        assertEquals("10 min", AutoPause.label(10))
        assertEquals("59 min", AutoPause.label(59))
        assertEquals("1 h", AutoPause.label(60))
        assertEquals("1 h 30 min", AutoPause.label(90))
        assertEquals("12 h", AutoPause.label(AutoPause.MAX_MINUTES))
    }

    @Test
    fun clampsToTheSupportedRange() {
        assertEquals(AutoPause.MIN_MS, AutoPause.clamp(0))
        assertEquals(AutoPause.MIN_MS, AutoPause.clamp(-5_000))
        assertEquals(AutoPause.MAX_MS, AutoPause.clamp(Long.MAX_VALUE))
        assertEquals(AutoPause.DEFAULT_MS, AutoPause.clamp(AutoPause.DEFAULT_MS))
    }

    /** Every preset is a valid value and round-trips through the minutes it stores. */
    @Test
    fun presetsRoundTripThroughMinutes() {
        for (minutes in AutoPause.PRESETS_MINUTES) {
            val ms = AutoPause.millisOf(minutes)
            assertTrue("preset $minutes is a whole number of minutes", ms % 60_000L == 0L)
            assertEquals(minutes, AutoPause.minutesOf(ms))
            assertEquals(AutoPause.label(minutes), AutoPause.label(ms))
        }
    }
}
