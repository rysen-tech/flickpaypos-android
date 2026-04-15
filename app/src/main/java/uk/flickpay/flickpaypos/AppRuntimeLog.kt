package uk.flickpay.flickpaypos

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AppRuntimeLog {

    private const val FILE_NAME = "flickpaypos_runtime.log"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_LINES = 1800
    private val lock = Any()
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    enum class Level(val shortCode: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E")
    }

    fun d(context: Context?, tag: String, message: String) {
        Log.d(tag, message)
        if (context != null) write(context, Level.DEBUG, tag, message, null)
    }

    fun i(context: Context?, tag: String, message: String) {
        Log.i(tag, message)
        if (context != null) write(context, Level.INFO, tag, message, null)
    }

    fun w(context: Context?, tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, error)
        }
        if (context != null) write(context, Level.WARN, tag, message, error)
    }

    fun e(context: Context?, tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, error)
        }
        if (context != null) write(context, Level.ERROR, tag, message, error)
    }

    fun read(context: Context): String {
        val appContext = context.applicationContext
        synchronized(lock) {
            val file = logFile(appContext)
            if (!file.exists()) return ""
            return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }
    }

    fun clear(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            val file = logFile(appContext)
            if (!file.exists()) return true
            return runCatching { file.writeText("", Charsets.UTF_8); true }.getOrDefault(false)
        }
    }

    private fun write(
        context: Context,
        level: Level,
        tag: String,
        message: String,
        error: Throwable?
    ) {
        val appContext = context.applicationContext
        val entry = buildEntry(level, tag, message, error)
        if (entry.isBlank()) return
        synchronized(lock) {
            val file = logFile(appContext)
            val existing = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            val merged = trimToLimit(appendEntry(existing, entry))
            runCatching { file.writeText(merged, Charsets.UTF_8) }
        }
    }

    private fun buildEntry(level: Level, tag: String, message: String, error: Throwable?): String {
        val ts = timestampFormatter.format(Instant.now())
        val sanitizedMessage = sanitizeLine(message)
        val out = StringBuilder()
        out.append(ts)
            .append(" ")
            .append(level.shortCode)
            .append("/")
            .append(tag.ifBlank { "FlickpayPOS" })
            .append(" ")
            .append(sanitizedMessage)
        if (error != null) {
            val stack = error.stackTraceToString()
                .lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .take(24)
                .toList()
            for (line in stack) {
                out.append("\n    ").append(sanitizeLine(line))
            }
        }
        return out.toString()
    }

    private fun appendEntry(existing: String, entry: String): String {
        if (existing.isBlank()) return entry
        return existing.trimEnd() + "\n" + entry
    }

    private fun trimToLimit(content: String): String {
        val lines = content.lineSequence().toMutableList()
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        var output = lines.joinToString("\n")
        while (output.toByteArray(Charsets.UTF_8).size > MAX_BYTES && lines.isNotEmpty()) {
            lines.removeAt(0)
            output = lines.joinToString("\n")
        }
        return output
    }

    private fun sanitizeLine(value: String): String {
        return value.replace("\r", " ").trimEnd()
    }

    private fun logFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
