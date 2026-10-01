@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class StorageItem(val runtime: String, val id: String, val bytes: Long?,
    val profiles: List<Pair<String, String>>, val sessions: List<Pair<String, String>>) {
    val key get() = "$runtime:$id"
    val used get() = profiles.isNotEmpty() || sessions.isNotEmpty()
    val canDelete get() = !used && id != "legacy"
    companion object {
        fun parse(encoded: String): List<StorageItem> = JSONArray(encoded).let { array ->
            List(array.length()) { index -> array.getJSONObject(index).let { row ->
                fun names(field: String, title: String) = row.getJSONArray(field).let { entries ->
                    List(entries.length()) { i -> entries.getJSONObject(i).let { it.getString("id") to it.getString(title) } }
                }
                StorageItem(row.getString("runtime"), row.getString("storageId"),
                    if (row.isNull("bytes")) null else row.getLong("bytes"), names("profiles", "name"), names("sessions", "title"))
            } }
        }
    }
}

/** Observes storage and requests service operations; owns no runtime or filesystem lifecycle. */
class StorageActivity : TerminalActivity() {
    private var runtime by mutableStateOf<IRuntimeService?>(null)
    private var binding = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            var entries by remember { mutableStateOf<List<StorageItem>?>(null) }
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            var confirm by remember { mutableStateOf<StorageItem?>(null) }
            var deleting by remember { mutableStateOf<StorageItem?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var revision by remember { mutableIntStateOf(0) }
            val measurement = remember { Semaphore(1) }
            val back = { if (selected != null) selected = null else finish() }
            BackHandler { back() }
            LaunchedEffect(runtime, revision) {
                val service = runtime ?: return@LaunchedEffect
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { StorageItem.parse(service.storageJson) } }
                        .onSuccess { entries = it }.onFailure { entries = null; error = it.message }
                    delay(2000)
                }
            }
            LaunchedEffect(deleting) {
                val row = deleting ?: return@LaunchedEffect
                runCatching { withContext(Dispatchers.IO) {
                    (runtime ?: error(getString(R.string.usb_service_unavailable))).deleteStorage(row.runtime, row.id)
                } }.onSuccess { result ->
                    if (result.isEmpty()) { selected = null; entries = entries?.filterNot { it.key == row.key }; revision++ }
                    else error = result
                }.onFailure { error = it.message }
                deleting = null
            }
            SettingsPageScaffold(stringResource(R.string.storage_title), back) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.storage_summary), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (runtime == null) item { Text(stringResource(R.string.usb_service_unavailable)) }
                    else if (entries == null) item { CircularProgressIndicator() }
                    val detail = entries?.firstOrNull { it.key == selected }
                    if (selected != null && entries != null && detail == null) item { Text(stringResource(R.string.storage_missing)) }
                    else if (detail != null) {
                        item { StorageRow(detail, runtime, measurement, null) }
                        item { Text(stringResource(R.string.storage_profiles), style = MaterialTheme.typography.titleMedium) }
                        if (detail.profiles.isEmpty()) item { Text(stringResource(R.string.storage_none)) }
                        items(detail.profiles, key = { "profile:${it.first}" }) { profile ->
                            ListItem(onClick = { startActivity(Intent(this@StorageActivity, ProfileEditActivity::class.java).putExtra("profileId", profile.first)) },
                                shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth()) { Text(profile.second) }
                        }
                        item { Text(stringResource(R.string.storage_sessions), style = MaterialTheme.typography.titleMedium) }
                        if (detail.sessions.isEmpty()) item { Text(stringResource(R.string.storage_none)) }
                        items(detail.sessions, key = { "session:${it.first}" }) { session ->
                            ListItem(supportingContent = { Text(session.first) }) { Text(session.second.ifBlank { session.first }) }
                        }
                        item { Text(stringResource(when {
                            detail.id == "legacy" -> R.string.storage_legacy_protected
                            detail.used -> R.string.storage_in_use_protected
                            else -> R.string.storage_delete_hint
                        }), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { Button(onClick = { confirm = detail }, enabled = detail.canDelete && deleting == null,
                            shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text(stringResource(R.string.storage_delete))
                        } }
                    } else if (selected == null && entries != null) {
                        listOf(true, false).forEach { used ->
                            item { Text(stringResource(if (used) R.string.storage_used else R.string.storage_unused), style = MaterialTheme.typography.titleMedium) }
                            val group = entries.orEmpty().filter { it.used == used }
                            if (group.isEmpty()) item { Text(stringResource(R.string.storage_none)) }
                            items(group, key = { it.key }) { row -> StorageRow(row, runtime, measurement) { selected = row.key } }
                        }
                    }
                }
            }
            confirm?.let { row -> AlertDialog(onDismissRequest = { confirm = null },
                title = { Text(stringResource(R.string.storage_delete)) },
                text = { Text(stringResource(R.string.storage_delete_confirm, row.id)) },
                confirmButton = { TextButton(onClick = { confirm = null; deleting = row }) { Text(stringResource(R.string.storage_delete)) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } }) }
        }
    }
    @Composable private fun StorageRow(row: StorageItem, service: IRuntimeService?, measurement: Semaphore, click: (() -> Unit)?) {
        var bytes by remember(row.key) { mutableStateOf(row.bytes) }
        var failed by remember(row.key) { mutableStateOf(false) }
        LaunchedEffect(row.key, service) {
            val owner = service ?: return@LaunchedEffect
            while (true) {
                runCatching { measurement.withPermit { withContext(Dispatchers.IO) { owner.measureStorage(row.runtime, row.id) } } }
                    .onSuccess { bytes = it; failed = false }.onFailure { failed = true }
                delay(60_000)
            }
        }
        val size = bytes?.let { Formatter.formatShortFileSize(this, it) }
            ?: stringResource(if (failed) R.string.storage_size_unknown else R.string.storage_size_calculating)
        val title = stringResource(runtimeTitleResource(row.runtime)) + " · " + row.id
        val description = stringResource(R.string.storage_counts, size, row.profiles.size, row.sessions.size)
        if (click == null) ListItem(supportingContent = { Text(description) }) { Text(title) }
        else ListItem(onClick = click, supportingContent = { Text(description) }, shapes = terminalListItemShapes(),
            modifier = Modifier.fillMaxWidth(), colors = ListItemDefaults.colors(containerColor = Color.Transparent)) { Text(title) }
    }
    override fun onStart() {
        super.onStart()
        binding = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        runtime = null
        if (binding) unbindService(connection)
        binding = false
        super.onStop()
    }
}
