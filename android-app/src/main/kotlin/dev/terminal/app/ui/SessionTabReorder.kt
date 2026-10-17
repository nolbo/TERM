package dev.terminal.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.terminal.app.R
import kotlin.math.roundToInt
import kotlin.math.abs

/** Fixed slots keep hit testing stable while neighbors animate and the list scrolls. */
@Composable
internal fun ReorderableSessionTabs(sessions: List<SessionUi>, activeSessionId: String?, active: Boolean,
    onReorder: (List<String>) -> Unit, modifier: Modifier,
    scroll: ScrollState = rememberScrollState(),
    paneState: TerminalPaneState = TerminalPaneState(),
    colors: TerminalChromeColors = LocalToolbarColors.current,
    canPair: (String, String) -> Boolean = { _, _ -> false },
    onPair: (String, String, Boolean) -> Unit = { _, _, _ -> },
    splitActions: @Composable (Modifier, Boolean, Boolean) -> Unit = { _, _, _ -> },
    content: @Composable (SessionUi, Modifier, Boolean, Boolean, Int?, Boolean) -> Unit) {
    val ids = sessions.map { it.id }
    val rows = sessionTabRows(ids, paneState.splitSessionIds)
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalTerminalHaptics.current
    val step = 52.dp
    val stepPx = with(density) { step.toPx() }
    val rowPx = with(density) { 48.dp.toPx() }
    val insetPx = with(density) { 4.dp.toPx() }
    val gapPx = with(density) { 2.dp.toPx() }
    val effects = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var dragging by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<List<String>>?>(null) }
    val hover = remember { SplitTabHover() }
    var splitPreview by remember { mutableStateOf<SplitTabPreview?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var grid by remember { mutableStateOf(Rect.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val update by rememberUpdatedState(onReorder)
    val pair by rememberUpdatedState(onPair)
    val canDrop by rememberUpdatedState(canPair)
    val currentActive by rememberUpdatedState(active)
    val currentIds by rememberUpdatedState(ids)
    val currentRows by rememberUpdatedState(rows)
    val order = preview ?: rows
    val visualRows = dragging?.let { source -> splitPreview?.let {
        splitTabPreviewRows(order, source, it.target, it.sourceFirst)
    } } ?: order
    fun showActions(row: List<String>): Boolean = row.size == 2 &&
        (activeSessionId in row || (dragging in row && splitPreview?.target in row))
    // Membership keys keep the same motion when a preview reverses or becomes the saved pair.
    val menuStates = mutableMapOf<List<String>, State<Float>>()
    visualRows.filter { it.size == 2 }.forEach { row ->
        val members = row.sorted()
        menuStates[members] = key(members) {
            animateFloatAsState(if (showActions(row)) 1f else 0f, effects, label = "Split tab menu width")
        }
    }
    val currentMenuStates by rememberUpdatedState(menuStates)
    fun menuReveal(row: List<String>): Float = currentMenuStates[row.sorted()]?.value?.coerceIn(0f, 1f) ?: 0f
    fun segmentWidth(menu: Float): Float =
        ((grid.width - insetPx * 2 - gapPx - (rowPx + gapPx) * menu) / 2).coerceAtLeast(0f)
    val upLabel = stringResource(R.string.toolbar_move_up)
    val downLabel = stringResource(R.string.toolbar_move_down)
    val splitLabel = stringResource(R.string.terminal_open_split)
    fun cancel() { dragging = null; preview = null; splitPreview = null; hover.clear() }
    fun tabAt(position: Offset, slots: List<List<String>>, starting: Boolean): String? {
        val row = slots.getOrNull((position.y / stepPx).toInt()) ?: return null
        if (position.y < 0 || position.y % stepPx > rowPx) return null
        val logicalX = if (rtl) grid.width - position.x else position.x
        if (logicalX < insetPx || logicalX > grid.width - insetPx) return null
        val tabWidth = segmentWidth(menuReveal(row))
        val id = if (row.size == 1) row.first() else {
            val x = logicalX - insetPx
            val column = (x / (tabWidth + gapPx)).toInt()
            if (x < 0 || column !in 0..1 || x - column * (tabWidth + gapPx) > tabWidth) return null
            row[column]
        }
        if (starting) {
            val bodyEnd = if (row.size == 1) grid.width - insetPx - rowPx * if (id == activeSessionId) 2 else 1
                else insetPx + row.indexOf(id) * (tabWidth + gapPx) + tabWidth - rowPx
            if (logicalX >= bodyEnd) return null
        }
        return id
    }
    fun updateDrag() {
        val id = dragging ?: return
        val items = preview ?: return
        val local = pointer - grid.topLeft
        val logicalX = if (rtl) grid.width - local.x else local.x
        val armed = splitPreview
        val rowIndex = armed?.row ?: (local.y / stepPx).toInt()
        val candidate = if (armed != null) armed.target else tabAt(local, items, starting = false)
        // Tabs only move vertically: grabbing their title must work just like grabbing
        // their horizontal center. Once entered, tolerate drift within the same body.
        val centered = splitTabHoverContains(local.y - rowIndex * stepPx, rowPx,
            candidate != null && candidate == hover.target)
        val insidePreview = armed != null && local.y in rowIndex * stepPx..rowIndex * stepPx + rowPx &&
            logicalX in insetPx..grid.width - insetPx
        val target = candidate?.takeIf { (centered || insidePreview) && viewport.contains(pointer) && canDrop(id, it) }
        val next = hover.update(target, rowIndex, local.y, logicalX / grid.width.coerceAtLeast(1f),
            SystemClock.uptimeMillis(), with(density) { 10.dp.toPx() })
        if (next != null && next != splitPreview) {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        }
        splitPreview = next
        // Hold only the hovered neighbor while the user decides. Crossing it continues live reordering.
        if (hover.target != null) return
        val index = items.indexOfFirst { id in it }
        val destination = liveSessionTabDestination(index, items.lastIndex, local.y, grabOffset, stepPx, rowPx)
        if (index >= 0 && index != destination) {
            preview = items.toMutableList().apply { add(destination, removeAt(index)) }
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        }
    }
    fun drop() {
        val slots = preview
        val source = dragging
        val intent = splitPreview
        val valid = currentActive && viewport.contains(pointer) && currentIds == ids
        val index = slots?.indexOfFirst { source in it } ?: -1
        val destination = slots?.let {
            sessionTabDropDestination(index, it.lastIndex, pointer.y - grid.top, stepPx, rowPx,
                grabOffset, hover.target != null)
        }
        val result = slots?.toMutableList()?.apply {
            if (index >= 0 && destination != null && index != destination) add(destination, removeAt(index))
        }?.flatten()
        val pairedOrder = if (slots != null && source != null && intent != null)
            splitTabPreviewRows(slots, source, intent.target, intent.sourceFirst).flatten() else null
        cancel()
        if (valid && source != null && intent != null && pairedOrder != null && canDrop(source, intent.target)) {
            if (pairedOrder != currentIds) update(pairedOrder)
            pair(source, intent.target, intent.sourceFirst)
        }
        else if (valid && result != null && result != currentIds) update(result)
    }
    // Runtime changes and paging away revoke a drag; title updates keep stable IDs.
    LaunchedEffect(ids, activeSessionId, paneState.sessionIds, paneState.splitSessionIds) { cancel() }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragging != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val edge = minOf(with(density) { 48.dp.toPx() }, viewport.height / 3)
            updateDrag()
            val direction = when {
                hover.target != null -> 0f
                !viewport.contains(pointer) -> 0f
                pointer.y < viewport.top + edge -> -1f
                pointer.y > viewport.bottom - edge -> 1f
                else -> 0f
            }
            if (direction != 0f) {
                scroll.scrollBy(direction * with(density) { 360.dp.toPx() } * seconds)
            }
        }
    }
    ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward, modifier) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInRoot() }
            .verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(Modifier.fillMaxWidth().height((step * rows.size - 4.dp).coerceAtLeast(0.dp))
                .onGloballyPositioned {
                    // Hit testing uses the full scrolled content, rather than its clipped visible bounds.
                    val origin = it.localToRoot(Offset.Zero)
                    grid = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                }
                .pointerInput(ids, activeSessionId, paneState.sessionIds, paneState.splitSessionIds, rtl) {
                    awaitEachGesture {
                        // Observe before the nested Tab consumes its press. Until the long press,
                        // leave events untouched so normal taps, buttons and scrolling still work.
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val id = tabAt(down.position, currentRows, starting = true) ?: return@awaitEachGesture
                        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var waiting = true
                            while (waiting) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                waiting = change?.pressed == true &&
                                    (change.position - down.position).getDistance() <= viewConfiguration.touchSlop &&
                                    event.changes.none { it.id != down.id && it.pressed }
                            }
                            true
                        }
                        // A transient enable/height update must not restart an existing pointer stream.
                        // Recheck eligibility at activation and drop instead of using it as a coroutine key.
                        if (endedEarly == true || !currentActive) return@awaitEachGesture
                        preview = currentRows
                        dragging = id
                        pointer = grid.topLeft + down.position
                        grabOffset = down.position.y % stepPx
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Compose sends ACTION_CANCEL as a consumed up event; it must not commit a drop.
                                if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                event.changes.forEach { it.consume() }
                                pointer = grid.topLeft + change.position
                                updateDrag()
                                if (!change.pressed) { drop(); break }
                            }
                        } finally { cancel() }
                    }
                }) {
                // Stable composition order preserves pointer ownership during previews.
                sessions.forEach { session -> key(session.id) {
                    val index = visualRows.indexOfFirst { session.id in it }.coerceAtLeast(0)
                    val group = visualRows.getOrNull(index).orEmpty()
                    val segmentPx = segmentWidth(if (showActions(group)) 1f else 0f)
                    val lifted = dragging == session.id && splitPreview == null
                    // Lifting a tab changes its drag position, not its membership or split geometry.
                    val segment = group.indexOf(session.id).takeIf { group.size == 2 }
                    val targetTop = if (lifted) (pointer.y - grid.top - grabOffset)
                        .coerceIn(0f, (grid.height - rowPx).coerceAtLeast(0f)) else index * stepPx
                    val top by animateFloatAsState(targetTop, if (lifted) snap() else effects, label = "Session tab position")
                    val left by animateFloatAsState(if (segment == null) 0f else insetPx + segment * (segmentPx + gapPx),
                        effects, label = "Session tab split position")
                    val width by animateFloatAsState(if (segment == null) grid.width else segmentPx,
                        effects, label = "Session tab split width")
                    val item = Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .width(with(density) { width.coerceAtLeast(0f).toDp() })
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            scaleX = if (lifted && segment == null) 1.02f else 1f
                            scaleY = if (lifted && segment == null) 1.02f else 1f
                            shadowElevation = if (lifted) 3.dp.toPx() else 0f
                            shape = RoundedCornerShape(20.dp)
                            // The tab owns clipping; scaling must not clip a second, wider capsule.
                            clip = false
                        }
                        .semantics {
                            traversalIndex = index + (segment ?: 0) / 3f
                            customActions = if (!active || dragging != null) emptyList() else buildList {
                                if (index > 0) add(CustomAccessibilityAction(upLabel) {
                                    update(rows.toMutableList().apply { add(index - 1, removeAt(index)) }.flatten()); true
                                })
                                if (index < rows.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                    update(rows.toMutableList().apply { add(index + 1, removeAt(index)) }.flatten()); true
                                })
                                activeSessionId?.takeIf { canDrop(session.id, it) }?.let { target ->
                                    add(CustomAccessibilityAction(splitLabel) {
                                        pair(session.id, target, paneState.split && paneState.first == session.id && target in paneState.sessionIds)
                                        true
                                    })
                                }
                            }
                        }
                    content(session, item, dragging != null, lifted, segment, group.size == 2 && !showActions(group))
                } }
                visualRows.forEachIndexed { index, row -> key(index) {
                    val actionsVisible = showActions(row)
                    val reveal = menuReveal(row)
                    val alpha by animateFloatAsState(if (actionsVisible) 1f else 0f,
                        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab actions visibility")
                    val top by animateFloatAsState(index * stepPx, effects, label = "Split tab actions position")
                    val inactiveGroup = dragging == null && row.size == 2 && row == paneState.splitSessionIds && activeSessionId !in row
                    val dividerAlpha by animateFloatAsState(
                        if (inactiveGroup) 1f else 0f,
                        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Inactive split tab dividers")
                    if (dividerAlpha > 0f) {
                        val left = insetPx + segmentWidth(reveal)
                        SplitTabDivider(Modifier.offset {
                            IntOffset(left.roundToInt(), top.roundToInt())
                        }, colors, dividerAlpha)
                    }
                    if (alpha > 0f && reveal > 0f) splitActions(Modifier.offset {
                        IntOffset((grid.width - insetPx - rowPx * reveal).roundToInt(), top.roundToInt())
                    }.width(48.dp * reveal).height(48.dp).clipToBounds().graphicsLayer { this.alpha = alpha }
                        .then(if (!actionsVisible) Modifier.clearAndSetSemantics { } else Modifier),
                        active && dragging == null && actionsVisible && reveal >= 0.99f, inactiveGroup)
                } }
            }
        }
    }
}

/** Fill an existing split gap without changing tab sizes or handling pointer input. */
@Composable
internal fun SplitTabDivider(modifier: Modifier, colors: TerminalChromeColors, alpha: Float) {
    Box(modifier.width(2.dp).height(48.dp).graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
        .background(colors.sheetBackground), contentAlignment = Alignment.Center) {
        VerticalDivider(Modifier.height(24.dp), thickness = 1.dp, color = colors.divider)
    }
}

/** A split is one reorderable row; session IDs and the persisted single list remain unchanged. */
internal fun sessionTabRows(ids: List<String>, pair: List<String>): List<List<String>> {
    val grouped = pair.takeIf { it.size == 2 && it.distinct().size == 2 && ids.containsAll(it) }.orEmpty()
    var added = false
    return buildList {
        ids.forEach { id ->
            if (id !in grouped) add(listOf(id))
            else if (!added) { add(grouped); added = true }
        }
    }
}

internal data class SplitTabPreview(val target: String, val row: Int, val sourceFirst: Boolean)

/** Enter at the row's center; a small exit margin keeps normal finger drift from clearing it. */
internal fun splitTabHoverContains(y: Float, height: Float, retaining: Boolean): Boolean =
    if (retaining) y in height * 0.08f..height * 0.92f
    else y in height * 0.22f..height * 0.78f

/** Let the pointer reach a neighbor's center before moving that neighbor out from under it. */
internal fun liveSessionTabDestination(index: Int, lastIndex: Int, y: Float, grab: Float,
    step: Float, height: Float): Int {
    val destination = ((y - grab) / step).roundToInt().coerceIn(0, lastIndex)
    if (destination > index && y < destination * step + height * 0.78f) return index
    if (destination < index && y > destination * step + height * 0.22f) return index
    return destination
}

/** Keep the live slot in gaps; body drops use the hovered center or the dragged tab's position. */
internal fun sessionTabDropDestination(index: Int, lastIndex: Int, y: Float, step: Float,
    height: Float, grab: Float, retainingHover: Boolean = false): Int {
    val row = (y / step).toInt()
    val withinRow = y - row * step
    if (row !in 0..lastIndex || withinRow !in 0f..height) return index
    if (splitTabHoverContains(withinRow, height, retainingHover)) return row
    // Reaching a slot with a low/high grip need not enter the split-hover band.
    return ((y - grab) / step).roundToInt().coerceIn(0, lastIndex)
}

/** A stationary hover arms splitting; passing over a tab still means reordering. */
internal class SplitTabHover {
    var target: String? = null
        private set
    private var row = -1
    private var enteredAt = 0L
    private var anchorY = 0f
    private var armed = false
    private var sourceFirst = false

    fun clear() { target = null; row = -1; armed = false }

    fun update(candidate: String?, candidateRow: Int, y: Float, xFraction: Float, now: Long,
        tolerance: Float): SplitTabPreview? {
        if (candidate == null || candidateRow < 0) { clear(); return null }
        if (target != candidate || row != candidateRow || (!armed && abs(y - anchorY) > tolerance)) {
            target = candidate; row = candidateRow; enteredAt = now; anchorY = y; armed = false
        }
        if (!armed && now - enteredAt >= DWELL_MILLIS) { armed = true; sourceFirst = xFraction < 0.5f }
        if (!armed) return null
        // A small dead zone prevents the order flickering while a finger rests near the middle.
        if (xFraction < 0.44f) sourceFirst = true else if (xFraction > 0.56f) sourceFirst = false
        return SplitTabPreview(candidate, candidateRow, sourceFirst)
    }

    companion object { const val DWELL_MILLIS = 300L }
}

/** Keep the hovered row fixed while morphing into the one possible pair; empty slots close on drop. */
internal fun splitTabPreviewRows(rows: List<List<String>>, source: String, target: String,
    sourceFirst: Boolean): List<List<String>> {
    val sourceRow = rows.indexOfFirst { source in it }
    val targetRow = rows.indexOfFirst { target in it }
    if (source == target || sourceRow < 0 || targetRow < 0) return rows
    val remaining = rows.flatten().filterNot { it == source || it == target }.iterator()
    val hasPair = rows.any { it.size == 2 }
    return rows.indices.map { index ->
        when {
            index == targetRow -> if (sourceFirst) listOf(source, target) else listOf(target, source)
            !hasPair && index == sourceRow -> emptyList()
            else -> listOf(remaining.next())
        }
    }
}
