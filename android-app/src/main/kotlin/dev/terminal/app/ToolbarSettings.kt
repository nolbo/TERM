@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.SettingsGroup
import dev.terminal.app.ui.SettingsItemSpacing
import dev.terminal.app.ui.terminalListItemShapes

@Composable
internal fun ToolbarSettings(preferences: SharedPreferences, onBack: () -> Unit,
    tools: List<ToolbarTool> = toolbarTools, onOpenTool: (String) -> Unit) {
    var config by remember { mutableStateOf(parseToolbarConfig(preferences.getString(KEY_TOOLBAR, null),
        readHotkeyLayout(preferences).enabled, tools.map { it.id }.toSet())) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf<Rect?>(null) }
    val update: (ToolbarConfig) -> Unit = {
        config = it
        preferences.edit().putString(KEY_TOOLBAR, serializeToolbarConfig(it)).apply()
    }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val editLabel = stringResource(if (editing) R.string.toolbar_done else R.string.toolbar_edit)
    SettingsPageScaffold(stringResource(R.string.toolbar_settings), onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = !editing },
                modifier = Modifier.semantics { contentDescription = editLabel },
                icon = {
                    AnimatedContent(editing, transitionSpec = {
                        fadeIn(effects) togetherWith fadeOut(effects)
                    }, label = "toolbarEditIcon") { edit ->
                        Icon(painterResource(if (edit) R.drawable.ic_material_symbol_check_circle_24 else R.drawable.ic_toolbar_edit), null)
                    }
                },
                text = { Text(editLabel) })
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { viewport = it.boundsInRoot() }.verticalScroll(scroll)
            .padding(horizontal = 16.dp).padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ListItem(onClick = { update(config.copy(enabled = !config.enabled)) },
                trailingContent = { Switch(config.enabled, onCheckedChange = null) },
                supportingContent = { Text(stringResource(R.string.toolbar_enabled_summary)) },
                verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                modifier = Modifier.semantics { role = Role.Switch; toggleableState =
                    if (config.enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Text(stringResource(R.string.toolbar_enabled))
            }
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.toolbar_tools), Modifier.padding(start = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (config.tools.isEmpty() && !editing) Text(stringResource(R.string.toolbar_empty))
                SettingsGroup {
                    ToolbarReorderList(config, editing, tools, scroll, { viewport }, update, onOpenTool)
                }
            }
            Text(stringResource(if (editing) R.string.toolbar_edit_summary else R.string.toolbar_settings_summary),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Fixed slots keep drag hit testing independent of spring-animated neighboring rows. */
@Composable
private fun ToolbarReorderList(config: ToolbarConfig, editing: Boolean, tools: List<ToolbarTool>,
    scroll: ScrollState, viewport: () -> Rect?, onUpdate: (ToolbarConfig) -> Unit, onOpen: (String) -> Unit) {
    val density = LocalDensity.current
    // Match the single-line ListItem used by Profiles → Add profile.
    val rowHeight = 56.dp
    val rowStep = rowHeight + SettingsItemSpacing
    val rowPixels = with(density) { rowStep.toPx() }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    var dragging by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var gridTop by remember { mutableFloatStateOf(0f) }
    val currentConfig by rememberUpdatedState(config)
    val update by rememberUpdatedState(onUpdate)
    val order = preview ?: config.tools
    val displayed = order + if (editing) tools.map { it.id }.filter { it !in order } else emptyList()
    val height by animateDpAsState(if (displayed.isEmpty()) 0.dp else rowStep * displayed.size - SettingsItemSpacing, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarListHeight")
    fun cancel() { dragging = null; preview = null }
    fun reorder() {
        val id = dragging ?: return
        val items = preview ?: return
        val index = items.indexOf(id)
        val target = ((pointer - gridTop - grabOffset) / rowPixels).roundToInt().coerceIn(0, items.lastIndex)
        if (index != target) preview = items.toMutableList().apply { add(target, removeAt(index)) }
    }
    LaunchedEffect(editing, config.tools) { cancel() }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val visible = viewport() ?: continue
            val edge = with(density) { 48.dp.toPx() }
            val direction = when {
                pointer < visible.top + edge -> -1f
                pointer > visible.bottom - edge -> 1f
                else -> 0f
            }
            if (direction != 0f) {
                scroll.scrollBy(direction * with(density) { 360.dp.toPx() } * seconds)
                reorder()
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(height).onGloballyPositioned { gridTop = it.boundsInRoot().top }
        .semantics { isTraversalGroup = true }) {
        // Compose in registry order so the pointer handler survives visual reordering.
        tools.forEach { tool -> key(tool.id) {
            val id = tool.id
            val added = id in config.tools
            val index = displayed.indexOf(id).takeIf { it >= 0 } ?: config.tools.size
            val lifted = dragging == id
            val dragTop = (pointer - gridTop - grabOffset).coerceIn(0f, ((order.size - 1).coerceAtLeast(0)) * rowPixels)
            val position = remember { Animatable(index * rowPixels) }
            val target = if (lifted) dragTop else index * rowPixels
            LaunchedEffect(target, lifted) {
                if (lifted) position.snapTo(target) else position.animateTo(target, spatial)
            }
            val leadingWidth by animateDpAsState(if (editing && added) 48.dp else 0.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarHandleWidth")
            val startPadding by animateDpAsState(if (editing && added) 8.dp else 16.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarRowStartPadding")
            val endPadding by animateDpAsState(if (editing) 8.dp else 16.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarRowEndPadding")
            val editAlpha by animateFloatAsState(if (editing) 1f else 0f, effects, label = "toolbarEditFade")
            val scale by animateFloatAsState(if (lifted) 1.02f else 1f, spatial, label = "toolbarLiftScale")
            val elevation by animateDpAsState(if (lifted) 8.dp else 0.dp,
                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarLiftElevation")
            val color by animateColorAsState(if (lifted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.motionScheme.fastEffectsSpec(), label = "toolbarLiftColor")
            val name = stringResource(tool.title)
            val moveLabel = stringResource(R.string.toolbar_move, name)
            val upLabel = stringResource(R.string.toolbar_move_up)
            val downLabel = stringResource(R.string.toolbar_move_down)
            AnimatedVisibility(visible = added || editing, enter = fadeIn(effects), exit = fadeOut(effects),
                modifier = Modifier.offset { IntOffset(0, (if (lifted) dragTop else position.value).roundToInt()) }
                    .zIndex(if (lifted) 1f else 0f).semantics { traversalIndex = index.toFloat() }) {
                Box {
                    ListItem(onClick = { if (!editing) onOpen(tool.settings) },
                        modifier = Modifier.fillMaxWidth().height(rowHeight).graphicsLayer {
                            scaleX = scale; scaleY = scale; shadowElevation = elevation.toPx()
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                        },
                        shapes = terminalListItemShapes(),
                        colors = ListItemDefaults.colors(containerColor = color),
                        contentPadding = PaddingValues(start = startPadding, end = endPadding),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(leadingWidth.coerceAtLeast(0.dp)).height(48.dp).clipToBounds()) {
                                if (added && (editing || leadingWidth > 0.5.dp)) {
                                    var origin by remember { mutableFloatStateOf(0f) }
                                    Box(Modifier.size(48.dp).graphicsLayer { alpha = editAlpha }
                                        .onGloballyPositioned { origin = it.boundsInRoot().top }
                                        .pointerInput(id, editing, added) {
                                            if (!editing) return@pointerInput
                                            detectDragGestures(onDragStart = { local ->
                                                preview = currentConfig.tools
                                                pointer = origin + local.y
                                                grabOffset = pointer - gridTop - currentConfig.tools.indexOf(id) * rowPixels
                                                dragging = id
                                            }, onDrag = { change, delta ->
                                                change.consume(); pointer += delta.y; reorder()
                                            }, onDragCancel = { cancel() }, onDragEnd = {
                                                preview?.let { update(currentConfig.copy(tools = it)) }
                                                cancel()
                                            })
                                        }.semantics {
                                            contentDescription = moveLabel
                                            customActions = buildList {
                                                val from = config.tools.indexOf(id)
                                                if (from > 0) add(CustomAccessibilityAction(upLabel) {
                                                    onUpdate(config.moveTool(from, from - 1)); true
                                                })
                                                if (from < config.tools.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                                    onUpdate(config.moveTool(from, from + 1)); true
                                                })
                                            }
                                        }, contentAlignment = Alignment.Center) {
                                        Icon(painterResource(R.drawable.ic_toolbar_drag), null, Modifier.size(20.dp))
                                    }
                                }
                            }
                            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val trailingWidth by animateDpAsState(if (editing) 48.dp else 24.dp,
                                MaterialTheme.motionScheme.defaultSpatialSpec(), label = "toolbarActionWidth")
                            Box(Modifier.width(trailingWidth).height(48.dp), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null,
                                    Modifier.graphicsLayer { alpha = 1f - editAlpha })
                                if (editing || editAlpha > 0.01f) IconButton(onClick = {
                                    cancel()
                                    onUpdate(if (added) config.copy(tools = config.tools - id) else config.addTool(id))
                                }, modifier = Modifier.graphicsLayer { alpha = editAlpha }, enabled = editing && dragging == null) {
                                    Icon(painterResource(if (added) R.drawable.ic_toolbar_remove else R.drawable.ic_material_symbol_add_24),
                                        stringResource(if (added) R.string.toolbar_remove else R.string.toolbar_add, name))
                                }
                            }
                        }
                    }
                    if (index < displayed.lastIndex && !lifted) HorizontalDivider(
                        Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } }
    }
}
