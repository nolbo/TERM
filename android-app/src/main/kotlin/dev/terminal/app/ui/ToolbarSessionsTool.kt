@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffset
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.terminal.app.R
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarSessionsTool(
    sessions: List<SessionUi>,
    activeSessionId: String?,
    active: Boolean,
    terminatingSessionIds: Set<String>,
    profiles: List<SessionProfile>,
    onSelectProfile: (SessionProfile) -> Unit,
    onAddProfile: () -> Unit,
    profilePickerVisible: Boolean,
    onSelect: (String) -> Unit,
    onRename: (SessionUi) -> Unit,
    onTerminate: (SessionUi) -> Unit,
    onNew: () -> Unit,
    onDismissProfilePicker: () -> Unit,
    onSettings: () -> Unit,
    colors: TerminalChromeColors = LocalToolbarColors.current,
    onReorder: (List<String>) -> Unit = {},
    expansionProgress: Float? = null,
    profileEnvironmentNames: Map<EnvironmentId, String> = emptyMap(),
    profileInstallationStatuses: Map<EnvironmentId, ProfileInstallationStatus> = emptyMap(),
    headerTabScroll: ScrollState = rememberScrollState(),
    workspaceState: dev.terminal.runtime.workspace.WorkspaceRegistry.State = dev.terminal.runtime.workspace.WorkspaceRegistry.State(),
    onSelectWorkspace: (String) -> Unit = {},
    onCreateWorkspace: (String) -> Unit = {},
    onLaunchWorkspace: (dev.terminal.runtime.workspace.WorkspaceLaunch) -> Unit = {},
    onCloseWorkspaceSessions: () -> Unit = {},
    onMove: (SessionUi) -> Unit = {},
    onDeleteEmptyTemporaryWorkspace: (String) -> Unit = {},
    workspaceSessionStateKnown: Boolean = false,
    onRunWorkspaceStartups: () -> Unit = {},
    onInfo: (SessionUi) -> Unit = {},
) {
    val haptics = LocalTerminalHaptics.current
    // Keep each list's position when switching between the default and expanded headers.
    val tabScroll = rememberScrollState()
    val profileScroll = rememberLazyListState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sessionsActive = active && !profilePickerVisible
    BackHandler(enabled = active && profilePickerVisible, onBack = onDismissProfilePicker)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val pageTransition = updateTransition(profilePickerVisible, label = "sessionsPage")
    var pageWidth by remember { mutableIntStateOf(0) }
    val sessionOffset = pageTransition.animateIntOffset(transitionSpec = { spatial }, label = "sessionPageOffset") {
        IntOffset(if (it) -pageWidth else 0, 0)
    }
    val sessionAlpha = pageTransition.animateFloat(transitionSpec = { effects }, label = "sessionPageAlpha") {
        if (it) 0f else 1f
    }
    val progressState = LocalToolbarExpansionProgress.current
    val preparedProgress by remember(progressState) { derivedStateOf { if (progressState.value == 1f) 1f else 0f } }
    val sessionPageVisible = !profilePickerVisible || pageTransition.currentState != pageTransition.targetState
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { pageWidth = it.width }) {
        pageTransition.AnimatedContent(modifier = Modifier.fillMaxSize().zIndex(1f),
            transitionSpec = {
                val direction = if (targetState) AnimatedContentTransitionScope.SlideDirection.Start
                    else AnimatedContentTransitionScope.SlideDirection.End
                ((slideIntoContainer(direction, spatial) + fadeIn(effects)) togetherWith
                    (slideOutOfContainer(direction, spatial) + fadeOut(effects))).using(null)
            },
        ) { pickingProfile ->
            if (pickingProfile) {
                val expandedHeight = LocalToolbarExpandedContentHeight.current
                // Reveal the final-sized list through the animated sheet instead of remeasuring it every frame.
                val viewport = if (expandedHeight != null) Modifier.fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true).height(expandedHeight)
                    else Modifier.fillMaxSize()
                Column(viewport) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            onDismissProfilePicker()
                        }, enabled = active && profilePickerVisible, shapes = IconButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.ic_material_symbol_arrow_back_24),
                                stringResource(R.string.back), tint = colors.appBarIcon)
                        }
                        Text(stringResource(R.string.new_session), style = MaterialTheme.typography.titleMedium,
                            color = colors.foreground)
                    }
                    ToolbarScrollEdges(profileScroll.canScrollBackward, profileScroll.canScrollForward,
                        Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.fillMaxSize(), state = profileScroll,
                            contentPadding = PaddingValues(bottom = 8.dp)) {
                            val workspace = workspaceState.workspaces.firstOrNull { it.id == workspaceState.selected }
                            workspaceLaunchItems(workspace, profiles, profileInstallationStatuses,
                                enabled = active && profilePickerVisible, accent = colors.accent,
                                foreground = colors.foreground, supporting = colors.unselectedTabContent,
                                onSelect = { launch ->
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onLaunchWorkspace(launch)
                                })
                            item {
                                Text(stringResource(if (workspace?.launches?.isNotEmpty() == true) R.string.profiles else R.string.select_profile),
                                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge, color = colors.foreground)
                            }
                            items(profiles, key = { it.id }) { profile ->
                                ListItem(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onSelectProfile(profile)
                                }, enabled = active && profilePickerVisible,
                                    leadingContent = {
                                        ProfilePickerIcon(profile, profileInstallationStatuses, tint = colors.accent)
                                    }, supportingContent = {
                                        ProfileSupportingContent(profile, profileEnvironmentNames)
                                    }, verticalAlignment = Alignment.CenterVertically,
                                    shapes = terminalListItemShapes(),
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                                        contentColor = colors.foreground, supportingContentColor = colors.unselectedTabContent,
                                        leadingContentColor = colors.accent),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(profile.name) }
                            }
                            item {
                                TextButton(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                    onAddProfile()
                                }, enabled = active && profilePickerVisible, shapes = ButtonDefaults.shapes(),
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.foreground),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.create_profile))
                                }
                            }
                        }
                    }
                }
            } else Spacer(Modifier.fillMaxSize())
        }
        // Keep the session page ready for Back, including its compact header after a handle drag.
        // While covered, observe only the expanded/compact boundary instead of every height frame.
        Column(Modifier.fillMaxSize().offset { sessionOffset.value }.graphicsLayer { alpha = sessionAlpha.value }) {
            val progress = (expansionProgress ?: if (sessionPageVisible) progressState.value else preparedProgress).coerceIn(0f, 1f)
            val tabsActive = sessionsActive && progress == 1f
            val showWorkspaceName = progress == 1f || sessions.isEmpty()
            val selectedWorkspace = workspaceState.workspaces.firstOrNull { it.id == workspaceState.selected }
            val emptyPermanentWorkspace = selectedWorkspace?.takeIf { !it.temporary && sessions.isEmpty() }
            val emptyTemporaryWorkspace = selectedWorkspace
                ?.takeIf { it.temporary && workspaceSessionStateKnown && sessions.isEmpty() &&
                    it.id !in workspaceState.memberships.values }
            // Use the sheet's spatial motion directly; a second animation would lag behind dragging.
            val headerHeight = ToolbarHotkeyViewportHeight + (48.dp - ToolbarHotkeyViewportHeight) * progress
            val headerPadding = 4.dp + 4.dp * progress
            Row(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = headerPadding),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    WorkspaceSelector(workspaceState, sessionsActive, onSelectWorkspace, onCreateWorkspace,
                        colors.appBarIcon,
                        showName = showWorkspaceName,
                        modifier = if (showWorkspaceName) Modifier.weight(1f) else Modifier)
                    if (progress < 1f && sessions.isNotEmpty()) {
                        Box(Modifier.weight(1f).height(48.dp).clipToBounds().graphicsLayer { alpha = 1f - progress }) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = sessions.indexOfFirst { it.id == activeSessionId }.coerceAtLeast(0),
                                scrollState = headerTabScroll,
                                modifier = Modifier.fillMaxSize(),
                                containerColor = colors.sheetBackground,
                                contentColor = colors.accent,
                                edgePadding = 0.dp,
                                indicator = {},
                                divider = {},
                            ) {
                                sessions.forEachIndexed { index, session ->
                                    val selected = session.id == activeSessionId
                                    Box(Modifier.widthIn(max = 220.dp).height(48.dp)) {
                                        SessionToolTab(session, selected, sessionsActive && progress == 0f,
                                            terminatingSessionIds, onSelect, onRename, onMove, onTerminate, colors, onInfo = onInfo, horizontal = true)
                                        if (index > 0 && !selected && sessions[index - 1].id != activeSessionId) {
                                            VerticalDivider(Modifier.align(Alignment.CenterStart).height(24.dp),
                                                thickness = 1.dp, color = colors.divider)
                                        }
                                    }
                                }
                            }
                            ScrollEdgeFade(headerTabScroll.canScrollBackward,
                                Brush.horizontalGradient(if (rtl) listOf(colors.sheetBackground.copy(alpha = 0f), colors.sheetBackground)
                                    else listOf(colors.sheetBackground, colors.sheetBackground.copy(alpha = 0f))),
                                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp))
                            ScrollEdgeFade(headerTabScroll.canScrollForward,
                                Brush.horizontalGradient(if (rtl) listOf(colors.sheetBackground, colors.sheetBackground.copy(alpha = 0f))
                                    else listOf(colors.sheetBackground.copy(alpha = 0f), colors.sheetBackground)),
                                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp))
                        }
                    }
                    if (emptyPermanentWorkspace != null) IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onRunWorkspaceStartups()
                    }, enabled = sessionsActive && workspaceSessionStateKnown && workspaceState.startupLaunches().isNotEmpty(),
                        shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_material_symbol_play_arrow_24),
                            stringResource(R.string.workspace_run_default_sessions), tint = colors.appBarIcon)
                    }
                    else if (progress == 1f && (sessions.isNotEmpty() || emptyTemporaryWorkspace != null)) IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        if (emptyTemporaryWorkspace != null) onDeleteEmptyTemporaryWorkspace(emptyTemporaryWorkspace.id)
                        else onCloseWorkspaceSessions()
                    }, enabled = tabsActive && (emptyTemporaryWorkspace != null || sessions.any {
                        it.id !in terminatingSessionIds && (!it.pendingLaunch || it.launchError != null)
                    }), shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(if (emptyTemporaryWorkspace != null) R.drawable.ic_material_symbol_delete_24
                            else R.drawable.ic_material_symbol_close_24),
                            if (emptyTemporaryWorkspace != null)
                                stringResource(R.string.workspace_delete_temporary_description, emptyTemporaryWorkspace.name)
                            else stringResource(R.string.workspace_close_sessions_description, workspaceFilterName(workspaceState)),
                            tint = colors.appBarIcon)
                    }
                }
                IconButton(onClick = onNew, enabled = sessionsActive, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), stringResource(R.string.new_session), tint = colors.appBarIcon)
                }
                TerminalSettingsButton(onSettings, enabled = sessionsActive, tint = colors.appBarIcon)
            }
            Spacer(Modifier.height(8.dp * progress))
            if (progress > 0f) ReorderableSessionTabs(sessions, activeSessionId, tabsActive, onReorder,
                Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = progress }, scroll = tabScroll) { session, itemModifier, dragging, lifted ->
                SessionToolTab(session, session.id == activeSessionId, tabsActive && !dragging,
                    terminatingSessionIds, onSelect, onRename, onMove, onTerminate, colors, itemModifier, lifted, onInfo = onInfo)
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
    onMove: (SessionUi) -> Unit,
    onTerminate: (SessionUi) -> Unit,
    colors: TerminalChromeColors,
    itemModifier: Modifier = Modifier,
    lifted: Boolean = false,
    horizontal: Boolean = false,
    onInfo: (SessionUi) -> Unit = {},
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
    val menuLabel = stringResource(R.string.workspace_session_menu, sessionTitle)
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val menuEnabled = enabled && !session.pendingLaunch && session.id !in terminatingSessionIds
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
        onLongClickLabel = menuLabel,
        onLongClick = if (!menuEnabled) null else ({
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menuExpanded = true
        }),
        onClick = selectSession,
    ) else Modifier
    Tab(
        selected = selected,
        enabled = enabled,
        onClick = selectSession,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(48.dp)
            .then(itemModifier).clip(RoundedCornerShape(cornerRadius))
            .background(if (selected) colors.selectedTabContainer else if (lifted) colors.container else Color.Transparent)
            .semantics { stateDescription = stateLabel },
        selectedContentColor = colors.selectedTabContent,
        unselectedContentColor = colors.unselectedTabContent,
        interactionSource = tabInteractionSource,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (horizontal) 48.dp else 40.dp)
                    .then(bodyGestures).padding(start = 12.dp, end = if (horizontal && !selected) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ProfileIcon(session.launchSpec?.icon ?: "auto", session.runtime,
                        contentDescription = stringResource(runtimeTitleResource(session.runtime)),
                        size = 20.dp, tint = if (selected) colors.selectedTabContent else colors.unselectedTabContent)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium)
                        session.subtitle?.let { subtitle ->
                            Text(subtitle.ifBlank { stringResource(R.string.shell_default_title) },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                color = (if (selected) colors.selectedTabSupporting else colors.unselectedTabContent)
                                    .copy(alpha = if (colors.colorScheme != null) 1f else 0.75f))
                        }
                    }
                }
                if (horizontal) SessionWorkspaceMenu(
                    expanded = menuExpanded && menuEnabled,
                    onRename = { menuExpanded = false; onRename(session) },
                    onMove = { menuExpanded = false; onMove(session) },
                    onInfo = { menuExpanded = false; onInfo(session) },
                    onDismiss = { menuExpanded = false },
                )
            }
            if (selected && !horizontal) Box {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        menuExpanded = true
                    },
                    shape = CircleShape,
                    enabled = menuEnabled,
                ) {
                    Icon(painterResource(R.drawable.ic_material_symbol_more_vert_24), menuLabel)
                }
                SessionWorkspaceMenu(
                    expanded = menuExpanded && menuEnabled,
                    onRename = { menuExpanded = false; onRename(session) },
                    onMove = { menuExpanded = false; onMove(session) },
                    onInfo = { menuExpanded = false; onInfo(session) },
                    onDismiss = { menuExpanded = false },
                )
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
