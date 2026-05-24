package com.example.androidautodisplay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogFileHelper {
    private const val MAX_EVENT_LOG_BYTES = 384 * 1024
    private const val TRIM_EVENT_LOG_TO_BYTES = 256 * 1024
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
            getEventLogFile(context).appendText(entry)
            getNativeLogFile(context).appendText(entry)
        }
    }

    private fun trimEventLogIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_EVENT_LOG_BYTES) {
            return
        }
        val bytes = file.readBytes()
        file.writeBytes(bytes.takeLast(TRIM_EVENT_LOG_TO_BYTES).toByteArray())
    }
}
