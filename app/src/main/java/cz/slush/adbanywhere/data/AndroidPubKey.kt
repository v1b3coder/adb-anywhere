/*
 * AndroidPubKey - Android's adb public-key encoding, parsing and fingerprints.
 *
 * The wire format is the 524-byte little-endian structure from AOSP
 * system/core/libcrypto_utils/android_pubkey.cpp:
 *   u32 modulus_size_words, u32 n0inv, u8 modulus[256], u8 rr[256], u32 exponent
 */
package cz.slush.adbanywhere.data

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object AndroidPubKey {
    const val MODULUS_SIZE = 256
    const val ENCODED_SIZE = 524
    private const val MODULUS_WORDS = MODULUS_SIZE / 4
    private val TWO_32 = BigInteger.ONE.shiftLeft(32)

    data class Key(
        val modulus: BigInteger,
        val exponent: BigInteger,
        val raw: ByteArray,
        val label: String = "",
    ) {
        val fingerprint: String = fingerprintOf(raw)
        val bits: Int = modulus.bitLength()
    }

    /** Encodes an RSA public key into Android's adbkey.pub binary format. */
    fun encode(publicKey: RSAPublicKey): ByteArray {
        require(publicKey.modulus.bitLength() == 2048) { "ADB keys must be RSA-2048" }
        val modulus = publicKey.modulus
        val n = littleEndianFixed(modulus, MODULUS_SIZE)
        // BigInteger.TWO is API 33; shiftLeft keeps the value and minSdk 31.
        val rr = littleEndianFixed(BigInteger.ONE.shiftLeft(MODULUS_SIZE * 8 * 2).mod(modulus), MODULUS_SIZE)
        val n0inv = modulus.mod(TWO_32).modInverse(TWO_32).negate().mod(TWO_32)
        return ByteBuffer.allocate(ENCODED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_WORDS)
            .putInt(n0inv.toInt())
            .put(n)
            .put(rr)
            .putInt(publicKey.publicExponent.toInt())
            .array()
    }

    /** Decodes Android's adbkey.pub binary format. Returns null when invalid. */
    fun decode(raw: ByteArray): Key? {
        if (raw.size < ENCODED_SIZE) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val words = buffer.int
        if (words != MODULUS_WORDS) return null
        buffer.int // n0inv is recomputed by libcrypto, not needed for verification
        val modulusBytes = ByteArray(MODULUS_SIZE).also { buffer.get(it) }
        buffer.position(buffer.position() + MODULUS_SIZE) // rr
        val exponent = buffer.int
        if (exponent < 3 || exponent > Int.MAX_VALUE || exponent % 2 == 0) return null
        val modulus = BigInteger(1, modulusBytes.reversedArray())
        if (modulus.bitLength() != 2048) return null
        return Key(modulus, BigInteger.valueOf(exponent.toLong() and 0xffffffffL), raw.copyOf(ENCODED_SIZE))
    }

    /** Parses adbkey.pub text, an ssh-rsa key, or a base64 DER SubjectPublicKeyInfo. */
    fun parse(text: String): Key? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.contains("BEGIN PUBLIC KEY")) return parsePem(trimmed)
            val parts = trimmed.split(Regex("\\s+"), limit = 3)
            val body = parts[0]
            if (body == "ssh-rsa") {
                val label = parts.getOrNull(2) ?: ""
                parseSshRsa(trimmed)?.copy(label = label)
            } else {
                val label = parts.getOrNull(1) ?: ""
                val raw = Base64.getMimeDecoder().decode(body)
                if (raw.size >= ENCODED_SIZE && decode(raw) != null) {
                    decode(raw)?.copy(label = label)
                } else {
                    parseDer(raw)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun encodeLine(publicKey: RSAPublicKey, label: String): String {
        val b64 = Base64.getEncoder().encodeToString(encode(publicKey))
        return if (label.isBlank()) b64 else "$b64 ${label.trim()}"
    }

    fun fingerprintOf(raw: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun displayFingerprint(fingerprint: String): String =
        fingerprint.chunked(4).joinToString(" ").uppercase()

    private fun littleEndianFixed(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray()
        val out = ByteArray(size)
        val copy = if (big.size > size) big.copyOfRange(big.size - size, big.size) else big
        for (i in copy.indices) out[i] = copy[copy.size - 1 - i]
        return out
    }

    private fun parseSshRsa(text: String): Key? {
        val body = text.removePrefix("ssh-rsa ").substringBefore(' ')
        val bytes = Base64.getDecoder().decode(body)
        var offset = 0
        fun readString(): ByteArray {
            require(offset + 4 <= bytes.size)
            val len = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
            require(len in 0..bytes.size - offset)
            return bytes.copyOfRange(offset, offset + len).also { offset += len }
        }
        val type = String(readString(), Charsets.US_ASCII)
        if (type != "ssh-rsa") return null
        val e = BigInteger(1, readString().dropWhile { it == 0.toByte() }.toByteArray())
        val n = BigInteger(1, readString().dropWhile { it == 0.toByte() }.toByteArray())
        return keyFromNumbers(n, e, text.substringAfter(' ', ""))
    }

    private fun parsePem(text: String): Key? {
        val b64 = text.lineSequence()
            .filterNot { it.contains("-----") }
            .joinToString("")
        return parseDer(Base64.getDecoder().decode(b64))
    }

    private fun parseDer(der: ByteArray): Key? {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        return keyFromNumbers(key.modulus, key.publicExponent, "")
    }

    private fun keyFromNumbers(modulus: BigInteger, exponent: BigInteger, label: String): Key? {
        if (modulus.bitLength() != 2048) return null
        if (exponent < BigInteger.valueOf(3) || exponent.bitLength() > 31) return null
        if (!exponent.testBit(0)) return null
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.RSAPublicKeySpec(modulus, exponent),
        ) as RSAPublicKey
        return Key(publicKey.modulus, publicKey.publicExponent, encode(publicKey), label.trim())
    }
}
