/*
 * ForegroundTracker - the pure part of the foreground-app allowlist. It keeps the
 * last resumed package and clears it while the screen is off, the keyguard is
 * locked, or UsageStats is unavailable. The app's own approval dialog does not
 * replace it.
 */
package cz.slush.adbanywhere.firewall

data class ForegroundState(
    val packageName: String? = null,
    val interactive: Boolean = true,
    val locked: Boolean = false,
    /** False when PACKAGE_USAGE_STATS is missing or revoked; the allowlist denies. */
    val usageAvailable: Boolean = true,
) {
    val foreground: String?
        get() = if (interactive && !locked && usageAvailable) packageName else null
}

class ForegroundTracker(
    private val selfPackage: String? = null,
    promptActivity: String? = null,
) {
    private val prompt = promptActivity?.substringAfterLast('.')
    private var packageName: String? = null
    private var interactive = true
    private var locked = false
    private var usageAvailable = true

    /**
     * A call can raise the app's own approval dialog, so that dialog is not a
     * foreground app the user chose: it leaves the previous package in place.
     */
    fun onResumed(pkg: String, activity: String? = null) {
        if (pkg == selfPackage && activity?.substringAfterLast('.') == prompt) return
        packageName = pkg
    }

    fun onInteractive(value: Boolean) {
        interactive = value
    }

    fun onLocked(value: Boolean) {
        locked = value
    }

    /**
     * UsageStats can be revoked while the app runs. Without it the last resumed
     * package is not evidence that an allowed app is still open, so the tracker
     * clears the package and the allowlist denies.
     */
    fun onUsageAvailable(value: Boolean) {
        usageAvailable = value
        if (!value) packageName = null
    }

    fun state(): ForegroundState = ForegroundState(packageName, interactive, locked, usageAvailable)
}
