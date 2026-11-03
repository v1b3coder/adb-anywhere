/*
 * PairingCode - parses the "code:port" value typed into the pairing notification.
 */
package cz.slush.adbanywhere.data

data class PairingCode(val code: String, val port: Int) {
    companion object {
        fun parse(text: String): PairingCode? {
            val parts = text.trim().split(':')
            if (parts.size != 2) return null
            val code = parts[0].trim()
            val portText = parts[1].trim()
            if (code.length != 6 || !code.all { it.isDigit() }) return null
            if (portText.isEmpty() || !portText.all { it.isDigit() }) return null
            val port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return PairingCode(code, port)
        }
    }
}
