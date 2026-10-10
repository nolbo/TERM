@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceLaunch

/** Both new-session surfaces share the same Workspace category and Profile installation state. */
internal fun LazyListScope.workspaceLaunchItems(workspace: Workspace?, profiles: List<SessionProfile>,
    installationStatuses: Map<EnvironmentId, ProfileInstallationStatus>,
    enabled: Boolean = true, accent: Color, foreground: Color, supporting: Color, largeHeading: Boolean = false,
    onSelect: (WorkspaceLaunch) -> Unit) {
    if (workspace == null || workspace.launches.isEmpty()) return
    item(key = "workspace-launch-heading:${workspace.id}") {
        Text(stringResource(R.string.workspace_new_session_group, workspace.name),
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = if (largeHeading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            color = foreground)
    }
    items(workspace.launches, key = { "workspace-launch:${it.id}" }) { launch ->
        val profile = profiles.firstOrNull { it.id == launch.profileId }
        ListItem(onClick = { onSelect(launch) }, enabled = enabled && profile != null,
            leadingContent = {
                if (profile != null) ProfilePickerIcon(profile, installationStatuses, tint = accent)
                else Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { WorkspaceIcon(workspace.icon, tint = accent) }
            }, supportingContent = when {
                profile == null -> { { Text(stringResource(R.string.workspace_profile_missing)) } }
                launch.name.isNotBlank() -> { { Text(profile.name) } }
                else -> null
            }, verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                contentColor = foreground, supportingContentColor = supporting, leadingContentColor = accent),
            modifier = Modifier.fillMaxWidth()) { Text(workspaceLaunchName(launch, profile)) }
    }
}
