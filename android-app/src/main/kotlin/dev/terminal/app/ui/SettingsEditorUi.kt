@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

/** The same field/draft interaction used by Profile settings. */
@Composable
internal fun SettingsTextRow(label: Int, value: String, singleLine: Boolean = true, hint: Int? = null,
    enabled: Boolean = true, valid: (String) -> Boolean = { true }, confirmLabel: Int = R.string.save,
    onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(value) }
    ListItem(onClick = { draft = value; visible = true }, enabled = enabled,
        supportingContent = { Text(value.ifBlank { stringResource(R.string.profile_not_set) },
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(label)) }
    if (visible) AlertDialog(onDismissRequest = { visible = false }, title = { Text(stringResource(label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(label)) },
                    singleLine = singleLine, isError = !valid(draft), modifier = Modifier.fillMaxWidth())
                hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { onChange(draft); visible = false },
            enabled = enabled && valid(draft), shapes = ButtonDefaults.shapes()) { Text(stringResource(confirmLabel)) } },
        dismissButton = { TextButton(onClick = { visible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun SettingsDescription(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsNavigationRow(title: String, supporting: String? = null, enabled: Boolean = true,
    leadingIcon: Int? = null, onClick: () -> Unit) {
    ListItem(onClick = onClick, enabled = enabled,
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leadingIcon?.let { { Icon(painterResource(it), contentDescription = null) } },
        trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), contentDescription = null) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(title) }
}

@Composable
internal fun SettingsOperationError(error: String?) {
    error?.let {
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** Existing items use the Profile-style bottom toolbar; new items use the full-width Save action. */
@Composable
internal fun SettingsEditorPage(title: String, description: String, error: String?, saving: Boolean,
    newItem: Boolean, dirty: Boolean, canSave: Boolean, onBack: () -> Unit, onSave: () -> Unit,
    onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val back = { if (!saving) { if (dirty) discard = true else onBack() } }
    BackHandler(onBack = back)
    SettingsPageScaffold(title, back, floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            if (!newItem) HorizontalFloatingToolbar(expanded = true) {
                FloatingToolbarAction(stringResource(R.string.save), R.drawable.ic_material_symbol_save_24,
                    enabled = !saving && canSave, onClick = onSave)
                onDelete?.let { remove -> FloatingToolbarAction(stringResource(R.string.delete), R.drawable.ic_material_symbol_delete_24,
                    enabled = !saving, destructive = true, onClick = remove) }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = if (newItem) 16.dp else FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsDescription(description)
            SettingsOperationError(error)
            content()
            if (newItem) Button(onClick = onSave, enabled = !saving && canSave,
                modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.profile_discard)) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.palette_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}
