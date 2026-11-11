/*
 * AdbTls - the TLS pieces the app's ADB connections share: the client
 * certificate, the server key pin and the ADB token signature.
 *
 * A TLS 1.3 client certificate signs the whole handshake transcript, so a
 * signature given to one server cannot be replayed to another. A plain ADB
 * token signature covers only the token, so it can; the app signs a plain
 * token only inside a channel it has already verified (docs/DESIGN.md I15).
 */
package cz.slush.adbanywhere.adb

import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** One ADB identity: the RSA key that signs and the certificate TLS presents. */
class AdbIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

/** The server presented a key other than the pinned one. */
class AdbServerIdentityException(message: String) : CertificateException(message)

object AdbTls {
    const val PROTOCOL = "TLSv1.3"

    /** SHA-256 of the DER SubjectPublicKeyInfo; what the daemon pin compares. */
    fun spkiSha256(key: PublicKey): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key.encoded)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * A client context that presents [identity] and accepts only a server whose
     * key hashes to [serverPin]. A null pin accepts any server: the system adbd
     * generates a random TLS key per process, so it cannot be pinned, but the
     * transcript signature still cannot be relayed.
     */
    fun clientContext(identity: AdbIdentity, serverPin: ByteArray?): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(ClientKeyManager(identity)), arrayOf(PinningTrustManager(serverPin)), null)
        return context
    }

    /**
     * Upgrades an ADB socket after the A_STLS exchange. The handshake runs
     * before this returns; a pin mismatch surfaces as [AdbServerIdentityException].
     */
    fun upgrade(socket: Socket, identity: AdbIdentity, serverPin: ByteArray?): SSLSocket {
        val factory = clientContext(identity, serverPin).socketFactory
        val host = socket.inetAddress?.hostAddress ?: "127.0.0.1"
        val ssl = factory.createSocket(socket, host, socket.port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf(PROTOCOL)
        ssl.useClientMode = true
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            identityFailure(e)?.let { throw it }
            throw e
        }
        return ssl
    }

    /** The pin failure inside a wrapped handshake exception, if that was the cause. */
    fun identityFailure(error: Throwable): AdbServerIdentityException? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is AdbServerIdentityException) return cause
            cause = cause.cause
        }
        return null
    }

    /** DER DigestInfo prefix for SHA-1; adbd signs the raw token as a SHA-1 digest. */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )
    const val TOKEN_SIZE = 20

    fun signToken(key: PrivateKey, token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE) { "ADB token must be $TOKEN_SIZE bytes" }
        return Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(SHA1_DIGEST_INFO)
            update(token)
            sign()
        }
    }

    fun verifyToken(key: PublicKey, token: ByteArray, signature: ByteArray): Boolean =
        token.size == TOKEN_SIZE && runCatching {
            Signature.getInstance("NONEwithRSA").run {
                initVerify(key)
                update(SHA1_DIGEST_INFO)
                update(token)
                verify(signature)
            }
        }.getOrDefault(false)

    private class ClientKeyManager(private val identity: AdbIdentity) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String = ALIAS

        override fun getCertificateChain(alias: String?): Array<X509Certificate> =
            arrayOf(identity.certificate)

        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = emptyArray<String>()
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null

        private companion object {
            const val ALIAS = "adb"
        }
    }

    private class PinningTrustManager(private val pin: ByteArray?) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client mode only")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw AdbServerIdentityException("no server certificate")
            if (pin == null) return
            val actual = spkiSha256(leaf.publicKey)
            if (!MessageDigest.isEqual(actual, pin)) {
                throw AdbServerIdentityException(
                    "the server key ${hex(actual).take(16)}… is not the app's daemon key",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
