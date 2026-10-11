@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.app.ToolbarCommand
import dev.terminal.app.validToolbarCommand

@Composable
internal fun ToolbarCommandEditor(source: ToolbarCommand, newItem: Boolean, error: String?,
    onSave: (ToolbarCommand) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val id = rememberSaveable { source.id }
    var name by rememberSaveable { mutableStateOf(source.name) }
    var script by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(source.text)) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val draft = ToolbarCommand(id, name, script.text)
    val canSave = validToolbarCommand(draft)
    val nameValid = name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl)
    val scriptValid = validToolbarCommand(draft.copy(name = "Command"))
    val nameLabel = stringResource(R.string.toolbar_command_name)
    val back = { if (name != source.name || script.text != source.text) discard = true else onBack() }
    BackHandler(onBack = back)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = {
            Text(stringResource(if (newItem) R.string.toolbar_command_add else R.string.toolbar_commands_edit),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, navigationIcon = {
            IconButton(onClick = back) {
                Icon(painterResource(R.drawable.ic_material_symbol_arrow_back_24), stringResource(R.string.back))
            }
        }) }, floatingActionButtonPosition = FabPosition.Center, floatingActionButton = {
            if (!newItem) HorizontalFloatingToolbar(expanded = true, modifier = Modifier.imePadding()) {
                FloatingToolbarAction(stringResource(R.string.save), R.drawable.ic_material_symbol_save_24,
                    enabled = canSave, onClick = { onSave(draft) })
                FloatingToolbarAction(stringResource(R.string.delete), R.drawable.ic_material_symbol_delete_24,
                    enabled = true, destructive = true, onClick = { deleting = true })
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = if (newItem) 16.dp else FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsOperationError(error)
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(nameLabel) }, isError = name.isNotEmpty() && !nameValid,
                supportingText = if (name.isNotEmpty() && !nameValid) { { Text(stringResource(R.string.toolbar_command_name_limit)) } } else null)
            Text(stringResource(R.string.toolbar_command_text), style = MaterialTheme.typography.labelLarge)
            ShellScriptEditor(script, { script = it }, Modifier.fillMaxWidth().weight(1f))
            Text(stringResource(R.string.toolbar_text_limit), style = MaterialTheme.typography.bodySmall,
                color = if (script.text.isNotEmpty() && !scriptValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (newItem) Button(onClick = { onSave(draft) }, enabled = canSave,
                modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) }
        }
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
