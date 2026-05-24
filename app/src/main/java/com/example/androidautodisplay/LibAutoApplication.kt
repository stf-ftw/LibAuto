package com.example.androidautodisplay

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess

class LibAutoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFileHelper.appendEvent(this, "Application", "onCreate pid=${Process.myPid()}")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogFileHelper.appendException(this, "Uncaught Java crash on ${thread.name}", throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(10)
            }
        }
    }
}
