package dev.terminal.app

import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.*

class SharedFoldersActivity : TerminalActivity() {
    private var snapshot by mutableStateOf<SharedFoldersSnapshot?>(null)
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var observing: Job? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(binder)
            observing?.cancel()
            observing = lifecycleScope.launch {
                do {
                    refresh()
                    if (snapshot?.operations?.none { it.getString("state") == "RUNNING" } != false) break
                    delay(1000)
                } while (isActive)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; error = getString(R.string.runtime_disconnected) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { SharedFoldersScreen(snapshot, error, saving, { finish() },
            onCreate = { startActivity(Intent(this, SharedFolderCreateActivity::class.java)) },
            onEdit = { folder -> startActivity(SharedFolderEditActivity.intent(this, folder.id)) },
            onCancel = { id -> mutate { it.cancelSharedFolderOperation(id); "" } }) }
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onStop() { observing?.cancel(); observing = null; runtime = null; if (bound) unbindService(connection); bound = false; super.onStop() }
    private suspend fun refresh() {
        val service = runtime ?: return
        val result = withContext(Dispatchers.IO) { runCatching { SharedFoldersSnapshot.parse(service.sharedFoldersJson) } }
        if (runtime === service) { snapshot = result.getOrNull(); if (result.isFailure) error = result.exceptionOrNull()?.message }
    }
    private fun mutate(action: (IRuntimeService) -> String) {
        val service = runtime ?: run { error = getString(R.string.runtime_disconnected); return }
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(service) } }
            val failure = result.getOrElse { it.message ?: "Runtime unavailable" }
            error = failure.takeIf(String::isNotEmpty)
            saving = false
            observing?.cancel()
            observing = lifecycleScope.launch {
                do { refresh(); if (snapshot?.operations?.none { it.getString("state") == "RUNNING" } != false) break; delay(1000) } while (isActive)
            }
        }
    }
}
