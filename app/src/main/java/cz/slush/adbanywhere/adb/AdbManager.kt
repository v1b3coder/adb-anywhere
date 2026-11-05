/*
 * AdbManager - one libadb connection manager per logical connection (system
 * adbd or the app's daemon). The key and certificate are shared.
 */
package cz.slush.adbanywhere.adb

import android.content.Context
import android.os.Build
import cz.slush.adbanywhere.data.AppKeyStore
import cz.slush.adbanywhere.service.AppLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

class AdbManager(
    context: Context,
    private val keyStore: AppKeyStore,
) : AbsAdbConnectionManager() {
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(12, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = keyStore.keyPair.private

    override fun getCertificate(): Certificate = keyStore.certificate

    override fun getDeviceName(): String = "adb-anywhere"

    @Synchronized
    fun connectTo(host: String, port: Int): Boolean {
        return try {
            if (isConnected) disconnect()
            connect(host, port)
        } catch (e: Exception) {
            AppLog.warn("system", "connect $host:$port failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun pairTo(host: String, port: Int, code: String): Boolean {
        return try {
            pair(host, port, code)
        } catch (e: Exception) {
            AppLog.error("pair", "pair $host:$port failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun disconnectQuietly() {
        try {
            disconnect()
        } catch (_: Exception) {
            // The socket may already be gone; nothing to do.
        }
    }
}
