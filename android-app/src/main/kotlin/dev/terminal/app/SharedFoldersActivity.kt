package dev.terminal.app

import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

class SharedFoldersActivity : TerminalActivity() {
    private var snapshot by mutableStateOf<SharedFoldersSnapshot?>(null)
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var observing: Job? = null
    private var exportSource: String? = null
    private var pendingExportRequest: String? = null
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            pendingExportRequest = JSONObject().put("id", UUID.randomUUID().toString()).put("mode", "EXPORT_LEGACY")
                .put("sourceId", source).put("uri", uri.toString()).toString()
            dispatchPendingExport()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(binder)
            dispatchPendingExport()
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
        exportSource = savedInstanceState?.getString("export-source")
        pendingExportRequest = savedInstanceState?.getString("pending-export")
        compose { SharedFoldersScreen(snapshot, error, saving, { finish() },
            onCreate = { startActivity(Intent(this, SharedFolderCreateActivity::class.java)) },
            onEdit = { folder -> startActivity(SharedFolderEditActivity.intent(this, folder.id)) },
            onOperation = { request, done -> mutate(done) { it.startSharedFolderOperation(UUID.randomUUID().toString(), request.toString()) } },
            onExport = { id -> exportSource = id; exportPicker.launch("TERM-legacy-workspace.zip") },
            onCancel = { id -> mutate { it.cancelSharedFolderOperation(id); "" } }) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export-source", exportSource)
        outState.putString("pending-export", pendingExportRequest)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onStop() { observing?.cancel(); observing = null; runtime = null; if (bound) unbindService(connection); bound = false; super.onStop() }
    private suspend fun refresh() {
        val service = runtime ?: return
        val result = withContext(Dispatchers.IO) { runCatching { SharedFoldersSnapshot.parse(service.sharedFoldersJson) } }
        if (runtime === service) { snapshot = result.getOrNull(); if (result.isFailure) error = result.exceptionOrNull()?.message }
    }
    private fun dispatchPendingExport() {
        val encoded = pendingExportRequest ?: return
        if (runtime == null || saving) return
        val request = JSONObject(encoded)
        mutate(submittedExport = encoded) { it.startSharedFolderOperation(request.getString("id"), encoded) }
    }
    private fun mutate(done: () -> Unit = {}, submittedExport: String? = null, action: (IRuntimeService) -> String) {
        val service = runtime ?: run { error = getString(R.string.runtime_disconnected); return }
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(service) } }
            val failure = result.getOrElse { it.message ?: "Runtime unavailable" }
            // Binder validation failures are visible and require an explicit retry;
            // an unacknowledged transport failure keeps the same id for reconnection.
            if (result.isSuccess && submittedExport != null && pendingExportRequest == submittedExport) pendingExportRequest = null
            error = failure.takeIf(String::isNotEmpty)
            if (failure.isEmpty()) done()
            saving = false
            observing?.cancel()
            observing = lifecycleScope.launch {
                do { refresh(); if (snapshot?.operations?.none { it.getString("state") == "RUNNING" } != false) break; delay(1000) } while (isActive)
            }
            if (submittedExport == null || runtime !== service) dispatchPendingExport()
        }
    }
}
