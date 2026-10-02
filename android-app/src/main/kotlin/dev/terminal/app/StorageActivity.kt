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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.ProfileIcon
import dev.terminal.app.ui.FloatingToolbarAction
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.SettingsItemSpacing
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
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
    val profiles: List<Pair<String, String>>, val sessions: List<Pair<String, String>>, val name: String? = null,
    val migratedLegacy: Boolean = false) {
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
                    row.optString("name").takeIf { !row.isNull("name") && it.isNotBlank() }, row.optBoolean("migratedLegacy"))
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
            var selectedKey by remember { mutableStateOf(selected) }
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
                        .onSuccess { rows ->
                            entries = rows
                            rows.firstOrNull { it.migratedLegacy && selectedKey == "${it.runtime}:legacy" }
                                ?.let { selectedKey = it.key }
                        }.onFailure { entries = null; error = it.message }
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
            val detail = entries?.firstOrNull { it.key == selectedKey }
            SettingsPageScaffold(stringResource(titleResource), { finish() },
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    detail?.let { row ->
                        StorageActions(
                            enabled = runtime != null && renaming == null && deleting == null,
                            canDelete = row.canDelete,
                            onRename = { nameDraft = row.name.orEmpty(); editingName = true },
                            onDelete = { confirm = row },
                        )
                    }
                }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    state = scrollState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = if (detail != null) FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2 else 8.dp)) {
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
                            ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
                                leadingContent = if (runtime != null) { { LoadingIndicator(Modifier.size(32.dp)) } } else null,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (runtime == null) R.string.storage_unavailable else R.string.storage_loading))
                            }
                        }
                    }
                    if (selectedKey != null && entries != null && detail == null) item {
                        StorageSection(R.string.storage_title) { StorageEmpty(R.string.storage_missing) }
                    } else if (detail != null) {
                        item { StorageSection(R.string.storage_information) {
                            StorageInfoRow(R.string.storage_name, detail.name ?: stringResource(R.string.profile_not_set))
                            StorageInfoRow(R.string.profile_runtime, stringResource(runtimeTitleResource(detail.runtime)))
                            StorageInfoRow(R.string.storage_id, detail.id)
                            StorageInfoRow(R.string.storage_size, storageSize(detail, runtime, measurement))
                            StorageInfoRow(R.string.storage_profile_count, stringResource(R.string.storage_count_value, detail.profiles.size))
                            StorageInfoRow(R.string.storage_session_count, stringResource(R.string.storage_count_value, detail.sessions.size))
                        } }
                        item {
                            StorageSection(R.string.storage_profiles) {
                                if (detail.profiles.isEmpty()) StorageEmpty()
                                detail.profiles.forEach { profile ->
                                    ListItem(onClick = { startActivity(Intent(this@StorageScreenActivity, ProfileEditActivity::class.java).putExtra("profileId", profile.first)) },
                                        leadingContent = { ProfileIcon("auto", detail.runtime) },
                                        trailingContent = { StorageChevron() }, verticalAlignment = Alignment.CenterVertically,
                                        colors = terminalSettingsListItemColors(),
                                        shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth()) { Text(profile.second) }
                                }
                            }
                        }
                        if (detail.sessions.isNotEmpty()) item {
                            StorageSection(R.string.storage_sessions) {
                                detail.sessions.forEach { session ->
                                    ListItem(modifier = Modifier.settingsItemSurface(), supportingContent = { Text(session.first, style = MaterialTheme.typography.bodySmall) },
                                        colors = terminalSettingsListItemColors(),
                                        verticalAlignment = Alignment.CenterVertically) { Text(session.second.ifBlank { session.first }) }
                                }
                            }
                        }
                    } else if (selectedKey == null && entries != null) {
                        listOf(true, false).forEach { used ->
                            val group = entries.orEmpty().filter { it.used == used }
                            if (!used && group.isEmpty()) return@forEach
                            val title = if (used) R.string.storage_used else R.string.storage_unused
                            item {
                                Text(stringResource(title), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            if (group.isEmpty()) item { StorageGroupCard(true, true) { StorageEmpty() } }
                            // Keep each row lazy with small gaps and reduced inner corners.
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
            entries?.firstOrNull { it.key == selectedKey }?.let { row ->
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
    @Composable private fun storageSize(row: StorageItem, service: IRuntimeService?, measurement: Semaphore): String {
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
        return bytes?.let { Formatter.formatShortFileSize(this, it) }
            ?: stringResource(if (failed) R.string.storage_size_unknown else R.string.storage_size_calculating)
    }
    @Composable private fun StorageRow(row: StorageItem, service: IRuntimeService?, measurement: Semaphore, click: () -> Unit) {
        val size = storageSize(row, service, measurement)
        val runtimeTitle = stringResource(runtimeTitleResource(row.runtime))
        val colors = terminalSettingsListItemColors()
        ListItem(onClick = click, supportingContent = { Text(size) },
            trailingContent = { StorageChevron() }, verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth(), colors = colors) { Text(runtimeTitle) }
    }
    @Composable private fun StorageInfoRow(label: Int, value: String) {
        ListItem(modifier = Modifier.settingsItemSurface(), supportingContent = { Text(value) }, verticalAlignment = Alignment.CenterVertically,
            colors = terminalSettingsListItemColors()) {
            Text(stringResource(label))
        }
    }
    @Composable private fun StorageActions(enabled: Boolean, canDelete: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
        HorizontalFloatingToolbar(expanded = true) {
            FloatingToolbarAction(stringResource(R.string.storage_rename), R.drawable.ic_toolbar_edit,
                enabled = enabled, onClick = onRename)
            FloatingToolbarAction(stringResource(R.string.storage_delete), R.drawable.ic_material_symbol_delete_24,
                enabled = enabled && canDelete, onClick = onDelete, destructive = true)
        }
    }
    @Composable private fun StorageSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp)) { ProfileSection(title, content) }
    }
    @Composable private fun StorageGroupCard(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
        val corners = MaterialTheme.shapes.extraLarge
        Card(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else SettingsItemSpacing), shape = corners.copy(
            topStart = if (first) corners.topStart else CornerSize(4.dp),
            topEnd = if (first) corners.topEnd else CornerSize(4.dp),
            bottomStart = if (last) corners.bottomStart else CornerSize(4.dp),
            bottomEnd = if (last) corners.bottomEnd else CornerSize(4.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), content = content)
    }
    @Composable private fun StorageEmpty(message: Int = R.string.storage_none) {
        ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors()) {
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
