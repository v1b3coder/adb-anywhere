/*
 * AutoPause - the idle timeout that turns an unused relay into a pause. It is a
 * duration, not a clock time: the relay closes after this long without an adb
 * command from a developer machine (D24).
 */
package cz.slush.adbanywhere.data

object AutoPause {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 12 * 60
    const val DEFAULT_MINUTES = 10
    const val MIN_MS = MIN_MINUTES * 60_000L
    const val MAX_MS = MAX_MINUTES * 60_000L
    const val DEFAULT_MS = DEFAULT_MINUTES * 60_000L

    /** The one-tap choices in Settings; the wheel covers everything else. */
    val PRESETS_MINUTES = listOf(5, 10, 30, 60, 120, 240)

    fun clamp(ms: Long): Long = ms.coerceIn(MIN_MS, MAX_MS)

    fun minutesOf(ms: Long): Int = (clamp(ms) / 60_000L).toInt()

    fun millisOf(minutes: Int): Long = clamp(minutes * 60_000L)

    /** "10 min", "1 h", "1 h 30 min". */
    fun label(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    fun label(ms: Long): String = label(minutesOf(ms))
}
