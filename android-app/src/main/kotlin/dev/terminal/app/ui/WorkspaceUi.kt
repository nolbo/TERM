@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import dev.terminal.app.SettingsReorderList
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.workspace.*
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun WorkspaceSelector(state: WorkspaceRegistry.State, enabled: Boolean, select: (String) -> Unit,
    create: (String) -> Unit, launch: (WorkspaceLaunch) -> Unit, tint: Color) {
    var expanded by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val title = workspaceFilterName(state)
    val configurations = state.workspaces.firstOrNull { it.id == state.selected }?.launches.orEmpty()
    Box {
        if (state.workspaces.isEmpty()) IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_material_symbol_layers_24), stringResource(R.string.workspace_selector, title), tint = tint)
        } else TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.widthIn(max = 128.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = tint)
        }
        DropdownMenuPopup(expanded && enabled, { expanded = false }) {
          DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), modifier = Modifier.verticalScroll(rememberScrollState())) {
            SelectableDropdownMenuItem(selected = state.selected == WorkspaceRegistry.ALL, text = { Text(stringResource(R.string.workspace_all)) }, shapes = MenuDefaults.itemShape(0, state.workspaces.size + 3 + configurations.size), onClick = { expanded = false; select(WorkspaceRegistry.ALL) })
            SelectableDropdownMenuItem(selected = state.selected == WorkspaceRegistry.UNASSIGNED, text = { Text(stringResource(R.string.workspace_unassigned)) }, shapes = MenuDefaults.itemShape(1, 3), onClick = { expanded = false; select(WorkspaceRegistry.UNASSIGNED) })
            state.workspaces.forEach { workspace ->
                SelectableDropdownMenuItem(selected = state.selected == workspace.id, shapes = MenuDefaults.itemShape(1, 3), text = { Text(workspace.name + if (workspace.temporary) " · " + stringResource(R.string.workspace_temporary) else "") },
                    onClick = { expanded = false; select(workspace.id) })
            }
            DropdownMenuItem(shape = if (configurations.isEmpty()) MenuDefaults.trailingItemShape else MaterialTheme.shapes.extraSmall, text = { Text(stringResource(R.string.workspace_create_temporary)) }, onClick = { expanded = false; creating = true })
            configurations.forEachIndexed { index, configuration ->
                DropdownMenuItem(shape = if (index == configurations.lastIndex) MenuDefaults.trailingItemShape else MaterialTheme.shapes.extraSmall, text = { Text(stringResource(R.string.workspace_run, configuration.name)) }, onClick = { expanded = false; launch(configuration) })
            }
          }
        }
    }
    if (creating) WorkspaceNameDialog(R.string.workspace_create_temporary, onSave = { creating = false; create(it) }, onDismiss = { creating = false })
}

@Composable
internal fun workspaceFilterName(state: WorkspaceRegistry.State): String = when (state.selected) {
    WorkspaceRegistry.ALL -> stringResource(R.string.workspace_all)
    WorkspaceRegistry.UNASSIGNED -> stringResource(R.string.workspace_unassigned)
    else -> state.workspaces.firstOrNull { it.id == state.selected }?.name.orEmpty()
}

@Composable
internal fun SessionWorkspaceMenu(expanded: Boolean, onRename: () -> Unit, onMove: () -> Unit, onDismiss: () -> Unit) {
    DropdownMenuPopup(expanded, onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1),
            modifier = Modifier.verticalScroll(rememberScrollState())) {
            DropdownMenuItem(shape = MenuDefaults.leadingItemShape,
                text = { Text(stringResource(R.string.rename_session_action)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_toolbar_edit), null,
                    modifier = Modifier.size(MenuDefaults.LeadingIconSize)) }, onClick = onRename)
            DropdownMenuItem(shape = MenuDefaults.trailingItemShape,
                text = { Text(stringResource(R.string.workspace_move)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_material_symbol_layers_24), null,
                    modifier = Modifier.size(MenuDefaults.LeadingIconSize)) }, onClick = onMove)
        }
    }
}

@Composable
internal fun WorkspaceMoveDialog(state: WorkspaceRegistry.State, sessionId: String, sessionTitle: String,
    onMove: (String?) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val current = state.memberships[sessionId]
    var destination by rememberSaveable(sessionId) { mutableStateOf(current) }
    var creating by rememberSaveable(sessionId) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val validDestination = destination == null || state.workspaces.any { it.id == destination }
    if (creating) {
        WorkspaceNameDialog(R.string.workspace_create_temporary,
            onSave = { creating = false; onCreate(it) }, onDismiss = { creating = false })
        return
    }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_material_symbol_layers_24), null) },
        title = { Text(stringResource(R.string.workspace_move)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.workspace_move_description, sessionTitle))
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).selectableGroup()) {
                SettingsGroup {
                    WorkspaceMoveChoice(stringResource(R.string.workspace_unassigned), destination == null,
                        onSelect = { destination = null },
                        icon = { Icon(painterResource(R.drawable.ic_material_symbol_layers_24), null) })
                    state.workspaces.forEach { workspace ->
                        WorkspaceMoveChoice(workspace.name, destination == workspace.id,
                            temporary = workspace.temporary, onSelect = { destination = workspace.id },
                            icon = { Icon(painterResource(R.drawable.ic_material_symbol_layers_24), null) })
                    }
                }
            }
            SettingsGroup {
                ListItem(onClick = { creating = true }, shapes = terminalListItemShapes(),
                    colors = terminalSettingsListItemColors(), verticalAlignment = Alignment.CenterVertically,
                    leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_add_24), null) }) {
                    Text(stringResource(R.string.workspace_create_temporary))
                }
            }
        }
    }, confirmButton = {
        Button(onClick = { onMove(destination) }, enabled = validDestination && destination != current,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.workspace_move_action)) }
    }, dismissButton = {
        TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
    })
}

@Composable
private fun WorkspaceMoveChoice(name: String, selected: Boolean, onSelect: () -> Unit,
    icon: @Composable () -> Unit, temporary: Boolean = false) {
    ListItem(onClick = onSelect, shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton; this.selected = selected },
        leadingContent = icon, trailingContent = { RadioButton(selected, onClick = null) },
        supportingContent = if (temporary) ({ Text(stringResource(R.string.workspace_temporary)) }) else null) {
        Text(name)
    }
}

@Composable
internal fun WorkspaceNameDialog(title: Int, initial: String = "", onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = validWorkspaceName(name)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun WorkspacesScreen(state: WorkspaceRegistry.State?, profiles: List<SessionProfile>, error: String?, saving: Boolean,
    onBack: () -> Unit, onSave: (Workspace, () -> Unit) -> Unit, onDelete: (String, () -> Unit) -> Unit, onRun: (Workspace, WorkspaceLaunch) -> Unit,
    onReorder: (List<String>) -> Unit = {}, connected: Boolean = true) {
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf<Rect?>(null) }
    val enabled = connected && !saving
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val editLabel = stringResource(if (editing) R.string.toolbar_done else R.string.toolbar_edit)
    SettingsPageScaffold(stringResource(R.string.workspaces_title), onBack, floatingActionButton = {
        if (state?.workspaces?.isNotEmpty() == true) ExtendedFloatingActionButton(onClick = { if (enabled) editing = !editing },
            modifier = Modifier.semantics { contentDescription = editLabel },
            icon = {
                AnimatedContent(editing, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "workspaceEditIcon") { edit ->
                    Icon(painterResource(if (edit) R.drawable.ic_material_symbol_check_circle_24 else R.drawable.ic_toolbar_edit), null)
                }
            }, text = { Text(editLabel) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { viewport = it.boundsInRoot() }.verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(if (editing) R.string.workspaces_edit_summary else R.string.workspaces_summary), style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                SettingsGroup {
                    ListItem(onClick = { draft = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "").put("temporary", false).put("launches", org.json.JSONArray()).toString() },
                        enabled = !saving, shapes = terminalListItemShapes()) { Text(stringResource(R.string.workspace_create)) }
                }
                if (editing) {
                    SettingsReorderList(state.workspaces, editing, scroll, { viewport }, onReorder,
                        onEdit = { draft = it.json().toString() }, itemId = { it.id }, itemName = { it.name }, enabled = enabled,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_material_symbol_layers_24), null) },
                        supportingContent = { workspace ->
                            Text(stringResource(if (workspace.temporary) R.string.workspace_promote else R.string.workspace_edit_summary))
                        })
                } else state.workspaces.forEach { workspace ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(workspace.name, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelLarge)
                        SettingsGroup {
                            ListItem(onClick = { draft = workspace.json().toString() }, enabled = !saving, shapes = terminalListItemShapes(),
                                supportingContent = { Text(stringResource(if (workspace.temporary) R.string.workspace_promote else R.string.workspace_edit_summary)) }) { Text(stringResource(R.string.workspace_edit)) }
                            workspace.launches.forEach { launch ->
                                val profile = profiles.firstOrNull { it.id == launch.profileId }
                                ListItem(onClick = { onRun(workspace, launch) }, enabled = !saving && profile != null,
                                    shapes = terminalListItemShapes(), supportingContent = { Text(profile?.name ?: stringResource(R.string.workspace_profile_missing)) }) { Text(stringResource(R.string.workspace_run, launch.name)) }
                            }
                            ListItem(onClick = { deleting = workspace.id }, enabled = !saving, shapes = terminalListItemShapes()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    draft?.let { encoded ->
        WorkspaceEditor(encoded, profiles, saving, onSave = { onSave(it) { draft = null } }, onDismiss = { draft = null })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.workspace_delete)) },
            text = { Text(stringResource(R.string.workspace_delete_summary)) },
            confirmButton = { TextButton(onClick = { onDelete(id) { deleting = null } }, enabled = !saving) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun WorkspaceEditor(encoded: String, profiles: List<SessionProfile>, saving: Boolean, onSave: (Workspace) -> Unit, onDismiss: () -> Unit) {
    val original = remember(encoded) { JSONObject(encoded) }
    var name by rememberSaveable(encoded) { mutableStateOf(original.getString("name")) }
    var launches by rememberSaveable(encoded) { mutableStateOf(original.getJSONArray("launches").toString()) }
    var editingLaunch by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(launches) { org.json.JSONArray(launches).let { json -> List(json.length()) { WorkspaceLaunch.parse(json.getJSONObject(it)) } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.workspace_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true)
            if (original.getBoolean("temporary")) Text(stringResource(R.string.workspace_promote))
            Text(stringResource(R.string.workspace_launches), style = MaterialTheme.typography.labelLarge)
            WorkspaceStartupLaunchesSetting(rows, profiles, enabled = !saving) { updated ->
                launches = org.json.JSONArray(updated.map { it.json() }).toString()
            }
            rows.forEach { launch ->
                Row {
                    TextButton(onClick = { editingLaunch = launch.json().toString() }, modifier = Modifier.weight(1f)) { Text(launch.name) }
                    TextButton(onClick = { launches = org.json.JSONArray(rows.filterNot { it.id == launch.id }.map { it.json() }).toString() }) { Text(stringResource(R.string.delete)) }
                }
            }
            TextButton(onClick = { editingLaunch = "new" }, enabled = profiles.isNotEmpty() && rows.size < 64) { Text(stringResource(R.string.workspace_add_launch)) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(Workspace(original.getString("id"), name.trim(), false, rows)) }, enabled = !saving && validWorkspaceName(name)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } })
    editingLaunch?.let { launch -> WorkspaceLaunchEditor(launch, profiles, onSave = { next ->
        launches = org.json.JSONArray((rows.filterNot { it.id == next.id } + next).map { it.json() }).toString()
        editingLaunch = null
    }, onDismiss = { editingLaunch = null }) }
}

@Composable
private fun WorkspaceLaunchEditor(encoded: String, profiles: List<SessionProfile>, onSave: (WorkspaceLaunch) -> Unit, onDismiss: () -> Unit) {
    val original = remember(encoded) { if (encoded == "new") null else WorkspaceLaunch.parse(JSONObject(encoded)) }
    var name by rememberSaveable(encoded) { mutableStateOf(original?.name.orEmpty()) }
    var profileId by rememberSaveable(encoded) { mutableStateOf(original?.profileId ?: profiles.firstOrNull()?.id.orEmpty()) }
    var directory by rememberSaveable(encoded) { mutableStateOf(original?.directory.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.workspace_launches)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true)
            profiles.forEach { profile -> ListItem(onClick = { profileId = profile.id }, shapes = terminalListItemShapes(),
                leadingContent = { RadioButton(profileId == profile.id, null) }) { Text(profile.name) } }
            OutlinedTextField(directory, { directory = it }, label = { Text(stringResource(R.string.workspace_directory)) }, singleLine = true)
            Text(stringResource(R.string.workspace_directory_summary), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(WorkspaceLaunch(original?.id ?: UUID.randomUUID().toString(), name.trim(), profileId, directory,
            startWithWorkspace = original?.startWithWorkspace ?: false)) },
        enabled = validWorkspaceName(name) && profiles.any { it.id == profileId } && (directory.isEmpty() || directory.startsWith('/')) && directory.length <= 4096 && directory.none(Char::isISOControl)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun validWorkspaceName(value: String) = value.trim().isNotEmpty() && value.length <= 128 && value.none(Char::isISOControl)
