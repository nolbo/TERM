package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import dev.terminal.app.ui.DiagnosticsMenuScreen
import dev.terminal.app.ui.TerminalActivity

/** A settings entry point; opening it neither binds nor starts the runtime service. */
class DiagnosticsActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { DiagnosticsMenuScreen({ finish() }) { destination ->
            startActivity(Intent(this, checkNotNull(diagnosticsDestination(destination))))
        } }
    }
}

internal fun diagnosticsDestination(screen: String): Class<out TerminalActivity>? = when (screen) {
    "diagnostics-native" -> NativeDiagnosticsActivity::class.java
    "diagnostics-userspace", "rootless-info" -> UserspaceLinuxDiagnosticsActivity::class.java
    "diagnostics-avf" -> AvfLinuxDiagnosticsActivity::class.java
    "avf-logs" -> AvfLogsActivity::class.java
    "diagnostics-memory" -> MemoryDiagnosticsActivity::class.java
    "crash-reports" -> CrashReportsActivity::class.java
    else -> null
}
