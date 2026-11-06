/*
 * AppKeyStoreTest - the app's key, certificate and public key must always come
 * from one generation, even when several threads touch the store on first run.
 */
package cz.slush.adbanywhere

import cz.slush.adbanywhere.data.AppKeyStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.nio.file.Files
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppKeyStoreTest {
    @Test
    fun concurrentFirstUseKeepsOneIdentity() {
        val dir = Files.createTempDirectory("appkeystore").toFile()
        val store = AppKeyStore(dir)
        val pool = Executors.newFixedThreadPool(8)
        val moduli = (1..24).map { index ->
            pool.submit<BigInteger> {
                when (index % 3) {
                    0 -> (store.keyPair.public as RSAPublicKey).modulus
                    1 -> (store.certificate.publicKey as RSAPublicKey).modulus
                    else -> (store.keyPair.private as RSAPrivateCrtKey).modulus
                }
            }
        }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, moduli.distinct().size)
        val reloaded = AppKeyStore(dir)
        assertEquals(
            (reloaded.keyPair.public as RSAPublicKey).modulus,
            (reloaded.certificate.publicKey as RSAPublicKey).modulus,
        )
    }
}
