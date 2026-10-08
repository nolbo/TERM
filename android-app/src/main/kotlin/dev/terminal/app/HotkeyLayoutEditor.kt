@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.terminal.app.ui.LocalTerminalHaptics
import dev.terminal.app.ui.FloatingToolbarAction
import kotlin.math.ceil

private data class HotkeyDrag(
    val index: Int,
    val originalLayout: HotkeyLayout,
    val initial: HotkeyBounds,
    val initialPointer: Offset,
    val pointer: Offset,
    val translation: Offset,
    val candidate: HotkeyBounds,
    val preview: HotkeyLayout?,
    val corner: HotkeyCorner?,
)

private fun LayoutCoordinates.rootRect(): Rect =
    Rect(localToRoot(Offset.Zero), Size(size.width.toFloat(), size.height.toFloat()))

/** Share the animated geometry with the buttons, their hit areas, and all four corner handles. */
@Composable
internal fun animatedHotkeyLayout(layout: HotkeyLayout, draggingIndex: Int? = null): HotkeyLayout {
    val bounds = hotkeyBounds(layout)
    val motion = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    return layout.copy(keys = layout.keys.mapIndexed { index, hotkey -> key(index) {
        val rect = bounds[index]
        val followsPointer = index == draggingIndex
        val spec = if (followsPointer) snap<Float>() else motion
        val x by animateFloatAsState(rect.x, spec, label = "Hotkey x")
        val y by animateFloatAsState(rect.y, spec, label = "Hotkey y")
        val width by animateFloatAsState(rect.width, spec, label = "Hotkey width")
        val height by animateFloatAsState(rect.height, spec, label = "Hotkey height")
        val safeWidth = width.coerceIn(1f, layout.columns.toFloat())
        val safeHeight = height.coerceIn(1f, MAX_HOTKEY_ROWS.toFloat())
        // Render touch coordinates immediately; keep the animation at that position for release.
        // Visual coordinates may leave the canvas while dragging or settling back into it.
        hotkey.copy(bounds = if (followsPointer) rect else HotkeyBounds(x, y, safeWidth, safeHeight))
    } })
}

@Composable
internal fun EditableHotkeyPreview(
    layout: HotkeyLayout,
    scrollState: ScrollState,
    viewportBounds: () -> Rect?,
    onChange: (HotkeyLayout) -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = remember { mutableMapOf<Int, LayoutCoordinates>() }
    val handles = remember { mutableMapOf<HotkeyCorner, LayoutCoordinates>() }
    var grid by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var drag by remember { mutableStateOf<HotkeyDrag?>(null) }
    val currentLayout by rememberUpdatedState(layout)
    val change by rememberUpdatedState(onChange)
    val viewport by rememberUpdatedState(viewportBounds)
    val haptics = LocalTerminalHaptics.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gap = with(density) { HOTKEY_GAP_DP.dp.toPx() }
    val rowPitch = with(density) { (HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP).dp.toPx() }
    val edge = with(density) { 48.dp.toPx() }
    val speed = with(density) { 480.dp.toPx() }

    fun canvasPoint(root: Offset): Offset? {
        val coordinates = grid?.takeIf { it.isAttached } ?: return null
        val local = root - coordinates.localToRoot(Offset.Zero)
        val x = if (rtl) coordinates.size.width - local.x else local.x
        return Offset(x / ((coordinates.size.width + gap) / currentLayout.columns), local.y / rowPitch)
    }
    fun start(index: Int, root: Offset, corner: HotkeyCorner? = null) {
        val initial = hotkeyBounds(currentLayout).getOrNull(index) ?: return
        val pointer = canvasPoint(root) ?: return
        selectedIndex = index
        drag = HotkeyDrag(index, currentLayout, initial, pointer, root, Offset.Zero, initial, currentLayout, corner)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun move(root: Offset) {
        val current = drag ?: return
        val pointer = canvasPoint(root) ?: return
        val delta = pointer - current.initialPointer
        val candidate = transformHotkeyBounds(current.initial, delta.x, delta.y, current.originalLayout.columns, current.corner)
        val preview = if (current.corner == null) resolveHotkeyMove(current.originalLayout, current.index, candidate,
            current.initial.x + delta.x + current.initial.width / 2, current.initial.y + delta.y + current.initial.height / 2)
        else if (candidate == current.candidate) current.preview
        else resolveHotkeyPlacement(current.originalLayout, current.index, candidate)
        drag = current.copy(pointer = root, translation = delta, candidate = candidate, preview = preview)
    }
    fun finish() {
        val current = drag
        drag = null
        if (current != null && current.originalLayout == currentLayout && viewport()?.contains(current.pointer) == true) {
            val preview = current.preview
            if (preview != null && preview != currentLayout) {
                change(preview)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }

    // Root coordinates remain stable while edge scrolling moves the canvas beneath the pointer.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val pointer = drag?.pointer ?: break
            val bounds = viewport() ?: continue
            val direction = when {
                !bounds.contains(pointer) -> 0f
                pointer.y < bounds.top + edge -> -((bounds.top + edge - pointer.y) / edge)
                pointer.y > bounds.bottom - edge -> (pointer.y - bounds.bottom + edge) / edge
                else -> 0f
            }
            if (direction != 0f) {
                scrollState.scrollBy(direction * speed * seconds)
                move(pointer)
            }
        }
    }
    LaunchedEffect(layout.keys.size) {
        if (selectedIndex !in layout.keys.indices) selectedIndex = -1
    }
    LaunchedEffect(layout) {
        if (drag?.originalLayout?.let { it != layout } == true) drag = null
    }

    val sourceBounds = hotkeyBounds(layout)
    val target = drag?.let { active -> active.preview ?: layout.copy(keys = positionedHotkeys(layout).toMutableList().apply {
        set(active.index, get(active.index).copy(bounds = active.candidate))
    }) } ?: layout
    val moving = drag?.takeIf { it.corner == null }
    val visualTarget = moving?.let { active -> target.copy(keys = positionedHotkeys(target).toMutableList().apply {
        set(active.index, get(active.index).copy(bounds = active.initial.copy(
            x = active.initial.x + active.translation.x, y = active.initial.y + active.translation.y)))
    }) } ?: target
    val shown = animatedHotkeyLayout(visualTarget, moving?.index)
    val selectedBounds = hotkeyBounds(shown).getOrNull(selectedIndex)
    val valid = drag?.let { it.preview != null && viewport()?.contains(it.pointer) == true } ?: true
    val selectionColor by animateColorAsState(if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "Hotkey placement validity")
    val elevation by animateDpAsState(if (drag == null) 0.dp else 8.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "Hotkey drag elevation")
    val guideColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val actionLabels = listOf(R.string.hotkey_move_left, R.string.hotkey_move_right, R.string.hotkey_move_up,
        R.string.hotkey_move_down, R.string.hotkey_wider, R.string.hotkey_narrower, R.string.hotkey_taller, R.string.hotkey_shorter)
        .map { stringResource(it) }
    val actions = listOf(Triple(-1f, 0f, false), Triple(1f, 0f, false), Triple(0f, -1f, false), Triple(0f, 1f, false),
        Triple(1f, 0f, true), Triple(-1f, 0f, true), Triple(0f, 1f, true), Triple(0f, -1f, true))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HotkeyGrid(shown, editing = true,
            minimumRows = minOf(MAX_HOTKEY_ROWS, maxOf(6,
                ceil(maxOf(hotkeyBounds(target).maxOf { it.bottom }, hotkeyBounds(shown).maxOf { it.bottom })).toInt() + 2)),
            canvasModifier = Modifier.drawBehind {
                val pitch = (size.width + gap) / layout.columns
                for (column in 0..layout.columns) for (row in 0..ceil(size.height / rowPitch).toInt()) {
                    drawCircle(guideColor, 1.dp.toPx(), Offset(column * pitch, row * rowPitch))
                }
            }.onGloballyPositioned { grid = it }.pointerInput(layout) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        val root = grid?.takeIf { it.isAttached }?.localToRoot(local)
                        val index = root?.let { point -> cells.entries.firstOrNull {
                            it.key in layout.keys.indices && it.value.isAttached && it.value.rootRect().contains(point)
                        }?.key }
                        if (root != null && index != null) start(index, root)
                    },
                    onDrag = { event, _ ->
                        if (drag != null) {
                            event.consume()
                            grid?.takeIf { it.isAttached }?.localToRoot(event.position)?.let(::move)
                        }
                    },
                    onDragEnd = ::finish,
                    onDragCancel = { drag = null },
                )
            },
            cellModifier = { index ->
                Modifier.onGloballyPositioned { cells[index] = it }.zIndex(if (drag?.index == index) 1f else 0f).graphicsLayer {
                    shadowElevation = if (drag?.index == index) elevation.toPx() else 0f
                    shape = CircleShape
                }.semantics {
                    selected = index == selectedIndex
                    customActions = actions.mapIndexed { action, (dx, dy, resize) ->
                        CustomAccessibilityAction(actionLabels[action]) {
                            if (drag != null) return@CustomAccessibilityAction false
                            val bounds = transformHotkeyBounds(sourceBounds[index], dx, dy, layout.columns,
                                if (resize) HotkeyCorner.BottomRight else null)
                            val updated = (if (resize) resolveHotkeyPlacement(layout, index, bounds)
                                else resolveHotkeyMove(layout, index, bounds)) ?: layout
                            if (updated != layout) { selectedIndex = index; change(updated); true } else false
                        }
                    }
                }
            },
            decoration = { pitch ->
                if (selectedBounds != null) {
                    val rect = selectedBounds
                    val x = pitch * rect.x
                    val y = ((HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP) * rect.y).dp
                    val width = pitch * rect.width - HOTKEY_GAP_DP.dp
                    val height = ((HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP) * rect.height - HOTKEY_GAP_DP).dp
                    Box(Modifier.offset(x, y).size(width, height).zIndex(2f).border(2.dp, selectionColor, CircleShape))
                    HotkeyCorner.entries.forEach { corner ->
                        val cornerName = stringResource(when (corner) {
                            HotkeyCorner.TopLeft -> R.string.hotkey_top_left
                            HotkeyCorner.TopRight -> R.string.hotkey_top_right
                            HotkeyCorner.BottomLeft -> R.string.hotkey_bottom_left
                            HotkeyCorner.BottomRight -> R.string.hotkey_bottom_right
                        })
                        val description = stringResource(R.string.hotkey_resize_handle, layout.keys[selectedIndex].name, cornerName)
                        Box(Modifier.offset(x + (if (corner.left) 0.dp else width) - 16.dp,
                            y + (if (corner.top) 0.dp else height) - 16.dp).size(32.dp).zIndex(2f)
                            .onGloballyPositioned { handles[corner] = it }
                            .semantics { contentDescription = description }
                            .pointerInput(layout, selectedIndex, corner) {
                                detectDragGestures(
                                    onDragStart = { local -> handles[corner]?.takeIf { it.isAttached }
                                        ?.localToRoot(local)?.let { start(selectedIndex, it, corner) } },
                                    onDrag = { event, _ ->
                                        event.consume()
                                        handles[corner]?.takeIf { it.isAttached }?.localToRoot(event.position)?.let(::move)
                                    },
                                    onDragEnd = ::finish,
                                    onDragCancel = { drag = null },
                                )
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).background(selectionColor, CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
                        }
                    }
                }
            },
        ) { index -> if (drag == null) selectedIndex = index }
        if (selectedIndex in layout.keys.indices) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(layout.keys[selectedIndex].name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                FloatingToolbarAction(stringResource(R.string.hotkey_edit), R.drawable.ic_toolbar_edit,
                    enabled = drag == null, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        onEdit(selectedIndex)
                    })
                FloatingToolbarAction(stringResource(R.string.hotkey_remove), R.drawable.ic_material_symbol_delete_24,
                    enabled = drag == null && layout.keys.size > 1, destructive = true, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        change(removeHotkey(layout, selectedIndex))
                        selectedIndex = -1
                    })
            }
        }
    }
}
