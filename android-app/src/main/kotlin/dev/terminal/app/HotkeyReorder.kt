package dev.terminal.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import dev.terminal.app.ui.LocalTerminalHaptics

private data class HotkeyDrag(
    val from: Int,
    val target: Int?,
    val pointer: Offset,
    val grabOffset: Offset,
    val size: Size,
)

/** Unclipped root coordinates keep hit testing stable while the settings page scrolls. */
private fun LayoutCoordinates.rootRect(): Rect = Rect(localToRoot(Offset.Zero), Size(size.width.toFloat(), size.height.toFloat()))

/** Gaps and unfilled cells choose the nearest occupied position, including wide keys. */
internal fun hotkeyDropTarget(pointer: Offset, cells: Map<Int, Rect>, grid: Rect, viewport: Rect): Int? {
    if (!grid.contains(pointer) || !viewport.contains(pointer)) return null
    return cells.minByOrNull { (_, rect) ->
        val dx = pointer.x - pointer.x.coerceIn(rect.left, rect.right)
        val dy = pointer.y - pointer.y.coerceIn(rect.top, rect.bottom)
        dx * dx + dy * dy
    }?.key
}

@Composable
internal fun ReorderableHotkeyPreview(
    layout: HotkeyLayout,
    scrollState: ScrollState,
    viewportBounds: () -> Rect?,
    onMove: (Int, Int) -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = remember(layout) { mutableMapOf<Int, LayoutCoordinates>() }
    var grid by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var drag by remember(layout) { mutableStateOf<HotkeyDrag?>(null) }
    val move by rememberUpdatedState(onMove)
    val viewport by rememberUpdatedState(viewportBounds)
    val haptics = LocalTerminalHaptics.current
    val density = LocalDensity.current
    val targetColor = MaterialTheme.colorScheme.primary
    val edge = with(density) { 48.dp.toPx() }
    val speed = with(density) { 480.dp.toPx() }
    fun target(pointer: Offset): Int? {
        val bounds = grid?.takeIf { it.isAttached }?.rootRect() ?: return null
        val visible = viewport() ?: return null
        return hotkeyDropTarget(pointer, cells.filterValues { it.isAttached }.mapValues { it.value.rootRect() }, bounds, visible)
    }

    LaunchedEffect(drag?.target) {
        val current = drag ?: return@LaunchedEffect
        if (current.target != null && current.target != current.from) {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        }
    }

    // Keep scrolling even when the finger rests at an edge. Only the settings viewport scrolls.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val current = drag ?: break
            val bounds = viewport() ?: continue
            val direction = when {
                !bounds.contains(current.pointer) -> 0f
                current.pointer.y < bounds.top + edge -> -((bounds.top + edge - current.pointer.y) / edge)
                current.pointer.y > bounds.bottom - edge -> (current.pointer.y - bounds.bottom + edge) / edge
                else -> 0f
            }
            if (direction != 0f) scrollState.scrollBy(direction * speed * seconds)
            drag = drag?.let { it.copy(target = target(it.pointer)) }
        }
    }
    Box(modifier.onGloballyPositioned { grid = it }.pointerInput(layout, cells) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val root = grid?.takeIf { it.isAttached }?.localToRoot(position)
                val source = root?.let { point -> cells.entries.firstOrNull { it.value.isAttached && it.value.rootRect().contains(point) } }
                if (root != null && source != null) {
                    val rect = source.value.rootRect()
                    drag = HotkeyDrag(source.key, source.key, root, root - rect.topLeft, rect.size)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { change, _ ->
                drag?.let { current ->
                    change.consume()
                    grid?.takeIf { it.isAttached }?.localToRoot(change.position)?.let { root ->
                        drag = current.copy(pointer = root, target = target(root))
                    }
                }
            },
            onDragEnd = {
                val current = drag
                drag = null
                if (current != null) target(current.pointer)?.let { to ->
                    if (to != current.from) move(current.from, to)
                }
            },
            onDragCancel = { drag = null },
        )
    }) {
        HotkeyGrid(layout, editing = true, cellModifier = { index ->
            Modifier.onGloballyPositioned { cells[index] = it }
                .alpha(if (drag?.from == index) 0.35f else 1f)
        }, onKey = { if (drag == null) onEdit(it) })
        drag?.let { current ->
            val key = layout.keys[current.from]
            Surface(
                modifier = Modifier.offset {
                    // Reading scroll state keeps the lifted key under the finger as its parent moves.
                    scrollState.value
                    (grid?.takeIf { it.isAttached }?.let { coordinates ->
                        current.pointer - current.grabOffset - coordinates.localToRoot(Offset.Zero)
                    } ?: Offset.Zero).round()
                }.size(with(density) { current.size.width.toDp() }, with(density) { current.size.height.toDp() })
                    .clearAndSetSemantics {},
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(key.name, fontFamily = FontFamily.Monospace, fontSize = layout.fontSize.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Show the actual insertion edge above the lifted key, so its shadow cannot hide the destination.
        drag?.let { current ->
            val destination = current.target?.takeIf { it != current.from }
            val coordinates = destination?.let { cells[it]?.takeIf { cell -> cell.isAttached } }
            if (destination != null && coordinates != null) {
                Box(Modifier.offset {
                    scrollState.value
                    val rect = coordinates.rootRect()
                    val origin = grid?.takeIf { it.isAttached }?.localToRoot(Offset.Zero) ?: Offset.Zero
                    Offset(
                        (if (destination < current.from) rect.left else rect.right) - origin.x - 2.dp.toPx(),
                        rect.top - origin.y,
                    ).round()
                }.width(4.dp).height(with(density) { coordinates.size.height.toDp() })
                    .background(targetColor, CircleShape))
            }
        }
    }
}
