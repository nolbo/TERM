@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.terminal.app.R
import dev.terminal.app.ProfileSection
import dev.terminal.app.openInternalStorageSettings
import dev.terminal.runtime.session.*
import dev.terminal.runtime.storage.*
import org.json.JSONArray
import org.json.JSONObject

internal data class SharedFoldersSnapshot(val folders: List<SharedFolder>, val paths: Map<String, String>,
    val environments: List<Environment>) {
    companion object {
        fun parse(encoded: String): SharedFoldersSnapshot {
            val json = JSONObject(encoded)
            fun rows(key: String): List<JSONObject> = json.getJSONArray(key).let { a -> List(a.length()) { a.getJSONObject(it) } }
            val folders = rows("folders")
            return SharedFoldersSnapshot(folders.map(SharedFolder::parse), folders.associate { it.getString("id") to it.getString("hostPath") },
                rows("environments").map(Environment::fromJson))
        }
    }
}

@Composable
internal fun SharedFoldersScreen(snapshot: SharedFoldersSnapshot?, error: String?, onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (SharedFolder) -> Unit) {
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
                        SettingsNavigationRow(stringResource(R.string.shared_folder_create),
                            leadingIcon = R.drawable.ic_material_symbol_add_24, onClick = onCreate)
                        snapshot.folders.forEach { folder ->
                            val connections = snapshot.environments.filter { it.runtime != EnvironmentRuntime.AVF && it.id in folder.environments }.joinToString { it.name }
                            SettingsNavigationRow(folder.name,
                                supporting = connections.ifEmpty { stringResource(R.string.shared_folder_no_environments) },
                                onClick = { onEdit(folder) })
                        }
                    }
                }
                if (snapshot.folders.isEmpty()) item { SettingsDescription(stringResource(R.string.shared_folders_empty)) }
            }
        }
    }
}

@Composable
internal fun SharedFolderEditor(encoded: String, snapshot: SharedFoldersSnapshot?, error: String?, saving: Boolean,
    onSave: (SharedFolder) -> Unit, onDelete: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    canSubmit: Boolean = true) {
    val original = remember(encoded) { JSONObject(encoded) }
    val newItem = original.optBoolean("_new")
    var name by rememberSaveable(encoded) { mutableStateOf(original.getString("name")) }
    var selected by rememberSaveable(encoded) { mutableStateOf(original.getJSONArray("environments").toString()) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var converting by rememberSaveable { mutableStateOf(false) }
    val selectedIds = JSONArray(selected).let { array -> List(array.length()) { EnvironmentId(array.getString(it)) }.toSet() }
    val environments = snapshot?.environments?.filter { it.runtime != EnvironmentRuntime.AVF }
    val ids = if (environments == null) selectedIds else selectedIds.intersect(environments.mapTo(mutableSetOf()) { it.id })
    val originalIds = original.getJSONArray("environments").let { array -> List(array.length()) { EnvironmentId(array.getString(it)) }.toSet() }
    val dirty = name != original.getString("name") || ids != originalIds
    val persisted = snapshot?.folders?.firstOrNull { it.id == original.getString("id") }
    // Only folders moved by the retired public-storage workaround need one-time access.
    val needsConversion = persisted?.androidStorage == true
    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageGranted = android.os.Environment.isExternalStorageManager()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val save = { onSave(SharedFolder(original.getString("id"), name, ids)) }
    SettingsEditorPage(stringResource(if (newItem) R.string.shared_folder_create else R.string.shared_folder_settings),
        stringResource(R.string.shared_folder_connections_summary), error, saving, newItem, dirty,
        canSave = canSubmit && SharedFolder.NAME.matches(name) && (!needsConversion || storageGranted), onBack = onBack,
        onSave = { if (needsConversion) converting = true else save() },
        onDelete = if (newItem || onDelete == null) null else { { deleting = true } }) {
        ProfileSection(R.string.workspace_information) {
            SettingsTextRow(R.string.workspace_name, name, hint = R.string.shared_folder_name_rules,
                enabled = !saving, valid = SharedFolder.NAME::matches, confirmLabel = R.string.confirm) { name = it }
        }
        if (snapshot != null && persisted != null && environments.orEmpty().any { it.id in persisted.environments }) {
            ProfileSection(R.string.shared_folder_access) {
                environments.orEmpty().filter { it.id in persisted.environments }.forEach { environment ->
                    when (environment.runtime) {
                        EnvironmentRuntime.NATIVE, EnvironmentRuntime.ROOTLESS -> {
                            Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(environment.name, style = MaterialTheme.typography.bodyLarge)
                                SelectionContainer { Text(if (environment.runtime == EnvironmentRuntime.NATIVE) snapshot.paths[persisted.id].orEmpty() else persisted.guestPath,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        EnvironmentRuntime.AVF -> Unit
                    }
                }
            }
            if (dirty) Text(stringResource(R.string.shared_folder_access_saved), Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProfileSection(R.string.shared_folder_environments) {
            environments?.forEach { environment ->
                ListItem(onClick = {
                    selected = JSONArray((if (environment.id in ids) ids - environment.id else ids + environment.id).map { it.value }).toString()
                }, enabled = !saving,
                    supportingContent = { Text(stringResource(runtimeTitleResource(environment.sessionRuntime.wireName))) },
                    trailingContent = { Checkbox(environment.id in ids, null) }, verticalAlignment = Alignment.CenterVertically,
                    shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(environment.name) }
            }
        }
        if (needsConversion) {
            ProfileSection(R.string.shared_folder_android_storage) {
                SettingsNavigationRow(stringResource(R.string.environment_storage_android_permission),
                    stringResource(if (storageGranted) R.string.environment_storage_os_enabled else R.string.environment_storage_os_disabled),
                    enabled = !saving, onClick = { openInternalStorageSettings(context) })
            }
            Text(stringResource(R.string.shared_folder_android_conversion_summary),
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.shared_folders_security), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (converting && needsConversion) AlertDialog(onDismissRequest = { if (!saving) converting = false },
        title = { Text(stringResource(R.string.shared_folder_android_storage)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.shared_folder_android_conversion_summary))
        } },
        confirmButton = { TextButton(onClick = { converting = false; save() }, enabled = !saving && storageGranted,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { converting = false }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
    if (deleting && onDelete != null) AlertDialog(onDismissRequest = { if (!saving) deleting = false }, title = { Text(stringResource(R.string.shared_folder_delete)) },
        text = { Text(stringResource(R.string.shared_folder_delete_summary)) },
        confirmButton = { TextButton(onClick = { onDelete(original.getString("id")) }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}
