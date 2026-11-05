/*
 * AdbCallParser - normalizes an A_OPEN service string into the fields the call
 * policy can reason about. It parses framing and service arguments, never the
 * service payload.
 */
package cz.slush.adbanywhere.firewall

object AdbCallParser {
    private val supported = setOf(
        "shell", "exec", "abb_exec", "sync", "tcp", "reverse", "framebuffer",
    )

    fun isUnsupported(service: String): Boolean = service !in supported

    fun parse(service: String): AdbCall {
        val colon = service.indexOf(':')
        val name = if (colon >= 0) service.substring(0, colon) else service
        val rest = if (colon >= 0) service.substring(colon + 1) else ""
        val base = name.substringBefore(',')
        val fields = linkedMapOf("service" to base, "raw" to service)

        return when (base) {
            "shell" -> shell(base, rest, fields, interactive = rest.isEmpty())
            "exec" -> shell(base, rest, fields, interactive = false)
            "abb_exec" -> {
                val argv = rest.split('\u0000').filter { it.isNotEmpty() }
                argv.forEachIndexed { index, value -> fields["argv[$index]"] = value }
                AdbCall(
                    service = base,
                    raw = service,
                    fields = fields,
                    argv = argv,
                )
            }
            "sync" -> AdbCall(service = base, raw = service, fields = fields)
            "tcp" -> {
                val port = rest.toIntOrNull()
                if (port != null) fields["port"] = port.toString()
                AdbCall(
                    service = base,
                    raw = service,
                    fields = fields,
                    port = port,
                )
            }
            "reverse" -> {
                val spec = rest.removePrefix("forward:")
                val parts = spec.split(';', limit = 2)
                val remote = parts.getOrNull(0).orEmpty()
                val local = parts.getOrNull(1).orEmpty()
                fields["remote"] = remote
                fields["local"] = local
                AdbCall(service = base, raw = service, fields = fields)
            }
            "framebuffer" -> AdbCall(service = base, raw = service, fields = fields)
            "jdwp" -> {
                rest.toIntOrNull()?.let { fields["pid"] = it.toString() }
                AdbCall(service = base, raw = service, fields = fields)
            }
            else -> AdbCall(service = base, raw = service, fields = fields)
        }
    }

    private fun shell(
        service: String,
        command: String,
        fields: MutableMap<String, String>,
        interactive: Boolean,
    ): AdbCall {
        val argv = ShellLexer.lex(command)
        val metacharacters = ShellLexer.hasMetacharacters(command)
        fields["command"] = command
        fields["interactive"] = interactive.toString()
        fields["metacharacters"] = metacharacters.toString()
        argv.forEachIndexed { index, value -> fields["argv[$index]"] = value }
        return AdbCall(
            service = service,
            raw = if (command.isEmpty()) service else "$service:$command",
            fields = fields,
            argv = argv,
            interactive = interactive,
            metacharacters = metacharacters,
        )
    }
}
