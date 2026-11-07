/*
 * RelativeTime - compact age labels for firewall filter last-use timestamps.
 */
package cz.slush.adbanywhere.ui

internal fun relativeTime(lastUsed: Long, now: Long = System.currentTimeMillis()): String {
    if (lastUsed <= 0L) return "never"
    val minutes = (now - lastUsed).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> plural(minutes, "minute")
        minutes < 24L * 60L -> plural(minutes / 60L, "hour")
        minutes < 7L * 24L * 60L -> plural(minutes / (24L * 60L), "day")
        minutes < 30L * 24L * 60L -> plural(minutes / (7L * 24L * 60L), "week")
        minutes < 365L * 24L * 60L -> plural(minutes / (30L * 24L * 60L), "month")
        else -> plural(minutes / (365L * 24L * 60L), "year")
    }
}

private fun plural(value: Long, unit: String): String =
    "$value $unit${if (value == 1L) "" else "s"} ago"
