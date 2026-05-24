package com.example.androidautodisplay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogFileHelper {
    private const val MAX_EVENT_LOG_BYTES = 384 * 1024
    private const val TRIM_EVENT_LOG_TO_BYTES = 256 * 1024
    private const val MAX_NATIVE_LOG_BYTES = 512 * 1024
    private const val TRIM_NATIVE_LOG_TO_BYTES = 384 * 1024
    private val lock = Any()

    fun getLogDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "LibAutoLogs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getNativeLogFile(context: Context): File {
        val dir = getLogDir(context)
        return File(dir, "native-log.txt")
    }

    fun getEventLogFile(context: Context): File {
        val dir = getLogDir(context)
        return File(dir, "libauto-events.txt")
    }

    fun appendEvent(context: Context, tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            val file = getEventLogFile(context)
            trimEventLogIfNeeded(file)
            file.appendText("$timestamp [$tag] $message\n")
        }
    }

    fun appendException(context: Context, header: String, throwable: Throwable) {
        val entry = buildString {
            append("=== ")
            append(header)
            append(" ===\n")
            append(throwable.stackTraceToString())
            append("\n")
        }
        synchronized(lock) {
            appendBounded(getEventLogFile(context), entry, MAX_EVENT_LOG_BYTES, TRIM_EVENT_LOG_TO_BYTES)
            appendBounded(getNativeLogFile(context), entry, MAX_NATIVE_LOG_BYTES, TRIM_NATIVE_LOG_TO_BYTES)
        }
    }

    private fun trimEventLogIfNeeded(file: File) {
        trimLogIfNeeded(file, MAX_EVENT_LOG_BYTES, TRIM_EVENT_LOG_TO_BYTES)
    }

    private fun appendBounded(file: File, entry: String, maxBytes: Int, trimToBytes: Int) {
        trimLogIfNeeded(file, maxBytes, trimToBytes)
        file.appendText(entry)
        trimLogIfNeeded(file, maxBytes, trimToBytes)
    }

    private fun trimLogIfNeeded(file: File, maxBytes: Int, trimToBytes: Int) {
        if (!file.exists() || file.length() <= maxBytes) {
            return
        }
        val bytes = file.readBytes()
        file.writeBytes(bytes.takeLast(trimToBytes).toByteArray())
    }
}
