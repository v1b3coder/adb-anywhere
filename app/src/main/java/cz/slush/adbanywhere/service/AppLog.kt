/*
 * AppLog - the app's own event log. It records who triggered what, what the
 * service did and which changes it applied, so the Logs screen can explain a
 * failure instead of only showing the custom adb's output. It is a bounded in-memory
 * ring mirrored to a size-capped file, and it records nothing until the user
 * turns diagnostics on.
 */
package cz.slush.adbanywhere.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

object AppLog {
    private const val TAG = "AdbAnywhere"
    private const val CAPACITY = 400
    private const val MAX_BYTES = 256 * 1024L
    private const val FILE_NAME = "app-events.log"

    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val fileWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-log").apply { isDaemon = true }
    }

    private val _entries = MutableStateFlow("")
    val entries: StateFlow<String> = _entries.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var file: File? = null

    /**
     * Loads the persisted tail even while diagnostics is off, so events recorded
     * before the user turned it off stay readable. New lines are recorded only
     * while it is on.
     */
    fun init(context: Context, enabled: Boolean) {
        val target = File(context.createDeviceProtectedStorageContext().filesDir, FILE_NAME)
        synchronized(lock) {
            file = target
            lines.clear()
            runCatching { target.readLines() }
                .getOrDefault(emptyList())
                .takeLast(CAPACITY)
                .forEach(lines::addLast)
            _entries.value = lines.joinToString("\n")
        }
        _enabled.value = enabled
        if (target.length() > MAX_BYTES) trim(target)
    }

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        // Record the transition while the old flag still applies.
        if (!value) info("settings", "diagnostic log disabled")
        _enabled.value = value
        if (value) info("settings", "diagnostic log enabled")
    }

    fun info(source: String, message: String) = record('I', source, message, null)

    fun warn(source: String, message: String) = record('W', source, message, null)

    fun error(source: String, message: String, throwable: Throwable? = null) =
        record('E', source, message, throwable)

    private fun record(level: Char, source: String, message: String, throwable: Throwable?) {
        val text = message.replace('\n', ' ')
        when (level) {
            'W' -> Log.w(TAG, "[$source] $text", throwable)
            'E' -> Log.e(TAG, "[$source] $text", throwable)
            else -> Log.i(TAG, "[$source] $text", throwable)
        }
        if (!_enabled.value) return
        val line = "${LocalDateTime.now().format(timeFormat)} $level " +
            "${source.padEnd(8)} $text"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
            _entries.value = lines.joinToString("\n")
        }
        fileWriter.execute { append(line) }
    }

    private fun append(line: String) {
        val target = file ?: return
        synchronized(lock) {
            runCatching { target.appendText(line + "\n") }
            if (target.length() > MAX_BYTES) trimLocked(target)
        }
    }

    private fun trim(target: File) = synchronized(lock) { trimLocked(target) }

    /** Keeps the newest lines and drops the rest through a replace, not an append. */
    private fun trimLocked(target: File) {
        val keep = runCatching { target.readLines().takeLast(CAPACITY) }
            .getOrDefault(emptyList())
        runCatching {
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(keep.joinToString("\n", postfix = "\n"))
            if (!temp.renameTo(target)) {
                target.writeText(keep.joinToString("\n", postfix = "\n"))
                temp.delete()
            }
        }
    }
}
