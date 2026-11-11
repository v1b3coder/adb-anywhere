/*
 * DaemonTlsKey - the patched daemon's TLS server key. The app generates it,
 * pushes it to the daemon directory over a verified channel and pins the
 * SHA-256 of its public key. A process that holds the loopback port without
 * this key fails the pin, so it cannot pose as the daemon (docs/DESIGN.md D20).
 */
package cz.slush.adbanywhere.data

import android.content.Context
import cz.slush.adbanywhere.adb.AdbTls
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class DaemonTlsKey(private val dir: File) {
    constructor(context: Context) : this(context.createDeviceProtectedStorageContext().filesDir)

    private val key: RSAPrivateCrtKey by lazy { shared(dir) }

    val publicKey: PublicKey
        get() = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(key.modulus, key.publicExponent),
        )

    /** SHA-256 of the DER SubjectPublicKeyInfo; the TLS client pins it. */
    val pin: ByteArray get() = AdbTls.spkiSha256(publicKey)

    /** The PKCS#8 PEM the daemon reads with --tls-key. */
    val pem: String
        get() {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
        }

    private companion object {
        const val FILE = "daemonserver.pk8"
        val lock = Any()
        val keys = mutableMapOf<String, RSAPrivateCrtKey>()

        fun shared(dir: File): RSAPrivateCrtKey = synchronized(lock) {
            keys.getOrPut(dir.canonicalPath) { loadOrCreate(dir) }
        }

        fun loadOrCreate(dir: File): RSAPrivateCrtKey {
            val file = File(dir, FILE)
            runCatching {
                KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateCrtKey
            }.getOrNull()?.let { return it }
            val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair().private as RSAPrivateCrtKey
            file.delete()
            file.writeBytes(generated.encoded)
            return generated
        }
    }
}
