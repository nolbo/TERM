package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.MemoryDiagnosticsScreen
import kotlinx.coroutines.*
import org.json.JSONObject

class MemoryDiagnosticsActivity : RuntimeInformationActivity() {
    override val createServiceOnBind = false
    internal var snapshot by mutableStateOf<MemorySnapshot?>(null)
        private set
    private var refreshing by mutableStateOf(false)
    private var resumed = false
    private var polling: Job? = null
    internal val measuring get() = polling?.isActive == true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { MemoryDiagnosticsScreen(snapshot, refreshing, { finish() }, ::refresh) }
    }
    override fun onResume() { super.onResume(); resumed = true; refresh() }
    override fun onPause() { resumed = false; polling?.cancel(); super.onPause() }
    override fun runtimeInformationChanged() { if (resumed) refresh() }
    internal fun refresh() {
        if (!resumed) return
        polling?.cancel()
        polling = lifecycleScope.launch {
            while (isActive) {
                refreshing = true
                val service = runtime
                snapshot = withContext(Dispatchers.IO) {
                    measureMemory(this@MemoryDiagnosticsActivity) { service?.runtimeAppMemoryJson?.let(::JSONObject) }
                }
                refreshing = false
                delay(15_000)
            }
        }
    }
}
