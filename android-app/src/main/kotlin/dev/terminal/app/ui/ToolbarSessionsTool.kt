@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.terminal.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarSessionsTool(
    sessions: List<SessionUi>,
    activeSessionId: String?,
    active: Boolean,
    terminatingSessionIds: Set<String>,
    profiles: List<dev.terminal.runtime.session.SessionProfile>,
    onSelectProfile: (dev.terminal.runtime.session.SessionProfile) -> Unit,
    onAddProfile: () -> Unit,
    newSessionMenuExpanded: Boolean,
    onSelect: (String) -> Unit,
    onRename: (SessionUi) -> Unit,
    onTerminate: (SessionUi) -> Unit,
    onNew: () -> Unit,
    onDismissNewSession: () -> Unit,
    onSettings: () -> Unit,
    colors: TerminalChromeColors = LocalToolbarColors.current,
    onReorder: (List<String>) -> Unit = {},
    expansionProgress: Float = LocalToolbarExpansionProgress.current,
) {
    val haptics = LocalTerminalHaptics.current
    // Keep each list's position when switching between the default and expanded headers.
    val tabScroll = rememberScrollState()
    val headerTabScroll = rememberScrollState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val progress = expansionProgress.coerceIn(0f, 1f)
    val tabsActive = active && progress == 1f
    LaunchedEffect(active) { if (!active && newSessionMenuExpanded) onDismissNewSession() }
    Column(Modifier.fillMaxSize()) {
        // Use the sheet's spatial motion directly; a second animation would lag behind dragging.
        val headerHeight = ToolbarHotkeyViewportHeight + (48.dp - ToolbarHotkeyViewportHeight) * progress
        Row(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (progress < 1f && sessions.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(48.dp).clipToBounds().graphicsLayer { alpha = 1f - progress }) {
                        PrimaryScrollableTabRow(
                            selectedTabIndex = sessions.indexOfFirst { it.id == activeSessionId }.coerceAtLeast(0),
                            scrollState = headerTabScroll,
                            modifier = Modifier.fillMaxSize(),
                            containerColor = colors.background,
                            contentColor = colors.accent,
                            edgePadding = 6.dp,
                            indicator = {},
                            divider = {},
                        ) {
                            sessions.forEachIndexed { index, session ->
                                val selected = session.id == activeSessionId
                                Box(Modifier.widthIn(max = 220.dp).height(48.dp)) {
                                    SessionToolTab(session, selected, active && progress == 0f,
                                        terminatingSessionIds, onSelect, onRename, onTerminate, colors, horizontal = true)
                                    if (index > 0 && !selected && sessions[index - 1].id != activeSessionId) {
                                        VerticalDivider(Modifier.align(Alignment.CenterStart).height(24.dp),
                                            thickness = 1.dp, color = colors.divider)
                                    }
                                }
                            }
                        }
                        ScrollEdgeFade(headerTabScroll.canScrollForward,
                            Brush.horizontalGradient(if (rtl) listOf(colors.background, colors.background.copy(alpha = 0f))
                                else listOf(colors.background.copy(alpha = 0f), colors.background)),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp))
                    }
                }
                if (progress > 0f) {
                    SessionToolSummary(sessions, colors,
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).graphicsLayer { alpha = progress })
                }
            }
            Box {
                IconButton(onClick = onNew, enabled = active, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), stringResource(R.string.new_session), tint = colors.appBarIcon)
                }
                DropdownMenuPopup(expanded = newSessionMenuExpanded, onDismissRequest = onDismissNewSession,
                    modifier = Modifier.widthIn(min = 240.dp)) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), containerColor = colors.container) {
                        val itemColors = MenuDefaults.itemColors(
                            textColor = colors.onContainer,
                            leadingIconColor = colors.accent,
                            trailingIconColor = colors.onContainer,
                            disabledTextColor = colors.onContainer.copy(alpha = 0.38f),
                            disabledLeadingIconColor = colors.onContainer.copy(alpha = 0.38f),
                            disabledTrailingIconColor = colors.onContainer.copy(alpha = 0.38f),
                        )
                        profiles.forEachIndexed { index, profile ->
                            DropdownMenuItem(onClick = { onSelectProfile(profile) },
                                text = { Column { Text(profile.name); Text(stringResource(runtimeTitleResource(profile.runtime.wireName)), style = MaterialTheme.typography.bodySmall) } },
                                shape = MenuDefaults.itemShape(index, profiles.size + 1).shape,
                                colors = itemColors,
                                leadingIcon = { Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    ProfileIcon(profile.icon, profile.runtime.wireName, tint = colors.accent)
                                } })
                        }
                        DropdownMenuItem(onClick = onAddProfile, text = { Text(stringResource(R.string.add_profile)) },
                            shape = MenuDefaults.itemShape(profiles.size, profiles.size + 1).shape,
                            colors = itemColors,
                            leadingIcon = {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), null, Modifier.size(24.dp))
                                }
                            })
                    }
                }
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                onSettings()
            }, enabled = active, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_material_symbol_settings_24), stringResource(R.string.settings), tint = colors.appBarIcon)
            }
        }
        if (progress > 0f) ReorderableSessionTabs(sessions, activeSessionId, tabsActive, onReorder,
            Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = progress }, scroll = tabScroll) { session, itemModifier, dragging, lifted ->
            SessionToolTab(session, session.id == activeSessionId, tabsActive && !dragging,
                terminatingSessionIds, onSelect, onRename, onTerminate, colors, itemModifier, lifted)
        }
    }
}

private val sessionSummaryStates = listOf("RUNNING", "PREPARING", "CREATED", "EXITED", "LAUNCH_FAILED", "UNKNOWN")

@Composable
private fun SessionToolSummary(sessions: List<SessionUi>, colors: TerminalChromeColors, modifier: Modifier) {
    val counts = remember(sessions) {
        sessions.groupingBy { if (it.state in sessionSummaryStates) it.state else "UNKNOWN" }.eachCount()
    }
    Column(modifier) {
        Text(stringResource(R.string.toolbar_session_count, sessions.size),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, color = colors.foreground)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            var first = true
            sessionSummaryStates.forEach { state ->
                counts[state]?.let { count ->
                    if (!first) Text(" · ", style = MaterialTheme.typography.labelSmall,
                        color = colors.foreground.copy(alpha = 0.75f))
                    Text(stringResource(R.string.toolbar_session_state_count, sessionStateLabel(state), count),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.foreground.copy(alpha = 0.75f))
                    first = false
                }
            }
        }
    }
}

@Composable
private fun SessionToolTab(
    session: SessionUi,
    selected: Boolean,
    enabled: Boolean,
    terminatingSessionIds: Set<String>,
    onSelect: (String) -> Unit,
    onRename: (SessionUi) -> Unit,
    onTerminate: (SessionUi) -> Unit,
    colors: TerminalChromeColors,
    itemModifier: Modifier = Modifier,
    lifted: Boolean = false,
    horizontal: Boolean = false,
) {
    val haptics = LocalTerminalHaptics.current
    val tabInteractionSource = remember(session.id) { MutableInteractionSource() }
    val tabPressed by tabInteractionSource.collectIsPressedAsState()
    // Match the empty-state Button: release/cancel immediately targets the resting shape.
    val cornerRadius by animateDpAsState(
        targetValue = if (tabPressed) 8.dp else 20.dp,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Session tab corner radius",
    )
    val sessionTitle = session.title.ifBlank { stringResource(R.string.shell_default_title) }
    val stateLabel = sessionStateLabel(session.state)
    val renameLabel = stringResource(R.string.rename_session_description, sessionTitle)
    val selectSession = {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        onSelect(session.id)
    }
    // The body owns both gestures so releasing a long press cannot also select the tab.
    // Share the tab's pressed state and ripple; keep the close button outside this target.
    val bodyGestures = if (horizontal) Modifier.combinedClickable(
        interactionSource = tabInteractionSource,
        indication = null,
        enabled = enabled,
        hapticFeedbackEnabled = false,
        onLongClickLabel = renameLabel,
        onLongClick = if (session.pendingLaunch) null else ({
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onRename(session)
        }),
        onClick = selectSession,
    ) else Modifier
    Tab(
        selected = selected,
        enabled = enabled,
        onClick = selectSession,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(48.dp)
            .then(itemModifier).clip(RoundedCornerShape(cornerRadius))
            .background(if (selected || lifted) colors.container else Color.Transparent)
            .semantics { stateDescription = stateLabel },
        selectedContentColor = colors.accent,
        unselectedContentColor = colors.unselectedTabContent,
        interactionSource = tabInteractionSource,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = if (horizontal) 48.dp else 40.dp)
                .then(bodyGestures).padding(start = 12.dp, end = if (horizontal && !selected) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ProfileIcon(session.launchSpec?.icon ?: "auto", session.runtime,
                    contentDescription = stringResource(runtimeTitleResource(session.runtime)),
                    size = 20.dp, tint = if (selected) colors.accent else colors.unselectedTabContent)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                    session.subtitle?.let { subtitle ->
                        Text(subtitle.ifBlank { stringResource(R.string.shell_default_title) },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                            color = (if (selected) colors.onContainer else colors.unselectedTabContent).copy(alpha = 0.75f))
                    }
                }
            }
            if (selected && !horizontal) IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onRename(session)
                },
                shape = CircleShape,
                enabled = enabled && !session.pendingLaunch,
            ) {
                Icon(painterResource(R.drawable.ic_toolbar_edit), renameLabel)
            }
            if (!horizontal || selected) IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onTerminate(session)
                },
                shape = CircleShape,
                enabled = enabled && session.id !in terminatingSessionIds &&
                    (!session.pendingLaunch || session.launchError != null),
            ) {
                Icon(painterResource(R.drawable.ic_material_symbol_close_24), stringResource(R.string.terminate_session_description, sessionTitle))
            }
        }
    }
}
