@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.terminal.app.*
import dev.terminal.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Persistent, non-modal bottom sheet with a handle-only hidden anchor. No runtime ownership. */
@Composable
internal fun TerminalToolbar(
    config: ToolbarConfig,
    mode: ToolbarSheetMode,
    onModeChange: (ToolbarSheetMode) -> Unit,
    maxHeight: Dp,
    colors: TerminalChromeColors,
    // Pager content is registered by stable tool ID; new tools need no sheet/gesture changes.
    content: @Composable (String, Boolean) -> Unit,
) {
    var selectedTool by rememberSaveable { mutableStateOf(config.tools.firstOrNull()) }
    if (!config.enabled) {
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        return
    }
    val tools = config.tools
    val pager = rememberPagerState(initialPage = tools.indexOf(selectedTool).coerceAtLeast(0)) { tools.size }
    LaunchedEffect(tools) {
        if (tools.isNotEmpty()) pager.scrollToPage(tools.indexOf(selectedTool).coerceAtLeast(0))
    }
    LaunchedEffect(pager, tools) {
        snapshotFlow { pager.settledPage }.collect { index -> tools.getOrNull(index)?.let { selectedTool = it } }
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val hidden = 24.dp
    val expanded = maxHeight.coerceAtLeast(96.dp)
    val normal = 168.dp.coerceAtMost((hidden + expanded) / 2)
    val anchors = remember(density, expanded, normal) {
        with(density) { DraggableAnchors {
            ToolbarSheetMode.Hidden at -hidden.toPx()
            ToolbarSheetMode.Default at -normal.toPx()
            ToolbarSheetMode.Expanded at -expanded.toPx()
        } }
    }
    val state = remember { AnchoredDraggableState(mode, anchors) }
    val onChange by rememberUpdatedState(onModeChange)
    SideEffect { state.updateAnchors(anchors, state.targetValue) }
    LaunchedEffect(mode) { if (state.targetValue != mode) state.animateTo(mode, motion) }
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.distinctUntilChanged().collect { onChange(it) }
    }
    val height = with(density) { (-state.requireOffset()).toDp().coerceIn(hidden, expanded) }
    val modeLabel = stringResource(when (state.settledValue) {
        ToolbarSheetMode.Hidden -> R.string.toolbar_hidden
        ToolbarSheetMode.Default -> R.string.toolbar_default
        ToolbarSheetMode.Expanded -> R.string.toolbar_expanded
    })
    val handleLabel = stringResource(R.string.toolbar_handle)
    val expandLabel = stringResource(R.string.toolbar_expand)
    val defaultLabel = stringResource(R.string.toolbar_default)
    val hideLabel = stringResource(R.string.toolbar_hide)
    fun settle(target: ToolbarSheetMode) { scope.launch { state.animateTo(target, motion) } }
    Column(Modifier.fillMaxWidth().background(colors.background).navigationBarsPadding()) {
        Surface(color = colors.background, contentColor = colors.foreground,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxWidth().height(height).clipToBounds()) {
            Column {
                Box(Modifier.fillMaxWidth().height(hidden)
                    .anchoredDraggable(state, Orientation.Vertical,
                        flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = motion))
                    .clickable {
                        settle(when (state.settledValue) {
                            ToolbarSheetMode.Hidden -> ToolbarSheetMode.Default
                            ToolbarSheetMode.Default -> ToolbarSheetMode.Expanded
                            ToolbarSheetMode.Expanded -> ToolbarSheetMode.Hidden
                        })
                    }.semantics {
                        contentDescription = handleLabel
                        stateDescription = modeLabel
                        customActions = listOf(
                            CustomAccessibilityAction(expandLabel) { settle(ToolbarSheetMode.Expanded); true },
                            CustomAccessibilityAction(defaultLabel) { settle(ToolbarSheetMode.Default); true },
                            CustomAccessibilityAction(hideLabel) { settle(ToolbarSheetMode.Hidden); true },
                        )
                    }, contentAlignment = Alignment.Center) {
                    val showPages = tools.size > 1 && pager.isScrollInProgress && height > hidden
                    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    androidx.compose.animation.AnimatedVisibility(visible = !showPages,
                        enter = fadeIn(effects), exit = fadeOut(effects)) {
                        Box(Modifier.size(32.dp, 4.dp).background(colors.divider, RoundedCornerShape(2.dp)))
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = showPages,
                        enter = fadeIn(effects), exit = fadeOut(effects)) {
                        val pageLabel = stringResource(R.string.toolbar_page_position, pager.currentPage + 1, tools.size)
                        Row(Modifier.background(colors.container, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .semantics { contentDescription = pageLabel },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            tools.forEachIndexed { index, _ ->
                                Box(Modifier.size(6.dp).background(
                                    if (index == pager.currentPage) colors.accent else colors.onContainer.copy(alpha = 0.4f),
                                    CircleShape))
                            }
                        }
                    }
                }
                if (height > hidden) {
                    if (config.tools.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.toolbar_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        ToolbarPages(config.tools, pager, state.settledValue != ToolbarSheetMode.Hidden,
                            content = content)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ToolbarPages(tools: List<String>, pager: PagerState, interactive: Boolean,
    content: @Composable (String, Boolean) -> Unit) {
    HorizontalPager(state = pager, userScrollEnabled = interactive,
        key = { tools[it] }, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
        content(tools[page], interactive && !pager.isScrollInProgress && page == pager.currentPage)
    }
}

@Composable
internal fun ToolbarHotkeys(layout: HotkeyLayout, control: Boolean, alt: Boolean,
    colors: TerminalChromeColors, enabled: Boolean, onKey: (TerminalToolbarKey) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HotkeyGrid(layout = layout, control = control, alt = alt, terminalColors = colors,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(max = (HOTKEY_HEIGHT_DP * 2 + 4 + 8).dp)
                .verticalScroll(rememberScrollState()).padding(4.dp)) { index ->
            onKey(when (val shortcut = layout.keys[index].shortcut) {
                "CTRL" -> TerminalToolbarKey.Control
                "ALT" -> TerminalToolbarKey.Alt
                else -> TerminalToolbarKey.Shortcut(shortcut)
            })
        }
    }
}
