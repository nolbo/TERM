@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.annotation.DrawableRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
internal fun ToolbarCommandEditor(source: ToolbarCommand, error: String?,
    onSave: (ToolbarCommand) -> Unit, onBack: () -> Unit) {
    val id = rememberSaveable { source.id }
    var name by rememberSaveable { mutableStateOf(source.name) }
    var script by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(source.text)) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf(source.name) }
    var saveAfterRename by rememberSaveable { mutableStateOf(false) }
    val draft = ToolbarCommand(id, name, script.text)
    val nameValid = name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl)
    val scriptValid = validToolbarCommand(draft.copy(name = "Command"))
    val renameLabel = stringResource(R.string.toolbar_command_rename)
    val rename = { nameDraft = name; saveAfterRename = false; renaming = true }
    val cursor = script.selection.end.coerceIn(0, script.text.length)
    val cursorLine = script.text.take(cursor).count { it == '\n' } + 1
    val cursorColumn = cursor - script.text.lastIndexOf('\n', cursor - 1)
    val back = { if (name != source.name || script.text != source.text) discard = true else onBack() }
    BackHandler(onBack = back)
    SettingsPageScaffold(
        title = name.ifBlank { stringResource(R.string.toolbar_command_untitled) },
        onBack = back,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        floatingHeader = { progress ->
            Row(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorHeaderAction(stringResource(R.string.back), R.drawable.ic_material_symbol_arrow_back_24,
                    true, progress, back)
                Surface(modifier = Modifier.weight(1f).height(48.dp).editorHeaderShadow(progress)
                    .clip(CircleShape).clickable(role = Role.Button, onClickLabel = renameLabel, onClick = rename),
                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(name.ifBlank { stringResource(R.string.toolbar_command_untitled) },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                }
                EditorHeaderAction(stringResource(R.string.save), R.drawable.ic_material_symbol_save_24,
                    scriptValid, progress) {
                    if (nameValid) onSave(draft) else {
                        nameDraft = name; saveAfterRename = true; renaming = true
                    }
                }
            }
        },
    ) { padding ->
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
}

private fun Modifier.editorHeaderShadow(progress: Float) = graphicsLayer {
    shape = CircleShape
    shadowElevation = 3.dp.toPx() * progress
}

@Composable
private fun EditorHeaderAction(label: String, @DrawableRes icon: Int, enabled: Boolean,
    progress: Float, onClick: () -> Unit) {
    Surface(modifier = Modifier.size(48.dp).editorHeaderShadow(progress), shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        FloatingToolbarAction(label, icon, enabled, onClick)
    }
}
