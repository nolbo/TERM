@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.collection.intListOf
import dev.terminal.app.R
import dev.terminal.app.ProfileSection
import dev.terminal.app.SettingsReorderList
import dev.terminal.app.WorkspaceLaunchEditActivity
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.workspace.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

@Composable
internal fun WorkspaceSelector(state: WorkspaceRegistry.State, enabled: Boolean, select: (String) -> Unit,
    create: (String) -> Unit, tint: Color, showName: Boolean = false, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val title = workspaceFilterName(state)
    val selected = state.workspaces.firstOrNull { it.id == state.selected }
    val selectedIcon = when (state.selected) {
        WorkspaceRegistry.ALL, WorkspaceRegistry.UNASSIGNED -> R.drawable.ic_material_symbol_team_dashboard_24
        else -> workspaceIconDrawable(selected?.icon ?: WorkspaceIcon.DEFAULT)
    }
    val menuMaxWidth = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtLeast(48.dp)
    val menuEdgeMargin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val abovePosition = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Above)
    val anchorPosition = remember(menuEdgeMargin, abovePosition) {
        MenuAnchorPosition.Custom(
            xCandidates = {
                val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
                    else anchorBounds.right - menuSize.width
                val end = (windowSize.width - menuSize.width - menuEdgeMargin).coerceAtLeast(menuEdgeMargin)
                // Edge anchors can fail Material's margin checks and fall back to the window centre.
                intListOf(start.coerceIn(menuEdgeMargin, end))
            },
            yCandidates = {
                intListOf(abovePosition.calculatePosition(anchorBounds, windowSize, layoutDirection, menuSize).y)
            })
    }
    val menuPosition = MenuDefaults.rememberDropdownMenuPopupPositionProvider(anchorPosition)
    Box(modifier) {
        Box {
            if (showName && selected != null) TextButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = tint)) {
                Icon(painterResource(selectedIcon),
                    stringResource(R.string.workspace_selector, title))
                Spacer(Modifier.width(8.dp))
                Text(title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else IconButton(onClick = { expanded = true }, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(selectedIcon),
                    stringResource(R.string.workspace_selector, title), tint = tint)
            }
            DropdownMenuPopup(expanded && enabled, { expanded = false },
                popupPositionProvider = menuPosition) {
                Column(Modifier.widthIn(min = minOf(240.dp, menuMaxWidth), max = menuMaxWidth)
                    .width(IntrinsicSize.Max).verticalScroll(rememberScrollState())) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2), modifier = Modifier.fillMaxWidth()) {
                        SelectableDropdownMenuItem(selected = state.selected == WorkspaceRegistry.UNASSIGNED, text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(painterResource(R.drawable.ic_material_symbol_team_dashboard_24), null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize))
                                Text(stringResource(R.string.workspace_unassigned), modifier = Modifier.weight(1f))
                            }
                        }, shapes = MenuDefaults.itemShape(0, state.workspaces.size + 1), onClick = { expanded = false; select(WorkspaceRegistry.UNASSIGNED) })
                        state.workspaces.forEachIndexed { index, workspace ->
                            SelectableDropdownMenuItem(selected = state.selected == workspace.id, shapes = MenuDefaults.itemShape(index + 1, state.workspaces.size + 1), text = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WorkspaceIcon(workspace.icon, size = MenuDefaults.LeadingIconSize)
                                    Text(workspace.name + if (workspace.temporary) " · " + stringResource(R.string.workspace_temporary) else "",
                                        modifier = Modifier.weight(1f), softWrap = true)
                                }
                            }, onClick = { expanded = false; select(workspace.id) })
                        }
                    }
                    Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2), modifier = Modifier.fillMaxWidth()) {
                        DropdownMenuItem(shape = MenuDefaults.trailingItemShape,
                            text = { Text(stringResource(R.string.workspace_create_temporary)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_material_symbol_add_24), null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
                            onClick = { expanded = false; creating = true })
                    }
                }
            }
        }
    }
    if (creating) WorkspaceNameDialog(R.string.workspace_create_temporary, onSave = { creating = false; create(it) }, onDismiss = { creating = false })
}

@Composable
internal fun workspaceFilterName(state: WorkspaceRegistry.State): String = when (state.selected) {
    WorkspaceRegistry.ALL,
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
                        icon = { Icon(painterResource(R.drawable.ic_material_symbol_team_dashboard_24), null) })
                    state.workspaces.forEach { workspace ->
                        WorkspaceMoveChoice(workspace.name, destination == workspace.id,
                            temporary = workspace.temporary, onSelect = { destination = workspace.id },
                            icon = { WorkspaceIcon(workspace.icon) })
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
internal fun WorkspacesScreen(state: WorkspaceRegistry.State?, error: String?, connected: Boolean,
    onBack: () -> Unit, onCreate: () -> Unit, onEdit: (Workspace) -> Unit, saving: Boolean = false,
    onReorder: (List<String>) -> Unit = {}) {
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
        Column(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { viewport = it.boundsInRoot() }
            .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsDescription(stringResource(if (editing) R.string.workspaces_edit_summary else R.string.workspaces_summary))
            if (error != null) SettingsOperationError(error)
            if (state == null) LinearWavyProgressIndicator(Modifier.fillMaxWidth()) else {
                ProfileSection(R.string.workspaces_title) {
                    SettingsNavigationRow(stringResource(R.string.workspace_create), enabled = enabled,
                        leadingIcon = R.drawable.ic_material_symbol_add_24, onClick = onCreate)
                }
                if (state.workspaces.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.workspace_list), Modifier.padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SettingsReorderList(state.workspaces, editing, scroll, { viewport }, onReorder, onEdit,
                        itemId = { it.id }, itemName = { it.name }, enabled = enabled,
                        leadingIcon = { WorkspaceIcon(it.icon) },
                        supportingContent = { workspace ->
                            Text(if (workspace.temporary) stringResource(R.string.workspace_promote)
                                else stringResource(R.string.workspace_launch_count, workspace.launches.size))
                        })
                }
                if (state.workspaces.isEmpty()) SettingsDescription(stringResource(R.string.workspaces_empty))
            }
        }
    }
}

@Composable
internal fun WorkspaceEditScreen(requestedId: String?, state: WorkspaceRegistry.State?, profiles: List<SessionProfile>,
    error: String?, saving: Boolean, onSave: (Workspace) -> Unit, onDelete: (String) -> Unit,
    onRun: (Workspace, WorkspaceLaunch) -> Unit, onBack: () -> Unit) {
    // Fetch by ID rather than putting the whole registry in Activity extras. Returning from a child
    // or reconnecting the service must not replace a draft with the runtime's saved projection.
    var encoded by rememberSaveable { mutableStateOf<String?>(null) }
    val defaultName = stringResource(R.string.workspace_default_name)
    LaunchedEffect(state) {
        if (encoded == null && state != null) encoded = if (requestedId == null) {
            JSONObject().put("id", UUID.randomUUID().toString())
                .put("name", EnvironmentRegistry.defaultName(defaultName, state.workspaces.map { it.name })).put("temporary", false)
                .put("icon", WorkspaceIcon.DEFAULT).put("launches", JSONArray()).put("_new", true).toString()
        } else state.workspaces.firstOrNull { it.id == requestedId }?.json()?.toString()
    }
    val source = encoded
    if (source != null) WorkspaceEditor(source, profiles, error, saving || state == null, onSave, onDelete, onRun, onBack)
    else SettingsPageScaffold(stringResource(R.string.workspace_settings), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsOperationError(error)
            if (state == null) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            else SettingsDescription(stringResource(R.string.workspace_missing))
        }
    }
}

@Composable
private fun WorkspaceEditor(encoded: String, profiles: List<SessionProfile>, error: String?, saving: Boolean,
    onSave: (Workspace) -> Unit, onDelete: (String) -> Unit, onRun: (Workspace, WorkspaceLaunch) -> Unit, onBack: () -> Unit) {
    val original = remember(encoded) { JSONObject(encoded) }
    val newItem = original.optBoolean("_new")
    var name by rememberSaveable(encoded) { mutableStateOf(original.getString("name")) }
    var icon by rememberSaveable(encoded) { mutableStateOf(original.getString("icon")) }
    var launches by rememberSaveable(encoded) { mutableStateOf(original.getJSONArray("launches").toString()) }
    var pendingLaunchId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val rows = remember(launches) { JSONArray(launches).let { json -> List(json.length()) { WorkspaceLaunch.parse(json.getJSONObject(it)) } } }
    val dirty = name != original.getString("name") || icon != original.getString("icon") || launches != original.getJSONArray("launches").toString()
    val launchEditor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expectedId = pendingLaunchId
        pendingLaunchId = null
        if (result.resultCode == Activity.RESULT_OK && expectedId != null) {
            val next = result.data?.getStringExtra(WorkspaceLaunchEditActivity.EXTRA_DRAFT)?.let {
                runCatching { WorkspaceLaunch.parse(JSONObject(it)) }.getOrNull()
            }?.takeIf { it.id == expectedId }
            val updated = when {
                next != null -> if (rows.any { it.id == next.id }) rows.map { if (it.id == next.id) next else it } else rows + next
                result.data?.getStringExtra(WorkspaceLaunchEditActivity.EXTRA_DELETED_ID) == expectedId -> rows.filterNot { it.id == expectedId }
                else -> rows
            }
            launches = JSONArray(updated.map { it.json() }).toString()
        }
    }
    val editLaunch: (String) -> Unit = { draft ->
        pendingLaunchId = JSONObject(draft).getString("id")
        launchEditor.launch(WorkspaceLaunchEditActivity.intent(context, draft))
    }
    SettingsEditorPage(stringResource(if (newItem) R.string.workspace_create else R.string.workspace_settings),
        stringResource(R.string.workspace_edit_summary), error, saving, newItem, dirty,
        canSave = validWorkspaceName(name), onBack = onBack,
        onSave = { onSave(Workspace(original.getString("id"), name.trim(), false, rows, icon)) },
        onDelete = if (newItem) null else { { deleting = true } }) {
        ProfileSection(R.string.workspace_information) {
            SettingsTextRow(R.string.workspace_name, name, enabled = !saving, valid = ::validWorkspaceName) { name = it }
            WorkspaceIconSetting(icon, enabled = !saving) { icon = it }
        }
        if (original.getBoolean("temporary")) SettingsDescription(stringResource(R.string.workspace_promote))
        ProfileSection(R.string.workspace_launches) {
            WorkspaceStartupLaunchesSetting(rows, profiles, enabled = !saving) { updated ->
                launches = JSONArray(updated.map { it.json() }).toString()
            }
            SettingsNavigationRow(stringResource(R.string.workspace_add_launch), enabled = !saving && profiles.isNotEmpty() && rows.size < 64,
                leadingIcon = R.drawable.ic_material_symbol_add_24, onClick = {
                    editLaunch(JSONObject().put("id", UUID.randomUUID().toString()).put("name", "")
                        .put("profileId", profiles.first().id).put("directory", "").put("_new", true).toString())
                })
            rows.forEach { launch ->
                val profile = profiles.firstOrNull { it.id == launch.profileId }
                val launchName = workspaceLaunchName(launch, profile)
                ListItem(onClick = { editLaunch(launch.json().toString()) }, enabled = !saving,
                    leadingContent = profile?.let { { ProfileIcon(it.icon, it.runtime.wireName) } },
                    supportingContent = if (launch.name.isNotBlank() || profile == null) {
                        { Text(profile?.name ?: stringResource(R.string.workspace_profile_missing)) }
                    } else null,
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onRun(Workspace.parse(original), launch) },
                                enabled = !saving && !newItem && !dirty && profile != null) {
                                Icon(painterResource(R.drawable.ic_material_symbol_play_arrow_24), stringResource(R.string.workspace_run, launchName))
                            }
                            Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), contentDescription = null)
                        }
                    }, verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                    colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(launchName) }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { if (!saving) deleting = false },
        title = { Text(stringResource(R.string.workspace_delete)) }, text = { Text(stringResource(R.string.workspace_delete_summary)) },
        confirmButton = { TextButton(onClick = { onDelete(original.getString("id")) }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !saving,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun WorkspaceLaunchEditor(encoded: String, profiles: List<SessionProfile>, onSave: (WorkspaceLaunch) -> Unit,
    onDelete: (String) -> Unit, onBack: () -> Unit) {
    val original = remember(encoded) { JSONObject(encoded) }
    val newItem = original.optBoolean("_new")
    var name by rememberSaveable(encoded) { mutableStateOf(original.getString("name")) }
    var profileId by rememberSaveable(encoded) { mutableStateOf(original.getString("profileId")) }
    var directory by rememberSaveable(encoded) { mutableStateOf(original.getString("directory")) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val dirty = name != original.getString("name") || profileId != original.getString("profileId") || directory != original.getString("directory")
    SettingsEditorPage(stringResource(if (newItem) R.string.workspace_add_launch else R.string.workspace_launch_settings),
        stringResource(R.string.workspace_directory_summary), null, false, newItem, dirty,
        canSave = validWorkspaceLaunchName(name) && profiles.any { it.id == profileId } && validWorkspaceDirectory(directory),
        onBack = onBack, onSave = { onSave(WorkspaceLaunch(original.getString("id"), name.trim(), profileId, directory,
            startWithWorkspace = original.optBoolean("startWithWorkspace"))) },
        onDelete = if (newItem) null else { { deleting = true } }) {
        ProfileSection(R.string.workspace_information) {
            SettingsTextRow(R.string.workspace_launch_name, name, hint = R.string.workspace_launch_name_summary,
                valid = ::validWorkspaceLaunchName) { name = it }
            SettingsChoiceRow(stringResource(R.string.profiles), null, profiles.map { SettingsChoice(it.name, it.id) }, profileId,
                selectedLabel = stringResource(R.string.workspace_profile_missing), onChange = { profileId = it })
            SettingsTextRow(R.string.workspace_directory, directory, hint = R.string.workspace_directory_summary,
                valid = ::validWorkspaceDirectory) { directory = it }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.workspace_launch_delete)) },
        confirmButton = { TextButton(onClick = { onDelete(original.getString("id")) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleting = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}

private fun validWorkspaceName(value: String) = value.trim().isNotEmpty() && value.length <= 128 && value.none(Char::isISOControl)
private fun validWorkspaceLaunchName(value: String) = value.length <= 128 && value.none(Char::isISOControl)
private fun validWorkspaceDirectory(value: String) = (value.isEmpty() || value.startsWith('/')) && value.length <= 4096 && value.none(Char::isISOControl)

@Composable
internal fun workspaceLaunchName(launch: WorkspaceLaunch, profile: SessionProfile?): String =
    launch.name.ifBlank { profile?.name ?: stringResource(R.string.workspace_launch_default_name) }
