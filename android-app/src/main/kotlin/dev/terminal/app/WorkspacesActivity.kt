package dev.terminal.app

import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.WorkspacesScreen
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.workspace.*
import kotlinx.coroutines.*

/** Settings bind to runtime-owned metadata. Stopping an Activity only detaches this client. */
abstract class WorkspaceSettingsActivity : TerminalActivity() {
    protected var state by mutableStateOf<WorkspaceRegistry.State?>(null)
        private set
    protected var error by mutableStateOf<String?>(null)
    protected var saving by mutableStateOf(false)
        private set
    protected var connected by mutableStateOf(false)
        private set
    private var runtime: IRuntimeService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(binder); connected = true; refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null; connected = false; error = getString(R.string.runtime_disconnected)
        }
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onStop() { runtime = null; connected = false; if (bound) unbindService(connection); bound = false; super.onStop() }
    protected fun refresh() {
        val service = runtime ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { WorkspaceRegistry.State.parse(service.workspacesJson) } }
            if (runtime === service) { state = result.getOrNull(); error = result.exceptionOrNull()?.message }
        }
    }
    protected fun mutate(done: () -> Unit, action: (IRuntimeService) -> String) {
        val service = runtime ?: run { error = getString(R.string.runtime_disconnected); return }
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { action(service) }.getOrElse { it.message ?: "Runtime unavailable" } }
            error = failure.takeIf(String::isNotEmpty)
            if (failure.isEmpty()) done()
            saving = false
        }
    }
}

class WorkspacesActivity : WorkspaceSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { WorkspacesScreen(state, error, connected, { finish() },
            onCreate = { startActivity(WorkspaceEditActivity.intent(this)) },
            onEdit = { startActivity(WorkspaceEditActivity.intent(this, it.id)) }, saving = saving,
            onReorder = { ids -> mutate({ refresh() }) { it.reorderWorkspaces(org.json.JSONArray(ids).toString()) } }) }
    }
}
