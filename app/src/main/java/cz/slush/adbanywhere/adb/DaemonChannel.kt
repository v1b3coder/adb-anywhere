/*
 * DaemonChannel - the app's channel to the patched daemon: TLS on the loopback
 * listener, the server key pinned to the app's DaemonTlsKey, the daemon key as
 * the client certificate, then ADB key auth inside that channel.
 *
 * Whoever holds the loopback port without the pinned key fails the handshake,
 * so a squatter can neither pose as the daemon nor collect a usable signature:
 * the transcript signature names the squatter's session, and the inner token is
 * signed only after the pin matched (docs/DESIGN.md D20, I15).
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.data.DaemonTlsKey
import cz.slush.adbanywhere.data.Endpoint

object DaemonChannel {
    fun identity(context: Context): AdbIdentity = AppKeyStore(context, AppKeyStore.DAEMON).adbIdentity

    fun mode(context: Context): AdbMode.Tls = AdbMode.Tls(DaemonTlsKey(context).pin, innerAuth = true)

    /** Connects to the verified daemon or throws; the caller closes the client. */
    fun connect(context: Context, loopbackPort: Int, timeoutMs: Int = 10_000): AdbClient =
        AdbClient.connect(Endpoint.LOOPBACK, loopbackPort, identity(context), mode(context), timeoutMs)

    fun shell(context: Context, loopbackPort: Int): Shell = Shell(connect(context, loopbackPort))
}

/**
 * The relay's upstream: the developer machine's own A_CNXN goes to the daemon's
 * TLS listener, the relay answers the daemon's A_STLS with the pinned TLS
 * handshake, and the developer's ADB key auth then runs inside that channel,
 * end to end with adbd. The relay never signs a token itself.
 */
object DaemonTunnel {
    fun open(context: Context, loopbackPort: Int, clientCnxn: ByteArray): java.net.Socket {
        val socket = java.net.Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(java.net.InetSocketAddress(Endpoint.LOOPBACK, loopbackPort), 3_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(clientCnxn)
                flush()
            }
            val reply = cz.slush.adbanywhere.firewall.AdbFrameCodec.read(
                socket.getInputStream(),
                cz.slush.adbanywhere.firewall.AdbFrameCodec.PRE_AUTH_MAX_PAYLOAD,
            )
            if (reply?.command != cz.slush.adbanywhere.firewall.AdbFrameCodec.A_STLS) {
                throw AdbProtocolException("the daemon's loopback listener did not ask for TLS")
            }
            socket.getOutputStream().apply {
                write(AdbClient.frame(cz.slush.adbanywhere.firewall.AdbFrameCodec.A_STLS, AdbClient.A_STLS_VERSION, 0, ByteArray(0)))
                flush()
            }
            val ssl = AdbTls.upgrade(socket, DaemonChannel.identity(context), DaemonChannel.mode(context).serverPin)
            ssl.soTimeout = 0
            return ssl
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}
