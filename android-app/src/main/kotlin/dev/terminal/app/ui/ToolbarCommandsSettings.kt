@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.terminal.app.*
import dev.terminal.app.R

@Composable
internal fun ToolbarCommandsSettings(preferences: SharedPreferences, onBack: () -> Unit,
    onEdit: (String?) -> Unit) {
    var commands by remember(preferences) { mutableStateOf(readToolbarCommands(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TOOLBAR_COMMANDS) commands = readToolbarCommands(preferences)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var editing by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf<Rect?>(null) }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val editLabel = stringResource(if (editing) R.string.toolbar_done else R.string.toolbar_edit)
    SettingsPageScaffold(stringResource(R.string.toolbar_commands), onBack, floatingActionButton = {
        if (commands.isNotEmpty()) ExtendedFloatingActionButton(onClick = { editing = !editing },
            modifier = Modifier.semantics { contentDescription = editLabel },
            icon = {
                AnimatedContent(editing, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                    label = "commandEditIcon") { edit ->
                    Icon(painterResource(if (edit) R.drawable.ic_material_symbol_check_circle_24 else R.drawable.ic_toolbar_edit), null)
                }
            }, text = { Text(editLabel) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { viewport = it.boundsInRoot() }
            .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsDescription(stringResource(if (editing) R.string.toolbar_commands_edit_summary else R.string.toolbar_commands_description))
            ProfileSection(R.string.toolbar_commands) {
                SettingsNavigationRow(stringResource(R.string.toolbar_command_add),
                    supporting = if (commands.size >= MAX_TOOLBAR_COMMANDS) stringResource(R.string.toolbar_commands_limit) else null,
                    enabled = commands.size < MAX_TOOLBAR_COMMANDS, leadingIcon = R.drawable.ic_material_symbol_add_24,
                    onClick = { onEdit(null) })
            }
            if (commands.isEmpty()) SettingsDescription(stringResource(R.string.toolbar_commands_none))
            else Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.toolbar_commands_list), Modifier.padding(start = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsReorderList(commands, editing, scroll, { viewport }, onReorder = { ids ->
                    val reordered = ids.map { id -> commands.first { it.id == id } }
                    preferences.edit().putString(KEY_TOOLBAR_COMMANDS, serializeToolbarCommands(reordered)).apply()
                    commands = reordered
                }, onEdit = { onEdit(it.id) }, itemId = { it.id }, itemName = { it.name },
                    leadingIcon = {}, supportingContent = { command ->
                        Text(command.text, fontFamily = LocalTerminalMonospaceFontFamily.current,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    })
            }
        }
    }
}
