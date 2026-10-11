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
    val colors = LocalToolbarColors.current
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    VisiblePolling(active, refresh, refreshSeconds, sections) {
        if (!active || !sections.any) return@VisiblePolling
        while (isActive) {
            val result = withContext(Dispatchers.IO) { runCatching { loader()?.let(::JSONObject) }.getOrNull() }
            val unchanged = result?.toString() == snapshot?.toString()
            snapshot = result
            failed = result == null
            delay((if (unchanged) maxOf(15, refreshSeconds) else refreshSeconds).coerceIn(2, 30) * 1000L)
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
                Surface(shape = MaterialTheme.shapes.large, color = colors.container, contentColor = colors.onContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MemoryUsageSummary(stringResource(R.string.toolbar_memory_device), used, total)
                    }
                }
            }
            if (sections.app) item {
                val app = data.optJSONObject("appMemory")
                val appTotal = app?.optLong("total", -1) ?: -1
                Surface(shape = MaterialTheme.shapes.large, color = colors.container, contentColor = colors.onContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MemoryUsageSummary(stringResource(R.string.toolbar_memory_app), appTotal, data.optLong("totalBytes", -1))
                        if (app != null) {
                            HorizontalDivider(color = colors.divider)
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
                                        modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.divider)
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
                    colors = ListItemDefaults.colors(
                        containerColor = colors.container, contentColor = colors.onContainer,
                        trailingContentColor = colors.onContainer,
                        disabledContainerColor = colors.container,
                        disabledContentColor = colors.onContainer.copy(alpha = 0.38f),
                        disabledTrailingContentColor = colors.onContainer.copy(alpha = 0.38f),
                    ),
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
    val colors = LocalToolbarColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(if (used >= 0) memorySize(used) else stringResource(R.string.toolbar_unavailable),
                style = MaterialTheme.typography.labelLarge)
        }
        if (used >= 0 && total > 0) {
            val ratio = (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.divider)
            Text(stringResource(R.string.toolbar_memory_of_device,
                java.text.NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(ratio), memorySize(total)),
                style = MaterialTheme.typography.labelSmall, color = colors.onContainer)
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
internal fun ToolbarToolSettings(screen: String, preferences: SharedPreferences, onBack: () -> Unit) {
    val tool = toolbarTools.first { it.settings == screen }
    var skipCloseConfirmation by remember {
        mutableStateOf(preferences.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false))
    }
    var refresh by remember { mutableIntStateOf(preferences.getInt("toolbar-memory-interval", 5)) }
    var memorySections by remember { mutableStateOf(readMemoryToolSections(preferences)) }
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
            }
        }
    }
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
