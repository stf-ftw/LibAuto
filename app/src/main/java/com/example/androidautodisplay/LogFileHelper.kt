package com.example.androidautodisplay

import android.content.Context
import java.io.File

object LogFileHelper {
    fun getNativeLogFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "LibAutoLogs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "native-log.txt")
    }

    fun appendException(context: Context, header: String, throwable: Throwable) {
        val file = getNativeLogFile(context)
        val entry = buildString {
            append("=== ")
            append(header)
            append(" ===\n")
            append(throwable.stackTraceToString())
            append("\n")
        }
        file.appendText(entry)
    }
}
