/*
 * PairingCode - the six-digit code and port from Android's pairing dialog.
 */
package cz.slush.adbanywhere.data

data class PairingCode(val code: String, val port: Int) {
    companion object {
        /** The six-digit code shown in Android's "Pair device with pairing code" dialog. */
        fun parseCode(text: String): String? {
            val value = text.trim()
            if (value.length != 6 || !value.all { it.isDigit() }) return null
            return value
        }

        /** The port shown under the IP address in the same dialog. */
        fun parsePort(text: String): Int? {
            val value = text.trim()
            if (value.isEmpty() || !value.all { it.isDigit() }) return null
            return value.toIntOrNull()?.takeIf { it in 1..65535 }
        }

        /** The debug-only "code:port" form used by the end-to-end harness. */
        fun parse(text: String): PairingCode? {
            val parts = text.trim().split(':')
            if (parts.size != 2) return null
            val code = parseCode(parts[0]) ?: return null
            val port = parsePort(parts[1]) ?: return null
            return PairingCode(code, port)
        }
    }
}
