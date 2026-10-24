@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.app.avfLogText
import dev.terminal.runtime.lifecycle.ConnectionLogSnapshot
import dev.terminal.runtime.lifecycle.ConnectionLogStore

@Composable
internal fun AvfLogsScreen(snapshot: ConnectionLogSnapshot?, loading: Boolean, changing: Boolean, error: String?,
    onBack: () -> Unit, onRefresh: () -> Unit, onEnabled: (Boolean) -> Unit, onClear: () -> Unit, onCopy: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    val zone = java.time.ZoneId.systemDefault()
    val logText = remember(snapshot, query, zone) { avfLogText(snapshot?.entries.orEmpty(), query, zone) }
    val busy = loading || changing
    SettingsPageScaffold(stringResource(R.string.avf_logs_title), onBack,
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            HorizontalFloatingToolbar(expanded = true,
                modifier = Modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
                FloatingToolbarAction(stringResource(if (loading) R.string.diagnostics_refreshing else R.string.refresh),
                    R.drawable.ic_material_symbol_refresh_24, enabled = !busy, loading = loading, onClick = onRefresh)
                FloatingToolbarAction(stringResource(R.string.avf_logs_copy), R.drawable.ic_material_symbol_content_copy_24,
                    enabled = !busy && logText.isNotEmpty(), onClick = { onCopy(logText) })
                FloatingToolbarAction(stringResource(R.string.avf_logs_clear), R.drawable.ic_material_symbol_delete_24,
                    enabled = !busy && (snapshot == null || snapshot.entries.isNotEmpty()), destructive = true,
                    onClick = { confirmingClear = true })
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SettingsDescription(stringResource(R.string.avf_logs_description)) }
            item { SettingsGroup {
                val enabled = snapshot?.enabled
                ListItem(supportingContent = { Text(stringResource(R.string.avf_logs_recording_support)) },
                    trailingContent = {
                        if (enabled != null) Switch(enabled, onCheckedChange = null, enabled = !busy)
                        else if (loading) LoadingIndicator(Modifier.size(24.dp))
                        else Text(stringResource(R.string.diagnostics_unknown))
                    }, verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
                    modifier = Modifier.fillMaxWidth().settingsItemSurface().then(if (enabled == null) Modifier else
                        Modifier.toggleable(enabled, enabled = !busy, role = Role.Switch) { onEnabled(it) })) {
                    Text(stringResource(R.string.avf_logs_recording))
                }
            } }
            item { Text(stringResource(R.string.avf_logs_retention, ConnectionLogStore.MAX_RECORDS, ConnectionLogStore.MAX_BYTES / 1024),
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { message -> item { SettingsOperationError(message) } }
            item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.avf_logs_search)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (!loading && snapshot != null && logText.isEmpty()) item {
                Text(stringResource(if (query.isEmpty()) R.string.avf_logs_empty else R.string.avf_logs_no_matches),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (logText.isNotEmpty()) item {
                SelectionContainer {
                    Text(logText, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), softWrap = false,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (confirmingClear) AlertDialog(onDismissRequest = { confirmingClear = false },
        title = { Text(stringResource(R.string.avf_logs_clear)) }, text = { Text(stringResource(R.string.avf_logs_clear_confirmation)) },
        confirmButton = { Button(onClick = { confirmingClear = false; onClear() }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.delete))
        } }, dismissButton = { TextButton(onClick = { confirmingClear = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
}
