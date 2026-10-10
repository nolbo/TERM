@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

internal data class SettingsChoice(val label: String, val value: String)

/** Shared by language, terminal type and profile choices. Changes apply only on confirmation. */
@Composable
internal fun SettingsChoiceRow(title: String, supporting: String?, choices: List<SettingsChoice>,
    selected: String, selectedLabel: String? = null, confirmLabel: Int = R.string.save, onChange: (String) -> Unit) {
    var draft by rememberSaveable(selected) { mutableStateOf(selected) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val label = choices.firstOrNull { it.value == selected }?.label ?: selectedLabel.orEmpty()
    ListItem(
        onClick = { draft = selected; visible = true },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Text(label, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(max = 160.dp)) },
        verticalAlignment = Alignment.CenterVertically,
        shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(title) }
    if (visible) AlertDialog(
        onDismissRequest = { visible = false }, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                choices.forEach { choice ->
                    Row(Modifier.fillMaxWidth().selectable(selected = draft == choice.value,
                        role = Role.RadioButton, onClick = { draft = choice.value }).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draft == choice.value, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(choice.label)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onChange(draft); visible = false },
            enabled = choices.any { it.value == draft }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(confirmLabel))
        } },
        dismissButton = { TextButton(onClick = { visible = false }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.cancel))
        } },
    )
}
