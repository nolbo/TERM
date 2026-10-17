package dev.terminal.app

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import dev.terminal.app.ui.TerminalPaneState
import dev.terminal.app.ui.SplitDirection
import dev.terminal.view.TerminalView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns stable renderer slots; the Material pane scaffold owns only their presentation. */
internal class TerminalPaneLayout(
    context: Context,
    private val createTerminal: (Int) -> TerminalView,
    private val bindTerminal: (Int, TerminalView, String) -> Unit,
    private val releaseTerminal: (TerminalView) -> Unit,
    private val focusPane: (Int) -> Unit,
    private val changeFraction: (Float) -> Unit,
    parentCompositionContext: CompositionContext? = null,
    private val onDirection: (SplitDirection) -> Unit = {},
    private val onUnsplit: () -> Unit = {},
    private val onCollapseThreshold: () -> Unit = {},
    private val onCollapse: (survivingPane: Int) -> Unit = {},
) : ViewGroup(context) {
    private data class Pane(val container: FrameLayout, val terminal: TerminalView,
        val dimmer: ColorDrawable, var preview: Float = 0f)
    private data class Appearance(val padding: Int = 0, val inset: Int = 0, val dividerColor: Int = 0,
        val gutterColor: Int? = null)
    private var panes by mutableStateOf<Map<Int, Pane>>(emptyMap())
    private var state by mutableStateOf(TerminalPaneState())
    private var appearance by mutableStateOf(Appearance())
    private var dragging by mutableStateOf(false)
    private var handleMenuExpanded by mutableStateOf(false)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var resizing = false
    private var handleTap = false
    private val collapseThresholdLatched = BooleanArray(2)
    private var collapsePreviewPane by mutableStateOf<Int?>(null)
    private var dividerDragPosition by mutableStateOf<Float?>(null)
    private var measuredLayout: TerminalPaneState? = null
    private var dragOffset = 0f
    private var dividerPosition = 0
    private var dividerSize = dp(12)
    private var released = false
    private val sideBySide get() = state.direction == SplitDirection.SideBySide
    private val bindPlacedPanes = Runnable { panes.keys.forEach(::bindIfReady) }
    private val scaffold = ComposeView(context).apply {
        setParentCompositionContext(parentCompositionContext)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val renderedLayout = state
            TerminalPaneScaffold(renderedLayout, Color(appearance.dividerColor), dragging,
                onGeometry = { position, size ->
                    dividerPosition = position
                    dividerSize = size
                    measuredLayout = renderedLayout
                    // A new session can reuse unchanged bounds, so native onLayout may be skipped.
                    removeCallbacks(bindPlacedPanes)
                    post(bindPlacedPanes)
                },
                onFractionChange = ::setFraction,
                pane = { index, modifier, preview ->
                    panes[index]?.let { pane -> key(pane.container) {
                        AndroidView(factory = { pane.container }, modifier = modifier,
                            update = { applyCollapsePreview(pane, preview) })
                    } }
                }, gutterColor = appearance.gutterColor?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerLow,
                handleMenuExpanded = handleMenuExpanded,
                onHandleClick = { if (state.split) handleMenuExpanded = true },
                onHandleMenuDismiss = { handleMenuExpanded = false },
                onDirection = onDirection, onUnsplit = onUnsplit,
                collapsePreviewPane = collapsePreviewPane,
                dragPosition = dividerDragPosition)
        }
    }

    init {
        clipChildren = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(scaffold)
    }

    fun terminal(index: Int): TerminalView? = panes[index]?.terminal

    fun setPaneBackground(view: TerminalView, color: Int) {
        panes.values.firstOrNull { it.terminal === view }?.container?.setBackgroundColor(color)
    }

    fun update(layout: TerminalPaneState, padding: Int, inset: Int, dividerColor: Int, gutterColor: Int? = null) {
        if (released) return
        val needsLayout = state != layout || appearance.padding != padding || appearance.inset != inset
        val next = panes.toMutableMap()
        layout.sessionIds.indices.forEach { index ->
            if (index !in next) {
                val terminal = createTerminal(index)
                val dimmer = ColorDrawable(android.graphics.Color.BLACK).apply { alpha = 0 }
                val container = object : FrameLayout(context) {
                    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                        super.onLayout(changed, left, top, right, bottom)
                        if (panes[index]?.container === this) bindIfReady(index)
                    }
                }.apply { clipChildren = true; foreground = dimmer; addView(terminal) }
                next[index] = Pane(container, terminal, dimmer)
            }
        }
        next.keys.filter { it >= layout.sessionIds.size }.forEach { index ->
            next.remove(index)?.let { applyCollapsePreview(it, 0f); releaseTerminal(it.terminal) }
        }
        if (state.sessionIds != layout.sessionIds || state.direction != layout.direction || !layout.split) {
            handleMenuExpanded = false
            endDrag()
            next.values.forEach { applyCollapsePreview(it, 0f) }
        }
        state = layout
        appearance = Appearance(padding, inset, dividerColor, gutterColor)
        panes = next
        next.forEach { (index, pane) ->
            pane.container.setPadding(padding, padding, padding, padding)
            pane.terminal.setScrollbackTopInset(if (index == 0 || sideBySide) inset else 0)
        }
        if (!layout.split) dragging = false
        if (needsLayout) requestLayout()
    }

    private fun bindIfReady(index: Int) {
        if (measuredLayout != state) return
        val pane = panes[index] ?: return
        if (pane.container.width <= 0 || pane.container.height <= 0) return
        // The scaffold has placed the native slot at its final positive geometry.
        state.sessionIds.getOrNull(index)?.let { bindTerminal(index, pane.terminal, it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        scaffold.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        scaffold.layout(0, 0, width, height)
        // A runtime mirror can become ready without changing any pane bounds.
        panes.keys.forEach(::bindIfReady)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val coordinate = if (sideBySide) event.x else event.y
            dragging = state.split && abs(coordinate - dividerPosition - dividerSize / 2f) <= dp(24)
            if (dragging) {
                downX = event.x
                downY = event.y
                resizing = false
                collapseThresholdLatched.fill(false)
                val otherCoordinate = if (sideBySide) event.y else event.x
                val otherCenter = (if (sideBySide) height else width) / 2f
                handleTap = abs(otherCoordinate - otherCenter) <= dp(24)
                handleMenuExpanded = false
                dragOffset = coordinate - dividerPosition
                parent?.requestDisallowInterceptTouchEvent(true)
            } else {
                val index = if (state.split && coordinate > dividerPosition) 1 else 0
                focusPane(index)
                panes[index]?.terminal?.requestFocus()
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = abs(event.x - downX)
            val dy = abs(event.y - downY)
            if (dx > touchSlop || dy > touchSlop) handleTap = false
            if ((if (sideBySide) dx else dy) > touchSlop) resizing = true
            if (resizing) dragPosition(event)?.let { position ->
                dividerDragPosition = position
                updateCollapseTarget(position)
                val total = (if (sideBySide) width else height) - dividerSize
                if (total > 0) setFraction((position - dividerSize / 2f) / total)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val collapsedPane = if (event.actionMasked == MotionEvent.ACTION_UP && resizing)
                dragPosition(event)?.let { updateCollapseTarget(it); collapsePaneAt(it) } else null
            val click = event.actionMasked == MotionEvent.ACTION_UP && handleTap && !resizing &&
                abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop
            endDrag()
            if (collapsedPane != null) onCollapse(1 - collapsedPane) else if (click) handleMenuExpanded = true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) handleTap = false
        return true
    }

    private fun dragPosition(event: MotionEvent): Float? =
        ((if (sideBySide) event.x else event.y) - dragOffset + dividerSize / 2f).takeIf { it.isFinite() }

    private fun collapsePaneAt(position: Float): Int? {
        val extent = if (sideBySide) width else height
        val edge = dp(COLLAPSE_DISTANCE_DP)
        // Overlapping end zones leave no safe resize area in an extremely short IME viewport.
        if (extent <= edge * 2) return null
        return when {
            position <= edge -> 0
            position >= extent - edge -> 1
            else -> null
        }
    }

    private fun updateCollapseTarget(position: Float) {
        val extent = if (sideBySide) width else height
        val rearm = dp(COLLAPSE_DISTANCE_DP + COLLAPSE_REARM_DISTANCE_DP)
        if (position >= rearm) collapseThresholdLatched[0] = false
        if (position <= extent - rearm) collapseThresholdLatched[1] = false
        val target = collapsePaneAt(position)
        collapsePreviewPane = target
        if (target != null && !collapseThresholdLatched[target]) {
            collapseThresholdLatched[target] = true
            onCollapseThreshold()
        }
    }

    private fun endDrag() {
        dragging = false
        handleTap = false
        resizing = false
        collapseThresholdLatched.fill(false)
        collapsePreviewPane = null
        dividerDragPosition = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun applyCollapsePreview(pane: Pane, value: Float) {
        val progress = value.coerceIn(0f, 1f)
        if (pane.preview == progress) return
        pane.preview = progress
        pane.dimmer.alpha = (progress * 0.5f * 255).roundToInt()
        val radius = dp(12) * progress
        pane.terminal.setRenderEffect(if (radius > 0f)
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
    }

    private fun setFraction(value: Float) {
        if (!value.isFinite() || released) return
        val fraction = value.coerceIn(TerminalPaneState.MIN_FRACTION, TerminalPaneState.MAX_FRACTION)
        if (fraction == state.fraction) return
        state = state.copy(fraction = fraction)
        requestLayout()
        changeFraction(fraction)
    }

    fun release() {
        if (released) return
        released = true
        handleMenuExpanded = false
        endDrag()
        removeCallbacks(bindPlacedPanes)
        scaffold.disposeComposition()
        panes.values.forEach { applyCollapsePreview(it, 0f); releaseTerminal(it.terminal) }
        panes = emptyMap()
        removeAllViews()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val COLLAPSE_DISTANCE_DP = 48
        const val COLLAPSE_REARM_DISTANCE_DP = 16
    }
}
