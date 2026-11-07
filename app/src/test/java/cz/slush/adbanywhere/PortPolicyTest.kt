/*
 * PortPolicyTest - the single validation both the settings UI and the setup use
 * for the two ports the custom adb binds.
 */
package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.Prefs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PortPolicyTest {
    @Test
    fun acceptsTheDefaults() {
        assertNull(Prefs.validatePorts(Prefs.DEFAULT_LOOPBACK_PORT, Prefs.DEFAULT_VPN_PORT))
    }

    @Test
    fun rejectsThePlainSystemAdbPort() {
        assertNotNull(Prefs.validatePorts(Prefs.PLAIN_ADB_PORT, Prefs.DEFAULT_VPN_PORT))
        assertNotNull(Prefs.validatePorts(Prefs.DEFAULT_LOOPBACK_PORT, Prefs.PLAIN_ADB_PORT))
    }

    @Test
    fun rejectsTheEphemeralRange() {
        assertNotNull(Prefs.validatePorts(Prefs.EPHEMERAL_PORT_START, Prefs.DEFAULT_VPN_PORT))
        assertNotNull(Prefs.validatePorts(Prefs.DEFAULT_LOOPBACK_PORT, Prefs.EPHEMERAL_PORT_START))
    }

    @Test
    fun rejectsEqualAndOutOfRangePorts() {
        assertNotNull(Prefs.validatePorts(15037, 15037))
        assertNotNull(Prefs.validatePorts(80, Prefs.DEFAULT_VPN_PORT))
        assertNotNull(Prefs.validatePorts(Prefs.DEFAULT_LOOPBACK_PORT, 65536))
    }
}
