@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.terminal.app.*
import dev.terminal.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal val LocalToolbarBackground = staticCompositionLocalOf { Color.Black }
internal val ToolbarHotkeyViewportHeight = (HOTKEY_HEIGHT_DP * 2 + 4 + 8).dp

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
    val normal = (hidden + ToolbarHotkeyViewportHeight).coerceAtMost(expanded)
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
                    ToolbarPageHandle(pager, tools.size,
                        visible = tools.size > 1 && pager.isScrollInProgress && height > hidden,
                        colors = colors)
                }
                if (height > hidden) {
                    if (config.tools.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.toolbar_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        CompositionLocalProvider(LocalToolbarBackground provides colors.background) {
                            ToolbarPages(config.tools, pager, state.settledValue != ToolbarSheetMode.Hidden,
                                content = content)
                        }
                    }
                }
            }
        }
    }
}

/** Morph in the existing handle slot so page feedback never resizes or covers content. */
@Composable
private fun ToolbarPageHandle(pager: PagerState, count: Int, visible: Boolean, colors: TerminalChromeColors) {
    val spread by animateFloatAsState(if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarDotSpread")
    val opacity by animateFloatAsState(if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "toolbarDotOpacity")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pageLabel = stringResource(R.string.toolbar_page_position, pager.currentPage + 1, count)
    Canvas(Modifier.size(width = maxOf(32, (count - 1) * 12 + 6).dp, height = 16.dp)
        .semantics { if (visible) contentDescription = pageLabel }) {
        val progress = spread.coerceIn(0f, 1.15f)
        val alpha = opacity.coerceIn(0f, 1f)
        val handleWidth = (32f - 20f * progress.coerceAtMost(1f)).dp.toPx()
        drawRoundRect(colors.divider.copy(alpha = 1f - alpha),
            topLeft = Offset(center.x - handleWidth / 2, center.y - 2.dp.toPx()),
            size = Size(handleWidth, 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
        if (count < 2 || alpha <= 0f) return@Canvas
        val spacing = 12.dp.toPx() * progress
        val radius = (2f + progress.coerceAtMost(1f)).dp.toPx()
        fun position(index: Float): Float = center.x +
            (index - (count - 1) / 2f) * spacing * if (rtl) -1f else 1f
        repeat(count) { index ->
            drawCircle(colors.foreground.copy(alpha = 0.4f * alpha), radius,
                center = Offset(position(index.toFloat()), center.y))
        }
        // Follow the finger directly; different leading/trailing curves stretch the selected dot.
        val page = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (count - 1).toFloat())
        val start = kotlin.math.floor(page)
        val fraction = page - start
        val tail = position(start + fraction * fraction)
        val head = position(start + 1f - (1f - fraction) * (1f - fraction))
        drawRoundRect(colors.accent.copy(alpha = alpha),
            topLeft = Offset(minOf(tail, head) - radius, center.y - radius),
            size = Size(kotlin.math.abs(head - tail) + radius * 2, radius * 2),
            cornerRadius = CornerRadius(radius))
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

/** Only fade edges with more content beyond them; overlays never intercept gestures. */
@Composable
internal fun ToolbarScrollEdges(backward: Boolean, forward: Boolean, modifier: Modifier = Modifier,
    background: Color = LocalToolbarBackground.current, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds()) {
        content()
        if (backward) Spacer(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(12.dp)
            .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)))))
        if (forward) Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp)
            .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))))
    }
}

@Composable
internal fun ToolbarHotkeys(layout: HotkeyLayout, control: Boolean, alt: Boolean,
    colors: TerminalChromeColors, enabled: Boolean, onKey: (TerminalToolbarKey) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward,
            Modifier.fillMaxWidth().heightIn(max = ToolbarHotkeyViewportHeight), colors.background) {
            HotkeyGrid(layout = layout, control = control, alt = alt, terminalColors = colors,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(4.dp)) { index ->
                onKey(when (val shortcut = layout.keys[index].shortcut) {
                    "CTRL" -> TerminalToolbarKey.Control
                    "ALT" -> TerminalToolbarKey.Alt
                    else -> TerminalToolbarKey.Shortcut(shortcut)
                })
            }
        }
    }
}
