/*
 * AppKeyStore - one of the app's ephemeral ADB identities. The private key never
 * leaves app-private storage. There are two (docs/DESIGN.md D20):
 *
 * - "app": the system adbd key. It is paired with Android's wireless debugging
 *   and used only to bootstrap; the patched daemon never trusts it.
 * - "daemon": the patched daemon key. Only the patched daemon trusts it, as the
 *   TLS client certificate and as the ADB key inside that TLS channel.
 *
 * A signature made with one key is therefore useless against the other adbd.
 */
package cz.slush.adbanywhere.data

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

class AppKeyStore(private val dir: File, private val name: String = SYSTEM) {
    constructor(context: Context, name: String = SYSTEM) :
        this(context.createDeviceProtectedStorageContext().filesDir, name)

    // The service and the activity each create an AppKeyStore.  The identity
    // must be shared across instances, not only across the lazy properties of one
    // instance, or the two first uses can still generate two different pairs.
    private val identity: Pair<KeyPair, X509Certificate> by lazy { sharedIdentity(dir, name) }

    // All four properties derive from one identity. Three independent lazies
    // could generate three pairs, and the certificate would then not match the
    // private key (CERTIFICATE_AND_PRIVATE_KEY_MISMATCH during pairing).
    val keyPair: KeyPair get() = identity.first
    val certificate: X509Certificate get() = identity.second
    val publicKeyB64: String by lazy {
        AndroidPubKey.encodeLine(keyPair.public as RSAPublicKey, name)
    }
    val adbIdentity: cz.slush.adbanywhere.adb.AdbIdentity
        get() = cz.slush.adbanywhere.adb.AdbIdentity(keyPair.private, certificate)
    val publicKey: AndroidPubKey.Key by lazy {
        AndroidPubKey.parse(publicKeyB64)!!
    }

    /** True once the key files exist; a migration checks it before first use. */
    fun exists(): Boolean = File(dir, "${name}key.pk8").exists()

    companion object {
        const val SYSTEM = "app"
        const val DAEMON = "daemon"

        private val lock = Any()
        private val identities = mutableMapOf<String, Pair<KeyPair, X509Certificate>>()

        private fun sharedIdentity(dir: File, name: String): Pair<KeyPair, X509Certificate> = synchronized(lock) {
            identities.getOrPut(dir.canonicalPath + "/" + name) { loadOrCreate(dir, name) }
        }

        private fun loadOrCreate(dir: File, name: String): Pair<KeyPair, X509Certificate> {
            load(dir, name)?.let { return it }
            val generated = generate()
            val privateFile = File(dir, "${name}key.pk8")
            val certFile = File(dir, "${name}cert.pem")
            val publicFile = File(dir, "${name}key.pub")
            privateFile.delete()
            certFile.delete()
            publicFile.delete()
            privateFile.writeBytes(generated.first.private.encoded)
            certFile.writeText(pem(generated.second))
            publicFile.writeText(AndroidPubKey.encodeLine(generated.first.public as RSAPublicKey, name))
            return generated
        }

        private fun load(dir: File, name: String): Pair<KeyPair, X509Certificate>? {
            val privateFile = File(dir, "${name}key.pk8")
            val certFile = File(dir, "${name}cert.pem")
            val publicFile = File(dir, "${name}key.pub")
            if (!privateFile.exists() || !certFile.exists()) return null
            return try {
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(privateFile.readBytes()),
                ) as java.security.interfaces.RSAPrivateCrtKey
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
                val certificateKey = certificate.publicKey as RSAPublicKey
                // A certificate and a private key from different generations must never
                // be combined: the daemon would see a key the private key cannot sign for.
                if (privateKey.modulus != certificateKey.modulus ||
                    privateKey.publicExponent != certificateKey.publicExponent
                ) {
                    return null
                }
                val publicLine = AndroidPubKey.encodeLine(certificateKey, name)
                if (!publicFile.exists() || publicFile.readText().trim() != publicLine.trim()) {
                    publicFile.writeText(publicLine)
                }
                KeyPair(certificate.publicKey, privateKey) to certificate
            } catch (_: Exception) {
                null
            }
        }

        private fun generate(): Pair<KeyPair, X509Certificate> {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500Name("CN=adb-anywhere")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - 86_400_000L),
                Date(now + 10L * 365 * 86_400_000L),
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return keyPair to certificate
        }

        private fun pem(certificate: X509Certificate): String {
            val encoded = Base64.getEncoder().encodeToString(certificate.encoded)
            return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
        }
    }
}
