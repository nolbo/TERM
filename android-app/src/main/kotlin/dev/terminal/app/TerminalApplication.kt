package dev.terminal.app

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

class TerminalApplication : Application(), androidx.work.Configuration.Provider,
    dev.terminal.runtime.lifecycle.RuntimeBackendProvider {
    override fun createDefaultRuntimeBackend(context: android.content.Context): dev.terminal.runtime.api.RuntimeBackend =
        dev.terminal.runtime.native.NativeBackend(context.applicationContext, java.io.File(context.filesDir, "native-runtime"))

    override fun createRuntimeBackend(context: android.content.Context): dev.terminal.runtime.api.RuntimeBackend =
        dev.terminal.runtime.avf.system.AospAvfRuntime.createBackend(context)

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
