@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class,
    androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveComponentOverrideApi::class,
)

package dev.terminal.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.collection.intListOf
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.VerticalDragHandleDefaults
import androidx.compose.material3.adaptive.layout.LocalThreePaneScaffoldOverride
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldOverride
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldOverrideScope
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SplitDirection
import dev.terminal.app.ui.SplitPaneActions
import dev.terminal.app.ui.TerminalPaneState
import dev.terminal.app.ui.splitPaneExtent
import kotlinx.coroutines.awaitCancellation
import kotlin.math.abs
import kotlin.math.roundToInt

/** Material pane presentation over the existing, independently owned terminal slots. */
@Composable
internal fun TerminalPaneScaffold(
    layout: TerminalPaneState,
    dividerColor: Color,
    dragging: Boolean,
    onGeometry: (Int, Int) -> Unit,
    onFractionChange: (Float) -> Unit,
    pane: @Composable (Int, Modifier, Float) -> Unit,
    gutterColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    handleMenuExpanded: Boolean = false,
    onHandleClick: () -> Unit = {},
    onHandleMenuDismiss: () -> Unit = {},
    onDirection: (SplitDirection) -> Unit = {},
    onUnsplit: () -> Unit = {},
    collapsePreviewPane: Int? = null,
    dragPosition: Float? = null,
) {
    val currentLayout = rememberUpdatedState(layout)
    val currentGeometry = rememberUpdatedState(onGeometry)
    val currentDragPosition = rememberUpdatedState(dragPosition)
    val placement = remember { TerminalScaffoldOverride(currentLayout, currentGeometry, currentDragPosition) }
    val sideBySide = layout.direction == SplitDirection.SideBySide
    val directive = PaneScaffoldDirective(
        maxHorizontalPartitions = if (sideBySide && layout.split) 2 else 1,
        horizontalPartitionSpacerSize = 12.dp,
        maxVerticalPartitions = if (!sideBySide && layout.split) 2 else 1,
        verticalPartitionSpacerSize = 12.dp,
        defaultPanePreferredWidth = 360.dp,
        defaultPanePreferredHeight = 420.dp,
        excludedBounds = emptyList(),
        shouldAutoFocusCurrentDestination = false,
    )
    val value = ThreePaneScaffoldValue(
        primary = PaneAdaptedValue.Expanded,
        secondary = if (layout.split) PaneAdaptedValue.Expanded else PaneAdaptedValue.Hidden,
        tertiary = PaneAdaptedValue.Hidden,
    )
    val paneModifier = Modifier.fillMaxSize().clipToBounds()
    CompositionLocalProvider(LocalThreePaneScaffoldOverride provides placement) {
        SupportingPaneScaffold(
            directive = directive,
            value = value,
            modifier = Modifier.fillMaxSize().background(if (layout.split) gutterColor else Color.Transparent),
            mainPane = {
                val preview = collapsePreviewProgress(layout, 0, collapsePreviewPane)
                pane(0, paneModifier, preview)
            },
            supportingPane = {
                if (layout.split) {
                    val preview = collapsePreviewProgress(layout, 1, collapsePreviewPane)
                    pane(1, paneModifier, preview)
                }
            },
            paneExpansionDragHandle = {
                if (layout.split) TerminalPaneDragHandle(layout, dividerColor, dragging, onFractionChange,
                    handleMenuExpanded, onHandleClick, onHandleMenuDismiss, onDirection, onUnsplit)
            },
        )
    }
}

@Composable
private fun collapsePreviewProgress(layout: TerminalPaneState, index: Int, previewPane: Int?): Float =
    key(layout.sessionIds.getOrNull(index), layout.split, layout.direction) {
        // Reset presentation state for a replacement session without recreating its native slot.
        val progress by animateFloatAsState(
            targetValue = if (layout.split && index == previewPane) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "Terminal pane collapse preview",
        )
        progress.coerceIn(0f, 1f)
    }

/** Preserve the terminal's two axes and 20–80% ratio instead of adaptive reflow constraints. */
private class TerminalScaffoldOverride(
    private val currentLayout: State<TerminalPaneState>,
    private val onGeometry: State<(Int, Int) -> Unit>,
    private val currentDragPosition: State<Float?>,
) : ThreePaneScaffoldOverride {
    @Composable
    override fun ThreePaneScaffoldOverrideScope.ThreePaneScaffold() {
        val paneLayout = currentLayout.value
        var containerSize by remember { mutableStateOf(IntSize.Zero) }
        val sideBySide = paneLayout.direction == SplitDirection.SideBySide
        val total = if (sideBySide) containerSize.width else containerSize.height
        val divider = with(LocalDensity.current) { 12.dp.roundToPx() }
        val minimum = with(LocalDensity.current) { (if (sideBySide) 96.dp else 72.dp).roundToPx() }
        val safeFirst = splitPaneExtent(total, divider, paneLayout.fraction, minimum)
        val targetOverflow = if (paneLayout.split && total > divider) {
            currentDragPosition.value?.let { position ->
                val requestedFirst = (position - divider / 2f).coerceIn(0f, (total - divider).toFloat())
                (requestedFirst - safeFirst).takeIf { abs(it) > 1f } ?: 0f
            } ?: 0f
        } else 0f
        // Animate presentation beyond the safe renderer bounds, never the PTY's measured size.
        // Only the animation state is keyed: the native renderer slots keep their identities.
        val overflow = key(paneLayout.sessionIds, paneLayout.split, paneLayout.direction) {
            animateFloatAsState(targetOverflow, spring(dampingRatio = 0.78f, stiffness = 600f),
                label = "Terminal pane elastic boundary")
        }
        Layout(
            contents = listOf<@Composable () -> Unit>(
                primaryPane,
                secondaryPane,
                { paneExpansionDragHandle?.invoke(paneExpansionState) },
            ),
            modifier = modifier.onSizeChanged { containerSize = it },
        ) { contents, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val sideBySide = paneLayout.direction == SplitDirection.SideBySide
            val total = if (sideBySide) width else height
            val dividerSize = if (paneLayout.split) 12.dp.roundToPx() else 0
            val firstExtent = if (paneLayout.split) splitPaneExtent(total, dividerSize,
                paneLayout.fraction, (if (sideBySide) 96.dp else 72.dp).roundToPx()) else total
            val secondExtent = (total - dividerSize - firstExtent).coerceAtLeast(0)
            val first = contents[0].map { measurable ->
                measurable.measure(Constraints.fixed(
                    if (sideBySide) firstExtent else width,
                    if (sideBySide) height else firstExtent,
                ))
            }
            val second = contents[1].map { measurable ->
                measurable.measure(Constraints.fixed(
                    if (sideBySide) secondExtent else width,
                    if (sideBySide) height else secondExtent,
                ))
            }
            val handleSize = 48.dp.roundToPx()
            val handles = contents[2].map { it.measure(Constraints.fixed(handleSize, handleSize)) }
            layout(width, height) {
                val available = (total - dividerSize).coerceAtLeast(0)
                val visualFirst = if (paneLayout.split)
                    (firstExtent + overflow.value).coerceIn(0f, available.toFloat()).roundToInt() else firstExtent
                val visualSecond = (available - visualFirst).coerceAtLeast(0)
                // Lookahead geometry must never change native hit testing or PTY dimensions.
                if (!isLookingAhead) onGeometry.value(visualFirst, dividerSize)
                first.forEach {
                    it.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        val scale = if (firstExtent > 0) visualFirst.toFloat() / firstExtent else 1f
                        scaleX = if (sideBySide) scale else 1f
                        scaleY = if (sideBySide) 1f else scale
                    }
                }
                second.forEach {
                    it.placeWithLayer(if (sideBySide) firstExtent + dividerSize else 0,
                        if (sideBySide) 0 else firstExtent + dividerSize) {
                        transformOrigin = TransformOrigin(1f, 1f)
                        val scale = if (secondExtent > 0) visualSecond.toFloat() / secondExtent else 1f
                        scaleX = if (sideBySide) scale else 1f
                        scaleY = if (sideBySide) 1f else scale
                    }
                }
                handles.forEach {
                    it.place(
                        if (sideBySide) visualFirst + dividerSize / 2 - handleSize / 2 else (width - handleSize) / 2,
                        if (sideBySide) (height - handleSize) / 2 else visualFirst + dividerSize / 2 - handleSize / 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalPaneDragHandle(layout: TerminalPaneState, dividerColor: Color, dragging: Boolean,
    onFractionChange: (Float) -> Unit, menuExpanded: Boolean, onHandleClick: () -> Unit,
    onDismiss: () -> Unit, onDirection: (SplitDirection) -> Unit, onUnsplit: () -> Unit) {
    val defaultSizes = VerticalDragHandleDefaults.sizes()
    val targetSize = if (dragging) defaultSizes.draggedSize else defaultSizes.size
    val width by animateDpAsState(targetSize.width, MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "Pane drag handle width")
    val height by animateDpAsState(targetSize.height, MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "Pane drag handle height")
    val animatedSize = DpSize(width, height)
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(dragging) {
        if (dragging) {
            val start = DragInteraction.Start()
            interactions.emit(start)
            try {
                awaitCancellation()
            } finally {
                interactions.tryEmit(DragInteraction.Stop(start))
            }
        }
    }
    val resizeLabel = stringResource(R.string.terminal_split_resize)
    val menuLabel = stringResource(R.string.terminal_split_menu)
    val edgeMargin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val belowPosition = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Below)
    val centeredAnchor = remember(edgeMargin, belowPosition) {
        MenuAnchorPosition.Custom(
            xCandidates = {
                val end = (windowSize.width - menuSize.width - edgeMargin).coerceAtLeast(edgeMargin)
                intListOf((anchorBounds.center.x - menuSize.width / 2).coerceIn(edgeMargin, end))
            },
            yCandidates = {
                intListOf(belowPosition.calculatePosition(anchorBounds, windowSize, layoutDirection, menuSize).y)
            })
    }
    val menuPosition = MenuDefaults.rememberDropdownMenuPopupPositionProvider(centeredAnchor)
    Box(Modifier.size(48.dp)) {
        VerticalDragHandle(
            // The native host owns touch input. Keep every internal state on the same animated size
            // so Material's immediate pressed/dragged switch cannot bypass the transition.
            sizes = VerticalDragHandleDefaults.sizes(animatedSize, animatedSize, animatedSize),
            shapes = VerticalDragHandleDefaults.shapes(CircleShape, CircleShape, CircleShape),
            modifier = Modifier.size(48.dp)
                .rotate(if (layout.direction == SplitDirection.SideBySide) 0f else 90f)
                .focusProperties { canFocus = false }
                .semantics {
                    contentDescription = resizeLabel
                    onClick(label = menuLabel) { onHandleClick(); true }
                    progressBarRangeInfo = ProgressBarRangeInfo(layout.fraction,
                        TerminalPaneState.MIN_FRACTION..TerminalPaneState.MAX_FRACTION)
                    setProgress { fraction ->
                        if (fraction.isFinite()) {
                            onFractionChange(fraction.coerceIn(TerminalPaneState.MIN_FRACTION, TerminalPaneState.MAX_FRACTION))
                            true
                        } else false
                    }
                },
            colors = VerticalDragHandleDefaults.colors(color = dividerColor,
                pressedColor = dividerColor, draggedColor = dividerColor),
            interactionSource = interactions,
        )
        DropdownMenuPopup(expanded = menuExpanded, onDismissRequest = onDismiss, popupPositionProvider = menuPosition) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), modifier = Modifier.width(168.dp)) {
                SplitPaneActions(layout.direction,
                    onDirection = { onDismiss(); onDirection(it) },
                    onUnsplit = { onDismiss(); onUnsplit() })
            }
        }
    }
}
