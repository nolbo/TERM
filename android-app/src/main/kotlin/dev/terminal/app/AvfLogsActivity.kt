package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.AvfLogsScreen
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.lifecycle.ConnectionLogSnapshot
import dev.terminal.runtime.lifecycle.ConnectionLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fixed app-private diagnostic files only. No VM, service binding or guest command. */
class AvfLogsActivity : TerminalActivity() {
    private val store by lazy { ConnectionLogStore(filesDir, SystemClock::elapsedRealtimeNanos) }
    private var snapshot by mutableStateOf<ConnectionLogSnapshot?>(null)
    private var loading by mutableStateOf(true)
    private var changing by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var refreshJob: Job? = null
    private var revision = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            AvfLogsScreen(snapshot, loading, changing, error, { finish() }, ::refresh,
                { enabled -> change { store.setEnabled(enabled) } }, { change { store.clear() } }, ::copy)
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { refreshJob?.cancel(); super.onPause() }

    private fun refresh() {
        if (changing) return
        refreshJob?.cancel()
        val current = ++revision
        refreshJob = lifecycleScope.launch {
            loading = true
            error = null
            try { snapshot = withContext(Dispatchers.IO) { store.snapshot() } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = getString(R.string.avf_logs_read_failed)
            } finally { if (current == revision) loading = false }
        }
    }

    private fun change(operation: () -> Unit) {
        if (changing) return
        refreshJob?.cancel()
        revision++
        changing = true
        error = null
        lifecycleScope.launch {
            try { snapshot = withContext(Dispatchers.IO) { operation(); store.snapshot() } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = getString(R.string.avf_logs_change_failed)
            } finally { changing = false; loading = false }
        }
    }

    private fun copy(text: String) {
        runCatching { getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.avf_logs_title), text)) }
            .onFailure { error = getString(R.string.avf_logs_copy_failed) }
    }
}
