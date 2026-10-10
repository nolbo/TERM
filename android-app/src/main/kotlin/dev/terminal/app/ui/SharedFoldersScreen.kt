@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.app.ProfileSection
import dev.terminal.runtime.session.*
import dev.terminal.runtime.storage.*
import org.json.JSONArray
import org.json.JSONObject

internal data class SharedFoldersSnapshot(val folders: List<SharedFolder>, val paths: Map<String, String>,
    val environments: List<Environment>, val sources: List<Pair<String, String>>, val operations: List<JSONObject>) {
    companion object {
        fun parse(encoded: String): SharedFoldersSnapshot {
            val json = JSONObject(encoded)
            fun rows(key: String): List<JSONObject> = json.getJSONArray(key).let { a -> List(a.length()) { a.getJSONObject(it) } }
            val folders = rows("folders")
            return SharedFoldersSnapshot(folders.map(SharedFolder::parse), folders.associate { it.getString("id") to it.getString("hostPath") },
                rows("environments").map(Environment::fromJson), rows("legacySources").map { it.getString("id") to it.getString("name") }, rows("operations"))
        }
    }
}

@Composable
internal fun SharedFoldersScreen(snapshot: SharedFoldersSnapshot?, error: String?, saving: Boolean, onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (SharedFolder) -> Unit,
    onOperation: (JSONObject, () -> Unit) -> Unit, onExport: (String) -> Unit, onCancel: (String) -> Unit) {
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPageScaffold(stringResource(R.string.shared_folders_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SettingsDescription(stringResource(R.string.shared_folders_summary)) }
            item { Text(stringResource(R.string.shared_folders_security), Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (error != null) item { SettingsOperationError(error) }
            if (snapshot == null) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth()) } else {
                item {
                    ProfileSection(R.string.shared_folder_list) {
                        SettingsNavigationRow(stringResource(R.string.shared_folder_create), enabled = !saving,
                            leadingIcon = R.drawable.ic_material_symbol_add_24, onClick = onCreate)
                        snapshot.folders.forEach { folder ->
                            val connections = snapshot.environments.filter { it.id in folder.environments }.joinToString { it.name }
                            SettingsNavigationRow(folder.name,
                                supporting = connections.ifEmpty { stringResource(R.string.shared_folder_no_environments) },
                                enabled = !saving, onClick = { onEdit(folder) })
                        }
                    }
                }
                if (snapshot.folders.isEmpty()) item { SettingsDescription(stringResource(R.string.shared_folders_empty)) }
                if (snapshot.sources.isNotEmpty()) item {
                    ProfileSection(R.string.shared_legacy_title) {
                        snapshot.sources.forEach { (id, name) ->
                            SettingsNavigationRow(stringResource(R.string.shared_legacy_import), name, enabled = !saving,
                                onClick = { importing = id })
                            SettingsNavigationRow(stringResource(R.string.shared_legacy_export), name, enabled = !saving,
                                onClick = { onExport(id) })
                        }
                    }
                    Text(stringResource(R.string.shared_legacy_summary), Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (snapshot.operations.isNotEmpty()) item {
                    ProfileSection(R.string.shared_operations) {
                        snapshot.operations.asReversed().forEach { operation ->
                            Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val mode = when (operation.getString("mode")) {
                                    "TO_VM" -> R.string.shared_to_vm
                                    "FROM_VM" -> R.string.shared_from_vm
                                    "IMPORT_LEGACY" -> R.string.shared_legacy_import
                                    else -> R.string.shared_legacy_export
                                }
                                Text(stringResource(mode), style = MaterialTheme.typography.bodyLarge)
                                val state = when (operation.getString("state")) {
                                    "RUNNING" -> R.string.shared_running
                                    "COMPLETED" -> R.string.shared_completed
                                    "CANCELLED" -> R.string.shared_cancelled
                                    "INTERRUPTED" -> R.string.shared_interrupted
                                    else -> R.string.shared_failed
                                }
                                Text(stringResource(state) + " · " + operation.optLong("bytes") + " B",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!operation.isNull("error")) Text(operation.getString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (operation.getString("state") == "RUNNING") {
                                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                                    TextButton(onClick = { onCancel(operation.getString("id")) }, enabled = !saving,
                                        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    importing?.let { id -> SharedFolderNameDialog(saving, onSave = { name ->
        onOperation(JSONObject().put("mode", "IMPORT_LEGACY").put("sourceId", id).put("name", name)) { importing = null }
    }, onDismiss = { importing = null }) }
}

@Composable
internal fun SharedFolderTransferDialog(request: String, saving: Boolean, canSubmit: Boolean,
    onStart: (JSONObject) -> Unit, onDismiss: () -> Unit) {
    var path by rememberSaveable(request) { mutableStateOf("") }
    val valid = runCatching { SharedRelativePath.components(path) }.isSuccess
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.shared_file_copy)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.shared_relative_file)) }, singleLine = true,
                enabled = !saving, isError = path.isNotEmpty() && !valid, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.shared_transfer_limits), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = {
        onStart(JSONObject(request).put("path", path))
    }, enabled = valid && !saving && canSubmit, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_start_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun SharedFolderEditor(encoded: String, snapshot: SharedFoldersSnapshot?, error: String?, saving: Boolean,
    onSave: (SharedFolder) -> Unit, onDelete: ((String) -> Unit)? = null,
    onTransfer: ((SharedFolder, Environment, String) -> Unit)? = null, onBack: () -> Unit,
    canSubmit: Boolean = true) {
    val original = remember(encoded) { JSONObject(encoded) }
    val newItem = original.optBoolean("_new")
    var name by rememberSaveable(encoded) { mutableStateOf(original.getString("name")) }
    var selected by rememberSaveable(encoded) { mutableStateOf(original.getJSONArray("environments").toString()) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val selectedIds = JSONArray(selected).let { array -> List(array.length()) { EnvironmentId(array.getString(it)) }.toSet() }
    val ids = if (snapshot == null) selectedIds else selectedIds.intersect(snapshot.environments.mapTo(mutableSetOf()) { it.id })
    val originalIds = original.getJSONArray("environments").let { array -> List(array.length()) { EnvironmentId(array.getString(it)) }.toSet() }
    val dirty = name != original.getString("name") || ids != originalIds
    val persisted = snapshot?.folders?.firstOrNull { it.id == original.getString("id") }
    SettingsEditorPage(stringResource(if (newItem) R.string.shared_folder_create else R.string.shared_folder_settings),
        stringResource(R.string.shared_folder_connections_summary), error, saving, newItem, dirty,
        canSave = canSubmit && SharedFolder.NAME.matches(name), onBack = onBack,
        onSave = { onSave(SharedFolder(original.getString("id"), name, ids)) },
        onDelete = if (newItem || onDelete == null) null else { { deleting = true } }) {
        ProfileSection(R.string.workspace_information) {
            SettingsTextRow(R.string.workspace_name, name, hint = R.string.shared_folder_name_rules,
                enabled = !saving, valid = SharedFolder.NAME::matches, confirmLabel = R.string.confirm) { name = it }
        }
        if (snapshot != null && persisted != null && persisted.environments.isNotEmpty()) {
            ProfileSection(R.string.shared_folder_access) {
                snapshot.environments.filter { it.id in persisted.environments }.forEach { environment ->
                    when (environment.runtime) {
                        EnvironmentRuntime.NATIVE, EnvironmentRuntime.ROOTLESS -> {
                            Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(environment.name, style = MaterialTheme.typography.bodyLarge)
                                SelectionContainer { Text(if (environment.runtime == EnvironmentRuntime.NATIVE) snapshot.paths[persisted.id].orEmpty() else persisted.guestPath,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        EnvironmentRuntime.AVF -> {
                            Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(environment.name, style = MaterialTheme.typography.bodyLarge)
                                SelectionContainer { Text(persisted.guestPath, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(stringResource(R.string.shared_avf_mount_summary), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (onTransfer != null) listOf("TO_VM" to R.string.shared_to_vm, "FROM_VM" to R.string.shared_from_vm).forEach { (mode, label) ->
                                SettingsNavigationRow(stringResource(label), environment.name, enabled = !saving && !dirty,
                                    onClick = { onTransfer(persisted, environment, mode) })
                            }
                        }
                    }
                }
            }
            if (snapshot.environments.any { it.id in persisted.environments && it.runtime == EnvironmentRuntime.AVF }) {
                Text(stringResource(R.string.shared_avf_copy_summary), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (dirty) Text(stringResource(R.string.shared_folder_access_saved), Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProfileSection(R.string.shared_folder_environments) {
            snapshot?.environments?.forEach { environment ->
                ListItem(onClick = {
                    selected = JSONArray((if (environment.id in ids) ids - environment.id else ids + environment.id).map { it.value }).toString()
                }, enabled = !saving,
                    supportingContent = { Text(stringResource(runtimeTitleResource(environment.sessionRuntime.wireName))) },
                    trailingContent = { Checkbox(environment.id in ids, null) }, verticalAlignment = Alignment.CenterVertically,
                    shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(environment.name) }
            }
        }
        Text(stringResource(R.string.shared_folders_security), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (deleting && onDelete != null) AlertDialog(onDismissRequest = { if (!saving) deleting = false }, title = { Text(stringResource(R.string.shared_folder_delete)) },
        text = { Text(stringResource(R.string.shared_folder_delete_summary)) },
        confirmButton = { TextButton(onClick = { onDelete(original.getString("id")) }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SharedFolderNameDialog(saving: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.shared_legacy_import)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true,
                enabled = !saving, isError = name.isNotEmpty() && !SharedFolder.NAME.matches(name), modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.shared_folder_name_rules), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onSave(name) }, enabled = !saving && SharedFolder.NAME.matches(name),
        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_start_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}
