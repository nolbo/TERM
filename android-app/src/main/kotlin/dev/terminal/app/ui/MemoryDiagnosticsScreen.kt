@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.terminal.app.MemorySnapshot
import dev.terminal.app.R
import dev.terminal.app.memoryBytes
import dev.terminal.app.memoryCompositionSegments
import dev.terminal.app.memoryRatio
import org.json.JSONObject

private val memoryAreas = listOf(
    "java-heap" to R.string.memory_java, "native-heap" to R.string.memory_native,
    "code" to R.string.memory_code, "stack" to R.string.memory_stack, "graphics" to R.string.memory_graphics,
    "private-other" to R.string.memory_private_other, "system" to R.string.memory_system,
)

// Category colors are stable across cards and snapshots; they do not indicate severity.
private val memoryAreaColors = listOf(
    Color(0xFFC4ADD9), Color(0xFFD8B477), Color(0xFFD694A7), Color(0xFFADB183),
    Color(0xFFC5916F), Color(0xFFAFB5BD), Color(0xFF9983B6),
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
    label: Int = title, prominent: Boolean = false, showReference: Boolean = true,
    showBar: Boolean = true, categoryColor: Color? = null) {
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    categoryColor?.let { Box(Modifier.size(10.dp).background(it, CircleShape)) }
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                }
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
        if (showBar) fraction?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(if (prominent) 8.dp else 4.dp))
        }
    }
}

@Composable
private fun MemoryCompositionBar(section: Int, values: List<Long?>, total: Long?) {
    val composition = memoryCompositionSegments(values, total)
    val label = stringResource(R.string.memory_composition_bar_description, stringResource(section))
    val unavailable = stringResource(if (total == null || total <= 0 || values.any { it == null })
        R.string.memory_composition_unavailable else R.string.memory_composition_inconsistent)
    val difference = composition?.difference?.takeIf { it > 0 }?.let {
        stringResource(R.string.memory_composition_difference, memoryValue(it))
    }
    val details = memoryAreas.mapIndexed { index, (_, title) ->
        stringResource(R.string.memory_composition_item, stringResource(title), memoryValue(values[index]))
    }.joinToString(" · ")
    val state = if (composition == null) unavailable else
        stringResource(R.string.memory_composition_bar_state, memoryValue(total), details) + difference?.let { " · $it" }.orEmpty()
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = label
        stateDescription = state
        composition?.let {
            progressBarRangeInfo = ProgressBarRangeInfo((1.0 - it.difference.toDouble() / requireNotNull(total)).toFloat(), 0f..1f)
        }
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (composition == null) Text(unavailable, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            MemoryCompositionProgressIndicator(composition.fractions,
                (1.0 - composition.difference.toDouble() / requireNotNull(total)).toFloat())
            difference?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MemoryCompositionProgressIndicator(fractions: List<Double>, progress: Float) {
    val stopColor = ProgressIndicatorDefaults.linearColor
    val first = fractions.indexOfFirst { it > 0.0 }
    val last = fractions.indexOfLast { it > 0.0 }
    // Material draws the default-height track, track gap and stop indicator. Only the
    // transparent active portion is replaced with colored, independently rounded segments.
    LinearProgressIndicator(progress = { progress }, color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val halfGap = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize.toPx() / 2f
            val radius = if (ProgressIndicatorDefaults.LinearStrokeCap == StrokeCap.Round) size.height / 2f else 0f
            var start = 0.0
            fractions.forEachIndexed { index, fraction ->
                val end = start + fraction
                if (fraction > 0.0) {
                    val width = (size.width * fraction).toFloat()
                    // Keep small segments inside their measured allocation; reduce the gap
                    // instead of expanding their width or hiding them behind a fixed gap.
                    val inset = minOf(halfGap, width / 4f)
                    val left = (size.width * start).toFloat() + if (index == first) 0f else inset
                    val right = (size.width * end).toFloat() - if (index == last) 0f else inset
                    drawRoundRect(memoryAreaColors[index],
                        topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - right else left, 0f),
                        size = Size((right - left).coerceAtLeast(0f), size.height), cornerRadius = CornerRadius(radius))
                }
                start = end
            }
        },
        drawStopIndicator = {
            ProgressIndicatorDefaults.drawStopIndicator(drawScope = this,
                stopSize = ProgressIndicatorDefaults.LinearTrackStopIndicatorSize,
                color = stopColor, strokeCap = ProgressIndicatorDefaults.LinearStrokeCap)
        })
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
            MemoryCompositionBar(section, memoryAreas.map { (key, _) -> process?.memoryBytes(key) }, total)
            memoryAreas.forEachIndexed { index, (key, title) ->
                MemoryUsageMetric(section, title, process?.memoryBytes(key), total, R.string.memory_percentage_of_pss,
                    showReference = false, showBar = false, categoryColor = memoryAreaColors[index])
            }
            if (section == R.string.memory_app_total) Text(stringResource(R.string.memory_pss_caveat),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
