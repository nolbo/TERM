package dev.terminal.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.terminal.app.R
import kotlin.math.roundToInt

/** Fixed slots keep hit testing stable while neighbors animate and the list scrolls. */
@Composable
internal fun ReorderableSessionTabs(sessions: List<SessionUi>, activeSessionId: String?, active: Boolean,
    onReorder: (List<String>) -> Unit, modifier: Modifier,
    content: @Composable (SessionUi, Modifier, Boolean, Boolean) -> Unit) {
    val ids = sessions.map { it.id }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptics = LocalTerminalHaptics.current
    val step = 52.dp
    val stepPx = with(density) { step.toPx() }
    val rowPx = with(density) { 48.dp.toPx() }
    val effects = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var dragging by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var grid by remember { mutableStateOf(Rect.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val update by rememberUpdatedState(onReorder)
    val currentIds by rememberUpdatedState(ids)
    val order = preview ?: ids
    val upLabel = stringResource(R.string.toolbar_move_up)
    val downLabel = stringResource(R.string.toolbar_move_down)
    fun cancel() { dragging = null; preview = null }
    fun reorder() {
        val id = dragging ?: return
        val items = preview ?: return
        val index = items.indexOf(id)
        val target = ((pointer.y - grid.top - grabOffset) / stepPx).roundToInt().coerceIn(0, items.lastIndex)
        if (index != target) {
            preview = items.toMutableList().apply { add(target, removeAt(index)) }
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        }
    }
    // Runtime changes and paging away revoke a drag; title updates keep stable IDs.
    LaunchedEffect(ids, active, activeSessionId) { cancel() }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragging != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val edge = minOf(with(density) { 48.dp.toPx() }, viewport.height / 3)
            val direction = when {
                !viewport.contains(pointer) -> 0f
                pointer.y < viewport.top + edge -> -1f
                pointer.y > viewport.bottom - edge -> 1f
                else -> 0f
            }
            if (direction != 0f) {
                scroll.scrollBy(direction * with(density) { 360.dp.toPx() } * seconds)
                reorder()
            }
        }
    }
    ToolbarScrollEdges(scroll.canScrollBackward, scroll.canScrollForward, modifier) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInRoot() }
            .verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(Modifier.fillMaxWidth().height((step * sessions.size - 4.dp).coerceAtLeast(0.dp))
                .onGloballyPositioned { grid = it.boundsInRoot() }
                .pointerInput(active, ids, activeSessionId) {
                    if (!active) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            val index = (position.y / stepPx).toInt()
                            val id = currentIds.getOrNull(index)
                            val actionsWidth = if (id == activeSessionId) rowPx * 2 else rowPx
                            // Long pressing action buttons never starts a reorder.
                            if (id != null && position.x < grid.width - actionsWidth) {
                                preview = currentIds
                                dragging = id
                                pointer = grid.topLeft + position
                                grabOffset = position.y - index * stepPx
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, _ ->
                            if (dragging != null) {
                                change.consume()
                                pointer = grid.topLeft + change.position
                                reorder()
                            }
                        },
                        onDragCancel = { cancel() },
                        onDragEnd = {
                            val result = preview
                            val valid = viewport.contains(pointer) && currentIds == ids
                            cancel()
                            if (valid && result != null && result != currentIds) update(result)
                        },
                    )
                }) {
                // Stable composition order preserves pointer ownership during previews.
                sessions.forEach { session -> key(session.id) {
                    val index = order.indexOf(session.id)
                    val lifted = dragging == session.id
                    val position by animateFloatAsState(index * stepPx, effects, label = "Session tab position")
                    val top = if (lifted) (pointer.y - grid.top - grabOffset)
                        .coerceIn(0f, (grid.height - rowPx).coerceAtLeast(0f)) else position
                    val item = Modifier.offset { IntOffset(0, top.roundToInt()) }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            scaleX = if (lifted) 1.02f else 1f
                            scaleY = if (lifted) 1.02f else 1f
                            shadowElevation = if (lifted) 3.dp.toPx() else 0f
                            shape = RoundedCornerShape(20.dp)
                            // The tab owns clipping; scaling must not clip a second, wider capsule.
                            clip = false
                        }
                        .semantics {
                            traversalIndex = index.toFloat()
                            customActions = if (!active || dragging != null) emptyList() else buildList {
                                if (index > 0) add(CustomAccessibilityAction(upLabel) {
                                    update(ids.toMutableList().apply { add(index - 1, removeAt(index)) }); true
                                })
                                if (index < ids.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                    update(ids.toMutableList().apply { add(index + 1, removeAt(index)) }); true
                                })
                            }
                        }
                    content(session, item, dragging != null, lifted)
                } }
            }
        }
    }
}
