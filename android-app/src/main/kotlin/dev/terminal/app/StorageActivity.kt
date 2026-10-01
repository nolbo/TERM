@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.ProfileIcon
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.StorageRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class StorageItem(val runtime: String, val id: String, val bytes: Long?,
    val profiles: List<Pair<String, String>>, val sessions: List<Pair<String, String>>, val name: String? = null) {
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
                    if (row.isNull("bytes")) null else row.getLong("bytes"), names("profiles", "name"), names("sessions", "title"),
                    row.optString("name").takeIf { !row.isNull("name") && it.isNotBlank() })
            } }
        }
    }
}

class StorageActivity : StorageScreenActivity()

/** Shared storage presentation and service operations; each screen owns its Activity lifecycle. */
abstract class StorageScreenActivity : TerminalActivity() {
    protected open val titleResource: Int get() = R.string.storage_title
    protected open fun selectedStorage(): StorageRegistry.Key? = null
    private var runtime by mutableStateOf<IRuntimeService?>(null)
    private var binding = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = try {
            selectedStorage()?.let { "${it.runtime.wireName}:${it.storageId}" }
        } catch (_: IllegalArgumentException) {
            finish()
            return
        }
        compose {
            var entries by remember { mutableStateOf<List<StorageItem>?>(null) }
            var confirm by remember { mutableStateOf<StorageItem?>(null) }
            var deleting by remember { mutableStateOf<StorageItem?>(null) }
            var editingName by rememberSaveable { mutableStateOf(false) }
            var nameDraft by rememberSaveable { mutableStateOf("") }
            var renaming by remember { mutableStateOf<Pair<StorageItem, String>?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var revision by remember { mutableIntStateOf(0) }
            val measurement = remember { Semaphore(1) }
            val scrollState = rememberLazyListState()
            LaunchedEffect(runtime, revision) {
                val service = runtime ?: return@LaunchedEffect
                while (true) {
                    runStorageOperation { withContext(Dispatchers.IO) { StorageItem.parse(service.storageJson) } }
                        .onSuccess { entries = it }.onFailure { entries = null; error = it.message }
                    delay(2000)
                }
            }
            LaunchedEffect(deleting) {
                val row = deleting ?: return@LaunchedEffect
                runStorageOperation { withContext(Dispatchers.IO) {
                    (runtime ?: error(getString(R.string.storage_unavailable))).deleteStorage(row.runtime, row.id)
                } }.onSuccess { result ->
                    if (result.isEmpty()) finish()
                    else error = result
                }.onFailure { error = it.message }
                deleting = null
            }
            LaunchedEffect(renaming) {
                val (row, value) = renaming ?: return@LaunchedEffect
                runStorageOperation { withContext(Dispatchers.IO) {
                    (runtime ?: error(getString(R.string.storage_unavailable))).renameStorage(row.runtime, row.id, value)
                } }.onSuccess { result ->
                    if (result.isEmpty()) {
                        entries = entries?.map { if (it.key == row.key) it.copy(name = value.trim().takeIf(String::isNotEmpty)) else it }
                        revision++
                    } else error = result
                }.onFailure { error = it.message }
                renaming = null
            }
            SettingsPageScaffold(stringResource(titleResource), { finish() }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    state = scrollState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    item {
                        Text(stringResource(R.string.storage_summary), Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error?.let { message -> item {
                        Card(Modifier.fillMaxWidth().padding(top = 20.dp), shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { error = null }, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.usb_dismiss_error))
                                }
                            }
                        }
                    } }
                    if (runtime == null || entries == null) item {
                        StorageSection(R.string.storage_title) {
                            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = if (runtime != null) { { LoadingIndicator(Modifier.size(32.dp)) } } else null,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (runtime == null) R.string.storage_unavailable else R.string.storage_loading))
                            }
                        }
                    }
                    val detail = entries?.firstOrNull { it.key == selected }
                    if (selected != null && entries != null && detail == null) item {
                        StorageSection(R.string.storage_title) { StorageEmpty(R.string.storage_missing) }
                    } else if (detail != null) {
                        item { StorageSection(R.string.storage_title) {
                            StorageRow(detail, runtime, measurement, null)
                            ListItem(onClick = { nameDraft = detail.name.orEmpty(); editingName = true },
                                enabled = renaming == null,
                                supportingContent = { Text(detail.name ?: stringResource(R.string.profile_not_set)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.storage_name))
                            }
                        } }
                        item {
                            StorageSection(R.string.storage_profiles) {
                                if (detail.profiles.isEmpty()) StorageEmpty()
                                detail.profiles.forEach { profile ->
                                    ListItem(onClick = { startActivity(Intent(this@StorageScreenActivity, ProfileEditActivity::class.java).putExtra("profileId", profile.first)) },
                                        leadingContent = { ProfileIcon("auto", detail.runtime) },
                                        trailingContent = { StorageChevron() }, verticalAlignment = Alignment.CenterVertically,
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth()) { Text(profile.second) }
                                }
                            }
                        }
                        if (detail.sessions.isNotEmpty()) item {
                            StorageSection(R.string.storage_sessions) {
                                detail.sessions.forEach { session ->
                                    ListItem(supportingContent = { Text(session.first, style = MaterialTheme.typography.bodySmall) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        verticalAlignment = Alignment.CenterVertically) { Text(session.second.ifBlank { session.first }) }
                                }
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                                Text(stringResource(when {
                                    detail.id == "legacy" -> R.string.storage_legacy_protected
                                    detail.used -> R.string.storage_in_use_protected
                                    else -> R.string.storage_delete_hint
                                }), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { confirm = detail }, enabled = detail.canDelete && deleting == null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Icon(painterResource(R.drawable.ic_material_symbol_delete_24), null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.storage_delete))
                                }
                            }
                        }
                    } else if (selected == null && entries != null) {
                        listOf(true, false).forEach { used ->
                            val group = entries.orEmpty().filter { it.used == used }
                            if (!used && group.isEmpty()) return@forEach
                            val title = if (used) R.string.storage_used else R.string.storage_unused
                            item {
                                Text(stringResource(title), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            if (group.isEmpty()) item { StorageGroupCard(true, true) { StorageEmpty() } }
                            // Keep each row lazy while drawing one continuous section card.
                            itemsIndexed(group, key = { _, row -> row.key }) { index, row ->
                                StorageGroupCard(index == 0, index == group.lastIndex) {
                                    StorageRow(row, runtime, measurement) {
                                        startActivity(StorageDetailActivity.intent(this@StorageScreenActivity, row.runtime, row.id))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            entries?.firstOrNull { it.key == selected }?.let { row ->
                if (editingName) AlertDialog(onDismissRequest = { editingName = false },
                    title = { Text(stringResource(R.string.storage_name)) },
                    text = { Column {
                        OutlinedTextField(nameDraft, { value ->
                            nameDraft = value.filterNot { it.isISOControl() }.take(StorageRegistry.MAX_NAME_LENGTH)
                        }, singleLine = true, label = { Text(stringResource(R.string.storage_name)) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.storage_name_hint), style = MaterialTheme.typography.bodySmall)
                    } },
                    confirmButton = { Button(onClick = { renaming = row to nameDraft; editingName = false }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.save))
                    } },
                    dismissButton = { TextButton(onClick = { editingName = false }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.cancel))
                    } })
            }
            confirm?.let { row -> AlertDialog(onDismissRequest = { confirm = null },
                title = { Text(stringResource(R.string.storage_delete)) },
                text = { Text(stringResource(R.string.storage_delete_confirm, row.name ?: row.id)) },
                confirmButton = { TextButton(onClick = { confirm = null; deleting = row }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.storage_delete)) } },
                dismissButton = { TextButton(onClick = { confirm = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } }) }
        }
    }
    @Composable private fun StorageRow(row: StorageItem, service: IRuntimeService?, measurement: Semaphore, click: (() -> Unit)?) {
        var bytes by remember(row.key) { mutableStateOf(row.bytes) }
        var failed by remember(row.key) { mutableStateOf(false) }
        LaunchedEffect(row.key, service) {
            val owner = service ?: return@LaunchedEffect
            while (true) {
                runStorageOperation { measurement.withPermit { withContext(Dispatchers.IO) { owner.measureStorage(row.runtime, row.id) } } }
                    .onSuccess { bytes = it; failed = false }.onFailure { failed = true }
                delay(60_000)
            }
        }
        val size = bytes?.let { Formatter.formatShortFileSize(this, it) }
            ?: stringResource(if (failed) R.string.storage_size_unknown else R.string.storage_size_calculating)
        val runtimeTitle = stringResource(runtimeTitleResource(row.runtime))
        val title = row.name ?: runtimeTitle
        val description = stringResource(R.string.storage_counts, size, row.profiles.size, row.sessions.size)
        val supporting: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.name != null) Text(runtimeTitle)
                Text(row.id, style = MaterialTheme.typography.bodySmall,
                    maxLines = if (click == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text(description)
            }
        }
        val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        if (click == null) ListItem(supportingContent = supporting,
            verticalAlignment = Alignment.CenterVertically, colors = colors) { Text(title) }
        else ListItem(onClick = click, supportingContent = supporting,
            trailingContent = { StorageChevron() }, verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth(), colors = colors) { Text(title) }
    }
    @Composable private fun StorageSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp)) { ProfileSection(title, content) }
    }
    @Composable private fun StorageGroupCard(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
        val corners = MaterialTheme.shapes.extraLarge
        Card(Modifier.fillMaxWidth(), shape = corners.copy(
            topStart = if (first) corners.topStart else CornerSize(0.dp),
            topEnd = if (first) corners.topEnd else CornerSize(0.dp),
            bottomStart = if (last) corners.bottomStart else CornerSize(0.dp),
            bottomEnd = if (last) corners.bottomEnd else CornerSize(0.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), content = content)
    }
    @Composable private fun StorageEmpty(message: Int = R.string.storage_none) {
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
            Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable private fun StorageChevron() {
        Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null)
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
