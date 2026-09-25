@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.terminal.app.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal val TerminalDarkColors = darkColorScheme(
    primary = Color(0xFFF1F3F5),
    onPrimary = Color(0xFF111418),
    primaryContainer = Color(0xFF30343A),
    onPrimaryContainer = Color(0xFFF1F3F5),
    inversePrimary = Color(0xFF5F6368),
    secondary = Color(0xFFC5C7CC),
    onSecondary = Color(0xFF2E3035),
    secondaryContainer = Color(0xFF44474D),
    onSecondaryContainer = Color(0xFFE1E2E7),
    tertiary = Color(0xFFADC6FF),
    onTertiary = Color(0xFF002E69),
    tertiaryContainer = Color(0xFF234573),
    onTertiaryContainer = Color(0xFFD7E3FF),
    background = Color(0xFF0C0E11),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF0C0E11),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF45474D),
    onSurfaceVariant = Color(0xFFC5C6CC),
    surfaceTint = Color(0xFFF1F3F5),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF303034),
    surfaceContainer = Color(0xFF181C21),
    surfaceContainerHigh = Color(0xFF22272D),
    surfaceContainerHighest = Color(0xFF2D3238),
    surfaceContainerLow = Color(0xFF13171B),
    surfaceContainerLowest = Color(0xFF070A0D),
    surfaceBright = Color(0xFF353A40),
    surfaceDim = Color(0xFF0C0E11),
    outline = Color(0xFF8F9096),
    outlineVariant = Color(0xFF45474D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/** Blue is reserved for active, available, ready, and in-progress semantic state. */
internal val TerminalStatusBlue = Color(0xFF8AB4F8)
internal val TerminalStatusBlueContainer = Color(0xFF193B68)
internal val TerminalOnStatusBlueContainer = Color(0xFFD7E3FF)
internal val TerminalSessionCreated = Color(0xFFFFD166)
internal val TerminalSessionExited = Color(0xFFFFB4AB)
internal val TerminalSessionUnknown = Color(0xFF8F9096)

internal fun sessionStateBadgeColor(state: String): Color = when (state) {
    "CREATED" -> TerminalSessionCreated
    "RUNNING" -> TerminalStatusBlue
    "EXITED" -> TerminalSessionExited
    else -> TerminalSessionUnknown
}

/**
 * Keeps list surfaces visibly rounded at rest while retaining Material Expressive's
 * state-specific shapes for press, focus, hover, selection, and drag transitions.
 */
@Composable
internal fun terminalListItemShapes(): ListItemShapes = ListItemDefaults.shapes(
    shape = MaterialTheme.shapes.extraLarge,
)

@Composable
fun DroidTheme(
    systemBarBackground: Color? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = systemBarBackground?.let { TerminalChromeColors(it).darkSystemIcons } ?: false
            isAppearanceLightNavigationBars = systemBarBackground?.let { TerminalChromeColors(it).darkSystemIcons } ?: false
        }
    }
    MaterialExpressiveTheme(
        colorScheme = TerminalDarkColors,
        motionScheme = MotionScheme.expressive(),
        typography = Typography(),
        shapes = Shapes(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.ic_material_symbol_arrow_back_24), stringResource(R.string.back))
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            painterResource(R.drawable.ic_material_symbol_arrow_back_24),
                            stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

private data class SetupRuntime(
    val title: Int,
    val label: Int,
    val description: Int,
    @param:DrawableRes val icon: Int,
)

@Composable
fun SetupScreen(onStart: () -> Unit, onImage: () -> Unit) {
    val runtimes = listOf(
        SetupRuntime(R.string.runtime_native_title, R.string.runtime_native_setup, R.string.runtime_native_description, R.drawable.ic_material_symbol_terminal_24),
        SetupRuntime(R.string.runtime_rootless_title, R.string.runtime_rootless_setup, R.string.runtime_rootless_description, R.drawable.ic_material_symbol_download_24),
        SetupRuntime(R.string.runtime_avf_title, R.string.runtime_avf_setup, R.string.runtime_avf_description, R.drawable.ic_material_symbol_memory_24),
    )
    AppScaffold(stringResource(R.string.app_name)) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.setup_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.setup_headline), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
            }
            items(runtimes) { runtime ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(painterResource(runtime.icon), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(runtime.label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(runtime.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(runtime.description), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { Button(onClick = onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.start_terminal)) } }
            item { OutlinedButton(onClick = onImage, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.select_avf_image)) } }
            item { Text(stringResource(R.string.avf_image_note), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

data class SessionUi(
    val id: String,
    val runtime: String,
    val title: String,
    val state: String,
    val exitCode: Int? = null,
    val hasExplicitTabTitle: Boolean = false,
    val initialTitle: String = title,
    val subtitle: String? = null,
    val profileId: String? = null,
    val launchSpec: dev.terminal.runtime.session.SessionLaunchSpec? = null,
)

data class RuntimeChoiceUi(
    val id: String, val available: Boolean, val detail: String, val installable: Boolean = false,
    val environmentRequired: Boolean = false, val state: String = "", val progress: RootlessProgressUi? = null,
)

@DrawableRes
internal fun runtimeTabIcon(runtimeId: String): Int = when (runtimeId) {
    "NATIVE" -> R.drawable.ic_runtime_native
    "ROOTLESS" -> R.drawable.ic_runtime_debian
    "ROOTLESS_UBUNTU" -> R.drawable.ic_runtime_ubuntu
    "ROOTLESS_ARCH" -> R.drawable.ic_runtime_arch
    "AVF" -> R.drawable.ic_material_symbol_memory_24
    else -> R.drawable.ic_material_symbol_terminal_24
}

internal fun runtimeTitleResource(runtimeId: String): Int = when (runtimeId) {
    "ROOTLESS" -> R.string.runtime_debian_title
    "ROOTLESS_UBUNTU" -> R.string.runtime_ubuntu_title
    "ROOTLESS_ARCH" -> R.string.runtime_arch_title
    "AVF" -> R.string.runtime_avf_title
    else -> R.string.runtime_native_title
}

@DrawableRes
private fun runtimeStatusIcon(choice: RuntimeChoiceUi): Int = when {
    choice.id == ROOTLESS_GROUP -> R.drawable.ic_runtime_rootless_group
    choice.available || choice.environmentRequired -> runtimeTabIcon(choice.id)
    choice.installable -> R.drawable.ic_material_symbol_download_24
    else -> R.drawable.ic_material_symbol_block_24
}

@Composable
internal fun RuntimeEnvironmentIcon(
    runtimeId: String,
    environmentRequired: Boolean,
    @DrawableRes drawable: Int = runtimeTabIcon(runtimeId),
    tint: Color = TerminalStatusBlue,
) {
    val warning = stringResource(R.string.rootless_environment_required)
    Box(Modifier.size(32.dp)) {
        Icon(painterResource(drawable), null, Modifier.size(24.dp).align(Alignment.Center), tint = tint)
        if (environmentRequired) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(16.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .clearAndSetSemantics { contentDescription = warning },
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = MaterialTheme.colorScheme.onError, fontSize = 12.sp,
                    lineHeight = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RuntimeChoiceIcon(choice: RuntimeChoiceUi) {
    RuntimeEnvironmentIcon(choice.id, choice.environmentRequired, runtimeStatusIcon(choice),
        if (choice.available || choice.id in rootlessRuntimeIds) TerminalStatusBlue else MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalChrome(
    sessions: List<SessionUi>,
    activeSessionId: String?,
    appBarContentVisible: Boolean,
    terminalBackgroundColor: Color,
    topInsets: WindowInsets,
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
    colors: TerminalChromeColors = TerminalChromeColors(terminalBackgroundColor),
) {
    val selectedTabIndex = sessions.indexOfFirst { it.id == activeSessionId }
    val layoutDirection = LocalLayoutDirection.current
    val haptics = LocalTerminalHaptics.current
    Surface(
        modifier = Modifier.zIndex(1f),
        color = terminalBackgroundColor,
        contentColor = colors.foreground,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = appBarContentVisible,
                    modifier = Modifier.fillMaxWidth(),
                    enter = expandVertically(
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            expandFrom = Alignment.Top,
                        ) + slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it } + fadeIn(
                            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        ),
                    exit = shrinkVertically(
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            shrinkTowards = Alignment.Top,
                        ) + slideOutVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it } + fadeOut(
                            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(topInsets).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f).padding(vertical = 4.dp).height(40.dp)) {
                            if (sessions.isEmpty()) {
                                Spacer(Modifier.fillMaxWidth().height(40.dp))
                            } else {
                        PrimaryScrollableTabRow(
                                    selectedTabIndex = selectedTabIndex.coerceAtLeast(0),
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    containerColor = terminalBackgroundColor,
                                    contentColor = colors.accent,
                                    edgePadding = 6.dp,
                                    indicator = {},
                                    divider = {},
                                ) {
                            sessions.forEachIndexed { index, session ->
                        val selected = index == selectedTabIndex
                        val sessionTitle = session.title.ifBlank { stringResource(R.string.shell_default_title) }
                        val stateLabel = sessionStateLabel(session.state)
                        val renameLabel = stringResource(R.string.rename_session_description, sessionTitle)
                        Box(Modifier.widthIn(max = 220.dp).height(40.dp)) {
                        Tab(
                            selected = selected,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                onSelect(session.id)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .height(40.dp)
                                .clip(CircleShape)
                                .background(if (selected) colors.container else Color.Transparent)
                                .semantics {
                                    stateDescription = stateLabel
                                    onLongClick(label = renameLabel) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onRename(session)
                                        true
                                    }
                                },
                            selectedContentColor = colors.accent,
                            unselectedContentColor = colors.unselectedTabContent,
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp)
                                        .padding(start = 12.dp, end = if (selected) 0.dp else 12.dp)
                                        .pointerInput(session.id, session.title) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(
                                                    requireUnconsumed = false,
                                                    pass = PointerEventPass.Initial,
                                                )
                                                val releasedOrMoved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                    while (true) {
                                                        val change = awaitPointerEvent(PointerEventPass.Initial)
                                                            .changes
                                                            .firstOrNull { it.id == down.id }
                                                            ?: return@withTimeoutOrNull true
                                                        if (!change.pressed ||
                                                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                                        ) {
                                                            return@withTimeoutOrNull true
                                                        }
                                                    }
                                                }
                                                if (releasedOrMoved == null) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onRename(session)
                                                    do {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        event.changes.forEach { it.consume() }
                                                    } while (event.changes.any { it.pressed })
                                                }
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProfileIcon(
                                        session.launchSpec?.icon ?: "auto", session.runtime,
                                        contentDescription = stringResource(runtimeTitleResource(session.runtime)),
                                        size = 20.dp,
                                        tint = if (selected) colors.accent else colors.unselectedTabContent,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                        session.subtitle?.let { subtitle ->
                                            Text(
                                                subtitle.ifBlank { stringResource(R.string.shell_default_title) },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (selected) colors.onContainer else colors.unselectedTabContent,
                                            )
                                        }
                                    }
                                }
                                if (selected) IconButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        onTerminate(session)
                                    },
                                    shapes = IconButtonDefaults.shapes(),
                                    enabled = session.id !in terminatingSessionIds,
                                ) {
                                    Icon(painterResource(R.drawable.ic_material_symbol_close_24), stringResource(R.string.terminate_session_description, sessionTitle))
                                }
                            }
                        }
                        // Only adjacent inactive tabs need a separator; the capsule separates the active tab.
                        if (!selected && index < sessions.lastIndex && index + 1 != selectedTabIndex) {
                            VerticalDivider(
                                modifier = Modifier.align(Alignment.CenterEnd).height(18.dp),
                                color = colors.divider,
                            )
                        }
                        }
                            }
                        }
                    }
                            // Fade scrolling tabs into the fixed new-session button's background.
                            Spacer(
                                Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight()
                                    .background(Brush.horizontalGradient(
                                        listOf(terminalBackgroundColor.copy(alpha = 0f), terminalBackgroundColor)
                                            .let { if (layoutDirection == LayoutDirection.Rtl) it.reversed() else it },
                                    )),
                            )
                }
                Box {
                IconButton(onClick = onNew, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), stringResource(R.string.new_session))
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
            }, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_material_symbol_settings_24), stringResource(R.string.settings))
            }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(profiles: List<dev.terminal.runtime.session.SessionProfile>,
    onSelectProfile: (dev.terminal.runtime.session.SessionProfile) -> Unit,
    onAddProfile: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val haptics = LocalTerminalHaptics.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(stringResource(R.string.profiles),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge)
            }
            items(profiles.size) { index ->
                val profile = profiles[index]
                ListItem(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch { sheetState.hide(); onSelectProfile(profile) }
                    },
                    shapes = terminalListItemShapes(),
                    leadingContent = { Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        ProfileIcon(profile.icon, profile.runtime.wireName, tint = TerminalStatusBlue)
                    } },
                    supportingContent = { Text(stringResource(runtimeTitleResource(profile.runtime.wireName))) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(profile.name) }
            }
            item {
                ListItem(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        scope.launch { sheetState.hide(); onAddProfile() }
                    },
                    shapes = terminalListItemShapes(),
                    leadingContent = {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_material_symbol_add_24), null, Modifier.size(24.dp))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.add_profile)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeSelectionSheet(
    runtimeChoices: List<RuntimeChoiceUi>,
    distributionStage: Boolean,
    onSelectRuntime: (RuntimeChoiceUi) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val haptics = LocalTerminalHaptics.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            if (distributionStage) TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                scope.launch { sheetState.hide(); onBack() }
            }, shapes = ButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_material_symbol_arrow_back_24), null)
                Text(stringResource(R.string.select_runtime))
            }
            Text(
                stringResource(if (distributionStage) R.string.select_distribution else R.string.select_runtime),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            runtimeChoices.forEach { choice ->
                val title = stringResource(if (choice.id == ROOTLESS_GROUP) R.string.new_session_rootless_group else runtimeTitleResource(choice.id))
                val statusDescription = stringResource(
                    when {
                        choice.environmentRequired -> R.string.rootless_environment_required
                        choice.available -> R.string.runtime_available
                        else -> R.string.runtime_installable
                    },
                )
                ListItem(
                    onClick = {
                        haptics.performHapticFeedback(
                            if (choice.id == ROOTLESS_GROUP) HapticFeedbackType.KeyboardTap else HapticFeedbackType.Confirm,
                        )
                        scope.launch {
                            sheetState.hide()
                            onSelectRuntime(choice)
                        }
                    },
                    shapes = terminalListItemShapes(),
                    leadingContent = {
                        RuntimeChoiceIcon(choice)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {
                            contentDescription = "$statusDescription, $title"
                            role = Role.Button
                        },
                    trailingContent = {
                        if (choice.id == ROOTLESS_GROUP) Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null)
                    },
                ) { Text(title) }
            }
        }
    }
}

@Composable
fun RuntimeUnavailableDialog(
    choice: RuntimeChoiceUi,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(runtimeTitleResource(choice.id))
    val action = runtimeSelectionAction(choice)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (action == RuntimeSelectionAction.INSTALL) {
                    stringResource(R.string.rootless_install_question, title)
                } else {
                    stringResource(R.string.runtime_settings_question, title)
                },
            )
        },
        text = { Text(choice.detail) },
        confirmButton = {
            Button(onClick = if (action == RuntimeSelectionAction.INSTALL) onInstall else onOpenSettings, shapes = ButtonDefaults.shapes()) {
                Text(
                    stringResource(
                        if (action == RuntimeSelectionAction.INSTALL) R.string.rootless_prepare else R.string.open_settings,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun RenameSessionDialog(
    session: SessionUi,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialTitle = if (session.hasExplicitTabTitle) session.title else ""
    var draft by rememberSaveable(session.id) { mutableStateOf(initialTitle) }
    val validated = validatedSessionTitle(draft)
    val showError = draft.isNotEmpty() && validated == null
    val focusRequester = remember(session.id) { FocusRequester() }
    val haptics = LocalTerminalHaptics.current
    val submit: () -> Unit = {
        validated?.let {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onRename(it)
        }
        Unit
    }

    LaunchedEffect(session.id) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_session_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(256) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.rename_session_label)) },
                    isError = showError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (showError) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.rename_session_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = submit, shapes = ButtonDefaults.shapes(), enabled = validated != null) {
                Text(stringResource(R.string.rename_session_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TerminalEmptyState(
    runtimeMessage: String,
    onNewSession: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.empty_terminal_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_terminal_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (runtimeMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(runtimeMessage, color = TerminalStatusBlue, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNewSession, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_material_symbol_add_24), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.new_session))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtraKeys(
    control: Boolean,
    alt: Boolean,
    terminalBackgroundColor: Color,
    layout: dev.terminal.app.HotkeyLayout,
    enabled: Boolean = true,
    visible: Boolean = true,
    onKey: (TerminalToolbarKey) -> Unit,
    colors: TerminalChromeColors = TerminalChromeColors(terminalBackgroundColor),
) {
    Column(
        Modifier.fillMaxWidth().background(terminalBackgroundColor)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        AnimatedVisibility(
            visible = layout.enabled && visible,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Bottom) +
                slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Bottom) +
                slideOutVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            dev.terminal.app.HotkeyGrid(
                layout = layout,
                modifier = Modifier.padding(4.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                control = control, alt = alt,
                terminalColors = colors,
                enabled = enabled && visible,
            ) { index ->
                val shortcut = layout.keys[index].shortcut
                onKey(when (shortcut) {
                    "CTRL" -> TerminalToolbarKey.Control
                    "ALT" -> TerminalToolbarKey.Alt
                    else -> TerminalToolbarKey.Shortcut(shortcut)
                })
            }
        }
    }
}

sealed interface TerminalToolbarKey {
    data class Shortcut(val value: String) : TerminalToolbarKey
    data object Control : TerminalToolbarKey
    data object Alt : TerminalToolbarKey
    data class KeyCode(val value: Int) : TerminalToolbarKey
    data class CodePoint(val value: Int) : TerminalToolbarKey
}

@Composable
fun RootlessSetupDialog(
    runtimeType: String,
    status: String,
    detail: String,
    running: Boolean,
    error: String?,
    onRetry: () -> Unit,
    progress: RootlessProgressUi? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(runtimeTitleResource(runtimeType))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rootless_setup_description))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        Text(error ?: detail, color = if (error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        if (progress != null) RootlessProgressIndicator(progress, running)
                        else if (running) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                Text(stringResource(R.string.rootless_install_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (error != null) Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.retry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun sessionStateLabel(state: String): String = stringResource(
    when (state) {
        "CREATED" -> R.string.session_state_created
        "RUNNING" -> R.string.session_state_running
        "EXITED" -> R.string.session_state_exited
        else -> R.string.session_state_unknown
    },
)

@Composable
internal fun SettingsOverview(onBack: () -> Unit, onOpen: (SettingsSearchTarget) -> Unit, hasAvfPrivileges: Boolean) {
    val rows = visibleSettingsSearchEntries(hasAvfPrivileges).map { entry ->
        LocalizedSettingsEntry(entry, stringResource(entry.title),
            entry.summary?.let { stringResource(it) }.orEmpty(),
            entry.path.map { stringResource(it) }.joinToString(" › "))
    }
    var query by rememberSaveable { mutableStateOf("") }
    val visibleRows = if (query.isBlank()) rows.filter { it.entry.path.isEmpty() }
        else rows.filter { it.matches(query) }
    SettingsPageScaffold(stringResource(R.string.settings), onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_material_symbol_search_24), contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, shapes = IconButtonDefaults.shapes()) {
                                Icon(
                                    painterResource(R.drawable.ic_material_symbol_close_24),
                                    stringResource(R.string.settings_search_clear),
                                )
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            if (visibleRows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_search_no_results),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                items(visibleRows, key = { "${it.entry.target.screen}:${it.entry.title}" }) { destination ->
                    ListItem(
                        onClick = { onOpen(destination.entry.target) },
                        supportingContent = { Text(listOf(destination.path, destination.summary).filter { it.isNotEmpty() }.joinToString("\n")) },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painterResource(destination.entry.icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        shapes = terminalListItemShapes(),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) { Text(destination.title) }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, text: String, onRefresh: () -> Unit) {
    AppScaffold(
        stringResource(R.string.diagnostics_title),
        onBack,
        actions = { IconButton(onClick = onRefresh, shapes = IconButtonDefaults.shapes()) { Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), stringResource(R.string.refresh)) } },
    ) { padding ->
        SelectionContainer {
            Text(text, Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun CrashScreen(onBack: () -> Unit, report: String, onCopy: () -> Unit, onClose: () -> Unit) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copy_complete)
    AppScaffold(stringResource(R.string.crash_report_title), onBack, snackbarHost) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.crash_report_intro))
            SelectionContainer { Text(report, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onCopy(); scope.launch { snackbarHost.showSnackbar(copiedMessage) } }, shapes = ButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_material_symbol_content_copy_24), null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_error))
                }
            }
        }
    }
}
