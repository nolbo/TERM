package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.SharedFolderEditor
import dev.terminal.app.ui.SharedFoldersSnapshot
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.storage.SharedFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A creation draft is UI-owned; only Save writes through the existing runtime service. */
class SharedFolderCreateActivity : TerminalActivity() {
    private var snapshot by mutableStateOf<SharedFoldersSnapshot?>(null)
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var runtime: IRuntimeService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IRuntimeService.Stub.asInterface(binder)
            runtime = service
            connected = true
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
        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            connected = false
            error = getString(R.string.runtime_disconnected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            val encoded = rememberSaveable {
                JSONObject().put("id", UUID.randomUUID().toString()).put("name", "")
                    .put("environments", JSONArray()).put("_new", true).toString()
            }
            SharedFolderEditor(encoded, snapshot, error, saving,
                onSave = ::save, onBack = { finish() }, canSubmit = connected && snapshot != null)
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

    private fun save(folder: SharedFolder) {
        val service = runtime ?: run { error = getString(R.string.runtime_disconnected); return }
        if (saving || snapshot == null) return
        saving = true
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { service.saveSharedFolder(folder.json().toString()) }
                    .getOrElse { it.message ?: getString(R.string.runtime_disconnected) }
            }
            saving = false
            error = failure.takeIf(String::isNotEmpty)
            if (failure.isEmpty()) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
