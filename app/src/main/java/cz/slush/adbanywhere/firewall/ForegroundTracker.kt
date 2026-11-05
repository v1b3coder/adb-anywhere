/*
 * ForegroundTracker - the pure part of the foreground-app allowlist. It keeps the
 * last resumed package and clears it while the screen is off or locked.
 */
package cz.slush.adbanywhere.firewall

data class ForegroundState(
    val packageName: String? = null,
    val interactive: Boolean = true,
    val locked: Boolean = false,
) {
    val foreground: String?
        get() = if (interactive && !locked) packageName else null
}

class ForegroundTracker {
    private var packageName: String? = null
    private var interactive = true
    private var locked = false

    fun onResumed(pkg: String) {
        packageName = pkg
    }

    fun onInteractive(value: Boolean) {
        interactive = value
    }

    fun onLocked(value: Boolean) {
        locked = value
    }

    fun state(): ForegroundState = ForegroundState(packageName, interactive, locked)
}
