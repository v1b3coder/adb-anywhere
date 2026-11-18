/*
 * LifecycleTest - D22: the persisted lifecycle intent has three states and only
 * an explicit user action moves between them.
 */
package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleTest {
    /**
     * A Start or Restart is not a Resume. Reopening the VPN listener because the
     * daemon was restarted would undo the security choice a pause records, so a
     * paused app stays paused until the user resumes it or turns the mode off.
     */
    @Test
    fun requestRunKeepsAPause() {
        assertEquals(Lifecycle.Running, Lifecycle.Stopped.afterRequestRun)
        assertEquals(Lifecycle.Paused, Lifecycle.Paused.afterRequestRun)
        assertEquals(Lifecycle.Running, Lifecycle.Running.afterRequestRun)
    }

    /** Only a running daemon can be paused; a stopped app has no relay to close. */
    @Test
    fun pauseNeedsARunningDaemon() {
        assertEquals(Lifecycle.Stopped, Lifecycle.Stopped.afterPause)
        assertEquals(Lifecycle.Paused, Lifecycle.Paused.afterPause)
        assertEquals(Lifecycle.Paused, Lifecycle.Running.afterPause)
    }

    /** Resume reopens the firewall; it must never start a stopped app by itself. */
    @Test
    fun resumeNeverStartsAStoppedApp() {
        assertEquals(Lifecycle.Stopped, Lifecycle.Stopped.afterResume)
        assertEquals(Lifecycle.Running, Lifecycle.Paused.afterResume)
        assertEquals(Lifecycle.Running, Lifecycle.Running.afterResume)
    }

    /** Stop is the full teardown: it clears a pause instead of remembering it. */
    @Test
    fun stopClearsEveryState() {
        assertEquals(Lifecycle.Stopped, Lifecycle.Stopped.afterStop)
        assertEquals(Lifecycle.Stopped, Lifecycle.Paused.afterStop)
        assertEquals(Lifecycle.Stopped, Lifecycle.Running.afterStop)
    }
}
