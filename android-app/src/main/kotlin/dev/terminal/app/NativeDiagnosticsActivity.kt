package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.native.NativeCompatibility
import dev.terminal.runtime.native.inspectNativeCompatibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NativeDiagnosticsActivity : TerminalActivity() {
    internal var information by mutableStateOf<NativeCompatibility?>(null)
        private set
    private var loading by mutableStateOf(true)
    private var job: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { NativeDiagnosticsScreen(information, loading, { finish() }, ::refresh) }
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { job?.cancel(); super.onPause() }
    private fun refresh() {
        job?.cancel()
        job = lifecycleScope.launch {
            loading = true
            information = withContext(Dispatchers.IO) { inspectNativeCompatibility(applicationContext) }
            loading = false
        }
    }
}
