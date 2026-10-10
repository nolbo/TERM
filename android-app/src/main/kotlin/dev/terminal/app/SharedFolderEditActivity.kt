@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Editing only owns the UI draft and its service binding, never files, sessions or jobs. */
class SharedFolderEditActivity : TerminalActivity() {
    private var snapshot by mutableStateOf<SharedFoldersSnapshot?>(null)
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var runtime: IRuntimeService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(binder)
            connected = true
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            connected = false
            error = getString(R.string.runtime_disconnected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folderId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        compose {
            val persisted = snapshot?.folders?.firstOrNull { it.id == folderId }
            var encoded by rememberSaveable(folderId) { mutableStateOf<String?>(null) }
            var transfer by rememberSaveable(folderId) { mutableStateOf<String?>(null) }
            LaunchedEffect(persisted) {
                // A reconnect refreshes access information without replacing the edit draft.
                if (encoded == null && persisted != null) encoded = persisted.json().toString()
            }
            val original = encoded
            val missing = snapshot != null && persisted == null
            if (original == null) {
                SettingsPageScaffold(stringResource(R.string.shared_folder_settings), { finish() }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        SettingsOperationError(error ?: if (missing) stringResource(R.string.shared_folder_missing) else null)
                        if (snapshot == null && error == null) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            } else {
                SharedFolderEditor(original, snapshot,
                    error ?: if (missing) stringResource(R.string.shared_folder_missing) else null, saving,
                    onSave = { folder -> mutate { it.saveSharedFolder(folder.json().toString()) } },
                    onDelete = { id -> mutate { it.deleteSharedFolder(id) } },
                    onTransfer = { folder, environment, mode ->
                        transfer = JSONObject().put("mode", mode).put("folderId", folder.id)
                            .put("environmentId", environment.id.value).toString()
                    }, onBack = { finish() }, canSubmit = connected && persisted != null)
            }
            transfer?.let { request ->
                SharedFolderTransferDialog(request, saving, canSubmit = connected && persisted != null,
                    onStart = { operation -> mutate {
                        it.startSharedFolderOperation(UUID.randomUUID().toString(), operation.toString())
                    } }, onDismiss = { transfer = null })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) error = getString(R.string.runtime_disconnected)
    }

    override fun onStop() {
        runtime = null
        connected = false
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    private fun refresh() {
        val service = runtime ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SharedFoldersSnapshot.parse(service.sharedFoldersJson) }
            }
            if (runtime === service) {
                snapshot = result.getOrNull()
                error = result.exceptionOrNull()?.message
            }
        }
    }

    private fun mutate(action: (IRuntimeService) -> String) {
        val service = runtime ?: run { error = getString(R.string.runtime_disconnected); return }
        if (saving) return
        if (snapshot?.folders?.none { it.id == intent.getStringExtra(EXTRA_ID) } != false) {
            error = getString(R.string.shared_folder_missing)
            return
        }
        saving = true
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { action(service) }.getOrElse { it.message ?: getString(R.string.runtime_disconnected) }
            }
            saving = false
            error = failure.takeIf(String::isNotEmpty)
            if (failure.isEmpty()) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "sharedFolderId"
        fun intent(context: Context, folderId: String) = Intent(context, SharedFolderEditActivity::class.java)
            .putExtra(EXTRA_ID, folderId)
    }
}
