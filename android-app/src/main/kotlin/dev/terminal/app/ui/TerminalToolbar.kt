@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.PagerDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import dev.terminal.app.*
import dev.terminal.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalToolbarColors = staticCompositionLocalOf { TerminalChromeColors(Color.Black) }
/** Share the sheet's animated geometry so tools also follow an interrupted animation or drag. */
internal val LocalToolbarExpansionProgress = compositionLocalOf<State<Float>> { mutableFloatStateOf(1f) }
internal val LocalToolbarExpandedContentHeight = compositionLocalOf<Dp?> { null }
private val LocalToolbarKeyInteractions = staticCompositionLocalOf<MutableInteractionSource?> { null }
private val LocalToolbarKeyGestureLocked = compositionLocalOf { false }
internal val ToolbarHotkeyViewportHeight = (HOTKEY_HEIGHT_DP * 2 + 4 + 8).dp

/** Persistent, non-modal bottom sheet with a handle-only hidden anchor. No runtime ownership. */
@Composable
internal fun TerminalToolbar(
    config: ToolbarConfig,
    mode: ToolbarSheetMode,
    onModeChange: (ToolbarSheetMode) -> Unit,
    maxHeight: Dp,
    colors: TerminalChromeColors,
    pageNavigationEnabled: Boolean = true,
    animateModeChanges: Boolean = true,
    cornerColors: TerminalSheetCorners = TerminalSheetCorners(colors.background, colors.background),
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
    LaunchedEffect(mode, animateModeChanges) {
        if (!animateModeChanges) state.snapTo(mode)
        else if (state.targetValue != mode) state.animateTo(mode, motion)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.distinctUntilChanged().collect { onChange(it) }
    }
    val hiddenPx = with(density) { hidden.toPx() }
    val normalPx = with(density) { normal.toPx() }
    val expandedPx = with(density) { expanded.toPx() }
    val contentVisible by remember(state, hiddenPx) { derivedStateOf { -state.requireOffset() > hiddenPx } }
    val expansionProgress = remember(state, normalPx, expandedPx) { derivedStateOf {
        // Tools read this state themselves; changing height must not recompose the whole pager.
        if (expandedPx > normalPx) ((-state.requireOffset() - normalPx) / (expandedPx - normalPx)).coerceIn(0f, 1f)
        else 0f
    } }
    val sheetHeight = Modifier.layout { measurable, constraints ->
        // Read animated pixels during measurement, keeping composition out of each height frame.
        val height = constraints.constrainHeight((-state.requireOffset()).coerceIn(hiddenPx, expandedPx).roundToInt())
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
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
    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    MaterialTheme(colorScheme = colors.colorScheme ?: MaterialTheme.colorScheme) {
        Column(Modifier.fillMaxWidth().drawBehind {
            // Draw before clipping so each cutout continues the canvas directly above it.
            drawRect(cornerColors.left)
            drawRect(cornerColors.right, topLeft = Offset(size.width / 2f, 0f),
                size = Size(size.width / 2f, size.height))
        }.clip(sheetShape).background(colors.sheetBackground).navigationBarsPadding()) {
            Surface(color = colors.sheetBackground, contentColor = colors.foreground,
                shape = sheetShape,
                modifier = Modifier.fillMaxWidth().then(sheetHeight).clipToBounds()) {
                Column {
                    Box(Modifier.fillMaxWidth().height(hidden)
                        .anchoredDraggable(state, Orientation.Vertical,
                            flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = motion))
                        .clickable(interactionSource = remember { HandleInteractionSource() },
                            indication = ripple(enableHoverIndication = false)) {
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
                            visible = tools.size > 1 && pager.isScrollInProgress && contentVisible,
                            colors = colors)
                    }
                    if (contentVisible) {
                        if (config.tools.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.toolbar_empty), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            CompositionLocalProvider(
                                LocalToolbarColors provides colors,
                                LocalToolbarExpansionProgress provides expansionProgress,
                                LocalToolbarExpandedContentHeight provides
                                    if (mode == ToolbarSheetMode.Expanded) (expanded - hidden) else null,
                            ) {
                                ToolbarPages(config.tools, pager, state.settledValue != ToolbarSheetMode.Hidden,
                                    pageNavigationEnabled, content = content)
                            }
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
    pageNavigationEnabled: Boolean,
    content: @Composable (String, Boolean) -> Unit) {
    val fling = PagerDefaults.flingBehavior(pager)
    val currentFling by rememberUpdatedState(fling)
    val defaultConnection = PagerDefaults.pageNestedScrollConnection(pager, Orientation.Horizontal)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val keyInteractions = remember { MutableInteractionSource() }
    val keyPressed by keyInteractions.collectIsPressedAsState()
    var pointersDown by remember { mutableStateOf(false) }
    var keyGestureLocked by remember { mutableStateOf(false) }
    LaunchedEffect(keyInteractions) {
        keyInteractions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press && pointersDown) keyGestureLocked = true
        }
    }
    val keyGestureObserver = Modifier.pointerInput(keyInteractions) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (keyGestureLocked) {
                        event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                    }
                    pointersDown = event.changes.any { it.pressed }
                    // Keep both scroll axes locked until every finger lifts, including
                    // after a key is disabled or replaced during this gesture.
                    if (!pointersDown) keyGestureLocked = false
                }
            }
        } finally {
            pointersDown = false
            keyGestureLocked = false
        }
    }
    val navigationEnabled by rememberUpdatedState(
        interactive && pageNavigationEnabled && !keyPressed && !keyGestureLocked)
    val pageConnection = remember(pager, defaultConnection, direction) {
        object : NestedScrollConnection by defaultConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (navigationEnabled) defaultConnection.onPreScroll(available, source) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (navigationEnabled) defaultConnection.onPostScroll(consumed, available, source) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!navigationEnabled || pager.currentPageOffsetFraction == 0f) return Velocity.Zero
                // A tab-list edge drag has moved the pager. Snap with the original release
                // velocity before the inner list and the default connection consume it.
                pager.scroll { with(currentFling) { performFling(available.x * direction) } }
                return Velocity(available.x, 0f)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (navigationEnabled) defaultConnection.onPostFling(consumed, available) else Velocity.Zero
        }
    }
    CompositionLocalProvider(LocalToolbarKeyInteractions provides keyInteractions,
        LocalToolbarKeyGestureLocked provides keyGestureLocked) {
        HorizontalPager(state = pager, userScrollEnabled = navigationEnabled,
            flingBehavior = fling, pageNestedScrollConnection = pageConnection,
            key = { tools[it] }, modifier = Modifier.weight(1f).fillMaxWidth().then(keyGestureObserver)) { page ->
            content(tools[page], interactive && !pager.isScrollInProgress && page == pager.currentPage)
        }
    }
}

/** Only fade edges with more content beyond them; overlays never intercept gestures. */
@Composable
internal fun ToolbarScrollEdges(backward: Boolean, forward: Boolean, modifier: Modifier = Modifier,
    background: Color = LocalToolbarColors.current.sheetBackground, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds()) {
        content()
        ScrollEdgeFade(backward, Brush.verticalGradient(listOf(background, background.copy(alpha = 0f))),
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(12.dp))
        ScrollEdgeFade(forward, Brush.verticalGradient(listOf(background.copy(alpha = 0f), background)),
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp))
    }
}

@Composable
internal fun ToolbarHotkeys(layout: HotkeyLayout, control: Boolean, alt: Boolean, shift: Boolean,
    colors: TerminalChromeColors, enabled: Boolean, onKey: (TerminalToolbarKey) -> Unit) {
    val scroll = rememberScrollState()
    val keyInteractions = LocalToolbarKeyInteractions.current ?: remember { MutableInteractionSource() }
    val keyPressed by keyInteractions.collectIsPressedAsState()
    val keyGestureLocked by rememberUpdatedState(LocalToolbarKeyGestureLocked.current)
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val density = LocalDensity.current.density
    val snapMotion = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(scroll, layout, density, snapMotion) {
        snapshotFlow { scroll.isScrollInProgress || dragged || keyPressed || keyGestureLocked }.distinctUntilChanged().collect { busy ->
            if (!busy) {
                val target = hotkeyScrollSnapOffset(layout, scroll.value, scroll.maxValue, scroll.viewportSize, density)
                if (target != scroll.value) launch { scroll.animateScrollTo(target, snapMotion) }
            }
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        HotkeyGrid(layout = layout, control = control, alt = alt, shift = shift, terminalColors = colors,
            enabled = enabled, pressInteractionSource = keyInteractions,
            modifier = Modifier.fillMaxSize().verticalScroll(scroll,
                enabled = enabled && !keyPressed && !keyGestureLocked).padding(4.dp)) { index ->
            onKey(when (val shortcut = layout.keys[index].shortcut) {
                "CTRL" -> TerminalToolbarKey.Control
                "ALT" -> TerminalToolbarKey.Alt
                "SHIFT" -> TerminalToolbarKey.Shift
                else -> TerminalToolbarKey.Shortcut(shortcut)
            })
        }
    }
}
