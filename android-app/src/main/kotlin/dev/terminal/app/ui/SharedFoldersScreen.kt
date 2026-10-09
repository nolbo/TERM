@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.session.*
import dev.terminal.runtime.storage.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    onSave: (SharedFolder, () -> Unit) -> Unit, onDelete: (String, () -> Unit) -> Unit,
    onOperation: (JSONObject, () -> Unit) -> Unit, onExport: (String) -> Unit, onCancel: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    var transfer by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPageScaffold(stringResource(R.string.shared_folders_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.shared_folders_summary), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.shared_folders_security), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                SettingsGroup {
                    ListItem(onClick = { editing = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "").put("environments", JSONArray()).toString() }, enabled = !saving,
                        shapes = terminalListItemShapes()) { Text(stringResource(R.string.shared_folder_create)) }
                }
                snapshot.folders.forEach { folder ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(folder.name, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelLarge)
                        SettingsGroup {
                            ListItem(onClick = { editing = folder.json().toString() }, enabled = !saving,
                                shapes = terminalListItemShapes(), supportingContent = { Text(stringResource(R.string.shared_folder_connections_summary)) }) { Text(stringResource(R.string.shared_folder_manage)) }
                            folder.environments.forEach { id ->
                                val environment = snapshot.environments.firstOrNull { it.id == id }
                                if (environment != null) {
                                    Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(environment.name, style = MaterialTheme.typography.titleSmall)
                                        when (environment.runtime) {
                                            EnvironmentRuntime.NATIVE -> SelectionContainer { Text(snapshot.paths[folder.id].orEmpty(), style = MaterialTheme.typography.bodySmall) }
                                            EnvironmentRuntime.ROOTLESS -> SelectionContainer { Text(folder.guestPath, style = MaterialTheme.typography.bodySmall) }
                                            EnvironmentRuntime.AVF -> {
                                                Text(stringResource(R.string.shared_avf_copy_summary), style = MaterialTheme.typography.bodySmall)
                                                listOf("TO_VM" to R.string.shared_to_vm, "FROM_VM" to R.string.shared_from_vm).forEach { (mode, label) ->
                                                    TextButton(onClick = { transfer = JSONObject().put("mode", mode).put("folderId", folder.id).put("environmentId", id.value).toString() }, enabled = !saving) { Text(stringResource(label)) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            ListItem(onClick = { deleting = folder.id }, enabled = !saving, shapes = terminalListItemShapes()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (snapshot.sources.isNotEmpty()) {
                    Text(stringResource(R.string.shared_legacy_title), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.shared_legacy_summary), style = MaterialTheme.typography.bodySmall)
                    snapshot.sources.forEach { (id, name) ->
                        SettingsGroup {
                            ListItem(onClick = { importing = id }, enabled = !saving, shapes = terminalListItemShapes(),
                                supportingContent = { Text(name) }) { Text(stringResource(R.string.shared_legacy_import)) }
                            ListItem(onClick = { onExport(id) }, enabled = !saving, shapes = terminalListItemShapes()) { Text(stringResource(R.string.shared_legacy_export)) }
                        }
                    }
                }
                if (snapshot.operations.isNotEmpty()) {
                    Text(stringResource(R.string.shared_operations), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelLarge)
                    SettingsGroup {
                        snapshot.operations.asReversed().forEach { operation ->
                            Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val mode = when (operation.getString("mode")) {
                                    "TO_VM" -> R.string.shared_to_vm
                                    "FROM_VM" -> R.string.shared_from_vm
                                    "IMPORT_LEGACY" -> R.string.shared_legacy_import
                                    else -> R.string.shared_legacy_export
                                }
                                Text(stringResource(mode), style = MaterialTheme.typography.titleSmall)
                                val state = when (operation.getString("state")) {
                                    "RUNNING" -> R.string.shared_running
                                    "COMPLETED" -> R.string.shared_completed
                                    "CANCELLED" -> R.string.shared_cancelled
                                    "INTERRUPTED" -> R.string.shared_interrupted
                                    else -> R.string.shared_failed
                                }
                                Text(stringResource(state) + " · " + operation.optLong("bytes") + " B", style = MaterialTheme.typography.bodySmall)
                                if (!operation.isNull("error")) Text(operation.getString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (operation.getString("state") == "RUNNING") {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    TextButton(onClick = { onCancel(operation.getString("id")) }) { Text(stringResource(R.string.cancel)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (snapshot != null) editing?.let { encoded -> SharedFolderEditor(encoded, snapshot.environments, saving,
        onSave = { onSave(it) { editing = null } }, onDismiss = { editing = null }) }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.shared_folder_delete)) },
        text = { Text(stringResource(R.string.shared_folder_delete_summary)) },
        confirmButton = { TextButton(onClick = { onDelete(id) { deleting = null } }, enabled = !saving) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
    importing?.let { id -> SharedFolderNameDialog(onSave = { name ->
        onOperation(JSONObject().put("mode", "IMPORT_LEGACY").put("sourceId", id).put("name", name)) { importing = null }
    }, onDismiss = { importing = null }) }
    transfer?.let { encoded ->
        var path by rememberSaveable(encoded) { mutableStateOf("") }
        val valid = runCatching { SharedRelativePath.components(path) }.isSuccess
        AlertDialog(onDismissRequest = { transfer = null }, title = { Text(stringResource(R.string.shared_file_copy)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.shared_relative_file)) }, singleLine = true)
                Text(stringResource(R.string.shared_transfer_limits), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { onOperation(JSONObject(encoded).put("path", path)) { transfer = null } }, enabled = valid && !saving) { Text(stringResource(R.string.shared_start_copy)) } },
            dismissButton = { TextButton(onClick = { transfer = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SharedFolderEditor(encoded: String, environments: List<Environment>, saving: Boolean, onSave: (SharedFolder) -> Unit, onDismiss: () -> Unit) {
    val original = remember(encoded) { JSONObject(encoded) }
    var name by rememberSaveable(encoded) { mutableStateOf(original.getString("name")) }
    var selected by rememberSaveable(encoded) { mutableStateOf(original.getJSONArray("environments").toString()) }
    val ids = JSONArray(selected).let { array -> List(array.length()) { EnvironmentId(array.getString(it)) }.toSet() }
        .intersect(environments.mapTo(mutableSetOf()) { it.id })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.shared_folder_manage)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true)
            Text(stringResource(R.string.shared_folder_name_rules), style = MaterialTheme.typography.bodySmall)
            environments.forEach { environment ->
                ListItem(onClick = { selected = JSONArray((if (environment.id in ids) ids - environment.id else ids + environment.id).map { it.value }).toString() },
                    leadingContent = { Checkbox(environment.id in ids, null) }, supportingContent = { Text(stringResource(runtimeTitleResource(environment.sessionRuntime.wireName))) },
                    shapes = terminalListItemShapes()) { Text(environment.name) }
            }
            Text(stringResource(R.string.shared_folder_connections_summary), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(SharedFolder(original.getString("id"), name, ids)) }, enabled = !saving && SharedFolder.NAME.matches(name)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SharedFolderNameDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.shared_legacy_import)) }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true)
            Text(stringResource(R.string.shared_folder_name_rules), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name) }, enabled = SharedFolder.NAME.matches(name)) { Text(stringResource(R.string.shared_start_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
