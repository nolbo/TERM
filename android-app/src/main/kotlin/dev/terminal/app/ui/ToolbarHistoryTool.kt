@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.terminal.emulator.CommandMarker
import dev.terminal.emulator.TerminalHistory
import dev.terminal.view.TerminalView
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
    val searchCount: Int = 0,
    val searchIndex: Int = -1,
    val query: String = "",
    val scope: HistorySearchScope = HistorySearchScope.Commands,
    val historyRevision: Long = -1,
)

@Composable
internal fun ToolbarHistoryTool(active: Boolean, onReturnToTerminal: () -> Unit, terminal: () -> TerminalView?) {
    val colors = LocalToolbarColors.current
    val expansion = LocalToolbarExpansionProgress.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf(HistorySearchScope.Commands) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CommandMarker?>(null) }
    var expandedCommands by remember { mutableStateOf(emptySet<CommandMarker>()) }
    var snapshot by remember { mutableStateOf(HistoryToolSnapshot()) }
    var refresh by remember { mutableIntStateOf(0) }
    val currentTerminal by rememberUpdatedState(terminal)
    val focusManager = LocalFocusManager.current
    val composeView = LocalView.current
    var searchFocused by remember { mutableStateOf(false) }
    // Capture before disabling the TextField changes its focus and dismisses the IME.
    val wasSearchFocused = searchFocused
    val returnToTerminal = !active && wasSearchFocused &&
        ViewCompat.getRootWindowInsets(composeView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    val searchOwner = remember { arrayOfNulls<TerminalView>(1) }
    DisposableEffect(active) {
        onDispose {
            searchOwner[0]?.clearHistorySearch()
            searchOwner[0] = null
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            menu = false
            if (wasSearchFocused) focusManager.clearFocus()
            if (returnToTerminal) onReturnToTerminal()
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
            if (searchOwner[0] !== view) {
                searchOwner[0]?.clearHistorySearch()
                searchOwner[0] = view
            }
            val emulator = view?.mEmulator
            val history = emulator?.history
            val commands = if (emulator != null && history != null && (history !== previous || history.revision != revision)) {
                previous = history
                revision = history.revision
                readCommandHistory(emulator)
            } else if (emulator != null) snapshot.commands else emptyList()
            view?.updateHistorySearch(query, searchScope.cells)
            snapshot = HistoryToolSnapshot(commands = commands, row = view?.topRow ?: 0,
                oldest = -(emulator?.screen?.activeTranscriptRows ?: 0),
                available = emulator != null && !emulator.isHistoryViewportOwnedByApplication,
                attached = emulator != null, searchCount = view?.historySearchMatches?.size ?: 0,
                searchIndex = view?.historySearchIndex ?: -1, query = query, scope = searchScope,
                historyRevision = history?.revision ?: -1)
            delay(200)
        }
    }
    val ready = active && snapshot.available && snapshot.query == query && snapshot.scope == searchScope
    LaunchedEffect(snapshot.commands) {
        expandedCommands = expandedCommands.intersect(snapshot.commands.map { it.marker }.toSet())
    }
    val current = currentHistoryCommand(snapshot.commands, snapshot.row, selected)
    val previous = commandHistoryTarget(snapshot.commands, snapshot.row, false, current)
    val next = commandHistoryTarget(snapshot.commands, snapshot.row, true, current)
    val searching = query.isNotEmpty()
    fun search(next: Boolean) {
        if (!ready) return
        val view = currentTerminal() ?: return
        if (view.moveHistorySearchMatch(next)) {
            selected = null
            snapshot = snapshot.copy(row = view.topRow, searchIndex = view.historySearchIndex,
                searchCount = view.historySearchMatches.size)
            refresh++
        }
    }
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
    val summary = when {
        !snapshot.attached || snapshot.query != query || snapshot.scope != searchScope -> stringResource(R.string.toolbar_loading)
        !snapshot.available -> stringResource(R.string.history_application_viewport)
        searching && snapshot.searchCount == 0 -> stringResource(R.string.history_no_results)
        searching -> stringResource(R.string.history_search_count, snapshot.searchIndex + 1, snapshot.searchCount)
        snapshot.commands.isEmpty() -> stringResource(R.string.history_empty)
        else -> ""
    }
    val summarySpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val summaryEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val summaryTransition = updateTransition(summary, label = "historySummary")
    val summaryProgress by summaryTransition.animateFloat(transitionSpec = { summarySpatial },
        label = "historySummarySpace") { if (it.isNotEmpty()) 1f else 0f }
    val summaryHeight = 28.dp * summaryProgress.coerceIn(0f, 1f)
    val compactHeaderHeight = ToolbarHotkeyViewportHeight - summaryHeight
    val headerHeight = compactHeaderHeight + (60.dp - compactHeaderHeight) * expansion.value
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(headerHeight), verticalAlignment = Alignment.CenterVertically) {
            HistoryNavigationButton(!searching && previous.command == null, false,
                ready && if (searching) snapshot.searchCount > 0 else (previous.command != null || snapshot.row > snapshot.oldest),
                searching) { if (searching) search(false) else jump(previous) }
            HistoryNavigationButton(!searching && next.command == null, true,
                ready && if (searching) snapshot.searchCount > 0 else (next.command != null || snapshot.row < 0),
                searching) { if (searching) search(true) else jump(next) }
            TextField(value = query, onValueChange = { query = it.take(256) }, singleLine = true,
                enabled = active, shape = CircleShape,
                placeholder = { Text(stringResource(R.string.history_search), maxLines = 1) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (searching) search(true)
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
            Box(Modifier.padding(start = 4.dp)) {
                val scopeLabel = stringResource(R.string.history_scope, stringResource(searchScope.title))
                val menuLabel = stringResource(R.string.history_scope_choose)
                val buttonColors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.buttonContainer, contentColor = colors.onButtonContainer,
                    disabledContainerColor = colors.container,
                    disabledContentColor = colors.onContainer.copy(alpha = 0.38f))
                val menuRotation by animateFloatAsState(if (menu) 180f else 0f,
                    MaterialTheme.motionScheme.fastSpatialSpec(), label = "historyScopeMenuRotation")
                SplitButtonLayout(
                    leadingButton = {
                        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text(scopeLabel) } }, state = rememberTooltipState()) {
                            SplitButtonDefaults.TonalLeadingButton(onClick = { menu = true }, enabled = active,
                                colors = buttonColors, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(48.dp).semantics { contentDescription = scopeLabel }) {
                                Icon(painterResource(R.drawable.ic_material_symbol_filter_alt_24), null, Modifier.size(24.dp))
                            }
                        }
                    },
                    trailingButton = {
                        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text(menuLabel) } }, state = rememberTooltipState()) {
                            SplitButtonDefaults.TonalTrailingButton(checked = menu, onCheckedChange = { menu = it },
                                enabled = active, colors = buttonColors, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = menuLabel; stateDescription = scopeLabel
                                }) {
                                Icon(painterResource(R.drawable.ic_material_symbol_expand_more_24), null,
                                    Modifier.size(24.dp).rotate(menuRotation))
                            }
                        }
                    })
                DropdownMenuPopup(expanded = menu && active, onDismissRequest = { menu = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1),
                        modifier = Modifier.verticalScroll(rememberScrollState())) {
                        HistorySearchScope.entries.forEachIndexed { index, scope ->
                            SelectableDropdownMenuItem(selected = scope == searchScope,
                                shapes = MenuDefaults.itemShape(index, HistorySearchScope.entries.size),
                                text = { Text(stringResource(scope.title)) },
                                onClick = { searchScope = scope; menu = false },
                                trailingContent = { if (scope == searchScope) Icon(
                                    painterResource(R.drawable.ic_material_symbol_check_24), null,
                                    modifier = Modifier.size(MenuDefaults.TrailingIconSize)) })
                        }
                    }
                }
            }
        }
        summaryTransition.AnimatedContent(
            modifier = Modifier.fillMaxWidth().height(summaryHeight).clipToBounds(),
            contentAlignment = Alignment.TopStart,
            transitionSpec = { (fadeIn(summaryEffects) togetherWith fadeOut(summaryEffects)).using(null) },
        ) { displayedSummary ->
            if (displayedSummary.isNotEmpty()) {
                Text(displayedSummary, Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .wrapContentHeight(Alignment.Top, unbounded = true).height(28.dp)
                    .semantics { if (displayedSummary != summary) hideFromAccessibility() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    color = colors.foreground)
            }
        }
        ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward,
            Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = expansion.value }) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().semantics {
                if (!expanded) hideFromAccessibility()
            }, contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(snapshot.commands.asReversed(), key = { _, item -> item.marker.anchor }) { index, command ->
                    HistoryCommandRow(command, current === command.marker, ready && expanded,
                        outputExpanded = command.marker in expandedCommands, historyRevision = snapshot.historyRevision,
                        onToggleOutput = {
                            expandedCommands = if (command.marker in expandedCommands) expandedCommands - command.marker
                                else expandedCommands + command.marker
                        },
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
private fun HistoryNavigationButton(edge: Boolean, next: Boolean, enabled: Boolean, searching: Boolean, onClick: () -> Unit) {
    val colors = LocalToolbarColors.current
    val label = stringResource(if (searching) {
        if (next) R.string.history_search_next else R.string.history_search_previous
    } else if (edge) {
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
    outputExpanded: Boolean, historyRevision: Long, onToggleOutput: () -> Unit,
    first: Boolean, last: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalToolbarColors.current
    val baseForeground by animateColorAsState(if (selected) colors.checkedContainer else colors.unselectedTabContent,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "historyCommandContent")
    val foreground = if (enabled) baseForeground else baseForeground.copy(alpha = 0.38f)
    val graphColor = colors.foreground.copy(alpha = 0.3f * if (enabled) 1f else 0.38f)
    val status = when (command.status) {
        HistoryCommandStatus.Running -> stringResource(R.string.history_running)
        HistoryCommandStatus.Failed -> stringResource(R.string.history_failed, command.exitCode ?: -1)
        HistoryCommandStatus.Completed -> command.exitCode?.let { stringResource(R.string.history_completed_code, it) }
            ?: stringResource(R.string.history_completed)
    }
    val inputPreview = command.marker.commandText
    val commandText = inputPreview ?: stringResource(R.string.history_command_unavailable)
    val expansionLabel = stringResource(if (outputExpanded) R.string.history_output_collapse else R.string.history_output_expand,
        commandText)
    val expansionState = stringResource(if (outputExpanded) R.string.history_output_expanded else R.string.history_output_collapsed)
    Column(modifier.fillMaxWidth()) {
        Surface(onClick, enabled = enabled, shape = MaterialTheme.shapes.extraLarge,
            color = Color.Transparent, contentColor = foreground,
            modifier = Modifier.fillMaxWidth().semantics { this.selected = selected; stateDescription = status }) {
            Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = size.width / 2
                        val gap = 14.dp.toPx()
                        if (!first) drawLine(graphColor, Offset(x, 0f),
                            Offset(x, center.y - gap), strokeWidth = 2.dp.toPx())
                        if (!last) drawLine(graphColor, Offset(x, center.y + gap),
                            Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(status) } }, state = rememberTooltipState()) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(when (command.status) {
                                HistoryCommandStatus.Running -> R.drawable.ic_material_symbol_play_arrow_24
                                HistoryCommandStatus.Completed -> R.drawable.ic_material_symbol_check_circle_24
                                HistoryCommandStatus.Failed -> R.drawable.ic_material_symbol_block_24
                            }), null, Modifier.size(24.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(commandText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontFamily = LocalTerminalMonospaceFontFamily.current, style = MaterialTheme.typography.bodySmall)
                    command.exitCode?.let { code ->
                        Text(stringResource(R.string.history_exit_code, code),
                            fontFamily = LocalTerminalMonospaceFontFamily.current, style = MaterialTheme.typography.labelSmall)
                    }
                }
                HistoryCopyButton(stringResource(R.string.history_copy_command),
                    enabled = enabled && inputPreview != null, foreground = foreground,
                    onCopy = { command.marker.commandInputText })
                IconButton(onClick = onToggleOutput, enabled = enabled,
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = foreground,
                        disabledContentColor = foreground),
                    modifier = Modifier.size(48.dp)) {
                    Box(Modifier.semantics {
                        contentDescription = expansionLabel
                        stateDescription = expansionState
                    }) {
                        SettingsExpansionIcon(outputExpanded)
                    }
                }
            }
        }
        SettingsExpansionContent(outputExpanded) {
            val output = remember(command.marker, historyRevision) { command.marker.outputText }
            HistoryCommandOutput(output, enabled, graphColor, last)
        }
    }
}

@Composable
private fun HistoryCopyButton(label: String, enabled: Boolean, foreground: Color, onCopy: () -> String?) {
    val context = LocalContext.current
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = {
            val value = onCopy()
            if (!value.isNullOrEmpty()) context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, value))
        }, enabled = enabled, shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = foreground,
                disabledContentColor = foreground.copy(alpha = 0.38f)), modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_material_symbol_content_copy_24), label, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HistoryCommandOutput(output: String?, enabled: Boolean, graphColor: Color, last: Boolean) {
    val colors = LocalToolbarColors.current
    Column(Modifier.fillMaxWidth().drawBehind {
        if (!last) {
            val x = 24.dp.toPx()
            drawLine(graphColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }.padding(start = 60.dp, end = 12.dp, bottom = 8.dp)) {
        Surface(color = colors.container, contentColor = colors.onContainer.copy(alpha = if (enabled) 1f else 0.38f),
            shape = MaterialTheme.shapes.medium) {
            SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(output?.ifEmpty { stringResource(R.string.history_output_empty) }
                    ?: stringResource(R.string.history_output_unavailable),
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = LocalTerminalMonospaceFontFamily.current, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
