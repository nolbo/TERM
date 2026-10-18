@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.terminal.app.R
import dev.terminal.app.ProfileSection
import kotlinx.coroutines.launch

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
    shape = if (LocalSettingsGroup.current) SettingsItemShape else MaterialTheme.shapes.extraLarge,
)

/** Disabled settings retain their surface and dim only their foreground content. */
@Composable
internal fun terminalSettingsListItemColors(containerColor: Color = settingsItemContainerColor()): ListItemColors {
    val disabledContent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    return ListItemDefaults.colors(
        containerColor = containerColor,
        disabledContainerColor = containerColor,
        disabledContentColor = disabledContent,
        disabledSupportingContentColor = disabledContent,
        disabledOverlineContentColor = disabledContent,
        disabledLeadingContentColor = disabledContent,
        disabledTrailingContentColor = disabledContent,
    )
}

@Composable
fun DroidTheme(
    systemBarBackground: Color? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val typography = remember { Typography(fontFamily = GoogleSansFontFamily) }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = systemBarBackground?.let { TerminalChromeColors(it).darkSystemIcons } ?: false
            isAppearanceLightNavigationBars = systemBarBackground?.let { TerminalChromeColors(it).darkSystemIcons } ?: false
        }
    }
    CompositionLocalProvider(LocalTerminalMonospaceFontFamily provides GoogleSansCodeFontFamily) {
        MaterialExpressiveTheme(
            colorScheme = TerminalDarkColors,
            motionScheme = MotionScheme.expressive(),
            typography = typography,
            shapes = Shapes(),
            content = content,
        )
    }
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
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val headerState = rememberTopAppBarState()
    val contentHeaderState = rememberTopAppBarState()
    val density = LocalDensity.current
    val fadeThreshold = with(density) { 4.dp.toPx() }
    val floatingFraction by animateFloatAsState(
        targetValue = if (contentHeaderState.heightOffset < -fadeThreshold) 1f else 0f,
        animationSpec = tween(250),
        label = "Settings small title fade",
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = headerState, canScroll = { contentHeaderState.heightOffset == 0f })
    val contentScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = contentHeaderState, canScroll = { headerState.collapsedFraction == 1f }, snapAnimationSpec = null)
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val layoutDirection = LocalLayoutDirection.current
    val topInset = with(density) { systemInsets.getTop(this).toDp() }
    val smallHeaderHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight + topInset
    val contentHeaderLimit = with(density) { -smallHeaderHeight.toPx() }
    SideEffect {
        contentHeaderState.heightOffsetLimit = contentHeaderLimit
        contentHeaderState.heightOffset = contentHeaderState.heightOffset.coerceIn(contentHeaderLimit, 0f)
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .semantics { isTraversalGroup = true }) {
        Scaffold(
            // Content moves one pixel per scroll pixel; the title fades as the large bar collapses.
            modifier = Modifier.nestedScroll(contentScrollBehavior.nestedScrollConnection)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
            // Top space belongs to the two header stages, including the initial status-bar inset.
            contentWindowInsets = systemInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            topBar = {
                if (floatingFraction < 1f) LargeTopAppBar(
                    // Material crossfades the large/small titles; extra scrolling fades the small title.
                    modifier = Modifier.graphicsLayer { alpha = 1f - floatingFraction },
                    title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    // Reserve space aligned with the separately pinned circular back button.
                    navigationIcon = { Spacer(Modifier.padding(horizontal = 12.dp).size(48.dp)) },
                    windowInsets = systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                ) else Spacer(Modifier.fillMaxWidth().height(smallHeaderHeight))
            },
            // Keep Surface's inherited tonal elevation from painting an opaque header backdrop.
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = { padding ->
                Box(Modifier.fillMaxSize()) {
                    content(PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = (padding.calculateTopPadding() + with(density) { contentHeaderState.heightOffset.toDp() }).coerceAtLeast(0.dp),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = padding.calculateBottomPadding(),
                    ))
                    // Full-strength edge fade above content, below the transparent bar and its title.
                    Box(Modifier.fillMaxWidth().height(smallHeaderHeight).background(Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background,
                        0.2f to MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                        0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.38f),
                        1f to Color.Transparent,
                    )))
                }
            },
        )
        FilledIconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, top = 8.dp).size(48.dp)
                .graphicsLayer {
                    shape = CircleShape
                    // Material elevation level 2, introduced only as content reaches the floating button.
                    shadowElevation = with(density) { (3.dp * contentHeaderState.collapsedFraction).toPx() }
                }
                .semantics { traversalIndex = -1f },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_material_symbol_arrow_back_24), stringResource(R.string.back))
        }
    }
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
    val pendingLaunch: Boolean = false,
    val launchRequestId: String? = null,
    val launchError: String? = null,
)

data class RuntimeChoiceUi(
    val id: String, val available: Boolean, val detail: String, val installable: Boolean = false,
    val environmentRequired: Boolean = false, val state: String = "", val progress: RootlessProgressUi? = null,
    val canCancelInstallation: Boolean = false,
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
fun NewSessionSheet(profiles: List<dev.terminal.runtime.session.SessionProfile>,
    onSelectProfile: (dev.terminal.runtime.session.SessionProfile) -> Unit,
    onAddProfile: () -> Unit, onDismiss: () -> Unit,
    environmentNames: Map<dev.terminal.runtime.session.EnvironmentId, String> = emptyMap(),
    installationStatuses: Map<dev.terminal.runtime.session.EnvironmentId, ProfileInstallationStatus> = emptyMap(),
    workspace: dev.terminal.runtime.workspace.Workspace? = null,
    onSelectWorkspaceLaunch: (dev.terminal.runtime.workspace.WorkspaceLaunch) -> Unit = {}) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val haptics = LocalTerminalHaptics.current
    val foreground = MaterialTheme.colorScheme.onSurface
    val supporting = MaterialTheme.colorScheme.onSurfaceVariant
    SessionBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            workspaceLaunchItems(workspace, profiles, installationStatuses, accent = TerminalStatusBlue,
                foreground = foreground, supporting = supporting, largeHeading = true, onSelect = { launch ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    scope.launch { sheetState.hide(); onSelectWorkspaceLaunch(launch) }
                })
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
                    leadingContent = { ProfilePickerIcon(profile, installationStatuses, tint = TerminalStatusBlue) },
                    supportingContent = { ProfileSupportingContent(profile, environmentNames) },
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

    SessionBottomSheet(
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
fun TerminalEmptyState(runtimeMessage: String) {
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
    }
}

sealed interface TerminalToolbarKey {
    data class Shortcut(val value: String) : TerminalToolbarKey
    data object Control : TerminalToolbarKey
    data object Alt : TerminalToolbarKey
    data object Shift : TerminalToolbarKey
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
    onCancelInstall: (() -> Unit)? = null,
    cancelling: Boolean = false,
    cancelled: Boolean = false,
    onDismiss: () -> Unit,
) {
    InstallationProgressDialog(
        title = stringResource(R.string.rootless_installation_title, stringResource(runtimeTitleResource(runtimeType))),
        onDismiss = onDismiss,
    ) {
        RootlessInstallCard(title = null, status = status, detail = detail, running = running, progress = progress,
            error = error, onCancelInstall = onCancelInstall, cancelling = cancelling, cancelled = cancelled, onRetry = onRetry)
    }
}

@Composable
internal fun sessionStateLabel(state: String): String = stringResource(
    when (state) {
        "CREATED" -> R.string.session_state_created
        "RUNNING" -> R.string.session_state_running
        "EXITED" -> R.string.session_state_exited
        "PREPARING" -> R.string.avf_session_preparing_state
        "LAUNCH_FAILED" -> R.string.avf_session_failed_state
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
            verticalArrangement = Arrangement.spacedBy(if (query.isBlank()) 16.dp else 8.dp),
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
            } else if (query.isBlank()) {
                val groups = listOf(listOf("environments", "profiles", "workspaces"),
                    listOf("shared-folders"), listOf("language", "terminal"),
                    listOf("rootless", "avf"), listOf("diagnostics", "updates", "about"))
                    .map { screens -> visibleRows.filter { it.entry.target.screen in screens } }
                    .filter { it.isNotEmpty() }
                items(groups, key = { "group:${it.first().entry.target.screen}" }) { group ->
                    SettingsGroup {
                        group.forEach { destination -> SettingsOverviewRow(destination, onOpen) }
                    }
                }
            } else {
                items(visibleRows, key = { "${it.entry.target.screen}:${it.entry.title}" }) { destination ->
                    SettingsOverviewRow(destination, onOpen)
                }
            }
        }
    }
}

@Composable
private fun SettingsOverviewRow(destination: LocalizedSettingsEntry, onOpen: (SettingsSearchTarget) -> Unit) {
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
                    Icon(painterResource(destination.entry.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        },
        trailingContent = if (destination.entry.path.isEmpty()) null else {
            { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) }
        },
        verticalAlignment = Alignment.CenterVertically,
        shapes = terminalListItemShapes(),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) { Text(destination.title) }
}

@Composable
private fun RefreshInformationButton(onRefresh: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = onRefresh, modifier = modifier, shapes = ButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.refresh))
    }
}

@Composable
private fun InformationMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun CrashScreen(onBack: () -> Unit, report: String, onCopy: () -> Unit, onDelete: () -> Unit,
    canCopy: Boolean = true, deleting: Boolean = false, deleteError: String? = null) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copy_complete)
    var confirmDeletion by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(deleteError) {
        deleteError?.let { snackbarHost.showSnackbar(it) }
    }
    SettingsPageScaffold(
        title = stringResource(R.string.crash_report_title),
        onBack = onBack,
        snackbarHostState = snackbarHost,
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            HorizontalFloatingToolbar(expanded = true) {
                FloatingToolbarAction(stringResource(R.string.crash_report_delete), R.drawable.ic_material_symbol_delete_24,
                    enabled = canCopy && !deleting, destructive = true, onClick = { confirmDeletion = true })
                FloatingToolbarAction(stringResource(R.string.copy_error), R.drawable.ic_material_symbol_content_copy_24,
                    enabled = canCopy && !deleting, onClick = {
                        onCopy()
                        scope.launch { snackbarHost.showSnackbar(copiedMessage) }
                    })
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.crash_report_intro), modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.fillMaxWidth()) {
                Text(report, fontFamily = LocalTerminalMonospaceFontFamily.current,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmDeletion) AlertDialog(onDismissRequest = { confirmDeletion = false },
        title = { Text(stringResource(R.string.crash_report_delete)) },
        text = { Text(stringResource(R.string.crash_report_delete_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmDeletion = false; onDelete() }, enabled = canCopy && !deleting,
            shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDeletion = false }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(android.R.string.cancel))
        } })
}
