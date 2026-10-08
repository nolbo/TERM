@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.terminal.CommandMarker
import com.termux.terminal.TerminalHistory
import com.termux.view.TerminalView
import dev.terminal.app.*
import dev.terminal.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class HistoryToolSnapshot(
    val commands: List<HistoryCommand> = emptyList(),
    val row: Int = 0,
    val oldest: Int = 0,
    val available: Boolean = false,
    val attached: Boolean = false,
    val query: String = "",
    val scope: HistorySearchScope = HistorySearchScope.Commands,
)

@Composable
internal fun ToolbarHistoryTool(active: Boolean, terminal: () -> TerminalView?) {
    val colors = LocalToolbarColors.current
    val expansion = LocalToolbarExpansionProgress.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf(HistorySearchScope.Commands) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CommandMarker?>(null) }
    var snapshot by remember { mutableStateOf(HistoryToolSnapshot()) }
    var refresh by remember { mutableIntStateOf(0) }
    val currentTerminal by rememberUpdatedState(terminal)
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            menu = false
            if (searchFocused) focusManager.clearFocus()
        }
    }
    // Read the UI mirror on its main thread, only on the selected, visible tool.
    // A checkpoint can replace the history object without replacing the emulator.
    LaunchedEffect(active, query, searchScope, refresh) {
        if (!active) return@LaunchedEffect
        if (query.isNotEmpty()) delay(180)
        var previous: TerminalHistory? = null
        var revision = -1L
        while (isActive) {
            val view = currentTerminal()
            val emulator = view?.mEmulator
            val history = emulator?.history
            val commands = if (emulator != null && history != null && (history !== previous || history.revision != revision)) {
                previous = history
                revision = history.revision
                readCommandHistory(emulator, query, searchScope)
            } else if (emulator != null) snapshot.commands else emptyList()
            snapshot = HistoryToolSnapshot(commands = commands, row = view?.topRow ?: 0,
                oldest = -(emulator?.screen?.activeTranscriptRows ?: 0),
                available = emulator != null && !emulator.isHistoryViewportOwnedByApplication,
                attached = emulator != null, query = query, scope = searchScope)
            delay(200)
        }
    }
    val ready = active && snapshot.available && snapshot.query == query && snapshot.scope == searchScope
    val current = currentHistoryCommand(snapshot.commands, snapshot.row, selected)
    val previous = commandHistoryTarget(snapshot.commands, snapshot.row, false, current)
    val next = commandHistoryTarget(snapshot.commands, snapshot.row, true, current)
    fun jump(target: HistoryTarget) {
        if (!ready) return
        val view = currentTerminal() ?: return
        val moved = target.command?.let { view.jumpToCommand(it.marker) }
            ?: view.jumpToHistoryRow(if (target.bottom) 0 else snapshot.oldest)
        if (moved) {
            selected = target.command?.marker
            snapshot = snapshot.copy(row = view.topRow)
            refresh++
        }
    }
    val scroll = rememberLazyListState()
    val expanded = expansion.value >= 0.99f
    LaunchedEffect(current, expanded) {
        val index = snapshot.commands.asReversed().indexOfFirst { it.marker === current }
        if (index >= 0 && active && expanded) scroll.animateScrollToItem(index)
    }
    val searchLabel = stringResource(R.string.history_search_description)
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            HistoryNavigationButton(previous.command == null, false,
                ready && (previous.command != null || snapshot.row > snapshot.oldest)) { jump(previous) }
            HistoryNavigationButton(next.command == null, true,
                ready && (next.command != null || snapshot.row < 0)) { jump(next) }
            TextField(value = query, onValueChange = { query = it.take(256) }, singleLine = true,
                enabled = active, shape = CircleShape,
                placeholder = { Text(stringResource(R.string.history_search), maxLines = 1) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    snapshot.commands.lastOrNull()?.let { jump(HistoryTarget(it)) }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.container, unfocusedContainerColor = colors.container,
                    disabledContainerColor = colors.container,
                    focusedTextColor = colors.onContainer, unfocusedTextColor = colors.onContainer,
                    disabledTextColor = colors.onContainer.copy(alpha = 0.38f), cursorColor = colors.accent,
                    focusedPlaceholderColor = colors.onContainer, unfocusedPlaceholderColor = colors.onContainer,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ), modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused }
                    .semantics { contentDescription = searchLabel })
            Box {
                val scopeLabel = stringResource(R.string.history_scope, stringResource(searchScope.title))
                TextButton(onClick = { menu = true }, enabled = active,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.foreground,
                        disabledContentColor = colors.foreground.copy(alpha = 0.38f)),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = scopeLabel }) {
                    Text(stringResource(searchScope.title), style = MaterialTheme.typography.labelMedium)
                    Icon(painterResource(R.drawable.ic_material_symbol_expand_more_24), null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    HistorySearchScope.entries.forEach { scope ->
                        DropdownMenuItem(text = { Text(stringResource(scope.title)) },
                            onClick = { searchScope = scope; menu = false },
                            trailingIcon = { if (scope == searchScope) Icon(
                                painterResource(R.drawable.ic_material_symbol_check_circle_24), null) })
                    }
                }
            }
        }
        val summary = when {
            !snapshot.attached || snapshot.query != query || snapshot.scope != searchScope -> stringResource(R.string.toolbar_loading)
            !snapshot.available -> stringResource(R.string.history_application_viewport)
            snapshot.commands.isEmpty() && query.isNotEmpty() -> stringResource(R.string.history_no_results)
            snapshot.commands.isEmpty() -> stringResource(R.string.history_empty)
            else -> stringResource(R.string.history_count, snapshot.commands.size)
        }
        Text(summary, Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(28.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = colors.foreground)
        ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward,
            Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = expansion.value }) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().semantics {
                if (!expanded) hideFromAccessibility()
            }, contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(snapshot.commands.asReversed(), key = { _, item -> item.marker.anchor }) { index, command ->
                    HistoryCommandRow(command, current === command.marker, ready && expanded,
                        first = index == 0, last = index == snapshot.commands.lastIndex,
                        modifier = Modifier.animateItem(
                            fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                        onClick = { jump(HistoryTarget(command)) })
                }
            }
        }
    }
}

@Composable
private fun HistoryNavigationButton(edge: Boolean, next: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalToolbarColors.current
    val label = stringResource(if (edge) {
        if (next) R.string.history_bottom else R.string.history_top
    } else if (next) R.string.history_next else R.string.history_previous)
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.foreground,
                disabledContentColor = colors.foreground.copy(alpha = 0.38f)),
            modifier = Modifier.size(48.dp)) {
            Icon(painterResource(if (edge) R.drawable.ic_material_symbol_chevron_line_down_24
                else R.drawable.ic_material_symbol_expand_more_24), label,
                Modifier.rotate(if (next) 0f else 180f))
        }
    }
}

@Composable
private fun HistoryCommandRow(command: HistoryCommand, selected: Boolean, enabled: Boolean,
    first: Boolean, last: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalToolbarColors.current
    val background by animateColorAsState(if (selected) colors.checkedContainer else Color.Transparent,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "historyCommandSelection")
    val baseForeground = if (selected) colors.onCheckedContainer else colors.foreground
    val foreground = if (enabled) baseForeground else baseForeground.copy(alpha = 0.38f)
    val status = when (command.status) {
        HistoryCommandStatus.Running -> stringResource(R.string.history_running)
        HistoryCommandStatus.Failed -> stringResource(R.string.history_failed, command.exitCode ?: -1)
        HistoryCommandStatus.Completed -> command.exitCode?.let { stringResource(R.string.history_completed_code, it) }
            ?: stringResource(R.string.history_completed)
    }
    val commandText = command.marker.commandText ?: stringResource(R.string.history_command_unavailable)
    Surface(onClick, enabled = enabled, shape = MaterialTheme.shapes.extraLarge,
        color = background, contentColor = foreground,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected; stateDescription = status }) {
        Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 56.dp).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width / 2
                    val gap = 16.dp.toPx()
                    if (!first) drawLine(foreground.copy(alpha = foreground.alpha * 0.3f), Offset(x, 0f),
                        Offset(x, center.y - gap), strokeWidth = 2.dp.toPx())
                    if (!last) drawLine(foreground.copy(alpha = foreground.alpha * 0.3f), Offset(x, center.y + gap),
                        Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
                TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(status) } }, state = rememberTooltipState()) {
                    Surface(shape = CircleShape, color = if (selected) colors.checkedContainer else colors.container,
                        contentColor = foreground) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(when (command.status) {
                                HistoryCommandStatus.Running -> R.drawable.ic_material_symbol_play_arrow_24
                                HistoryCommandStatus.Completed -> R.drawable.ic_material_symbol_check_circle_24
                                HistoryCommandStatus.Failed -> R.drawable.ic_material_symbol_block_24
                            }), null, Modifier.size(24.dp))
                        }
                    }
                }
            }
            Text(commandText, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, fontFamily = LocalTerminalMonospaceFontFamily.current,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
