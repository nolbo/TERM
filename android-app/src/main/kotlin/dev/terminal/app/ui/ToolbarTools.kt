@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.terminal.app.*
import dev.terminal.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ToolbarMemoryTool(active: Boolean, sessions: List<SessionUi>, refreshSeconds: Int,
    onSelectSession: (String) -> Unit, sections: MemoryToolSections = MemoryToolSections(), load: suspend () -> String?) {
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(active, refresh, refreshSeconds, sections) {
        if (!active || !sections.any) return@LaunchedEffect
        while (isActive) {
            val result = withContext(Dispatchers.IO) { runCatching { loader()?.let(::JSONObject) }.getOrNull() }
            snapshot = result
            failed = result == null
            delay(refreshSeconds.coerceIn(2, 30) * 1000L)
        }
    }
    val scroll = rememberLazyListState()
    ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward, Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val data = snapshot
        if (!sections.any) {
            item { Text(stringResource(R.string.toolbar_memory_none), style = MaterialTheme.typography.bodySmall) }
        } else if (data != null) {
            if (sections.device) item {
                val total = data.optLong("totalBytes", -1)
                val used = if (total > 0) total - data.optLong("availableBytes").coerceIn(0, total) else -1
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MemoryUsageSummary(stringResource(R.string.toolbar_memory_device), used, total)
                    }
                }
            }
            if (sections.app) item {
                val app = data.optJSONObject("appMemory")
                val appTotal = app?.optLong("total", -1) ?: -1
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MemoryUsageSummary(stringResource(R.string.toolbar_memory_app), appTotal, data.optLong("totalBytes", -1))
                        if (app != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            appMemoryAreas.forEach { (key, title) ->
                                val amount = app.optLong(key, -1)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        Text(if (amount >= 0) memorySize(amount) else stringResource(R.string.toolbar_unavailable),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (amount >= 0 && appTotal > 0) LinearProgressIndicator(
                                        progress = { (amount.toDouble() / appTotal).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
            if (sections.sessions) {
            item {
                Text(stringResource(R.string.toolbar_memory_sessions), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge)
            }
            if (sessions.isEmpty()) item {
                Text(stringResource(R.string.toolbar_memory_no_sessions), style = MaterialTheme.typography.bodySmall)
            }
            val values = data.optJSONObject("sessions")
            items(sessions, key = { it.id }) { session ->
                val bytes = if (values == null || values.isNull(session.id)) null
                    else values.optLong(session.id, -1).takeIf { it >= 0 }
                ListItem(
                    onClick = { onSelectSession(session.id) },
                    enabled = active,
                    shapes = terminalListItemShapes(),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    trailingContent = {
                        Text(bytes?.let(::memorySize) ?: stringResource(R.string.toolbar_unavailable),
                            style = MaterialTheme.typography.labelMedium)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            }
        } else item {
            Text(stringResource(if (failed) R.string.toolbar_unavailable else R.string.toolbar_loading),
                style = MaterialTheme.typography.bodySmall)
        }
        if (sections.any) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { refresh++ }, enabled = active) {
                    Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), stringResource(R.string.toolbar_refresh))
                }
            }
        }
    }
    }
}

@Composable
private fun MemoryUsageSummary(title: String, used: Long, total: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(if (used >= 0) memorySize(used) else stringResource(R.string.toolbar_unavailable),
                style = MaterialTheme.typography.labelLarge)
        }
        if (used >= 0 && total > 0) {
            val ratio = (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.toolbar_memory_of_device,
                java.text.NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(ratio), memorySize(total)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val appMemoryAreas = listOf(
    "java-heap" to R.string.toolbar_memory_java, "native-heap" to R.string.toolbar_memory_native,
    "code" to R.string.toolbar_memory_code, "stack" to R.string.toolbar_memory_stack,
    "graphics" to R.string.toolbar_memory_graphics, "private-other" to R.string.toolbar_memory_other,
    "system" to R.string.toolbar_memory_shared,
)

private fun memorySize(bytes: Long) = String.format(java.util.Locale.getDefault(), "%.1f MiB", bytes / 1048576.0)

@Composable
internal fun ToolbarCommandsTool(commands: List<ToolbarCommand>, active: Boolean, target: String,
    onSend: (String, Boolean) -> Unit, onSave: (ToolbarCommand) -> Unit) {
    var pending by remember { mutableStateOf<ToolbarCommand?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(active) { if (!active) pending = null }
    val scroll = rememberLazyListState()
    ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (commands.isEmpty()) item {
                Text(stringResource(R.string.toolbar_commands_empty), Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(commands, key = { it.id }) { command ->
                Surface(onClick = { pending = command }, enabled = active,
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(command.name, style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(command.text, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editId = command.id; name = command.name; text = command.text }) {
                            Icon(painterResource(R.drawable.ic_toolbar_edit),
                                stringResource(R.string.toolbar_command_edit_item, command.name), Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
    editId?.let { id ->
        ToolbarCommandEditDialog(ToolbarCommand(id, name, text), { name = it }, { text = it },
            onDismiss = { editId = null }, onSave = { onSave(it); editId = null })
    }
    pending?.takeIf { active }?.let { command ->
        ToolbarSendDialog(command.text, target, { pending = null }) {
            onSend(command.text, true); pending = null
        }
    }
}

@Composable
private fun ToolbarSendDialog(text: String, target: String, onDismiss: () -> Unit, onSend: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolbar_send_target, target)) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.toolbar_execute_description))
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace) }
        } },
        confirmButton = { Button(onClick = onSend) { Text(stringResource(R.string.toolbar_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun ToolbarToolSettings(screen: String, preferences: SharedPreferences, onBack: () -> Unit,
    initialCommandId: String? = null) {
    val tool = toolbarTools.first { it.settings == screen }
    var refresh by remember { mutableIntStateOf(preferences.getInt("toolbar-memory-interval", 5)) }
    var memorySections by remember { mutableStateOf(readMemoryToolSections(preferences)) }
    var commands by remember { mutableStateOf(readToolbarCommands(preferences)) }
    val initialCommand = remember(initialCommandId) { commands.firstOrNull { it.id == initialCommandId } }
    var editId by rememberSaveable { mutableStateOf(initialCommand?.id) }
    var name by rememberSaveable { mutableStateOf(initialCommand?.name.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(initialCommand?.text.orEmpty()) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    fun saveCommands(value: List<ToolbarCommand>) {
        preferences.edit().putString(KEY_TOOLBAR_COMMANDS, serializeToolbarCommands(value)).apply()
        commands = value
    }
    SettingsPageScaffold(stringResource(tool.title), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tool.id) {
                MEMORY_TOOL -> {
                    MemorySettingsSection(stringResource(R.string.toolbar_memory_display)) {
                        val options = listOf(Triple(KEY_MEMORY_DEVICE, R.string.toolbar_memory_device, memorySections.device),
                            Triple(KEY_MEMORY_APP, R.string.toolbar_memory_app, memorySections.app),
                            Triple(KEY_MEMORY_SESSIONS, R.string.toolbar_memory_sessions, memorySections.sessions))
                        options.forEachIndexed { index, (key, title, checked) ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                            ListItem(onClick = {
                                preferences.edit().putBoolean(key, !checked).apply()
                                memorySections = readMemoryToolSections(preferences)
                            }, trailingContent = { Switch(checked, onCheckedChange = null) },
                                modifier = Modifier.fillMaxWidth().semantics {
                                    role = Role.Switch
                                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes()) {
                                Text(stringResource(title))
                            }
                        }
                    }
                    MemorySettingsSection(stringResource(R.string.toolbar_memory_refresh_section)) {
                        SettingsChoiceRow(stringResource(R.string.toolbar_memory_interval), null,
                            listOf(2, 5, 10, 30).map { SettingsChoice(stringResource(R.string.toolbar_seconds, it), it.toString()) },
                            refresh.toString()) {
                            refresh = it.toInt(); preferences.edit().putInt("toolbar-memory-interval", refresh).apply()
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.toolbar_memory_app_caveat), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.toolbar_memory_caveat), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                COMMANDS_TOOL -> {
                    Text(stringResource(R.string.toolbar_commands_description))
                    commands.forEach { command ->
                        ListItem(onClick = { editId = command.id; name = command.name; text = command.text },
                            supportingContent = { Text(command.text, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { IconButton(onClick = { deleteId = command.id }) {
                                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_material_symbol_delete_24),
                                    stringResource(R.string.toolbar_remove, command.name))
                            } }, shapes = terminalListItemShapes()) { Text(command.name) }
                    }
                    Button(onClick = { editId = ""; name = ""; text = "" }, enabled = commands.size < 64) {
                        Text(stringResource(R.string.toolbar_command_add))
                    }
                }
            }
        }
    }
    editId?.let { id ->
        val draft = ToolbarCommand(id = id.ifEmpty { "new" }, name = name, text = text)
        ToolbarCommandEditDialog(draft, { name = it }, { text = it }, onDismiss = { editId = null }, onSave = {
            saveCommands(if (id.isEmpty()) commands + ToolbarCommand(name = it.name, text = it.text)
                else commands.map { command -> if (command.id == id) it else command })
            editId = null
        })
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(stringResource(R.string.toolbar_command_delete)) },
        text = { Text(commands.firstOrNull { it.id == id }?.name.orEmpty()) },
        confirmButton = { TextButton(onClick = { saveCommands(commands.filterNot { it.id == id }); deleteId = null }) { Text(stringResource(R.string.toolbar_command_delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun ToolbarCommandEditDialog(draft: ToolbarCommand, onName: (String) -> Unit,
    onText: (String) -> Unit, onDismiss: () -> Unit, onSave: (ToolbarCommand) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.toolbar_commands_edit)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draft.name, { if (it.length <= 60) onName(it) },
                label = { Text(stringResource(R.string.toolbar_command_name)) }, singleLine = true)
            OutlinedTextField(draft.text, { if (it.length <= MAX_TOOLBAR_TEXT) onText(it) },
                label = { Text(stringResource(R.string.toolbar_command_text)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 3, maxLines = 8)
            Text(stringResource(R.string.toolbar_text_limit), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(enabled = validToolbarCommand(draft), onClick = { onSave(draft) }) {
            Text(stringResource(R.string.save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun MemorySettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, Modifier.padding(start = 16.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(content = content)
        }
    }
}
