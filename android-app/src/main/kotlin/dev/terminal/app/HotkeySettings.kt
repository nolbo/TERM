@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalOnStatusBlueContainer
import dev.terminal.app.ui.TerminalStatusBlueContainer
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The editor and the live toolbar use the same ordered, spanning grid. */
@Composable
internal fun HotkeyGrid(
    layout: HotkeyLayout,
    modifier: Modifier = Modifier,
    control: Boolean = false,
    alt: Boolean = false,
    editing: Boolean = false,
    enabled: Boolean = true,
    cellModifier: (Int) -> Modifier = { Modifier },
    onKey: (Int) -> Unit,
) {
    // Honor compact heights in both the preview and the toolbar, including modifier keys.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides layout.height.dp) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val columns = minOf(layout.columns, ((maxWidth.value + 4) / 52).toInt().coerceAtLeast(1))
            val cellWidth = (maxWidth - (4 * (columns - 1)).dp) / columns
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hotkeyRows(layout.keys, columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { index ->
                            val key = layout.keys[index]
                            val span = key.span.coerceAtMost(columns)
                            val cell = Modifier.width(cellWidth * span + (4 * (span - 1)).dp).heightIn(min = layout.height.dp).then(cellModifier(index))
                            val description = if (editing) stringResource(R.string.hotkey_edit_description, index + 1, key.name, key.shortcut)
                                else "${key.name}: ${key.shortcut}"
                            val content: @Composable () -> Unit = {
                                Text(key.name, fontFamily = FontFamily.Monospace, fontSize = layout.fontSize.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!editing && key.shortcut in setOf("CTRL", "ALT")) {
                                FilledTonalToggleButton(
                                    enabled = enabled,
                                    checked = if (key.shortcut == "CTRL") control else alt,
                                    onCheckedChange = { onKey(index) },
                                    modifier = cell.semantics { contentDescription = description },
                                    shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight)
                                        .copy(pressedShape = CircleShape, checkedShape = CircleShape),
                                    colors = FilledTonalToggleButtonDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        checkedContainerColor = TerminalStatusBlueContainer,
                                        checkedContentColor = TerminalOnStatusBlueContainer,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                ) { content() }
                            } else {
                                val interactions = remember { MutableInteractionSource() }
                                val currentOnKey by rememberUpdatedState({ onKey(index) })
                                val repeatable = !editing && isRepeatableHotkey(key.shortcut)
                                var repeatJob by remember { mutableStateOf<Job?>(null) }
                                var repeated by remember { mutableStateOf(false) }
                                LaunchedEffect(interactions, key.shortcut, repeatable, enabled) {
                                    if (!repeatable || !enabled) return@LaunchedEffect
                                    try {
                                        interactions.interactions.collect { interaction ->
                                            when (interaction) {
                                                is PressInteraction.Press -> {
                                                    repeatJob?.cancel()
                                                    repeated = false
                                                    repeatJob = launch {
                                                        delay(400)
                                                        while (isActive) {
                                                            repeated = true
                                                            currentOnKey()
                                                            delay(60)
                                                        }
                                                    }
                                                }
                                                is PressInteraction.Release -> repeatJob?.cancel()
                                                is PressInteraction.Cancel -> {
                                                    repeatJob?.cancel()
                                                    repeated = false
                                                }
                                            }
                                        }
                                    } finally {
                                        repeatJob?.cancel()
                                        repeated = false
                                    }
                                }
                                FilledTonalButton(
                                    onClick = {
                                        // Release can reach onClick before the interaction collector.
                                        // Cancel synchronously and don't append an extra key after a hold.
                                        repeatJob?.cancel()
                                        if (!repeated) currentOnKey()
                                        repeated = false
                                    },
                                    enabled = enabled,
                                    interactionSource = interactions,
                                    modifier = cell.semantics { contentDescription = description },
                                    shapes = ButtonDefaults.shapes().copy(pressedShape = CircleShape),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                ) { content() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HotkeySettings(preferences: SharedPreferences, onBack: () -> Unit) {
    var saved by rememberSaveable { mutableStateOf(serializeHotkeyLayout(readHotkeyLayout(preferences))) }
    var draft by rememberSaveable { mutableStateOf(saved) }
    val layout = remember(draft) { parseHotkeyLayout(draft) }
    var editingIndex by rememberSaveable { mutableIntStateOf(-1) }
    var resetVisible by rememberSaveable { mutableStateOf(false) }
    var discardVisible by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val update: (HotkeyLayout) -> Unit = { draft = serializeHotkeyLayout(it) }
    val leave = { if (draft != saved) discardVisible = true else onBack() }
    androidx.activity.compose.BackHandler(onBack = leave)
    SettingsPageScaffold(stringResource(R.string.settings_hotkeys_title), leave) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.weight(1f).onGloballyPositioned { viewport = it }.verticalScroll(scrollState).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_hotkeys_description), style = MaterialTheme.typography.bodyLarge)
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HotkeySizeControl(R.string.hotkey_columns, layout.columns, 2..10) { update(layout.copy(columns = it)) }
                        HotkeySizeControl(R.string.hotkey_height, layout.height, HOTKEY_HEIGHT_RANGE) { update(layout.copy(height = it)) }
                        HotkeySizeControl(R.string.hotkey_font_size, layout.fontSize, 10..24) { update(layout.copy(fontSize = it)) }
                    }
                }
                Text(stringResource(R.string.hotkey_preview), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.hotkey_preview_support), style = MaterialTheme.typography.bodyMedium)
                ReorderableHotkeyPreview(
                    layout = layout, scrollState = scrollState,
                    viewportBounds = { viewport?.takeIf { it.isAttached }?.boundsInRoot() },
                    onMove = { from, to -> update(moveHotkey(layout, from, to)) },
                    onEdit = { editingIndex = it },
                )
                Button(onClick = { editingIndex = layout.keys.size }, enabled = layout.keys.size < MAX_HOTKEYS,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.hotkey_add))
                }
                TextButton(onClick = { resetVisible = true }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.hotkey_reset))
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = leave, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = {
                    preferences.edit().putString(KEY_HOTKEYS, draft).apply()
                    saved = draft
                    onBack()
                }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
            }
        }
    }
    if (editingIndex >= 0) {
        val index = editingIndex
        HotkeyEditDialog(
            initial = layout.keys.getOrNull(index) ?: Hotkey("", "CTRL+C"),
            index = index, count = layout.keys.size,
            onSave = { hotkey ->
                update(layout.copy(keys = layout.keys.toMutableList().apply {
                    if (index == size) add(hotkey) else set(index, hotkey)
                }))
                editingIndex = -1
            },
            onMove = { key, target ->
                val updated = layout.copy(keys = layout.keys.toMutableList().apply { set(index, key) })
                update(moveHotkey(updated, index, target))
                editingIndex = -1
            },
            onRemove = {
                update(layout.copy(keys = layout.keys.filterIndexed { position, _ -> position != index }))
                editingIndex = -1
            },
            onDismiss = { editingIndex = -1 },
        )
    }
    if (resetVisible) AlertDialog(
        onDismissRequest = { resetVisible = false }, title = { Text(stringResource(R.string.hotkey_reset)) },
        text = { Text(stringResource(R.string.hotkey_reset_support)) },
        confirmButton = { TextButton(onClick = { update(HotkeyLayout()); resetVisible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { resetVisible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
    if (discardVisible) AlertDialog(
        onDismissRequest = { discardVisible = false }, title = { Text(stringResource(R.string.hotkey_discard)) },
        confirmButton = { TextButton(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { discardVisible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun HotkeySizeControl(title: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val label = stringResource(title)
    Text("$label: $value", style = MaterialTheme.typography.labelLarge)
    Slider(value = value.toFloat(), onValueChange = { onChange(kotlin.math.round(it).toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
        modifier = Modifier.semantics { contentDescription = label })
}

@Composable
private fun HotkeyEditDialog(
    initial: Hotkey, index: Int, count: Int,
    onSave: (Hotkey) -> Unit, onMove: (Hotkey, Int) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
) {
    var name by rememberSaveable(index) { mutableStateOf(initial.name) }
    var shortcut by rememberSaveable(index) { mutableStateOf(initial.shortcut) }
    var span by rememberSaveable(index) { mutableIntStateOf(initial.span) }
    var showHelp by rememberSaveable(index) { mutableStateOf(false) }
    val hotkey = Hotkey(name.trim(), shortcut.trim().uppercase(Locale.ROOT), span)
    val valid = validHotkey(hotkey)
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (index == count) R.string.hotkey_add else R.string.hotkey_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.hotkey_name)) },
                    singleLine = true, isError = name.isNotEmpty() && (name.isBlank() || name.length > 32 || name.any { it.isISOControl() }),
                    supportingText = { Text(stringResource(R.string.hotkey_name_support)) })
                OutlinedTextField(shortcut, { shortcut = it }, label = { Text(stringResource(R.string.hotkey_shortcut)) },
                    singleLine = true, isError = !validHotkey(hotkey.copy(name = "key")),
                    supportingText = { Text(stringResource(R.string.hotkey_shortcut_hint)) })
                TextButton(onClick = { showHelp = !showHelp }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.hotkey_supported_keys))
                }
                if (showHelp) Text(stringResource(R.string.hotkey_shortcut_support), style = MaterialTheme.typography.bodySmall)
                // Compact presets and basic keys share the same grid as the toolbar.
                HotkeyGrid(HotkeyLayout(columns = 3, keys = listOf(
                    Hotkey("Ctrl+C", "CTRL+C"), Hotkey("Ctrl+D", "CTRL+D"), Hotkey("Ctrl+L", "CTRL+L"),
                    Hotkey("Ctrl", "CTRL"), Hotkey("Alt", "ALT"), Hotkey("Tab", "TAB"),
                )), editing = true) { preset ->
                    shortcut = listOf("CTRL+C", "CTRL+D", "CTRL+L", "CTRL", "ALT", "TAB")[preset]
                    if (name.isBlank()) name = shortcut
                }
                HotkeySizeControl(R.string.hotkey_span, span, 1..4) { span = it }
                if (index < count) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onMove(hotkey, index - 1) }, enabled = valid && index > 0, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.hotkey_move_before))
                        }
                        OutlinedButton(onClick = { onMove(hotkey, index + 1) }, enabled = valid && index < count - 1, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.hotkey_move_after))
                        }
                    }
                    TextButton(onClick = onRemove, enabled = count > 1, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.hotkey_remove)) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(hotkey) }, enabled = valid, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}
