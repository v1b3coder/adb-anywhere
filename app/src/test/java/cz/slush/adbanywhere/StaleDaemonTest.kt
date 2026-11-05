package cz.slush.adbanywhere

import cz.slush.adbanywhere.adb.StaleDaemon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleDaemonTest {
    @Test
    fun killScriptFindsOursWithoutMatchingItself() {
        val script = StaleDaemon.killFunction()
        assertTrue(script.contains("/proc/[0-9]*"))
        assertTrue(script.contains("kill -9"))
        assertTrue(script.contains("--lis"))
        assertTrue(script.contains("ten"))
        assertFalse(script.contains("--listen"))
    }

    @Test
    fun restartStartsOnlyAfterTheKillSucceeds() {
        val script = StaleDaemon.restartScript("setsid '/data/local/tmp/adb-anywhere/adbd' --listen 'tcp:127.0.0.1:15037' &")
        assertTrue(script.contains("kill_stale_adbd || exit 1"))
        assertTrue(script.indexOf("kill_stale_adbd || exit 1") < script.indexOf("setsid"))
    }

    @Test
    fun restartScriptWritesAndRemovesItsMarker() {
        val script = StaleDaemon.restartScript("setsid '/data/local/tmp/adb-anywhere/adbd' --listen 'tcp:127.0.0.1:15037' &")
        assertTrue(script.contains("echo ${'$'}m > \"${'$'}{0%.sh}.pid\""))
        assertTrue(script.contains("rm -f \"${'$'}0\" \"${'$'}{0%.sh}.pid\""))
    }

    @Test
    fun stopScriptDoesNotContainTheStartCommand() {
        assertFalse(StaleDaemon.stopScript().contains("--listen"))
    }
}
