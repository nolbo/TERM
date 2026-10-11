@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.*
import dev.terminal.app.R

@Composable
internal fun ToolbarToolSettings(screen: String, preferences: SharedPreferences, onBack: () -> Unit) {
    val tool = toolbarTools.first { it.settings == screen }
    var skipCloseConfirmation by remember {
        mutableStateOf(preferences.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false))
    }
    SettingsPageScaffold(stringResource(tool.title), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tool.id) {
                HISTORY_TOOL -> Text(stringResource(R.string.toolbar_history_description),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SESSIONS_TOOL -> SettingsGroup {
                    ListItem(onClick = {
                        skipCloseConfirmation = !skipCloseConfirmation
                        preferences.edit().putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, skipCloseConfirmation).apply()
                    }, supportingContent = { Text(stringResource(R.string.session_skip_close_confirmation_summary)) },
                        trailingContent = { Switch(skipCloseConfirmation, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().semantics {
                            role = Role.Switch
                            toggleableState = if (skipCloseConfirmation) ToggleableState.On else ToggleableState.Off
                        },
                        colors = ListItemDefaults.colors(containerColor = settingsItemContainerColor()),
                        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes()) {
                        Text(stringResource(R.string.session_skip_close_confirmation))
                    }
                }

            }
        }
    }
}
