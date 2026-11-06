/*
 * ForegroundAppMonitor - samples the resumed package with UsageStatsManager. It
 * polls faster while the relay has an external session or a prompt, and slower
 * otherwise. The module is advisory; screen off and keyguard clear the foreground,
 * and the app's own approval dialog does not replace it.
 */
package cz.slush.adbanywhere.firewall

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ForegroundAppMonitor(
    private val context: Context,
    promptActivity: String,
    private val active: () -> Boolean,
) {
    private val tracker = ForegroundTracker(context.packageName, promptActivity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ForegroundState())
    val state: StateFlow<ForegroundState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastQuery = 0L

    fun start() {
        if (job != null) return
        lastQuery = 0L
        job = scope.launch {
            while (true) {
                refresh()
                delay(if (active()) 1_000L else 5_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun refresh() {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        tracker.onInteractive(power?.isInteractive ?: true)
        tracker.onLocked(keyguard?.isKeyguardLocked ?: false)
        val now = System.currentTimeMillis()
        // The first sample must query even though no package is known yet:
        // the guard on the resolved foreground package kept the allowlist from
        // ever seeing one.
        val from = if (lastQuery == 0L) now - 3_600_000L else lastQuery
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val events = runCatching { usage?.queryEvents(from, now) }.getOrNull()
        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    tracker.onResumed(event.packageName, event.className)
                }
            }
        }
        lastQuery = now - 1_000L
        _state.value = tracker.state()
    }
}
