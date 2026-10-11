@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.terminal.app.MemorySnapshot
import dev.terminal.app.R
import dev.terminal.app.memoryBytes
import dev.terminal.app.memoryRatio
import org.json.JSONObject

private val memoryAreas = listOf(
    "java-heap" to R.string.memory_java, "native-heap" to R.string.memory_native,
    "code" to R.string.memory_code, "stack" to R.string.memory_stack, "graphics" to R.string.memory_graphics,
    "private-other" to R.string.memory_private_other, "system" to R.string.memory_system,
)

@Composable
internal fun MemoryDiagnosticsScreen(snapshot: MemorySnapshot?, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    val device = snapshot?.device
    val processes = listOf(R.string.memory_app_total to snapshot?.app,
        R.string.memory_ui_process to snapshot?.ui, R.string.memory_runtime_process to snapshot?.runtime)
        .map { (section, process) -> section to process?.takeIf { it.optString("basis") == "pss" } }
    SettingsPageScaffold(stringResource(R.string.memory_diagnostics_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "refresh") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.memory_diagnostics_description), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = onRefresh, enabled = !loading, shapes = ButtonDefaults.shapes()) {
                        if (loading) LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                        else Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
            item(key = "device") {
                MemoryCard(R.string.memory_device) {
                    MemoryUsageMetric(R.string.memory_device, R.string.memory_device_used, device?.used, device?.total,
                        R.string.memory_percentage_of_device, prominent = true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MemoryStat(R.string.memory_device_total, device?.total, Modifier.weight(1f))
                        MemoryStat(R.string.memory_device_available, device?.available, Modifier.weight(1f))
                    }
                }
            }
            item(key = "app") {
                MemoryCard(R.string.memory_app_title) {
                    processes.forEachIndexed { index, (section, process) ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MemoryUsageMetric(section, R.string.memory_pss_total, process?.memoryBytes("total"), device?.total,
                            R.string.memory_percentage_of_device, label = section, prominent = index == 0)
                    }
                }
            }
            processes.forEach { (section, process) -> item(key = section) {
                MemoryCompositionCard(section, process)
            } }
        }
    }
}

@Composable
private fun MemoryCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MemoryStat(title: Int, bytes: Long?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(memoryValue(bytes), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun MemoryUsageMetric(section: Int, title: Int, bytes: Long?, total: Long?, reference: Int,
    label: Int = title, prominent: Boolean = false, showReference: Boolean = true) {
    val fraction = memoryRatio(bytes, total)
    val value = memoryValue(bytes)
    val percentage = fraction?.let {
        java.text.NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(it.toDouble())
    } ?: stringResource(R.string.memory_ratio_unknown)
    val referenceText = stringResource(reference)
    val accessibilityLabel = stringResource(R.string.memory_progress_description, stringResource(section), stringResource(title))
    val state = stringResource(R.string.memory_progress_state, value, referenceText, percentage)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = accessibilityLabel
        stateDescription = state
        fraction?.let { progressBarRangeInfo = ProgressBarRangeInfo(it, 0f..1f) }
    }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                if (showReference) Text(referenceText, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.widthIn(max = 200.dp), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(value, style = when {
                        bytes == null -> MaterialTheme.typography.bodyLarge
                        prominent -> MaterialTheme.typography.headlineMedium
                        else -> MaterialTheme.typography.titleMedium
                    }, color = if (bytes == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End)
                }
                if (bytes != null) Text(percentage, style = MaterialTheme.typography.labelMedium,
                    color = if (fraction == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End)
            }
        }
        fraction?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(if (prominent) 8.dp else 4.dp))
        }
    }
}

@Composable
private fun MemoryCompositionCard(section: Int, process: JSONObject?) {
    val total = process?.memoryBytes("total")
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.memory_composition_title, stringResource(section)),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.memory_composition_basis, memoryValue(total)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            memoryAreas.forEach { (key, title) ->
                MemoryUsageMetric(section, title, process?.memoryBytes(key), total, R.string.memory_percentage_of_pss,
                    showReference = false)
            }
            if (section == R.string.memory_app_total) Text(stringResource(R.string.memory_pss_caveat),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
