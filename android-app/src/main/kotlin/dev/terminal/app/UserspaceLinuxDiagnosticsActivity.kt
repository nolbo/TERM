package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.rootless.RootlessEngineInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserspaceLinuxDiagnosticsActivity : TerminalActivity() {
    internal var information by mutableStateOf<RootlessEngineInfo?>(null)
        private set
    private var loading by mutableStateOf(true)
    private var job: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { UserspaceLinuxDiagnosticsScreen(information, loading, { finish() }, ::refresh) }
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { job?.cancel(); super.onPause() }
    private fun refresh() {
        job?.cancel()
        job = lifecycleScope.launch {
            loading = true
            information = try { withContext(Dispatchers.IO) { RootlessEngineInfo.inspect(this@UserspaceLinuxDiagnosticsActivity) } }
                catch (failure: Throwable) { if (failure is CancellationException) throw failure; null }
            loading = false
        }
    }
}
