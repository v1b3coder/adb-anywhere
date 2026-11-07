package cz.slush.adbanywhere

import cz.slush.adbanywhere.ui.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun formatsLastUsedAsRelativeAge() {
        assertEquals("never", relativeTime(0L, now))
        assertEquals("just now", relativeTime(now - 30_000L, now))
        assertEquals("1 minute ago", relativeTime(now - minute, now))
        assertEquals("2 hours ago", relativeTime(now - 2 * hour, now))
        assertEquals("3 days ago", relativeTime(now - 3 * day, now))
        assertEquals("2 weeks ago", relativeTime(now - 14 * day, now))
        assertEquals("2 months ago", relativeTime(now - 60 * day, now))
        assertEquals("1 year ago", relativeTime(now - 365 * day, now))
    }
}
