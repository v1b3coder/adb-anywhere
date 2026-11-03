package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AndroidPubKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AndroidPubKeyTest {
    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun encodeDecodeRoundTrip() {
        val publicKey = keyPair().public as RSAPublicKey
        val raw = AndroidPubKey.encode(publicKey)
        assertEquals(AndroidPubKey.ENCODED_SIZE, raw.size)
        val decoded = AndroidPubKey.decode(raw)
        assertNotNull(decoded)
        assertEquals(publicKey.modulus, decoded!!.modulus)
        assertEquals(publicKey.publicExponent, decoded.exponent)
        assertTrue(raw.contentEquals(decoded.raw))
    }

    @Test
    fun fixtureDecodesToExpectedFingerprint() {
        val text = javaClass.classLoader!!.getResourceAsStream("adbkey.pub")!!
            .bufferedReader().readText()
        val key = AndroidPubKey.parse(text)
        assertNotNull(key)
        assertEquals(
            "55c592263882e53bbd00ba0905193df4e51ee8ee413178a22bb1ffff907f7f84",
            key!!.fingerprint,
        )
        assertEquals(65537L, key.exponent.toLong())
        assertEquals("test@host", key.label)
    }

    @Test
    fun parseSshRsaKey() {
        val publicKey = keyPair().public as RSAPublicKey
        val e = mpint(publicKey.publicExponent)
        val n = mpint(publicKey.modulus)
        val body = mpintLength("ssh-rsa".toByteArray()) + "ssh-rsa".toByteArray() + e + n
        val text = "ssh-rsa " + Base64.getEncoder().encodeToString(body) + " laptop"
        val key = AndroidPubKey.parse(text)
        assertNotNull(key)
        assertEquals(publicKey.modulus, key!!.modulus)
        assertEquals(publicKey.publicExponent, key.exponent)
        assertEquals("laptop", key.label)
    }

    @Test
    fun parsePemKey() {
        val publicKey = keyPair().public as RSAPublicKey
        val der = java.security.KeyFactory.getInstance("RSA")
            .getKeySpec(publicKey, java.security.spec.X509EncodedKeySpec::class.java).encoded
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END PUBLIC KEY-----\n"
        val key = AndroidPubKey.parse(pem)
        assertNotNull(key)
        assertEquals(publicKey.modulus, key!!.modulus)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(AndroidPubKey.parse(""))
        assertNull(AndroidPubKey.parse("not a key"))
        assertNull(AndroidPubKey.parse("AAAA"))
        assertNull(AndroidPubKey.decode(ByteArray(10)))
    }

    @Test
    fun displayFingerprintIsGrouped() {
        assertEquals("55C5 9226 3882 E53B", AndroidPubKey.displayFingerprint("55c592263882e53bbd00ba0905193df4").substring(0, 19))
    }

    @Test
    fun rejectsSmallKeys() {
        val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        var failed = false
        try {
            AndroidPubKey.encode(small.public as RSAPublicKey)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun mpint(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes
    }

    private fun mpintLength(value: ByteArray): ByteArray =
        ByteBuffer.allocate(4).putInt(value.size).array()
}
