/*
 * WirelessDebuggingGuard - enforces D5: every check re-asserts
 * adb_wifi_enabled=0 and only reports success when the system TLS listener is
 * gone, not merely when the setting reads off. The platform reads, writes and the
 * wait are injected so the JVM tests can drive every outcome.
 */
package cz.slush.adbanywhere.data

import java.io.IOException

class WirelessDebuggingGuard(
    private val readSetting: () -> Int,
    private val writeDisabled: () -> Boolean,
    private val readTlsPort: () -> Int?,
    private val isPortOpen: (Int) -> Boolean,
    private val pause: () -> Unit,
) {
    /**
     * Writes the setting even when it already reads off, because the user or the
     * OEM can turn it back on without the app seeing a change. The framework
     * closes the TLS listener asynchronously, so a bounded wait separates a slow
     * close from an OEM that ignores the write.
     */
    fun ensureOff() {
        if (!writeDisabled()) {
            throw IOException(
                "Could not turn system wireless debugging off. Check WRITE_SECURE_SETTINGS.",
            )
        }
        var enabled = readSetting()
        var port = readTlsPort()
        var open = port != null && isPortOpen(port)
        repeat(ATTEMPTS) { attempt ->
            if (enabled == 0 && (port == null || !open)) return
            if (attempt < ATTEMPTS - 1) {
                pause()
                enabled = readSetting()
                port = readTlsPort()
                open = port != null && isPortOpen(port)
            }
        }
        throw IOException(
            "System wireless debugging is still on: adb_wifi_enabled=$enabled, " +
                "service.adb.tls.port=${port ?: ""}${if (open) " (listening)" else ""}. " +
                "Turn wireless debugging off in Settings, or reboot.",
        )
    }

    companion object {
        const val ATTEMPTS = 5
        const val RETRY_DELAY_MS = 300L

        /**
         * adbd publishes the TLS port and clears the property when the server
         * stops; 0 and out-of-range values are not usable ports.
         */
        fun parseTlsPort(raw: String?): Int? =
            raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
