@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.terminal.app.ui.FloatingToolbarAction
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.SettingsExpansionContent
import dev.terminal.app.ui.SettingsExpansionIcon
import dev.terminal.app.ui.TerminalOnStatusBlueContainer
import dev.terminal.app.ui.TerminalStatusBlueContainer
import dev.terminal.app.ui.LocalTerminalMonospaceFontFamily
import dev.terminal.app.ui.LocalTerminalHaptics
import dev.terminal.app.ui.terminalListItemShapes
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Both surfaces render the saved coordinates, including gaps and different key heights. */
@Composable
internal fun HotkeyGrid(
    layout: HotkeyLayout,
    modifier: Modifier = Modifier,
    control: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    editing: Boolean = false,
    enabled: Boolean = true,
    terminalColors: dev.terminal.app.ui.TerminalChromeColors? = null,
    cellModifier: (Int) -> Modifier = { Modifier },
    minimumRows: Int = 0,
    canvasModifier: Modifier = Modifier,
    decoration: @Composable BoxScope.(androidx.compose.ui.unit.Dp) -> Unit = {},
    onKey: (Int) -> Unit,
) {
    val haptics = LocalTerminalHaptics.current
    val bounds = remember(layout) { hotkeyBounds(layout) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides HOTKEY_HEIGHT_DP.dp) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            // Scroll a narrow viewport instead of repacking keys or losing their saved gaps.
            val canvasWidth = maxOf(maxWidth, (layout.columns * 52 - HOTKEY_GAP_DP).dp)
            val pitch = (canvasWidth + HOTKEY_GAP_DP.dp) / layout.columns
            val rows = maxOf(minimumRows.toFloat(), bounds.maxOfOrNull { it.bottom } ?: 1f)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Box(Modifier.width(canvasWidth).height(((HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP) * rows - HOTKEY_GAP_DP).dp)
                    .then(canvasModifier)) {
                    layout.keys.forEachIndexed { index, key ->
                        val rect = bounds[index]
                        val cell = Modifier.offset(pitch * rect.x, ((HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP) * rect.y).dp)
                            .size(pitch * rect.width - HOTKEY_GAP_DP.dp,
                                ((HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP) * rect.height - HOTKEY_GAP_DP).dp)
                            .then(cellModifier(index))
                        val description = if (editing) stringResource(R.string.hotkey_edit_description, index + 1, key.name, key.shortcut)
                            else "${key.name}: ${key.shortcut}"
                        val content: @Composable () -> Unit = {
                            Text(key.name, fontFamily = LocalTerminalMonospaceFontFamily.current, fontSize = layout.fontSize.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!editing && key.shortcut in setOf("CTRL", "ALT", "SHIFT")) {
                            FilledTonalToggleButton(
                                enabled = enabled,
                                checked = when (key.shortcut) {
                                    "CTRL" -> control
                                    "ALT" -> alt
                                    else -> shift
                                },
                                onCheckedChange = {
                                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                    onKey(index)
                                },
                                modifier = cell.semantics { contentDescription = description },
                                shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight)
                                    .copy(pressedShape = CircleShape, checkedShape = CircleShape),
                                colors = FilledTonalToggleButtonDefaults.colors(
                                    containerColor = terminalColors?.buttonContainer ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = terminalColors?.onButtonContainer ?: MaterialTheme.colorScheme.onSurface,
                                    disabledContainerColor = (terminalColors?.foreground ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.12f),
                                    disabledContentColor = (terminalColors?.foreground ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.38f),
                                    checkedContainerColor = terminalColors?.checkedContainer ?: TerminalStatusBlueContainer,
                                    checkedContentColor = terminalColors?.onCheckedContainer ?: TerminalOnStatusBlueContainer,
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
                                    if (!repeated) {
                                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        currentOnKey()
                                    }
                                    repeated = false
                                },
                                enabled = enabled,
                                interactionSource = interactions,
                                modifier = cell.semantics { contentDescription = description },
                                shapes = ButtonDefaults.shapes().copy(pressedShape = CircleShape),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = terminalColors?.buttonContainer ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = terminalColors?.onButtonContainer ?: MaterialTheme.colorScheme.onSurface,
                                    disabledContainerColor = (terminalColors?.foreground ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.12f),
                                    disabledContentColor = (terminalColors?.foreground ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.38f),
                                ),
                                contentPadding = PaddingValues(horizontal = 2.dp),
                            ) { content() }
                        }
                    }
                    decoration(pitch)
                }
            }
        }
    }
}

@Composable
internal fun HotkeySettings(preferences: SharedPreferences, onBack: () -> Unit) {
    val haptics = LocalTerminalHaptics.current
    var saved by rememberSaveable { mutableStateOf(serializeHotkeyLayout(readHotkeyLayout(preferences))) }
    var draft by rememberSaveable { mutableStateOf(saved) }
    val layout = remember(draft) { parseHotkeyLayout(draft) }
    var editingIndex by rememberSaveable { mutableIntStateOf(-1) }
    var discardVisible by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val update: (HotkeyLayout) -> Unit = { draft = serializeHotkeyLayout(it) }
    val leave = { if (draft != saved) discardVisible = true else onBack() }
    androidx.activity.compose.BackHandler(onBack = leave)
    SettingsPageScaffold(
        stringResource(R.string.settings_hotkeys_title), leave,
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            HorizontalFloatingToolbar(expanded = true) {
                FloatingToolbarAction(stringResource(R.string.hotkey_restore_saved), R.drawable.ic_material_symbol_undo_24,
                    enabled = draft != saved, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        draft = saved
                    })
                FloatingToolbarAction(stringResource(R.string.hotkey_add), R.drawable.ic_material_symbol_add_24,
                    enabled = layout.keys.size < MAX_HOTKEYS, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        editingIndex = layout.keys.size
                    })
                FloatingToolbarAction(stringResource(R.string.save), R.drawable.ic_material_symbol_save_24,
                    enabled = true, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        preferences.edit().putString(KEY_HOTKEYS, draft).apply()
                        saved = draft
                    })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { viewport = it }.verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp,
                bottom = FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings_hotkeys_description), style = MaterialTheme.typography.bodyLarge)
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val minimumColumns = kotlin.math.ceil(hotkeyBounds(layout).maxOf { it.right }).toInt().coerceAtLeast(2)
                    HotkeySizeControl(R.string.hotkey_columns, layout.columns, minimumColumns..10) {
                        update(resizeHotkeyCanvas(layout, it))
                    }
                    Text(stringResource(R.string.hotkey_canvas_support), style = MaterialTheme.typography.bodySmall)
                    HotkeySizeControl(R.string.hotkey_font_size, layout.fontSize, 10..24) { update(layout.copy(fontSize = it)) }
                }
            }
            Text(stringResource(R.string.hotkey_preview), style = MaterialTheme.typography.titleMedium)
            EditableHotkeyPreview(
                layout = layout, scrollState = scrollState,
                viewportBounds = { viewport?.takeIf { it.isAttached }?.boundsInRoot() },
                onChange = update,
                onEdit = { editingIndex = it },
            )
        }
    }
    if (editingIndex >= 0) {
        val index = editingIndex
        HotkeyEditDialog(
            initial = layout.keys.getOrNull(index) ?: Hotkey("", "CTRL+C"),
            index = index, count = layout.keys.size,
            onSave = { hotkey ->
                update(if (index == layout.keys.size) addHotkey(layout, hotkey)
                    else layout.copy(keys = layout.keys.toMutableList().apply { set(index, hotkey) }))
                editingIndex = -1
            },
            onDismiss = { editingIndex = -1 },
        )
    }
    if (discardVisible) AlertDialog(
        onDismissRequest = { discardVisible = false }, title = { Text(stringResource(R.string.hotkey_discard)) },
        confirmButton = { TextButton(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { discardVisible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun HotkeySizeControl(title: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val haptics = LocalTerminalHaptics.current
    val label = stringResource(title)
    Text("$label: $value", style = MaterialTheme.typography.labelLarge)
    Slider(value = value.toFloat(), onValueChange = {
        val updated = kotlin.math.round(it).toInt()
        if (updated != value) haptics.performDenseHapticFeedback(HapticFeedbackType.KeyboardTap)
        onChange(updated)
    },
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
        enabled = range.first < range.last,
        modifier = Modifier.semantics { contentDescription = label })
}

@Composable
private fun HotkeyEditDialog(
    initial: Hotkey, index: Int, count: Int,
    onSave: (Hotkey) -> Unit, onDismiss: () -> Unit,
) {
    var name by rememberSaveable(index) { mutableStateOf(initial.name) }
    var shortcut by rememberSaveable(index) { mutableStateOf(initial.shortcut) }
    var showHelp by rememberSaveable(index) { mutableStateOf(false) }
    val hotkey = initial.copy(name = name.trim(), shortcut = shortcut.trim().uppercase(Locale.ROOT))
    val valid = validHotkey(hotkey)
    val haptics = LocalTerminalHaptics.current
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
                Column {
                    val expansion = stringResource(if (showHelp) R.string.usb_expanded else R.string.usb_collapsed)
                    TextButton(onClick = { showHelp = !showHelp }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.semantics { stateDescription = expansion }) {
                        Text(stringResource(R.string.hotkey_supported_keys))
                        Spacer(Modifier.width(8.dp))
                        SettingsExpansionIcon(showHelp)
                    }
                    SettingsExpansionContent(showHelp) {
                        Text(stringResource(R.string.hotkey_shortcut_support), Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onSave(hotkey)
        }, enabled = valid, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}
