/*
 * Shell - runs commands on an ADB connection using the shell v2 protocol and
 * writes/reads files through the shell service.
 */
package cz.slush.adbanywhere.adb

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
) {
    val ok: Boolean get() = exitCode == 0

    fun requireOk(what: String): ShellResult {
        if (!ok) throw IOException("$what failed (exit $exitCode): ${stderr.ifBlank { stdout }.trim()}")
        return this
    }
}

class Shell(private val manager: AdbManager) {
    fun exec(command: String, timeoutMs: Long = 20_000): ShellResult {
        return try {
            execV2(command, timeoutMs)
        } catch (e: IOException) {
            // Older daemons may not offer shell_v2; fall back to the raw shell.
            if (e.message?.contains("shell_v2", ignoreCase = true) == true) execRaw(command, timeoutMs)
            else throw e
        }
    }

    private fun execV2(command: String, timeoutMs: Long): ShellResult {
        val stream = manager.openStream("shell,v2,raw:$command")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exit = -1
        try {
            val input = BufferedInputStream(stream.openInputStream())
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val header = readFully(input, 5) ?: break
                val id = header[0].toInt() and 0xff
                val length = (header[1].toInt() and 0xff) or
                    ((header[2].toInt() and 0xff) shl 8) or
                    ((header[3].toInt() and 0xff) shl 16) or
                    ((header[4].toInt() and 0xff) shl 24)
                if (length < 0 || length > 1 shl 20) throw IOException("bad shell frame length $length")
                val payload = readFully(input, length) ?: break
                when (id) {
                    1 -> stdout.write(payload)
                    2 -> stderr.write(payload)
                    3 -> {
                        if (payload.isNotEmpty()) exit = payload[0].toInt() and 0xff
                        break
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    throw IOException("shell command timed out: $command")
                }
            }
        } finally {
            runCatching { stream.close() }
        }
        return ShellResult(
            stdout.toString(StandardCharsets.UTF_8.name()),
            stderr.toString(StandardCharsets.UTF_8.name()),
            if (exit >= 0) exit else 0,
        )
    }

    private fun execRaw(command: String, timeoutMs: Long): ShellResult {
        val marker = "__ADB_ANYWHERE_RC__"
        val stream = manager.openStream("shell:$command; echo $marker$?")
        val output = ByteArrayOutputStream()
        try {
            val input = BufferedInputStream(stream.openInputStream())
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (System.currentTimeMillis() > deadline) throw IOException("shell command timed out")
            }
        } finally {
            runCatching { stream.close() }
        }
        val text = output.toString(StandardCharsets.UTF_8.name())
        val index = text.lastIndexOf(marker)
        if (index < 0) return ShellResult(text, "", 0)
        val exit = text.substring(index + marker.length).trim().toIntOrNull() ?: 0
        return ShellResult(text.substring(0, index), "", exit)
    }

    fun writeTextFile(path: String, text: String) {
        val quoted = quote(path)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exec("printf '%s' ${quote(text)} > $quoted").requireOk("write $path")
        val size = exec("wc -c < $quoted").stdout.trim().toLongOrNull()
        if (size != bytes.size.toLong()) {
            throw IOException("short write to $path: ${size ?: -1} != ${bytes.size}")
        }
    }

    fun writeFile(path: String, data: ByteArray) {
        val quoted = quote(path)
        val stream = manager.openStream("shell,v2,raw:cat > $quoted")
        try {
            val out = stream.openOutputStream()
            var offset = 0
            while (offset < data.size) {
                val length = minOf(65536, data.size - offset)
                val header = ByteArray(5)
                header[0] = 0 // stdin
                putLe32(header, 1, length)
                out.write(header)
                out.write(data, offset, length)
                offset += length
            }
            val close = ByteArray(5)
            close[0] = 4 // close stdin
            putLe32(close, 1, 0)
            out.write(close)
            out.flush()

            val input = BufferedInputStream(stream.openInputStream())
            var exit = -1
            while (true) {
                val header = readFully(input, 5) ?: break
                val id = header[0].toInt() and 0xff
                val length = le32(header, 1)
                val payload = readFully(input, length) ?: break
                if (id == 3) {
                    exit = if (payload.isNotEmpty()) payload[0].toInt() and 0xff else 0
                    break
                }
            }
            if (exit != 0) throw IOException("write to $path failed (exit $exit)")
        } finally {
            runCatching { stream.close() }
        }
        val size = exec("wc -c < $quoted").stdout.trim().toLongOrNull()
        if (size != data.size.toLong()) {
            throw IOException("short write to $path: ${size ?: -1} != ${data.size}")
        }
    }

    private fun putLe32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun le32(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xff) or
            ((buffer[offset + 1].toInt() and 0xff) shl 8) or
            ((buffer[offset + 2].toInt() and 0xff) shl 16) or
            ((buffer[offset + 3].toInt() and 0xff) shl 24)

    fun readText(path: String): String = exec("cat ${quote(path)}").stdout

    fun fileExists(path: String): Boolean =
        exec("test -f ${quote(path)} && echo yes || echo no").stdout.trim() == "yes"

    fun sha256(path: String): String? =
        exec("sha256sum ${quote(path)}").stdout.trim().substringBefore(' ').takeIf { it.length == 64 }

    fun chmod(path: String, mode: String) {
        exec("chmod $mode ${quote(path)}").requireOk("chmod $path")
    }

    fun mkdirs(path: String) {
        exec("mkdir -p ${quote(path)}").requireOk("mkdir $path")
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun close() {
        manager.disconnectQuietly()
    }

    fun probe(host: String, port: Int, timeoutMs: Int = 400): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return out
    }
}
