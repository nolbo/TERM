@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffset
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
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
    paneState: TerminalPaneState = TerminalPaneState(),
    canSplit: (SessionUi) -> Boolean = { false },
    onSplit: (SessionUi) -> Unit = {},
    canPair: (String, String) -> Boolean = { _, _ -> false },
    onPair: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onUnsplit: () -> Unit = {},
    onSplitDirection: (SplitDirection) -> Unit = {},
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
            // IME/anchor updates can briefly lower progress before the Expanded mode is published.
            // Keep near-expanded drags eligible through that geometry change.
            val tabsDraggable = sessionsActive && (progress >= 0.95f || LocalToolbarExpandedContentHeight.current != null)
            val showWorkspaceName = progress == 1f || sessions.isEmpty()
            val selectedWorkspace = workspaceState.workspaces.firstOrNull { it.id == workspaceState.selected }
            val emptyPermanentWorkspace = selectedWorkspace?.takeIf { !it.temporary && sessions.isEmpty() }
            val emptyTemporaryWorkspace = selectedWorkspace
                ?.takeIf { it.temporary && workspaceSessionStateKnown && sessions.isEmpty() &&
                    it.id !in workspaceState.memberships.values }
            // Use the sheet's spatial motion directly; a second animation would lag behind dragging.
            val headerHeight = ToolbarHotkeyViewportHeight + (48.dp - ToolbarHotkeyViewportHeight) * progress
            val headerPadding = 4.dp + 4.dp * progress
            // Isolate the header's changing tab/Workspace structure from the body slots.
            // Otherwise the gap-buffer composer can count the header twice when the picker closes.
            key(sessions.map { it.id }) { Row(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = headerPadding),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    WorkspaceSelector(workspaceState, sessionsActive, onSelectWorkspace, onCreateWorkspace,
                        colors.appBarIcon,
                        showName = showWorkspaceName,
                        modifier = if (showWorkspaceName) Modifier.weight(1f) else Modifier)
                    if (progress < 1f && sessions.isNotEmpty()) {
                        Box(Modifier.weight(1f).height(48.dp).clipToBounds().graphicsLayer { alpha = 1f - progress }) {
                            CompactSessionTabs(sessions, activeSessionId, paneState.splitSessionIds,
                                headerTabScroll, colors, Modifier.fillMaxSize()) { session, segment, expansion ->
                                SessionToolTab(session, session.id == activeSessionId, sessionsActive && progress == 0f,
                                    terminatingSessionIds, onSelect, onRename, onMove, onTerminate, colors, onInfo = onInfo, horizontal = true,
                                    paneState = paneState, canSplit = canSplit(session), onSplit = onSplit,
                                    onUnsplit = onUnsplit, onSplitDirection = onSplitDirection, groupedPosition = segment,
                                    compactExpansion = expansion, inactiveSplitGroup = !paneState.split && segment != null)
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
            } }
            Spacer(Modifier.height(8.dp * progress))
            if (progress > 0f) ReorderableSessionTabs(sessions, activeSessionId, tabsDraggable, onReorder,
                Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = progress }, scroll = tabScroll,
                paneState = paneState, canPair = canPair, onPair = onPair, colors = colors,
                splitActions = { modifier, enabled, inactiveGroup ->
                    sessions.firstOrNull { it.id == paneState.splitSessionIds.getOrNull(paneState.focus) }?.let { session ->
                        SplitTabActions(session, modifier, enabled && tabsActive && session.id !in terminatingSessionIds,
                            colors, paneState, onRename, onMove, onInfo, onSplit, onUnsplit, onSplitDirection, inactiveGroup)
                    }
                }) { session, itemModifier, dragging, lifted, segment, inactiveGroup ->
                SessionToolTab(session, session.id == activeSessionId, tabsActive && !dragging,
                    terminatingSessionIds, onSelect, onRename, onMove, onTerminate, colors, itemModifier, lifted, onInfo = onInfo,
                    paneState = paneState, canSplit = canSplit(session), onSplit = onSplit,
                    onUnsplit = onUnsplit, onSplitDirection = onSplitDirection,
                    groupedPosition = segment,
                    inactiveSplitGroup = inactiveGroup)
            }
        }
    }
}

@Composable
private fun CompactSessionTabs(sessions: List<SessionUi>, activeSessionId: String?, pair: List<String>,
    scroll: ScrollState, colors: TerminalChromeColors,
    modifier: Modifier, content: @Composable (SessionUi, Int?, Float?) -> Unit) {
    val rows = sessionTabRows(sessions.map { it.id }, pair)
    val byId = sessions.associateBy { it.id }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val listGesture = remember(scroll, rtl) {
        object : NestedScrollConnection {
            private var startedForward = false
            private var startedBackward = false
            fun start() {
                startedForward = scroll.canScrollForward
                startedBackward = scroll.canScrollBackward
            }
            private fun keepInList(x: Float): Boolean = when {
                x == 0f -> false
                (x > 0f) == rtl -> startedForward
                else -> startedBackward
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (keepInList(available.x)) Offset(available.x, 0f) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (keepInList(available.x)) Velocity(available.x, 0f) else Velocity.Zero
        }
    }
    BoxWithConstraints(modifier) {
        // Resolve the width in this measurement, before ScrollState can clamp to a stale minimum.
        val pairWidth = if (activeSessionId in pair) maxWidth.coerceIn(220.dp, 278.dp) else 106.dp
        // Finish scrolling the list before handing a fresh edge gesture to the tool pager.
        Row(Modifier.fillMaxSize().pointerInput(listGesture) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                listGesture.start()
            }
        }.nestedScroll(listGesture).horizontalScroll(scroll).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            rows.forEachIndexed { index, row -> key(row) {
                Box(Modifier.height(48.dp)) {
                    if (row.size == 2) {
                        val firstExpansion by animateFloatAsState(if (activeSessionId == row.first()) 1f else 0f,
                            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "Compact split tab focus")
                        val focusedPair = activeSessionId in row
                        val dividerAlpha by animateFloatAsState(if (focusedPair) 0f else 1f,
                            MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Compact split tab divider")
                        val fraction = if (focusedPair) firstExpansion.coerceIn(0f, 1f) else 0f
                        // One fraction and one remaining-width slot keep the group's width fixed.
                        val firstWidth = 48.dp + (pairWidth - 106.dp) * fraction
                        Box(Modifier.width(pairWidth).padding(horizontal = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                row.forEachIndexed { segment, id ->
                                    val size = if (segment == 0) Modifier.width(firstWidth) else Modifier.weight(1f)
                                    Box(size.height(48.dp)) {
                                        content(byId.getValue(id), segment,
                                            if (!focusedPair) 0f else if (segment == 0) fraction else 1f - fraction)
                                    }
                                }
                            }
                            SplitTabDivider(Modifier.offset(x = firstWidth).width(2.dp).height(48.dp), colors, dividerAlpha)
                        }
                    } else {
                        val id = row.single()
                        Box(Modifier.widthIn(max = 220.dp).height(48.dp)) {
                            content(byId.getValue(id), null, null)
                        }
                    }
                    if (index > 0 && activeSessionId !in row && activeSessionId !in rows[index - 1]) {
                        VerticalDivider(Modifier.align(Alignment.CenterStart).height(24.dp),
                            thickness = 1.dp, color = colors.divider)
                    }
                }
            } }
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
    paneState: TerminalPaneState = TerminalPaneState(),
    canSplit: Boolean = false,
    onSplit: (SessionUi) -> Unit = {},
    onUnsplit: () -> Unit = {},
    onSplitDirection: (SplitDirection) -> Unit = {},
    groupedPosition: Int? = null,
    compactExpansion: Float? = null,
    inactiveSplitGroup: Boolean = false,
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
    val iconOnly = horizontal && groupedPosition != null && !selected
    val expansion = compactExpansion ?: 1f
    val contentsAlpha = if (compactExpansion != null) animateFloatAsState(if (iconOnly) 0f else 1f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Compact split tab contents").value else 1f
    val stateLabel = sessionStateLabel(session.state)
    val shownInOtherPane = !selected && session.id in paneState.sessionIds && paneState.split
    val splitStateLabel = stringResource(R.string.terminal_split_other_pane)
    val menuLabel = stringResource(R.string.workspace_session_menu, sessionTitle)
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val menuEnabled = enabled && !session.pendingLaunch && session.id !in terminatingSessionIds
    val selectSession = {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        onSelect(session.id)
    }
    // The body owns both gestures so releasing a long press cannot also select the tab.
    // Share the tab's pressed state and ripple; keep the close button outside this target.
    val bodyGestures = Modifier.combinedClickable(
        interactionSource = tabInteractionSource,
        indication = null,
        enabled = enabled,
        hapticFeedbackEnabled = false,
        onLongClickLabel = menuLabel,
        onLongClick = if (!horizontal || !menuEnabled) null else ({
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menuExpanded = true
        }),
        onClick = selectSession,
    ).semantics {
        if (iconOnly) contentDescription = sessionTitle
        // Expanded touch long presses belong to the drag recognizer; retain the menu for accessibility.
        if (!horizontal && menuEnabled) onLongClick(menuLabel) { menuExpanded = true; true }
    }
    val startMorph by animateFloatAsState(if (groupedPosition == null || groupedPosition == 0) 0f else 1f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Session tab start corners")
    val endMorph by animateFloatAsState(if (groupedPosition == null ||
        (groupedPosition == 1 && (horizontal || inactiveSplitGroup))) 0f else 1f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Session tab end corners")
    val innerRadius by animateDpAsState(if (inactiveSplitGroup) 0.dp else 4.dp,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab inner corners")
    val startRadius = cornerRadius + (innerRadius - cornerRadius) * startMorph
    val endRadius = cornerRadius + (innerRadius - cornerRadius) * endMorph
    val shape = RoundedCornerShape(topStart = startRadius, topEnd = endRadius, bottomStart = startRadius, bottomEnd = endRadius)
    val activeSplitGroup = groupedPosition != null && paneState.split && session.id in paneState.splitSessionIds
    val unfocusedContent = if (activeSplitGroup) colors.unfocusedSplitTabContent else colors.unselectedTabContent
    val targetContainer = when {
        selected || activeSplitGroup -> colors.selectedTabContainer
        lifted -> colors.container
        groupedPosition != null -> colors.sheetBackground
        else -> Color.Transparent
    }
    val animatedContainer = if (groupedPosition != null) animateColorAsState(targetContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab background").value else targetContainer
    val targetForeground = if (selected) colors.selectedTabContent else unfocusedContent
    val animatedForeground = if (groupedPosition != null) animateColorAsState(targetForeground,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab foreground").value else targetForeground
    Tab(
        selected = selected,
        enabled = enabled,
        onClick = selectSession,
        modifier = itemModifier.fillMaxWidth().padding(horizontal = if (groupedPosition == null) 4.dp else 0.dp).height(48.dp)
            .clip(shape)
            .background(animatedContainer)
            .semantics { stateDescription = if (shownInOtherPane) "$stateLabel, $splitStateLabel" else stateLabel },
        selectedContentColor = colors.selectedTabContent,
        unselectedContentColor = unfocusedContent,
        interactionSource = tabInteractionSource,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (horizontal) 48.dp else 40.dp)
                    .then(bodyGestures).padding(
                        start = if (compactExpansion != null) 14.dp - 2.dp * expansion else 12.dp,
                        end = if (compactExpansion != null) 14.dp * (1f - expansion) else if (horizontal && !selected) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start) {
                    ProfileIcon(session.launchSpec?.icon ?: "auto", session.runtime,
                        contentDescription = if (iconOnly) null else stringResource(runtimeTitleResource(session.runtime)),
                        size = 20.dp, tint = animatedForeground)
                    if (!iconOnly || expansion > 0f || contentsAlpha > 0f) {
                        Spacer(Modifier.width(8.dp * expansion))
                        Column(Modifier.weight(1f).clipToBounds().graphicsLayer {
                            alpha = if (compactExpansion != null) contentsAlpha else 1f
                        }.then(if (iconOnly) Modifier.clearAndSetSemantics { } else Modifier)) {
                            Text(sessionTitle, color = animatedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                            session.subtitle?.let { subtitle ->
                                Text(subtitle.ifBlank { stringResource(R.string.shell_default_title) },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                    color = (if (selected) colors.selectedTabSupporting else unfocusedContent)
                                        .copy(alpha = if (colors.colorScheme != null || activeSplitGroup) 1f else 0.75f))
                            }
                        }
                    }
                }
                if (horizontal || !selected || groupedPosition != null) SessionWorkspaceMenu(
                    expanded = menuExpanded && menuEnabled,
                    onRename = { menuExpanded = false; onRename(session) },
                    onMove = { menuExpanded = false; onMove(session) },
                    onInfo = { menuExpanded = false; onInfo(session) },
                    onDismiss = { menuExpanded = false },
                    splitContent = { SplitSessionMenu(session, paneState, canSplit,
                        onSplit = { menuExpanded = false; onSplit(session) },
                        onUnsplit = { menuExpanded = false; onUnsplit() },
                        onDirection = { menuExpanded = false; onSplitDirection(it) }) },
                )
            }
            if (selected && !horizontal && groupedPosition == null) Box {
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
                    splitContent = { SplitSessionMenu(session, paneState, canSplit,
                        onSplit = { menuExpanded = false; onSplit(session) },
                        onUnsplit = { menuExpanded = false; onUnsplit() },
                        onDirection = { menuExpanded = false; onSplitDirection(it) }) },
                )
            }
            if (!horizontal || selected || (compactExpansion != null && expansion > 0f)) Box(
                if (compactExpansion != null) Modifier.width(48.dp * expansion).clipToBounds().graphicsLayer {
                    alpha = contentsAlpha
                }.then(if (!selected) Modifier.clearAndSetSemantics { } else Modifier) else Modifier,
                contentAlignment = Alignment.CenterEnd,
            ) { IconButton(
                modifier = if (compactExpansion != null) Modifier.requiredSize(48.dp) else Modifier,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onTerminate(session)
                },
                shape = CircleShape,
                enabled = enabled && (compactExpansion == null || selected) && session.id !in terminatingSessionIds &&
                    (!session.pendingLaunch || session.launchError != null),
            ) {
                Icon(painterResource(R.drawable.ic_material_symbol_close_24), stringResource(R.string.terminate_session_description, sessionTitle))
            } }
        }
    }
}

@Composable
private fun SplitTabActions(session: SessionUi, modifier: Modifier, enabled: Boolean,
    colors: TerminalChromeColors, state: TerminalPaneState, onRename: (SessionUi) -> Unit,
    onMove: (SessionUi) -> Unit, onInfo: (SessionUi) -> Unit, onSplit: (SessionUi) -> Unit,
    onUnsplit: () -> Unit, onDirection: (SplitDirection) -> Unit, inactiveSplitGroup: Boolean) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val label = stringResource(R.string.workspace_session_menu, session.title.ifBlank { stringResource(R.string.shell_default_title) })
    val haptics = LocalTerminalHaptics.current
    val innerRadius by animateDpAsState(if (inactiveSplitGroup) 0.dp else 4.dp,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab menu inner corners")
    val shape = RoundedCornerShape(topStart = innerRadius, topEnd = 20.dp, bottomStart = innerRadius, bottomEnd = 20.dp)
    val container by animateColorAsState(if (state.split) colors.selectedTabContainer else colors.sheetBackground,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab menu background")
    val foreground by animateColorAsState(if (state.split) colors.unfocusedSplitTabContent else colors.unselectedTabContent,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Split tab menu foreground")
    Box(modifier.clip(shape).background(container)) {
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); expanded = true },
            modifier = Modifier.fillMaxSize(), enabled = enabled, shape = CircleShape) {
            Icon(painterResource(R.drawable.ic_material_symbol_more_vert_24), label, tint = foreground)
        }
        SessionWorkspaceMenu(expanded = expanded && enabled,
            onRename = { expanded = false; onRename(session) }, onMove = { expanded = false; onMove(session) },
            onInfo = { expanded = false; onInfo(session) }, onDismiss = { expanded = false },
            splitContent = { SplitSessionMenu(session, state, false,
                onSplit = { expanded = false; onSplit(session) },
                onUnsplit = { expanded = false; onUnsplit() },
                onDirection = { expanded = false; onDirection(it) }) })
    }
}

@Composable
private fun SplitSessionMenu(session: SessionUi, state: TerminalPaneState, canSplit: Boolean,
    onSplit: () -> Unit, onUnsplit: () -> Unit, onDirection: (SplitDirection) -> Unit) {
    val showing = session.id in state.splitSessionIds
    DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2), modifier = Modifier.fillMaxWidth()) {
        if (showing) {
            SplitPaneActions(state.direction, onDirection, onUnsplit)
        } else {
            DropdownMenuItem(shape = MaterialTheme.shapes.extraLarge,
                text = { Text(stringResource(R.string.terminal_open_split)) }, enabled = canSplit,
                leadingIcon = { Icon(painterResource(R.drawable.ic_terminal_split), null,
                    Modifier.size(MenuDefaults.LeadingIconSize)) }, onClick = onSplit)
        }
    }
}

@Composable
internal fun SplitPaneActions(selectedDirection: SplitDirection, onDirection: (SplitDirection) -> Unit,
    onUnsplit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        SplitDirection.entries.forEach { direction ->
            val label = stringResource(if (direction == SplitDirection.SideBySide)
                R.string.terminal_split_side_by_side else R.string.terminal_split_stacked)
            SplitMenuTooltip(label) {
                IconToggleButton(checked = selectedDirection == direction,
                    onCheckedChange = { onDirection(direction) }, modifier = Modifier.size(48.dp),
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Icon(painterResource(R.drawable.ic_terminal_split), label,
                        Modifier.size(24.dp).rotate(if (direction == SplitDirection.Stacked) 90f else 0f))
                }
            }
        }
        val unsplitLabel = stringResource(R.string.terminal_unsplit)
        SplitMenuTooltip(unsplitLabel) {
            IconButton(onClick = onUnsplit, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_terminal_single), unsplitLabel, Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitMenuTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(), content = content)
}
