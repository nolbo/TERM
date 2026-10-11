@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.app.ToolbarCommand
import dev.terminal.app.MAX_TOOLBAR_TEXT
import dev.terminal.app.validToolbarCommand

@Composable
internal fun ToolbarCommandEditor(source: ToolbarCommand, newItem: Boolean, error: String?,
    onSave: (ToolbarCommand) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val id = rememberSaveable { source.id }
    var name by rememberSaveable { mutableStateOf(source.name) }
    var script by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(source.text)) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf(source.name) }
    var saveAfterRename by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val draft = ToolbarCommand(id, name, script.text)
    val nameValid = name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl)
    val scriptValid = validToolbarCommand(draft.copy(name = "Command"))
    val renameLabel = stringResource(R.string.toolbar_command_rename)
    val rename = { nameDraft = name; saveAfterRename = false; renaming = true; menu = false }
    val cursor = script.selection.end.coerceIn(0, script.text.length)
    val cursorLine = script.text.take(cursor).count { it == '\n' } + 1
    val cursorColumn = cursor - script.text.lastIndexOf('\n', cursor - 1)
    val back = { if (name != source.name || script.text != source.text) discard = true else onBack() }
    BackHandler(onBack = back)
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = { TopAppBar(title = {
            Text(name.ifBlank { stringResource(R.string.toolbar_command_untitled) },
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = renameLabel, onClick = rename),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, navigationIcon = {
            IconButton(onClick = back) {
                Icon(painterResource(R.drawable.ic_material_symbol_arrow_back_24), stringResource(R.string.back))
            }
        }, actions = {
            FloatingToolbarAction(stringResource(R.string.save), R.drawable.ic_material_symbol_save_24,
                enabled = scriptValid, onClick = {
                    if (nameValid) onSave(draft) else {
                        nameDraft = name; saveAfterRename = true; renaming = true
                    }
                })
            Box {
                FloatingToolbarAction(stringResource(R.string.toolbar_command_editor_options),
                    R.drawable.ic_material_symbol_more_vert_24, enabled = true, onClick = { menu = true })
                DropdownMenuPopup(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                        DropdownMenuItem(shape = if (newItem) MaterialTheme.shapes.large else MenuDefaults.leadingItemShape,
                            text = { Text(renameLabel) }, leadingIcon = {
                                Icon(painterResource(R.drawable.ic_toolbar_edit), null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize))
                            }, onClick = rename)
                        if (!newItem) DropdownMenuItem(shape = MenuDefaults.trailingItemShape,
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_material_symbol_delete_24), null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
                            onClick = { menu = false; deleting = true })
                    }
                }
            }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (error != null) Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { SettingsOperationError(error) }
            ShellScriptEditor(script, { script = it }, Modifier.fillMaxWidth().weight(1f))
            if (script.text.isNotEmpty() && !scriptValid) Text(stringResource(R.string.toolbar_text_limit),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shell_editor_language), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.shell_editor_position, cursorLine, cursorColumn),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.shell_editor_characters, script.text.length, MAX_TOOLBAR_TEXT),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (script.text.isNotEmpty() && !scriptValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (renaming) {
        val valid = nameDraft.isNotBlank() && nameDraft.length <= 60 && nameDraft.none(Char::isISOControl)
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text(renameLabel) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(nameDraft, { nameDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.toolbar_command_name)) },
                    isError = nameDraft.isNotEmpty() && !valid,
                    supportingText = { Text(stringResource(R.string.toolbar_command_name_limit)) })
            } }, confirmButton = {
                TextButton(onClick = {
                    name = nameDraft; renaming = false
                    if (saveAfterRename && scriptValid) onSave(draft.copy(name = nameDraft))
                }, enabled = valid, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.confirm)) }
            }, dismissButton = {
                TextButton(onClick = { renaming = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.profile_discard)) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.palette_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.toolbar_command_delete)) }, text = { Text(source.name) },
        confirmButton = {
            TextButton(onClick = onDelete, shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.toolbar_command_delete))
            }
        }, dismissButton = {
            TextButton(onClick = { deleting = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
        })
}
