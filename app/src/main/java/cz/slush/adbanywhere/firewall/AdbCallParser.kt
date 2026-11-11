/*
 * AdbCallParser - normalizes an A_OPEN service string into the fields the call
 * policy can reason about. It parses framing and service arguments, never the
 * service payload. The parsed fields are capped so a 1 MiB service string cannot
 * grow the audit ring or a persisted rule without bound.
 */
package cz.slush.adbanywhere.firewall

object AdbCallParser {
    /** The most fields the policy, the prompt and the audit log keep per call. */
    const val MAX_FIELDS = 32

    /** The most characters kept per field value. */
    const val MAX_FIELD_VALUE = 512

    /** The most characters kept from the raw service string. */
    const val MAX_RAW = 1024

    private val supported = setOf(
        "shell", "exec", "abb_exec", "sync", "tcp", "reverse", "framebuffer",
    )

    fun isUnsupported(service: String): Boolean = service !in supported

    fun parse(service: String): AdbCall {
        val colon = service.indexOf(':')
        val name = if (colon >= 0) service.substring(0, colon) else service
        val rest = if (colon >= 0) service.substring(colon + 1) else ""
        val base = name.substringBefore(',')
        val raw = service.take(MAX_RAW)
        val fields = Fields()
        fields.put("service", base)
        fields.put("raw", raw)

        return when (base) {
            "shell" -> shell(base, rest, fields, interactive = rest.isEmpty(), raw = raw)
            "exec" -> shell(base, rest, fields, interactive = rest.isEmpty(), raw = raw)
            "abb_exec" -> {
                val argv = rest.split('\u0000').filter { it.isNotEmpty() }
                argv.take(MAX_FIELDS).forEachIndexed { index, value ->
                    fields.put("argv[$index]", value)
                }
                AdbCall(
                    service = base,
                    raw = raw,
                    fields = fields.map,
                    argv = argv.take(MAX_FIELDS),
                    truncated = fields.truncated || argv.size > MAX_FIELDS,
                )
            }
            "sync" -> AdbCall(
                service = base,
                raw = raw,
                fields = fields.map,
                truncated = fields.truncated,
            )
            "tcp" -> {
                // tcp:<port> or tcp:<host>:<port>; adbd connects to loopback only.
                val host = if (rest.contains(':')) rest.substringBeforeLast(':') else ""
                val port = rest.substringAfterLast(':').toIntOrNull()
                if (host.isNotEmpty()) fields.put("host", host)
                if (port != null) fields.put("port", port.toString())
                AdbCall(
                    service = base,
                    raw = raw,
                    fields = fields.map,
                    port = port,
                    truncated = fields.truncated,
                )
            }
            "reverse" -> {
                val spec = rest.removePrefix("forward:")
                val parts = spec.split(';', limit = 2)
                val remote = parts.getOrNull(0).orEmpty()
                val local = parts.getOrNull(1).orEmpty()
                fields.put("remote", remote)
                fields.put("local", local)
                AdbCall(
                    service = base,
                    raw = raw,
                    fields = fields.map,
                    truncated = fields.truncated,
                )
            }
            "framebuffer" -> AdbCall(
                service = base,
                raw = raw,
                fields = fields.map,
                truncated = fields.truncated,
            )
            "jdwp" -> {
                rest.toIntOrNull()?.let { fields.put("pid", it.toString()) }
                AdbCall(
                    service = base,
                    raw = raw,
                    fields = fields.map,
                    truncated = fields.truncated,
                )
            }
            else -> AdbCall(
                service = base,
                raw = raw,
                fields = fields.map,
                truncated = fields.truncated,
            )
        }
    }

    private fun shell(
        service: String,
        command: String,
        fields: Fields,
        interactive: Boolean,
        raw: String,
    ): AdbCall {
        val full = ShellLexer.lex(command)
        val argv = full.take(MAX_FIELDS)
        val metacharacters = ShellLexer.hasMetacharacters(command)
        // `shell:sh` or `exec:sh -i` reads commands from stdin: it is the same
        // full shell as `shell:` and must be the same capability, not a command.
        @Suppress("NAME_SHADOWING")
        val interactive = interactive || (!metacharacters && isShellLauncher(full))
        fields.put("command", command)
        fields.put("interactive", interactive.toString())
        fields.put("metacharacters", metacharacters.toString())
        argv.forEachIndexed { index, value -> fields.put("argv[$index]", value) }
        return AdbCall(
            service = service,
            raw = raw,
            fields = fields.map,
            argv = argv,
            interactive = interactive,
            metacharacters = metacharacters,
            truncated = fields.truncated || full.size > MAX_FIELDS,
        )
    }

    private val shells = setOf("sh", "bash", "mksh", "ash", "dash", "zsh", "su")

    /** A shell binary with no script argument reads its commands from stdin. */
    internal fun isShellLauncher(argv: List<String>): Boolean {
        val first = argv.firstOrNull() ?: return false
        var rest = argv.drop(1)
        if (first.substringAfterLast('/') == "toybox" || first.substringAfterLast('/') == "busybox") {
            if (rest.firstOrNull()?.let { it in shells } != true) return false
            rest = rest.drop(1)
        } else if (first.substringAfterLast('/') !in shells) {
            return false
        }
        return rest.none { it == "-c" || (it.startsWith("-") && it.contains('c') && !it.startsWith("--")) } &&
            rest.none { !it.startsWith("-") }
    }

    private class Fields {
        val map = linkedMapOf<String, String>()
        var truncated = false

        fun put(name: String, value: String) {
            if (map.size >= MAX_FIELDS) {
                truncated = true
                return
            }
            if (value.length > MAX_FIELD_VALUE) truncated = true
            map[name] = value.take(MAX_FIELD_VALUE)
        }
    }
}
