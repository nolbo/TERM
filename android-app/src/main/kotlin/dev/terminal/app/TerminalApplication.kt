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
            runCatching {
                val capturedAt = System.currentTimeMillis()
                val text = "시간: ${java.util.Date(capturedAt)}\n프로세스: ${Application.getProcessName()}\n스레드: ${thread.name}\n\n" +
                    StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val archived = runCatching { CrashReportStore.from(this).record(text, error.toString(), capturedAt) }.getOrNull()
                val editor = getSharedPreferences(CrashReportStore.PREFERENCES, MODE_PRIVATE).edit()
                    .putString(CrashReportStore.KEY_REPORT, archived?.text ?: text)
                if (archived != null) editor.putString(CrashReportStore.KEY_REPORT_ID, archived.id)
                else editor.remove(CrashReportStore.KEY_REPORT_ID)
                // The dying process must commit before delegating to Android's crash handler.
                editor.commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
