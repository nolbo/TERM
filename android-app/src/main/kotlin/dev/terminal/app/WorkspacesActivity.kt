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

class WorkspacesActivity : TerminalActivity() {
    private var state by mutableStateOf<WorkspaceRegistry.State?>(null)
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var runtime: IRuntimeService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder); refresh() }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; error = getString(R.string.runtime_disconnected) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profiles = SessionProfiles(this).all()
        compose { WorkspacesScreen(state, profiles, error, saving, { finish() },
            onSave = { workspace, done -> mutate(done) { it.saveWorkspace(workspace.json().toString()) } },
            onDelete = { id, done -> mutate(done) { it.deleteWorkspace(id) } },
            onRun = { workspace, launch ->
                runCatching {
                    val profile = profiles.first { it.id == launch.profileId }
                    val spec = launch.resolve(profile, SessionProfiles(this)::resolve)
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("workspace-launch", spec.toJson().toString()).putExtra("workspace-id", workspace.id))
                }.onFailure { error = it.message }
            }) }
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onStop() { runtime = null; if (bound) unbindService(connection); bound = false; super.onStop() }
    private fun refresh() {
        val service = runtime ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { WorkspaceRegistry.State.parse(service.workspacesJson) } }
            if (runtime === service) { state = result.getOrNull(); error = result.exceptionOrNull()?.message }
        }
    }
    private fun mutate(done: () -> Unit, action: (IRuntimeService) -> String) {
        val service = runtime ?: return
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { action(service) }.getOrElse { it.message ?: "Runtime unavailable" } }
            error = failure.takeIf(String::isNotEmpty)
            if (failure.isEmpty()) done()
            saving = false
            refresh()
        }
    }
}
