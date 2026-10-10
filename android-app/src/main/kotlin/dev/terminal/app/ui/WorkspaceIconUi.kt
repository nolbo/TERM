@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.workspace.WorkspaceIcon as WorkspaceIcons

internal fun workspaceIconDrawable(id: String): Int = profileIconDrawable(
    if (id == "auto" || id !in WorkspaceIcons.builtIns) WorkspaceIcons.DEFAULT else id)

@Composable
internal fun WorkspaceIcon(icon: String, modifier: Modifier = Modifier, size: Dp = 24.dp,
    tint: Color = LocalContentColor.current, contentDescription: String? = null) {
    Icon(painterResource(workspaceIconDrawable(icon)), contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}

@Composable
internal fun WorkspaceIconSetting(icon: String, enabled: Boolean, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(icon) }
    ListItem(onClick = { draft = icon; visible = true }, enabled = enabled,
        trailingContent = { WorkspaceIcon(icon, tint = TerminalStatusBlue, contentDescription = iconChoiceLabel(icon)) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workspace_icon)) }
    if (visible) AlertDialog(onDismissRequest = { visible = false },
        title = { Text(stringResource(R.string.workspace_icon)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item { Text(stringResource(R.string.workspace_icon_help), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)) }
                items((WorkspaceIcons.builtIns.size + 2) / 3) { row ->
                    val icons = WorkspaceIcons.builtIns.drop(row * 3).take(3)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        icons.forEach { icon ->
                            IconChoiceTile(icon, selected = draft == icon, modifier = Modifier.weight(1f),
                                enabled = enabled, onSelect = { draft = icon })
                        }
                        repeat(3 - icons.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onChange(draft); visible = false }, enabled = enabled,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { visible = false }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.cancel))
        } },
    )
}

