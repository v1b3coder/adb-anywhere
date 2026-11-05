/*
 * ShellLexer - a small POSIX-like tokenizer for ADB shell commands. It never
 * expands variables or globs; it only finds the argv and whether the command uses
 * shell operators outside quotes.
 */
package cz.slush.adbanywhere.firewall

object ShellLexer {
    private val metacharacters = setOf(
        ';', '|', '&', '<', '>', '(', ')', '$', '`', '\n',
        '*', '?', '[', ']', '{', '}', '~',
    )
    private val doubleQuotedMetacharacters = setOf('$', '`', '\n')

    fun lex(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var single = false
        var double = false
        var escaped = false
        var started = false
        for (char in command) {
            if (escaped) {
                current.append(char)
                escaped = false
                started = true
                continue
            }
            if (char == '\\' && !single) {
                escaped = true
                started = true
                continue
            }
            if (char == '\'' && !double) {
                single = !single
                started = true
                continue
            }
            if (char == '"' && !single) {
                double = !double
                started = true
                continue
            }
            if (char.isWhitespace() && !single && !double) {
                if (started) {
                    tokens.add(current.toString())
                    current.clear()
                    started = false
                }
                continue
            }
            current.append(char)
            started = true
        }
        if (escaped) current.append('\\')
        if (started) tokens.add(current.toString())
        return tokens
    }

    fun hasMetacharacters(command: String): Boolean {
        var single = false
        var double = false
        var escaped = false
        for (char in command) {
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && !single) {
                escaped = true
                continue
            }
            if (char == '\'' && !double) {
                single = !single
                continue
            }
            if (char == '"' && !single) {
                double = !double
                continue
            }
            if (single) continue
            if (double) {
                if (char in doubleQuotedMetacharacters) return true
            } else if (char in metacharacters) {
                return true
            }
        }
        return false
    }
}
