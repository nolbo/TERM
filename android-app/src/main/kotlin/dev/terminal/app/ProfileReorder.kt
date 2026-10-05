@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.terminal.app.ui.ProfileIcon
import dev.terminal.app.ui.SettingsItemSpacing
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import kotlin.math.roundToInt

/** Stable composition and measured slots support multiline profiles without losing a drag. */
@Composable
internal fun ProfileReorderList(profiles: List<SessionProfile>, editing: Boolean,
    installedImages: Map<String, Boolean>, scroll: ScrollState, viewport: () -> Rect?,
    onReorder: (List<String>) -> Unit, onEdit: (SessionProfile) -> Unit) {
    val density = LocalDensity.current
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val ids = profiles.map { it.id }
    val currentIds by rememberUpdatedState(ids)
    val update by rememberUpdatedState(onReorder)
    val visibleBounds by rememberUpdatedState(viewport)
    val heights = remember { mutableStateMapOf<String, Float>() }
    val fallbackHeight = with(density) { 72.dp.toPx() }
    val spacing = with(density) { SettingsItemSpacing.toPx() }
    fun rowHeight(id: String) = heights[id] ?: fallbackHeight
    var dragging by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var gridTop by remember { mutableFloatStateOf(0f) }
    val order = preview ?: ids
    var nextTop = 0f
    val slots = order.associateWith { id -> nextTop.also { nextTop += rowHeight(id) + spacing } }
    val currentSlots by rememberUpdatedState(slots)
    val totalHeight = (nextTop - spacing).coerceAtLeast(0f)
    fun cancel() { dragging = null; preview = null }
    fun reorder() {
        val id = dragging ?: return
        val items = preview ?: return
        val remaining = items.filter { it != id }
        val target = profileDropTarget(remaining.map(::rowHeight), pointer - gridTop - grabOffset, spacing)
        if (target != items.indexOf(id)) preview = remaining.toMutableList().apply { add(target, id) }
    }
    LaunchedEffect(editing, ids) { cancel() }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragging != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val bounds = visibleBounds() ?: continue
            val edge = minOf(with(density) { 48.dp.toPx() }, bounds.height / 3)
            val direction = when {
                pointer < bounds.top + edge -> -1f
                pointer > bounds.bottom - edge -> 1f
                else -> 0f
            }
            if (direction != 0f) {
                scroll.scrollBy(direction * with(density) { 360.dp.toPx() } * seconds)
                reorder()
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(with(density) { totalHeight.toDp() })
        .onGloballyPositioned { gridTop = it.localToRoot(Offset.Zero).y }
        .semantics { isTraversalGroup = true }) {
        profiles.forEach { profile -> key(profile.id) {
            val id = profile.id
            val index = order.indexOf(id)
            val lifted = dragging == id
            val position by animateFloatAsState(slots.getValue(id), spatial, label = "profileRowPosition")
            val top = if (lifted) (pointer - gridTop - grabOffset)
                .coerceIn(0f, (totalHeight - rowHeight(id)).coerceAtLeast(0f)) else position
            val handleWidth by animateDpAsState(if (editing) 48.dp else 0.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "profileHandleWidth")
            val startPadding by animateDpAsState(if (editing) 8.dp else 16.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "profileStartPadding")
            val editAlpha by animateFloatAsState(if (editing) 1f else 0f, effects, label = "profileEditFade")
            val scale by animateFloatAsState(if (lifted) 1.02f else 1f, spatial, label = "profileLiftScale")
            val elevation by animateDpAsState(if (lifted) 8.dp else 0.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "profileLiftElevation")
            val color by animateColorAsState(if (lifted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.motionScheme.fastEffectsSpec(), label = "profileLiftColor")
            val topProximity = (1f - top / rowHeight(id)).coerceIn(0f, 1f)
            val bottomProximity = (1f - (totalHeight - rowHeight(id) - top) / rowHeight(id)).coerceIn(0f, 1f)
            val topRadius by animateDpAsState(4.dp + 24.dp * topProximity,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "profileTopCorners")
            val bottomRadius by animateDpAsState(4.dp + 24.dp * bottomProximity,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "profileBottomCorners")
            val shape = RoundedCornerShape(topStart = topRadius.coerceIn(4.dp, 28.dp), topEnd = topRadius.coerceIn(4.dp, 28.dp),
                bottomStart = bottomRadius.coerceIn(4.dp, 28.dp), bottomEnd = bottomRadius.coerceIn(4.dp, 28.dp))
            val moveLabel = stringResource(R.string.toolbar_move, profile.name)
            val upLabel = stringResource(R.string.toolbar_move_up)
            val downLabel = stringResource(R.string.toolbar_move_down)
            ListItem(onClick = { if (!editing) onEdit(profile) },
                modifier = Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)
                    .offset { IntOffset(0, top.roundToInt()) }.zIndex(if (lifted) 1f else 0f)
                    .onGloballyPositioned { heights[id] = it.size.height.toFloat() }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale; shadowElevation = elevation.toPx()
                        this.shape = shape; clip = true
                    }.semantics { traversalIndex = index.toFloat() },
                shapes = ListItemDefaults.shapes(shape = shape, selectedShape = shape, pressedShape = shape,
                    focusedShape = shape, hoveredShape = shape, draggedShape = shape),
                colors = ListItemDefaults.colors(containerColor = color),
                contentPadding = PaddingValues(start = startPadding, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(handleWidth.coerceAtLeast(0.dp)).height(48.dp).clipToBounds()) {
                            if (editing || handleWidth > 0.5.dp) {
                                var origin by remember { mutableFloatStateOf(0f) }
                                Box(Modifier.size(48.dp).graphicsLayer { alpha = editAlpha }
                                    .onGloballyPositioned { origin = it.localToRoot(Offset.Zero).y }
                                    .pointerInput(id, editing) {
                                        if (!editing) return@pointerInput
                                        detectDragGestures(onDragStart = { local ->
                                            preview = currentIds
                                            pointer = origin + local.y
                                            grabOffset = pointer - gridTop - currentSlots.getValue(id)
                                            dragging = id
                                        }, onDrag = { change, delta ->
                                            change.consume(); pointer += delta.y; reorder()
                                        }, onDragCancel = { cancel() }, onDragEnd = {
                                            val result = preview
                                            cancel()
                                            if (result != null && result != currentIds) update(result)
                                        })
                                    }.semantics {
                                        contentDescription = moveLabel
                                        customActions = if (!editing || dragging != null) emptyList() else buildList {
                                            if (index > 0) add(CustomAccessibilityAction(upLabel) {
                                                update(ids.toMutableList().apply { add(index - 1, removeAt(index)) }); true
                                            })
                                            if (index < ids.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                                update(ids.toMutableList().apply { add(index + 1, removeAt(index)) }); true
                                            })
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_toolbar_drag), null, Modifier.size(20.dp))
                                }
                            }
                        }
                        ProfileIcon(profile.icon, profile.runtime.wireName)
                    }
                },
                supportingContent = {
                    val detail = stringResource(runtimeTitleResource(profile.runtime.wireName)) + " · " + profile.shell
                    Text(detail + if (profile.runtime == SessionRuntime.AVF) "\n" + stringResource(R.string.avf_profile_image_status,
                        stringResource(when (installedImages[profile.storageId]) {
                            true -> R.string.avf_image_installed
                            false -> R.string.avf_image_not_installed
                            null -> R.string.avf_image_checking
                        })) else "")
                },
                trailingContent = {
                    Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null,
                        Modifier.graphicsLayer { alpha = 1f - editAlpha })
                }) { Text(profile.name) }
        } }
    }
}
