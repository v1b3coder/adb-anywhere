package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AdbLifecycle
import cz.slush.adbanywhere.data.AdbSetting
import cz.slush.adbanywhere.data.AdbState
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbLifecycleTest {
    @Test
    fun startWithPatchedAdbdDownEnablesAdbAndWifi() {
        assertEquals(
            AdbState(adbEnabled = 1, adbWifiEnabled = 1),
            AdbLifecycle.start(AdbState(0, 0), patchedAdbdAlive = false, wifiConnected = true),
        )
    }

    @Test
    fun startWithPatchedAdbdDownAndNoWifiLeavesWifiOff() {
        assertEquals(
            AdbState(adbEnabled = 1, adbWifiEnabled = 0),
            AdbLifecycle.start(AdbState(0, 0), patchedAdbdAlive = false, wifiConnected = false),
        )
    }

    @Test
    fun startWithPatchedAdbdUpKeepsAdbAndTurnsWifiOff() {
        for (adbEnabled in listOf(0, 1)) {
            assertEquals(
                AdbState(adbEnabled = adbEnabled, adbWifiEnabled = 0),
                AdbLifecycle.start(
                    AdbState(adbEnabled, adbWifiEnabled = 1),
                    patchedAdbdAlive = true,
                    wifiConnected = true,
                ),
            )
        }
    }

    @Test
    fun stopTurnsAdbAndWifiOff() {
        assertEquals(
            AdbState(adbEnabled = 0, adbWifiEnabled = 0),
            AdbLifecycle.stop(),
        )
    }

    @Test
    fun bootAfterStartEnablesAdbAndWifiOnWifi() {
        assertEquals(
            AdbState(adbEnabled = 1, adbWifiEnabled = 1),
            AdbLifecycle.boot(userWantsRunning = true, wifiConnected = true),
        )
    }

    @Test
    fun bootAfterStartWithoutWifiLeavesWifiOff() {
        assertEquals(
            AdbState(adbEnabled = 1, adbWifiEnabled = 0),
            AdbLifecycle.boot(userWantsRunning = true, wifiConnected = false),
        )
    }

    @Test
    fun bootAfterStopTurnsAdbAndWifiOff() {
        assertEquals(
            AdbState(adbEnabled = 0, adbWifiEnabled = 0),
            AdbLifecycle.boot(userWantsRunning = false, wifiConnected = true),
        )
    }

    @Test
    fun changesAreEmptyWhenTheStateAlreadyMatches() {
        val state = AdbState(adbEnabled = 1, adbWifiEnabled = 0)
        assertEquals(emptyList<AdbSetting>(), AdbLifecycle.changes(state, state))
    }

    @Test
    fun startIsNoOpWhenTheSystemAdbIsAlreadyOnWifi() {
        val state = AdbState(adbEnabled = 1, adbWifiEnabled = 1)
        val desired = AdbLifecycle.start(state, patchedAdbdAlive = false, wifiConnected = true)
        assertEquals(state, desired)
        assertEquals(emptyList<AdbSetting>(), AdbLifecycle.changes(state, desired))
    }

    @Test
    fun stopIsNoOpWhenTheSystemAdbIsAlreadyOff() {
        val state = AdbState(adbEnabled = 0, adbWifiEnabled = 0)
        assertEquals(state, AdbLifecycle.stop())
        assertEquals(emptyList<AdbSetting>(), AdbLifecycle.changes(state, AdbLifecycle.stop()))
    }

    @Test
    fun changesListOnlyDifferingSettings() {
        assertEquals(
            listOf(AdbSetting.ADB_ENABLED, AdbSetting.ADB_WIFI_ENABLED),
            AdbLifecycle.changes(
                AdbState(adbEnabled = 0, adbWifiEnabled = 0),
                AdbState(adbEnabled = 1, adbWifiEnabled = 1),
            ),
        )
        assertEquals(
            listOf(AdbSetting.ADB_WIFI_ENABLED),
            AdbLifecycle.changes(
                AdbState(adbEnabled = 1, adbWifiEnabled = 0),
                AdbState(adbEnabled = 1, adbWifiEnabled = 1),
            ),
        )
    }
}
