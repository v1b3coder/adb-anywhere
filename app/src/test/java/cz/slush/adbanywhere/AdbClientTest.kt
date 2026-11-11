package cz.slush.adbanywhere

import cz.slush.adbanywhere.adb.AdbClient
import cz.slush.adbanywhere.adb.AdbIdentity
import cz.slush.adbanywhere.adb.AdbMode
import cz.slush.adbanywhere.adb.AdbPlainTokenRefusedException
import cz.slush.adbanywhere.adb.AdbProtocolException
import cz.slush.adbanywhere.adb.AdbServerIdentityException
import cz.slush.adbanywhere.adb.AdbTls
import cz.slush.adbanywhere.adb.Shell
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.firewall.AdbFrame
import cz.slush.adbanywhere.firewall.AdbFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The app signs an ADB token only inside a channel it verified. Each test drives
 * the client against a scripted fake adbd on loopback.
 */
class AdbClientTest {
    private val dir = Files.createTempDirectory("adbclient").toFile()
    private val client = AppKeyStore(dir, "client")
    private val server = AppKeyStore(dir, "server")
    private val identity = AdbIdentity(client.keyPair.private, client.certificate)

    private fun frame(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream().also { AdbFrameCodec.write(it, command, arg0, arg1, payload) }.toByteArray()

    private class Peer(var input: InputStream, var output: OutputStream, var socket: Socket) {
        fun read(): AdbFrame? = AdbFrameCodec.read(input)
        fun send(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }
    }

    /** Runs [script] for one accepted connection and returns its result. */
    private fun <T> fakeAdbd(script: (Peer) -> T): Pair<Int, CompletableFuture<T>> {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val result = CompletableFuture<T>()
        Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    result.complete(script(Peer(socket.getInputStream(), socket.getOutputStream(), socket)))
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            } finally {
                listener.close()
            }
        }.start()
        return listener.localPort to result
    }

    /** The adbd side of A_STLS: a TLS 1.3 server that requires a client certificate. */
    private fun upgrade(peer: Peer): X509Certificate {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", server.keyPair.private, CharArray(0), arrayOf(server.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CharArray(0))
        }
        var seen: X509Certificate? = null
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
                seen = chain[0]
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }
        val ssl = context.socketFactory.createSocket(peer.socket, "127.0.0.1", peer.socket.port, true) as SSLSocket
        ssl.useClientMode = false
        ssl.needClientAuth = true
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.startHandshake()
        peer.input = ssl.inputStream
        peer.output = ssl.outputStream
        peer.socket = ssl
        return seen!!
    }

    private val token = ByteArray(20) { (it * 7).toByte() }

    @Test
    fun plainModeSignsTheTokenAndRunsAShell() {
        val (port, done) = fakeAdbd { peer ->
            assertEquals(AdbFrameCodec.A_CNXN, peer.read()!!.command)
            peer.send(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
            val signature = peer.read()!!
            assertEquals(AdbFrameCodec.ADB_AUTH_SIGNATURE, signature.arg0)
            assertTrue(AdbTls.verifyToken(client.keyPair.public, token, signature.payload))
            peer.send(frame(AdbFrameCodec.A_CNXN, 0x01000001, 4096, "device::".toByteArray()))
            val open = peer.read()!!
            assertEquals("shell,v2,raw:echo hi", AdbFrameCodec.service(open).trimEnd('\u0000'))
            peer.send(frame(AdbFrameCodec.A_OKAY, 99, open.arg0))
            val stdout = byteArrayOf(1, 3, 0, 0, 0) + "hi\n".toByteArray()
            val exit = byteArrayOf(3, 1, 0, 0, 0, 0)
            peer.send(frame(AdbFrameCodec.A_WRTE, 99, open.arg0, stdout + exit))
            peer.read() // the client's OKAY
            true
        }
        val adb = AdbClient.connect("127.0.0.1", port, identity, AdbMode.Plain)
        val result = Shell(adb).exec("echo hi")
        assertEquals("hi\n", result.stdout)
        assertEquals(0, result.exitCode)
        adb.close()
        assertTrue(done.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun tlsModeRefusesAPlainTokenWithoutSigning() {
        val (port, done) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
            // The client must close without ever sending a signature.
            runCatching { peer.read() }.getOrNull()
        }
        try {
            AdbClient.connect("127.0.0.1", port, identity, AdbMode.Tls())
            fail("a plain token was accepted on a TLS-only connection")
        } catch (_: AdbPlainTokenRefusedException) {
        }
        assertEquals(null, done.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun thePinnedTunnelSignsOnlyInsideTls() {
        val (port, done) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_STLS, AdbClient.A_STLS_VERSION, 0))
            assertEquals(AdbFrameCodec.A_STLS, peer.read()!!.command)
            val cert = upgrade(peer)
            peer.send(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
            val signature = peer.read()!!
            assertTrue(AdbTls.verifyToken(client.keyPair.public, token, signature.payload))
            peer.send(frame(AdbFrameCodec.A_CNXN, 0x01000001, 4096, "device::".toByteArray()))
            cert.encoded
        }
        val pin = AdbTls.spkiSha256(server.keyPair.public)
        val adb = AdbClient.connect("127.0.0.1", port, identity, AdbMode.Tls(pin, innerAuth = true))
        assertTrue(adb.tls)
        adb.close()
        assertArrayEquals(client.certificate.encoded, done.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun aServerWithAnotherKeyFailsThePin() {
        val (port, done) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_STLS, AdbClient.A_STLS_VERSION, 0))
            peer.read()
            runCatching { upgrade(peer) }
            "server done"
        }
        val wrongPin = AdbTls.spkiSha256(client.keyPair.public)
        try {
            AdbClient.connect("127.0.0.1", port, identity, AdbMode.Tls(wrongPin, innerAuth = true))
            fail("a server with an unpinned key was accepted")
        } catch (e: Exception) {
            assertTrue("$e", AdbTls.identityFailure(e) != null || e is AdbServerIdentityException)
        }
        done.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun theSystemModeDoesNotSignInsideTls() {
        val (port, done) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_STLS, AdbClient.A_STLS_VERSION, 0))
            peer.read()
            upgrade(peer)
            peer.send(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
            runCatching { peer.read() }.getOrNull()
        }
        try {
            AdbClient.connect("127.0.0.1", port, identity, AdbMode.Tls())
            fail("an unpinned TLS server got a token signature")
        } catch (_: AdbPlainTokenRefusedException) {
        }
        assertEquals(null, done.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun thePlainModeRefusesATlsUpgrade() {
        val (port, done) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_STLS, AdbClient.A_STLS_VERSION, 0))
            true
        }
        try {
            AdbClient.connect("127.0.0.1", port, identity, AdbMode.Plain)
            fail("plain mode upgraded")
        } catch (_: AdbProtocolException) {
        }
        done.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun offersTlsNeverSigns() {
        val (port, done) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_STLS, AdbClient.A_STLS_VERSION, 0))
            runCatching { peer.read() }.getOrNull()
        }
        assertTrue(AdbClient.offersTls("127.0.0.1", port))
        assertEquals(null, done.get(5, TimeUnit.SECONDS))
        val (plainPort, plainDone) = fakeAdbd { peer ->
            peer.read()
            peer.send(frame(AdbFrameCodec.A_AUTH, AdbFrameCodec.ADB_AUTH_TOKEN, 0, token))
            runCatching { peer.read() }.getOrNull()
        }
        assertFalse(AdbClient.offersTls("127.0.0.1", plainPort))
        assertEquals(null, plainDone.get(5, TimeUnit.SECONDS))
    }
}
