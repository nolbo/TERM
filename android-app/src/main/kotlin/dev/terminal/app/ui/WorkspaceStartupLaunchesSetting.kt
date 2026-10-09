@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.workspace.WorkspaceLaunch

/** Dialog Save changes only the enclosing Workspace draft. */
@Composable
internal fun WorkspaceStartupLaunchesSetting(launches: List<WorkspaceLaunch>, profiles: List<SessionProfile>,
    enabled: Boolean, onChange: (List<WorkspaceLaunch>) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val count = launches.count { it.startWithWorkspace }
    val title = stringResource(R.string.workspace_startup_launches)
    ListItem(onClick = {
        selected = ArrayList(launches.filter { it.startWithWorkspace }.map { it.id })
        visible = true
    }, enabled = enabled && launches.isNotEmpty(),
        supportingContent = { Text(stringResource(R.string.workspace_startup_launches_summary)) },
        trailingContent = { Text(if (count == 0) stringResource(R.string.workspace_startup_none)
            else stringResource(R.string.workspace_startup_count, count),
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 160.dp)) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(title) }
    if (visible) AlertDialog(onDismissRequest = { visible = false }, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                launches.forEach { launch ->
                    val profile = profiles.firstOrNull { it.id == launch.profileId }
                    val checked = launch.id in selected
                    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                        enabled = enabled && (profile != null || checked), onValueChange = { value ->
                            selected = ArrayList(if (value) selected + launch.id else selected - launch.id)
                        }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled && (profile != null || checked))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(launch.name)
                            Text(profile?.name ?: stringResource(R.string.workspace_profile_missing),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }, confirmButton = { Button(onClick = {
            onChange(launches.map { it.copy(startWithWorkspace = it.id in selected) })
            visible = false
        }, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { visible = false }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.cancel))
        } })
}
