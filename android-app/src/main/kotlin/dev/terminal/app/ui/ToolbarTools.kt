@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    load: suspend () -> String?) {
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(active, refresh, refreshSeconds) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            val result = withContext(Dispatchers.IO) { runCatching { loader()?.let(::JSONObject) }.getOrNull() }
            snapshot = result
            failed = result == null
            delay(refreshSeconds.coerceIn(2, 30) * 1000L)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.toolbar_memory), style = MaterialTheme.typography.titleSmall)
        snapshot?.let { data ->
            Text(stringResource(R.string.toolbar_memory_total,
                memorySize((data.optLong("totalBytes") - data.optLong("availableBytes")).coerceAtLeast(0)),
                memorySize(data.optLong("totalBytes"))))
            val values = data.optJSONObject("sessions")
            sessions.forEach { session ->
                val amount = if (values == null || values.isNull(session.id)) stringResource(R.string.toolbar_unavailable)
                    else memorySize(values.optLong(session.id))
                Text("${session.title}: $amount", style = MaterialTheme.typography.bodyMedium)
            }
        } ?: Text(stringResource(if (failed) R.string.toolbar_unavailable else R.string.toolbar_loading))
        Text(stringResource(R.string.toolbar_memory_caveat), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { refresh++ }, enabled = active) { Text(stringResource(R.string.toolbar_refresh)) }
    }
}

private fun memorySize(bytes: Long) = String.format(java.util.Locale.getDefault(), "%.1f MiB", bytes / 1048576.0)

@Composable
internal fun ToolbarClipboardTool(active: Boolean, target: String, confirmPaste: Boolean,
    onSend: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    // Never persist clipboard contents or read them merely because this page became visible.
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { text = null; confirm = false } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.toolbar_clipboard), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val value = runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                }.getOrNull()
                error = value != null && value.length > MAX_TOOLBAR_TEXT
                text = value?.takeIf { it.length <= MAX_TOOLBAR_TEXT }
            }, enabled = active) { Text(stringResource(R.string.toolbar_clipboard_read)) }
            OutlinedButton(onClick = {
                if (confirmPaste || text.orEmpty().any { it == '\n' || it == '\r' }) confirm = true
                else text?.let { onSend(it, false) }
            }, enabled = active && !text.isNullOrEmpty()) { Text(stringResource(R.string.toolbar_paste)) }
        }
        if (error) Text(stringResource(R.string.toolbar_text_limit), color = MaterialTheme.colorScheme.error)
        SelectionContainer { Text(text ?: stringResource(R.string.toolbar_clipboard_empty),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
    if (confirm && active) ToolbarSendDialog(text.orEmpty(), target, false,
        onDismiss = { confirm = false }, onSend = { text?.let { onSend(it, false) }; confirm = false })
}

@Composable
internal fun ToolbarCommandsTool(commands: List<ToolbarCommand>, active: Boolean, target: String,
    onSettings: () -> Unit, onSend: (String, Boolean) -> Unit) {
    var pending by remember { mutableStateOf<ToolbarCommand?>(null) }
    LaunchedEffect(active) { if (!active) pending = null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.toolbar_commands), style = MaterialTheme.typography.titleSmall)
        if (commands.isEmpty()) Text(stringResource(R.string.toolbar_commands_empty))
        commands.forEach { command ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(command.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { pending = command }, enabled = active) { Text(stringResource(R.string.toolbar_send)) }
            }
        }
        TextButton(onClick = onSettings, enabled = active) { Text(stringResource(R.string.toolbar_commands_edit)) }
    }
    pending?.takeIf { active }?.let { command ->
        ToolbarSendDialog(command.text, target, true, { pending = null }) {
            onSend(command.text, true); pending = null
        }
    }
}

@Composable
private fun ToolbarSendDialog(text: String, target: String, execute: Boolean, onDismiss: () -> Unit, onSend: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolbar_send_target, target)) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (execute) R.string.toolbar_execute_description else R.string.toolbar_paste_description))
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace) }
        } },
        confirmButton = { Button(onClick = onSend) { Text(stringResource(if (execute) R.string.toolbar_run else R.string.toolbar_paste)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun ToolbarToolSettings(screen: String, preferences: SharedPreferences, onBack: () -> Unit) {
    val tool = toolbarTools.first { it.settings == screen }
    var refresh by remember { mutableIntStateOf(preferences.getInt("toolbar-memory-interval", 5)) }
    var confirm by remember { mutableStateOf(preferences.getBoolean("toolbar-confirm-paste", true)) }
    var commands by remember { mutableStateOf(readToolbarCommands(preferences)) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
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
                    Card(shape = MaterialTheme.shapes.extraLarge) {
                        SettingsChoiceRow(stringResource(R.string.toolbar_memory_interval), null,
                            listOf(2, 5, 10, 30).map { SettingsChoice(stringResource(R.string.toolbar_seconds, it), it.toString()) },
                            refresh.toString()) {
                            refresh = it.toInt(); preferences.edit().putInt("toolbar-memory-interval", refresh).apply()
                        }
                    }
                    Text(stringResource(R.string.toolbar_memory_caveat))
                }
                CLIPBOARD_TOOL -> {
                    ListItem(onClick = { confirm = !confirm; preferences.edit().putBoolean("toolbar-confirm-paste", confirm).apply() },
                        trailingContent = { Switch(confirm, onCheckedChange = null) }, shapes = terminalListItemShapes()) {
                        Text(stringResource(R.string.toolbar_confirm_paste))
                    }
                    Text(stringResource(R.string.toolbar_clipboard_description))
                }
                COMMANDS_TOOL -> {
                    Text(stringResource(R.string.toolbar_commands_description))
                    commands.forEach { command ->
                        ListItem(onClick = { editId = command.id; name = command.name; text = command.text },
                            supportingContent = { Text(command.text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
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
        AlertDialog(onDismissRequest = { editId = null }, title = { Text(stringResource(R.string.toolbar_commands_edit)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text(stringResource(R.string.toolbar_command_name)) }, singleLine = true)
                OutlinedTextField(text, { if (it.length <= MAX_TOOLBAR_TEXT) text = it }, label = { Text(stringResource(R.string.toolbar_command_text)) },
                    minLines = 3, maxLines = 8)
                Text(stringResource(R.string.toolbar_text_limit), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { Button(enabled = validToolbarCommand(draft), onClick = {
                saveCommands(if (id.isEmpty()) commands + ToolbarCommand(name = name, text = text)
                    else commands.map { if (it.id == id) draft else it }); editId = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editId = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(stringResource(R.string.toolbar_command_delete)) },
        text = { Text(commands.firstOrNull { it.id == id }?.name.orEmpty()) },
        confirmButton = { TextButton(onClick = { saveCommands(commands.filterNot { it.id == id }); deleteId = null }) { Text(stringResource(R.string.toolbar_command_delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } }) }
}
