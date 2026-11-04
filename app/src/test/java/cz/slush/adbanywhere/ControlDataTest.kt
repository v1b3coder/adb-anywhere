package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AndroidPubKey
import cz.slush.adbanywhere.data.DeveloperKey
import cz.slush.adbanywhere.data.Endpoint
import cz.slush.adbanywhere.data.KeyFiles
import cz.slush.adbanywhere.data.PairingCode
import cz.slush.adbanywhere.data.VpnInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class ControlDataTest {
    private fun key() = AndroidPubKey.decode(
        AndroidPubKey.encode(
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair().public as RSAPublicKey,
        ),
    )!!

    @Test
    fun pairingCodeParsesCodeAndPort() {
        val parsed = PairingCode.parse(" 123456 : 37123 ")
        assertNotNull(parsed)
        assertEquals("123456", parsed!!.code)
        assertEquals(37123, parsed.port)
    }

    @Test
    fun pairingCodeRejectsBadValues() {
        assertNull(PairingCode.parse(""))
        assertNull(PairingCode.parse("123456"))
        assertNull(PairingCode.parse("12345:37123"))
        assertNull(PairingCode.parse("abcdef:37123"))
        assertNull(PairingCode.parse("123456:0"))
        assertNull(PairingCode.parse("123456:65536"))
        assertNull(PairingCode.parse("123456:12:34"))
    }

    @Test
    fun endpointBuildsListenSpec() {
        assertEquals(
            "tcp:127.0.0.1:15037,tcp:100.64.0.10:15038",
            Endpoint.listenSpec(15037, "100.64.0.10", 15038),
        )
        assertEquals("tcp:127.0.0.1:15037", Endpoint.listenSpec(15037, null, 15038))
        assertEquals("tcp:127.0.0.1:15037", Endpoint.listenSpec(15037, "", 15038))
    }

    @Test
    fun endpointChoosesVpnAddress() {
        val interfaces = listOf(
            VpnInterface("wlan0", listOf("192.168.1.5/24")),
            VpnInterface("tun1", listOf("100.64.0.10/32", "fe80::1/64")),
        )
        assertEquals("100.64.0.10", Endpoint.chooseVpnAddress(interfaces))
        assertNull(Endpoint.chooseVpnAddress(listOf(VpnInterface("wlan0", listOf("192.168.1.5/24")))))
    }

    @Test
    fun endpointRejectsUnusableIpv4() {
        assertTrue(Endpoint.isUsableIpv4("100.64.0.10/32"))
        assertTrue(Endpoint.isUsableIpv4("10.0.0.2"))
        assertFalse(Endpoint.isUsableIpv4("127.0.0.1"))
        assertFalse(Endpoint.isUsableIpv4("0.0.0.0"))
        assertFalse(Endpoint.isUsableIpv4("255.255.255.255"))
        assertFalse(Endpoint.isUsableIpv4("fe80::1"))
        assertFalse(Endpoint.isUsableIpv4("10.0.0"))
    }

    @Test
    fun keyFilesContainAppAndDeveloperKeys() {
        val app = key()
        val dev = key()
        val developer = DeveloperKey(dev, "laptop", "developer")
        val keys = KeyFiles.keysFile(app, listOf(developer))
        val lines = keys.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" app"))
        assertTrue(lines[1].endsWith(" laptop"))
    }

    @Test
    fun keyFilesDedupeAndSanitizeLabels() {
        val app = key()
        val dev = key()
        val first = DeveloperKey(dev, "my\nlaptop", "developer")
        val second = DeveloperKey(dev, "duplicate", "developer")
        val keys = KeyFiles.keysFile(app, listOf(first, second))
        assertEquals(2, keys.trim().lines().size)
        assertTrue(keys.contains("my laptop"))
    }

}
