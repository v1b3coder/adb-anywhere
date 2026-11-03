/*
 * AppKeyStore - the app's ephemeral ADB identity. The private key never leaves
 * app-private storage; only its public key goes into the daemon key file.
 */
package cz.slush.adbanywhere.data

import android.content.Context
import android.util.Base64
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
import java.util.Date

class AppKeyStore(context: Context) {
    private val dir = context.createDeviceProtectedStorageContext().filesDir
    private val privateFile = File(dir, "appkey.pk8")
    private val certFile = File(dir, "appcert.pem")
    private val publicFile = File(dir, "appkey.pub")

    val keyPair: KeyPair by lazy { loadOrCreate().first }
    val certificate: X509Certificate by lazy { loadOrCreate().second }
    val publicKeyB64: String by lazy {
        AndroidPubKey.encodeLine(keyPair.public as RSAPublicKey, "app")
    }
    val publicKey: AndroidPubKey.Key by lazy {
        AndroidPubKey.parse(publicKeyB64)!!
    }

    private fun loadOrCreate(): Pair<KeyPair, X509Certificate> {
        load()?.let { return it }
        val generated = generate()
        privateFile.delete()
        certFile.delete()
        publicFile.delete()
        privateFile.writeBytes(generated.first.private.encoded)
        certFile.writeText(pem(generated.second))
        publicFile.writeText(AndroidPubKey.encodeLine(generated.first.public as RSAPublicKey, "app"))
        return generated
    }

    private fun load(): Pair<KeyPair, X509Certificate>? {
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
            val publicLine = AndroidPubKey.encodeLine(certificateKey, "app")
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
        val encoded = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
    }
}
