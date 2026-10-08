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
import androidx.compose.foundation.horizontalScroll
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
                            item {
                                Text(stringResource(R.string.select_profile),
                                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge, color = colors.foreground)
                            }
                            items(profiles, key = { it.id }) { profile ->
                                ListItem(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onSelectProfile(profile)
                                }, enabled = active && profilePickerVisible,
                                    leadingContent = {
                                        ProfilePickerIcon(profile, profileInstallationStatuses, tint = colors.accent,
                                            badgeContentColor = colors.background)
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
            // Use the sheet's spatial motion directly; a second animation would lag behind dragging.
            val headerHeight = ToolbarHotkeyViewportHeight + (48.dp - ToolbarHotkeyViewportHeight) * progress
            val headerPadding = 4.dp + 4.dp * progress
            Row(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = headerPadding),
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
                                edgePadding = 0.dp,
                                indicator = {},
                                divider = {},
                            ) {
                                sessions.forEachIndexed { index, session ->
                                    val selected = session.id == activeSessionId
                                    Box(Modifier.widthIn(max = 220.dp).height(48.dp)) {
                                        SessionToolTab(session, selected, sessionsActive && progress == 0f,
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
                IconButton(onClick = onNew, enabled = sessionsActive, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), stringResource(R.string.new_session), tint = colors.appBarIcon)
                }
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onSettings()
                }, enabled = sessionsActive, shapes = IconButtonDefaults.shapes()) {
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
            style = MaterialTheme.typography.titleMedium, color = colors.foreground)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            var first = true
            sessionSummaryStates.forEach { state ->
                counts[state]?.let { count ->
                    if (!first) Text(" · ", style = MaterialTheme.typography.bodySmall,
                        color = colors.foreground.copy(alpha = 0.75f))
                    Text(stringResource(R.string.toolbar_session_state_count, sessionStateLabel(state), count),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
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
