package dev.terminal.app

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.LocalServicesScreen
import dev.terminal.app.ui.LocalServicesSnapshot
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.forwarding.LocalService
import dev.terminal.runtime.ipc.ILocalServicesListener
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Binds and observes only while visible. No query starts an execution environment. */
class LocalServicesActivity : TerminalActivity() {
    private var snapshot by mutableStateOf<LocalServicesSnapshot?>(null)
    private var unavailable by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var saving by mutableStateOf(false)
    private var actionError by mutableStateOf<String?>(null)
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var visible = false
    private var refreshJob: Job? = null
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val listener = object : ILocalServicesListener.Stub() {
        override fun onServicesChanged() { refreshRequests.trySend(Unit) }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) return
            runtime = IRuntimeService.Stub.asInterface(binder)
            runCatching { runtime?.registerLocalServicesListener(listener) }
            refreshRequests.trySend(Unit)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null; snapshot = null; unavailable = true; loading = false
        }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            if (bound) runCatching { unbindService(this) }
            bound = false
            if (visible) bindRuntime()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            LocalServicesScreen(snapshot, loading, unavailable, saving, actionError,
                { finish() }, ::refresh,
                onForward = { id, port, protocol, done -> mutate(done) { it.updateLocalServiceForwarding(id, port, protocol) } },
                onCopy = ::copy, onBrowse = ::browse, onDismissError = { actionError = null })
        }
    }
    override fun onStart() {
        super.onStart()
        visible = true
        refreshJob = lifecycleScope.launch {
            for (request in refreshRequests) {
                val service = runtime ?: continue
                loading = true
                val result = withContext(Dispatchers.IO) { runCatching { LocalServicesSnapshot.parse(service.localServicesJson) } }
                if (runtime !== service || !visible) continue
                snapshot = result.getOrNull(); unavailable = result.isFailure; loading = false
            }
        }
        bindRuntime()
    }
    private fun bindRuntime() {
        loading = true; unavailable = false
        bound = runCatching { bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) { loading = false; unavailable = true }
    }
    override fun onStop() {
        visible = false
        runCatching { runtime?.unregisterLocalServicesListener(listener) }
        runtime = null
        refreshJob?.cancel(); refreshJob = null
        if (bound) runCatching { unbindService(connection) }
        bound = false
        super.onStop()
    }
    private fun refresh() {
        if (!bound) bindRuntime() else refreshRequests.trySend(Unit)
    }
    private fun mutate(done: () -> Unit, action: (IRuntimeService) -> String) {
        val service = runtime ?: return
        if (saving) return
        saving = true; actionError = null
        lifecycleScope.launch {
            try {
                val error = withContext(Dispatchers.IO) { runCatching { action(service) }.getOrDefault("RUNTIME_UNAVAILABLE") }
                if (error.isEmpty()) done() else actionError = error
                refreshRequests.trySend(Unit)
            } finally { saving = false }
        }
    }
    private fun copy(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.local_services_android_address), value))
        Toast.makeText(this, getString(R.string.local_services_copied, value), Toast.LENGTH_SHORT).show()
    }
    private fun browse(service: LocalService) {
        val url = service.browserUrl ?: return
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https") || uri.host != "127.0.0.1" || uri.port !in 1..65_535) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { Toast.makeText(this, R.string.local_services_browser_unavailable, Toast.LENGTH_LONG).show() }
    }
    companion object {
        fun intent(context: Context) = Intent(context, LocalServicesActivity::class.java)
    }
}
