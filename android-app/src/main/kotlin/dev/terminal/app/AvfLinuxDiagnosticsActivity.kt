package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.avf.system.AvfDiagnostics
import dev.terminal.runtime.avf.system.inspectAvfDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AvfLinuxDiagnosticsActivity : TerminalActivity() {
    internal var information by mutableStateOf<AvfDiagnostics?>(null)
        private set
    private var loading by mutableStateOf(true)
    private var job: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { AvfLinuxDiagnosticsScreen(information, loading, { finish() }, ::refresh,
            { startActivity(android.content.Intent(this, AvfLogsActivity::class.java)) }) }
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { job?.cancel(); super.onPause() }
    private fun refresh() {
        job?.cancel()
        job = lifecycleScope.launch {
            loading = true
            information = withContext(Dispatchers.IO) { inspectAvfDiagnostics(this@AvfLinuxDiagnosticsActivity) }
            information?.let { info ->
                dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("avf.probe", code = info.status.name)
                info.checks.forEach { check -> dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record(
                    "avf.probe.check", code = "${check.condition.name}.${check.status.name}.${check.reason.name}") }
            }
            loading = false
        }
    }
}
