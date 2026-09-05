package dev.terminal.app.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.terminal.app.R
import kotlinx.coroutines.launch

internal val TerminalLightColors = lightColorScheme(
    primary = Color(0xFF006C47),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8BF8BD),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4D6357),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xFFF7FBF7),
    onSurface = Color(0xFF191C1A),
    surfaceContainer = Color(0xFFE8F0EA),
    surfaceContainerHigh = Color(0xFFE2EAE4),
    outline = Color(0xFF707973),
    error = Color(0xFFBA1A1A),
)

internal val TerminalDarkColors = darkColorScheme(
    primary = Color(0xFF6EF2AD),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFF8BF8BD),
    secondary = Color(0xFFB4CCBD),
    onSecondary = Color(0xFF20352A),
    surface = Color(0xFF0C0E11),
    onSurface = Color(0xFFE1E3DF),
    surfaceContainer = Color(0xFF181C21),
    surfaceContainerHigh = Color(0xFF22272D),
    outline = Color(0xFF89938C),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DroidTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> TerminalDarkColors
        else -> TerminalLightColors
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), shapes = Shapes(), content = content)
}

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
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                    Text(
                        title,
                        Modifier.weight(1f).padding(horizontal = if (onBack == null) 12.dp else 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    actions()
                }
            }
        },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        content = content,
    )
}

private data class SetupRuntime(
    val title: Int,
    val label: Int,
    val description: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun SetupScreen(onStart: () -> Unit, onImage: () -> Unit) {
    val runtimes = listOf(
        SetupRuntime(R.string.runtime_native_title, R.string.runtime_native_setup, R.string.runtime_native_description, Icons.Default.Terminal),
        SetupRuntime(R.string.runtime_rootless_title, R.string.runtime_rootless_setup, R.string.runtime_rootless_description, Icons.Default.Download),
        SetupRuntime(R.string.runtime_avf_title, R.string.runtime_avf_setup, R.string.runtime_avf_description, Icons.Default.Memory),
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
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(runtime.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(runtime.label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(runtime.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(runtime.description), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.start_terminal)) } }
            item { OutlinedButton(onClick = onImage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.select_avf_image)) } }
            item { Text(stringResource(R.string.avf_image_note), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

data class SessionUi(val id: String, val runtime: String, val title: String, val state: String, val exitCode: Int? = null)

data class RuntimeChoiceUi(val id: String, val available: Boolean, val detail: String)

@Composable
fun TerminalChrome(
    sessions: List<SessionUi>,
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onTerminate: (SessionUi) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id == activeSessionId
                    val runtimeLabel = stringResource(when (session.runtime) { "ROOTLESS" -> R.string.runtime_badge_rootless; "AVF" -> R.string.runtime_badge_avf; else -> R.string.runtime_badge_native })
                    val sessionTitle = session.title.ifBlank { stringResource(R.string.shell_default_title) }
                    Surface(
                        onClick = { onSelect(session.id) },
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp).semantics { this.selected = selected; role = Role.Tab },
                        shape = MaterialTheme.shapes.large,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Column(Modifier.widthIn(max = 148.dp)) {
                                Text(stringResource(R.string.session_title_format, runtimeLabel, sessionTitle), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                Text(if (selected) stringResource(R.string.current_session) else sessionStateLabel(session.state), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onTerminate(session) }) {
                                Icon(Icons.Default.Close, stringResource(R.string.terminate_session_description, sessionTitle))
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, stringResource(R.string.new_session)) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
        }
    }
}

@Composable
fun ExtraKeys(
    control: Boolean,
    alt: Boolean,
    onKey: (String) -> Unit,
    onControl: () -> Unit,
    onAlt: () -> Unit,
) {
    val rows = listOf(
        listOf(R.string.key_escape to "\u001b", R.string.key_slash to "/", R.string.key_minus to "-", R.string.key_home to "\u001b[H", R.string.key_end to "\u001b[F", R.string.key_page_up to "\u001b[5~", R.string.key_tab to "\t"),
        listOf(R.string.key_control to "", R.string.key_alt to "", R.string.key_up to "\u001b[A", R.string.key_down to "\u001b[B", R.string.key_left to "\u001b[D", R.string.key_right to "\u001b[C", R.string.key_page_down to "\u001b[6~"),
    )
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (labelResource, value) ->
                    val label = stringResource(labelResource)
                    val selected = (labelResource == R.string.key_control && control) || (labelResource == R.string.key_alt && alt)
                    FilledTonalButton(
                        onClick = when (labelResource) { R.string.key_control -> onControl; R.string.key_alt -> onAlt; else -> ({ onKey(value) }) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(2.dp).semantics { if (labelResource == R.string.key_control || labelResource == R.string.key_alt) this.selected = selected },
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                    ) { Text(label, maxLines = 1, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun NewSessionDialog(choices: List<RuntimeChoiceUi>, onDismiss: () -> Unit, onSelect: (RuntimeChoiceUi) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_session)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    val title = when (choice.id) { "ROOTLESS" -> R.string.runtime_rootless_title; "AVF" -> R.string.runtime_avf_title; else -> R.string.runtime_native_title }
                    val status = when {
                        choice.available -> R.string.runtime_available
                        choice.id == "ROOTLESS" -> R.string.rootless_setup_required
                        else -> R.string.runtime_unavailable
                    }
                    OutlinedButton(onClick = { onSelect(choice) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Icon(when { choice.available -> Icons.Default.CheckCircle; choice.id == "ROOTLESS" -> Icons.Default.Download; else -> Icons.Default.Block }, null)
                            }
                            Text(stringResource(status), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Text(choice.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun RootlessSetupDialog(
    status: String,
    detail: String,
    running: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_rootless_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rootless_setup_description))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        Text(error ?: detail, color = if (error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                Text(stringResource(R.string.rootless_install_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (error != null) Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
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
fun SettingsOverview(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val rows = listOf(
        Triple("appearance", R.string.settings_appearance_title, R.string.settings_appearance_summary),
        Triple("rootless", R.string.runtime_rootless_title, R.string.settings_rootless_summary),
        Triple("avf", R.string.settings_avf_title, R.string.settings_avf_summary),
        Triple("native", R.string.settings_native_title, R.string.settings_native_summary),
    )
    AppScaffold(stringResource(R.string.settings), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
            item { Text(stringResource(R.string.settings_intro), Modifier.padding(8.dp, 8.dp, 8.dp, 16.dp)) }
            items(rows) { (key, title, summary) ->
                ListItem(
                    headlineContent = { Text(stringResource(title)) },
                    supportingContent = { Text(stringResource(summary)) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(key) }.semantics { role = Role.Button },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, text: String, onRefresh: () -> Unit) {
    AppScaffold(
        stringResource(R.string.diagnostics_title),
        onBack,
        actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } },
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
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onCopy(); scope.launch { snackbarHost.showSnackbar(copiedMessage) } }) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_error))
                }
            }
        }
    }
}
