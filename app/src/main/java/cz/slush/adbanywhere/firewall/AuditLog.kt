/*
 * AuditLog - a bounded JSONL log of firewall decisions. It is separate from the
 * app's diagnostic log, and it is always on while the firewall is on.
 */
package cz.slush.adbanywhere.firewall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class AuditEvent(
    val time: Long = System.currentTimeMillis(),
    val event: String,
    val peer: String = "",
    val key: String = "",
    val fingerprint: String = "",
    val service: String = "",
    val fields: Map<String, String> = emptyMap(),
    val rule: String = "",
    val bytes: Long = 0,
    val ms: Long = 0,
    val detail: String = "",
) {
    fun toJson(redact: Boolean): JSONObject = JSONObject().apply {
        put("t", java.time.Instant.ofEpochMilli(time).toString())
        put("event", event)
        if (peer.isNotEmpty()) put("peer", peer)
        if (key.isNotEmpty()) put("key", key)
        if (fingerprint.isNotEmpty()) put("fingerprint", fingerprint)
        if (service.isNotEmpty()) put("service", service)
        if (fields.isNotEmpty()) {
            put("fields", JSONObject().apply {
                fields.forEach { (name, value) ->
                    put(name, if (redact) redact(value) else value.take(512))
                }
            })
        }
        if (rule.isNotEmpty()) put("rule", rule)
        if (bytes != 0L) put("bytes", bytes)
        if (ms != 0L) put("ms", ms)
        if (detail.isNotEmpty()) put("detail", detail.take(512))
    }

    companion object {
        fun redact(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return "sha256:" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

class AuditLog(
    private val file: File?,
    private val redact: () -> Boolean,
) {
    private val lock = Any()
    private val events = ArrayDeque<AuditEvent>()
    private val _entries = MutableStateFlow<List<AuditEvent>>(emptyList())
    val entries: StateFlow<List<AuditEvent>> = _entries.asStateFlow()

    fun record(event: AuditEvent) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > CAPACITY) events.removeFirst()
            _entries.value = events.toList()
            file?.let { appendLocked(it, event.toJson(redact()).toString()) }
        }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
            _entries.value = emptyList()
            file?.let { target ->
                runCatching { target.delete() }
                runCatching { File(target.parentFile, target.name + ".1").delete() }
                runCatching { File(target.parentFile, target.name + ".2").delete() }
                runCatching { File(target.parentFile, target.name + ".3").delete() }
            }
        }
    }

    private fun appendLocked(target: File, line: String) {
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(line + "\n")
            if (target.length() > MAX_BYTES) rotate(target)
        }
    }

    private fun rotate(target: File) {
        runCatching { File(target.parentFile, target.name + ".3").delete() }
        for (index in 2 downTo 1) {
            runCatching {
                File(target.parentFile, target.name + ".$index")
                    .renameTo(File(target.parentFile, target.name + "." + (index + 1)))
            }
        }
        runCatching { target.renameTo(File(target.parentFile, target.name + ".1")) }
    }

    companion object {
        private const val CAPACITY = 500
        private const val MAX_BYTES = 256 * 1024L
    }
}
