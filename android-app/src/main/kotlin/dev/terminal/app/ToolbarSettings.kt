@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.SharedPreferences
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.terminalListItemShapes

@Composable
internal fun ToolbarSettings(preferences: SharedPreferences, onBack: () -> Unit,
    tools: List<ToolbarTool> = toolbarTools, onOpenTool: (String) -> Unit) {
    var config by remember { mutableStateOf(parseToolbarConfig(preferences.getString(KEY_TOOLBAR, null),
        readHotkeyLayout(preferences).enabled, tools.map { it.id }.toSet())) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    val update: (ToolbarConfig) -> Unit = {
        config = it
        preferences.edit().putString(KEY_TOOLBAR, serializeToolbarConfig(it)).apply()
    }
    val currentConfig by rememberUpdatedState(config)
    val currentUpdate by rememberUpdatedState(update)
    val editLabel = stringResource(if (editing) R.string.toolbar_done else R.string.toolbar_edit)
    SettingsPageScaffold(stringResource(R.string.toolbar_settings), onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = !editing; dragging = null; dropTarget = null },
                modifier = Modifier.semantics { contentDescription = editLabel },
                icon = { Icon(painterResource(if (editing) R.drawable.ic_material_symbol_check_circle_24
                    else R.drawable.ic_toolbar_edit), null) },
                text = { Text(editLabel) })
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ListItem(onClick = { update(config.copy(enabled = !config.enabled)) },
                trailingContent = { Switch(config.enabled, onCheckedChange = null) },
                supportingContent = { Text(stringResource(R.string.toolbar_enabled_summary)) },
                verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                modifier = Modifier.semantics { role = Role.Switch; toggleableState =
                    if (config.enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Text(stringResource(R.string.toolbar_enabled))
            }
            Text(stringResource(R.string.toolbar_tools), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            if (config.tools.isEmpty()) Text(stringResource(R.string.toolbar_empty))
            val displayed = config.tools + if (editing) tools.map { it.id }.filter { it !in config.tools } else emptyList()
            if (displayed.isNotEmpty()) Card(shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column {
                    displayed.forEach { id ->
                        key(id) {
                            val tool = tools.first { it.id == id }
                            val added = id in config.tools
                            val name = stringResource(tool.title)
                            val moveLabel = stringResource(R.string.toolbar_move, name)
                            val upLabel = stringResource(R.string.toolbar_move_up)
                            val downLabel = stringResource(R.string.toolbar_move_down)
                            ListItem(onClick = { if (!editing) onOpenTool(tool.settings) },
                                leadingContent = if (editing && added) {{
                                    var origin by remember { mutableStateOf(Offset.Zero) }
                                    Box(Modifier.size(48.dp).onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }
                                        .pointerInput(id) {
                                            var pointer = Offset.Zero
                                            detectDragGestures(onDragStart = {
                                                pointer = origin + it; dragging = id; dropTarget = id
                                            }, onDrag = { change, delta ->
                                                change.consume(); pointer += delta
                                                dropTarget = currentConfig.tools.firstOrNull { bounds[it]?.contains(pointer) == true }
                                            }, onDragCancel = { dragging = null; dropTarget = null }, onDragEnd = {
                                                val from = currentConfig.tools.indexOf(id)
                                                val to = currentConfig.tools.indexOf(dropTarget)
                                                currentUpdate(currentConfig.moveTool(from, to))
                                                dragging = null; dropTarget = null
                                            })
                                        }.semantics {
                                            contentDescription = moveLabel
                                            customActions = buildList {
                                                val index = config.tools.indexOf(id)
                                                if (index > 0) add(CustomAccessibilityAction(upLabel) {
                                                    update(config.moveTool(index, index - 1)); true
                                                })
                                                if (index < config.tools.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                                    update(config.moveTool(index, index + 1)); true
                                                })
                                            }
                                        }, contentAlignment = Alignment.Center) {
                                        Icon(painterResource(R.drawable.ic_toolbar_drag), null)
                                    }
                                }} else null,
                                trailingContent = {
                                    if (editing) IconButton(onClick = {
                                        update(if (added) config.copy(tools = config.tools - id) else config.addTool(id))
                                    }) {
                                        Icon(painterResource(if (added) R.drawable.ic_toolbar_remove else R.drawable.ic_material_symbol_add_24),
                                            stringResource(if (added) R.string.toolbar_remove else R.string.toolbar_add, name))
                                    } else Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null)
                                },
                                verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                                colors = ListItemDefaults.colors(containerColor =
                                    if (dropTarget == id && dragging != null) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.fillMaxWidth().onGloballyPositioned { bounds[id] = it.boundsInRoot() }) {
                                Text(name)
                            }
                        }
                    }
                }
            }
            Text(stringResource(if (editing) R.string.toolbar_edit_summary else R.string.toolbar_settings_summary),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
