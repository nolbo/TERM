package dev.terminal.app

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

class TerminalApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setDefaultProcessName(packageName).build()
    internal val appUpdates by lazy { dev.terminal.app.update.AppUpdateController(this) }
    internal val terminalSessions by lazy { TerminalSessionStore(this) }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            getSharedPreferences("crash-report", MODE_PRIVATE).edit()
                .putString("report", "시간: ${java.util.Date()}\n스레드: ${thread.name}\n\n" + StringWriter().also { error.printStackTrace(PrintWriter(it)) })
                // The process is about to die; apply() can lose the report
                // before its asynchronous disk write has run.
                .commit()
            previous?.uncaughtException(thread, error)
        }
    }
}
