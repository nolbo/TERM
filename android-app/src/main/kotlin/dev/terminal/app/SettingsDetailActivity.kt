package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.AppScaffold
import dev.terminal.app.ui.RootlessSettingsState
import dev.terminal.app.ui.RootlessSettingsStatus
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.parseRootlessSettingsState
import dev.terminal.app.ui.validatedIntegerDraft
import dev.terminal.app.ui.validatedShellDraft
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File

class SettingsDetailActivity : TerminalActivity() {
    private val terminalPreferences by lazy { getSharedPreferences(TERMINAL_PREFERENCES, MODE_PRIVATE) }
    private val runtimePreferences by lazy { getSharedPreferences(RUNTIME_PREFERENCES, MODE_PRIVATE) }
    private val category get() = intent.getStringExtra(EXTRA_CATEGORY) ?: APPEARANCE
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var activityStarted = false
    private var rootlessState by mutableStateOf(RootlessSettingsState(RootlessSettingsStatus.CHECKING, ""))
    private var wakePolicy by mutableStateOf(DEFAULT_WAKE_POLICY)

    private val listener = object : IRuntimeStateListener.Stub() {
        override fun onRuntimeStateChanged(state: String, detail: String) = Unit
        override fun onSessionsChanged(revision: Long, sessionsJson: String) = Unit
        override fun onTerminalOutput(sessionId: String, bytes: ByteArray) = Unit
        override fun onSessionExited(sessionId: String, exitCode: Int) = Unit
        override fun onSessionTerminationFailed(sessionId: String, message: String) = Unit
        override fun onRootlessProgress(phase: String, detail: String) = runOnUiThread {
            rootlessState = RootlessSettingsState(
                if (phase == getString(R.string.rootless_ready_phase)) RootlessSettingsStatus.READY else RootlessSettingsStatus.CHECKING,
                detail,
            )
        }
        override fun onOperationError(operation: String, message: String) = runOnUiThread {
            if (operation == ROOTLESS_SETUP_OPERATION) {
                rootlessState = RootlessSettingsState(RootlessSettingsStatus.ERROR, message)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!activityStarted) {
                runCatching { unbindService(this) }
                bindingRequested = false
                return
            }
            runtime = IRuntimeService.Stub.asInterface(service)
            bound = true
            runCatching { runtime?.registerListener(listener) }
            syncRuntimeSettings()
            refreshServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            bound = false
            rootlessState = RootlessSettingsState(RootlessSettingsStatus.ERROR, getString(R.string.runtime_disconnected))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootlessState = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_status_connecting))
        wakePolicy = runtimePreferences.getString(KEY_WAKE_POLICY, DEFAULT_WAKE_POLICY) ?: DEFAULT_WAKE_POLICY
        compose { DetailScreen() }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        bindingRequested = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bindingRequested) {
            rootlessState = RootlessSettingsState(RootlessSettingsStatus.ERROR, getString(R.string.runtime_disconnected))
        }
    }

    override fun onStop() {
        activityStarted = false
        if (bindingRequested) {
            if (bound) runCatching { runtime?.unregisterListener(listener) }
            runCatching { unbindService(connection) }
        }
        bindingRequested = false
        bound = false
        runtime = null
        super.onStop()
    }

    private fun refreshServiceState() {
        val service = runtime ?: run {
            rootlessState = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_status_connecting))
            return
        }
        rootlessState = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_checking_detail))
        runCatching {
            rootlessState = parseRootlessSettingsState(service.runtimeStatusJson)
        }.onFailure {
            rootlessState = RootlessSettingsState(RootlessSettingsStatus.ERROR, it.message.orEmpty())
        }
    }

    private fun handleRootlessAction() {
        when (rootlessState.status) {
            RootlessSettingsStatus.SETUP_REQUIRED, RootlessSettingsStatus.ERROR -> prepareRootless()
            RootlessSettingsStatus.CHECKING, RootlessSettingsStatus.READY -> refreshServiceState()
        }
    }

    private fun prepareRootless() {
        val service = runtime ?: run {
            rootlessState = RootlessSettingsState(RootlessSettingsStatus.ERROR, getString(R.string.runtime_disconnected))
            return
        }
        rootlessState = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_install_detail))
        runCatching { service.prepareRootless() }.onFailure {
            rootlessState = RootlessSettingsState(RootlessSettingsStatus.ERROR, it.message.orEmpty())
        }
    }

    private fun saveRuntimeInt(key: String, value: Int) {
        runtimePreferences.edit().putInt(key, value).apply()
        syncRuntimeSettings()
    }

    private fun saveRuntimeBoolean(key: String, value: Boolean) {
        runtimePreferences.edit().putBoolean(key, value).apply()
        syncRuntimeSettings()
    }

    private fun syncRuntimeSettings() {
        val service = runtime ?: return
        runCatching {
            service.setAvfSettings(
                runtimePreferences.getInt(KEY_AVF_MEMORY, 2048),
                runtimePreferences.getInt(KEY_AVF_CPU, 2),
                runtimePreferences.getBoolean(KEY_AVF_PROTECTED, false),
                runtimePreferences.getBoolean(KEY_AVF_NETWORK, true),
            )
            service.setNativeSessionSettings(
                runtimePreferences.getString(KEY_NATIVE_TERM, DEFAULT_TERMINAL_TYPE) ?: DEFAULT_TERMINAL_TYPE,
                runtimePreferences.getBoolean(KEY_NATIVE_LOGIN, true),
            )
            service.setWakePolicy(runtimePreferences.getString(KEY_WAKE_POLICY, DEFAULT_WAKE_POLICY) ?: DEFAULT_WAKE_POLICY)
        }
    }

    private fun savedNativeShell(): String {
        val stored = runtimePreferences.getString(KEY_NATIVE_SHELL, DEFAULT_NATIVE_SHELL) ?: DEFAULT_NATIVE_SHELL
        return validatedShellDraft(stored) { path -> File(path).let { it.isFile && it.canExecute() } } ?: DEFAULT_NATIVE_SHELL
    }

    @Composable
    private fun DetailScreen() {
        val title = stringResource(
            when (category) {
                APPEARANCE -> R.string.settings_appearance_title
                AVF -> R.string.settings_avf_detail_title
                ROOTLESS -> R.string.runtime_rootless_title
                else -> R.string.settings_native_detail_title
            },
        )
        val description = stringResource(
            when (category) {
                APPEARANCE -> R.string.settings_appearance_description
                AVF -> R.string.settings_avf_description
                ROOTLESS -> R.string.settings_rootless_description
                else -> R.string.settings_native_description
            },
        )
        AppScaffold(title, { finish() }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(description, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                when (category) {
                    APPEARANCE -> appearanceItems()
                    AVF -> avfItems()
                    ROOTLESS -> rootlessItems()
                    else -> nativeItems()
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.appearanceItems() {
        item { SectionTitle(R.string.section_text) }
        item {
            IntegerSetting(KEY_FONT_SIZE, R.string.font_size, R.string.font_size_support, R.string.unit_sp, terminalPreferences.getInt(KEY_FONT_SIZE, 16), 8..72) {
                terminalPreferences.edit().putInt(KEY_FONT_SIZE, it).apply()
            }
        }
        item {
            ChoiceSetting(
                R.string.font_family,
                R.string.font_family_support,
                listOf(Choice(R.string.font_monospace, "MONOSPACE"), Choice(R.string.font_sans_monospace, "SANS_MONOSPACE")),
                terminalPreferences.getString(KEY_FONT_FAMILY, "MONOSPACE") ?: "MONOSPACE",
            ) { terminalPreferences.edit().putString(KEY_FONT_FAMILY, it).apply() }
        }
        item {
            BooleanSetting(R.string.fixed_width, R.string.fixed_width_support, terminalPreferences.getBoolean(KEY_FIXED_WIDTH, true)) {
                terminalPreferences.edit().putBoolean(KEY_FIXED_WIDTH, it).apply()
            }
        }
        item { SectionTitle(R.string.section_palette) }
        item {
            ChoiceSetting(
                R.string.color_palette,
                R.string.color_palette_support,
                listOf(Choice(R.string.palette_neutral_dark, "DROID_DARK"), Choice(R.string.palette_solarized_dark, "SOLARIZED_DARK"), Choice(R.string.palette_amoled, "AMOLED")),
                terminalPreferences.getString(KEY_PALETTE, "DROID_DARK") ?: "DROID_DARK",
            ) { terminalPreferences.edit().putString(KEY_PALETTE, it).apply() }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.avfItems() {
        item { SectionTitle(R.string.section_resources) }
        item {
            IntegerSetting(KEY_AVF_MEMORY, R.string.memory, R.string.memory_support, R.string.unit_mib, runtimePreferences.getInt(KEY_AVF_MEMORY, 2048), 512..32768) {
                saveRuntimeInt(KEY_AVF_MEMORY, it)
            }
        }
        item {
            IntegerSetting(KEY_AVF_CPU, R.string.virtual_cpu, R.string.virtual_cpu_support, R.string.unit_count, runtimePreferences.getInt(KEY_AVF_CPU, 2), 1..16) {
                saveRuntimeInt(KEY_AVF_CPU, it)
            }
        }
        item {
            BooleanSetting(R.string.protected_vm, R.string.protected_vm_support, runtimePreferences.getBoolean(KEY_AVF_PROTECTED, false)) {
                saveRuntimeBoolean(KEY_AVF_PROTECTED, it)
            }
        }
        item {
            BooleanSetting(R.string.guest_network, R.string.guest_network_support, runtimePreferences.getBoolean(KEY_AVF_NETWORK, true)) {
                saveRuntimeBoolean(KEY_AVF_NETWORK, it)
            }
        }
        item { SectionTitle(R.string.section_execution) }
        item {
            ChoiceSetting(
                R.string.wake_policy,
                R.string.wake_policy_support,
                listOf(Choice(R.string.wake_battery_optimized, DEFAULT_WAKE_POLICY), Choice(R.string.wake_keep_work, "KEEP_ACTIVE_WORK_RUNNING"), Choice(R.string.wake_always, "ALWAYS_AWAKE")),
                wakePolicy,
            ) {
                wakePolicy = it
                runtimePreferences.edit().putString(KEY_WAKE_POLICY, it).apply()
                runCatching { runtime?.setWakePolicy(it) }
            }
        }
        item {
            ActionSetting(R.string.debian_image, R.string.debian_image_support) {
                startActivity(
                    Intent(this@SettingsDetailActivity, SetupActivity::class.java)
                        .putExtra(SetupActivity.EXTRA_SHOW_SETUP, true),
                )
            }
        }
        item { ActionSetting(R.string.diagnostics_title, R.string.diagnostics_support) { startActivity(Intent(this@SettingsDetailActivity, DiagnosticsActivity::class.java)) } }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.nativeItems() {
        item { SectionTitle(R.string.section_shell) }
        item {
            TextSetting(KEY_NATIVE_SHELL, R.string.default_shell, R.string.default_shell_support, savedNativeShell()) {
                runtimePreferences.edit().putString(KEY_NATIVE_SHELL, it).apply()
            }
        }
        item {
            ChoiceSetting(
                R.string.terminal_type,
                R.string.terminal_type_support,
                listOf(Choice(R.string.term_xterm_256, "xterm-256color"), Choice(R.string.term_xterm, "xterm"), Choice(R.string.term_screen_256, "screen-256color")),
                runtimePreferences.getString(KEY_NATIVE_TERM, "xterm-256color") ?: "xterm-256color",
            ) {
                runtimePreferences.edit().putString(KEY_NATIVE_TERM, it).apply()
                syncRuntimeSettings()
            }
        }
        item {
            BooleanSetting(R.string.login_shell, R.string.login_shell_support, runtimePreferences.getBoolean(KEY_NATIVE_LOGIN, true)) {
                saveRuntimeBoolean(KEY_NATIVE_LOGIN, it)
            }
        }
        item { SectionTitle(R.string.section_history) }
        item {
            IntegerSetting(KEY_SCROLLBACK, R.string.scrollback, R.string.scrollback_support, R.string.unit_rows, terminalPreferences.getInt(KEY_SCROLLBACK, 10_000), 1_000..100_000) {
                terminalPreferences.edit().putInt(KEY_SCROLLBACK, it).apply()
            }
        }
        item {
            ActionSetting(R.string.android_permissions, R.string.android_permissions_support) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.rootlessItems() {
        item { SectionTitle(R.string.section_status) }
        item { RootlessStatusSetting(rootlessState, ::handleRootlessAction) }
        item { SectionTitle(R.string.section_environment) }
        item { InfoSetting(R.string.shared_workspace, R.string.shared_workspace_support) }
        item { InfoSetting(R.string.rootless_limitations, R.string.rootless_limitations_support) }
    }

    @Composable
    private fun SectionTitle(@StringRes title: Int) {
        Text(stringResource(title), Modifier.padding(top = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }

    @Composable
    private fun IntegerSetting(stateKey: String, @StringRes title: Int, @StringRes supporting: Int, @StringRes suffix: Int, initial: Int, range: IntRange, onSave: (Int) -> Unit) {
        var committed by rememberSaveable(stateKey, "committed") { mutableIntStateOf(initial) }
        var draft by rememberSaveable(stateKey, "draft") { mutableStateOf(initial.toString()) }
        var dialogVisible by rememberSaveable(stateKey, "dialog") { mutableStateOf(false) }
        val unit = stringResource(suffix)
        PreferenceListItem(
            title = title,
            supporting = supporting,
            trailing = { Text("$committed $unit", style = MaterialTheme.typography.labelLarge) },
            role = Role.Button,
        ) {
            draft = committed.toString()
            dialogVisible = true
        }
        if (dialogVisible) {
            val parsed = validatedIntegerDraft(draft, range)
            val focusManager = LocalFocusManager.current
            val save = {
                parsed?.let {
                    committed = it
                    onSave(it)
                    focusManager.clearFocus()
                    dialogVisible = false
                }
                Unit
            }
            AlertDialog(
                onDismissRequest = { dialogVisible = false },
                title = { Text(stringResource(title)) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(title)) },
                        supportingText = {
                            Text(if (parsed == null) stringResource(R.string.range_support, range.first, range.last) else stringResource(supporting))
                        },
                        trailingIcon = { Text(unit, Modifier.padding(end = 12.dp)) },
                        isError = parsed == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                },
                confirmButton = { Button(onClick = save, enabled = parsed != null) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { dialogVisible = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun TextSetting(stateKey: String, @StringRes title: Int, @StringRes supporting: Int, initial: String, onSave: (String) -> Unit) {
        var committed by rememberSaveable(stateKey, "committed") { mutableStateOf(initial) }
        var draft by rememberSaveable(stateKey, "draft") { mutableStateOf(initial) }
        var dialogVisible by rememberSaveable(stateKey, "dialog") { mutableStateOf(false) }
        PreferenceListItem(
            title = title,
            supporting = supporting,
            trailing = { Text(committed, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
            role = Role.Button,
        ) {
            draft = committed
            dialogVisible = true
        }
        if (dialogVisible) {
            val validated = validatedShellDraft(draft) { path -> File(path).let { it.isFile && it.canExecute() } }
            val focusManager = LocalFocusManager.current
            val save = {
                validated?.let {
                    committed = it
                    onSave(it)
                    focusManager.clearFocus()
                    dialogVisible = false
                }
                Unit
            }
            AlertDialog(
                onDismissRequest = { dialogVisible = false },
                title = { Text(stringResource(title)) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(title)) },
                        supportingText = { Text(if (validated == null) stringResource(R.string.executable_path_error) else stringResource(supporting)) },
                        isError = validated == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                },
                confirmButton = { Button(onClick = save, enabled = validated != null) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { dialogVisible = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun BooleanSetting(@StringRes title: Int, @StringRes supporting: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        var checked by rememberSaveable { mutableStateOf(initial) }
        val toggle = { checked = !checked; onChange(checked) }
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = Role.Switch, onClick = toggle),
        )
    }

    @Composable
    private fun ChoiceSetting(@StringRes title: Int, @StringRes supporting: Int, choices: List<Choice>, initial: String, onChange: (String) -> Unit) {
        var selected by rememberSaveable(initial) { mutableStateOf(initial) }
        var draftSelected by rememberSaveable(initial, "draft") { mutableStateOf(initial) }
        var dialogVisible by rememberSaveable(initial, "dialog") { mutableStateOf(false) }
        val selectedLabel = choices.firstOrNull { it.value == selected }?.label ?: choices.first().label
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = { Text(stringResource(selectedLabel), style = MaterialTheme.typography.labelLarge) },
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = Role.Button) {
                draftSelected = selected
                dialogVisible = true
            },
        )
        if (dialogVisible) {
            AlertDialog(
                onDismissRequest = { dialogVisible = false },
                title = { Text(stringResource(title)) },
                text = {
                    Column {
                        choices.forEach { choice ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                                    draftSelected = choice.value
                                }.heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = draftSelected == choice.value, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(choice.label))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        selected = draftSelected
                        onChange(draftSelected)
                        dialogVisible = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { dialogVisible = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun ActionSetting(@StringRes title: Int, @StringRes supporting: Int?, supportingText: String? = null, action: () -> Unit) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(supportingText ?: supporting?.let { stringResource(it) }.orEmpty()) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = action),
        )
    }

    @Composable
    private fun InfoSetting(@StringRes title: Int, @StringRes supporting: Int) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun PreferenceListItem(
        @StringRes title: Int,
        @StringRes supporting: Int,
        trailing: @Composable () -> Unit,
        role: Role,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = trailing,
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = role, onClick = onClick),
        )
    }

    @Composable
    private fun preferenceListItemColors() = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )

    @Composable
    private fun RootlessStatusSetting(state: RootlessSettingsState, action: () -> Unit) {
        val title = when (state.status) {
            RootlessSettingsStatus.CHECKING -> R.string.rootless_checking
            RootlessSettingsStatus.SETUP_REQUIRED -> R.string.rootless_setup_required
            RootlessSettingsStatus.READY -> R.string.rootless_ready
            RootlessSettingsStatus.ERROR -> R.string.rootless_error
        }
        val actionLabel = when (state.status) {
            RootlessSettingsStatus.CHECKING -> null
            RootlessSettingsStatus.READY -> R.string.check_again
            RootlessSettingsStatus.SETUP_REQUIRED -> R.string.install
            RootlessSettingsStatus.ERROR -> R.string.retry
        }
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(state.detail.ifBlank { stringResource(R.string.rootless_status_connecting) }) },
            leadingContent = {
                if (state.status == RootlessSettingsStatus.CHECKING) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            },
            trailingContent = {
                actionLabel?.let { TextButton(onClick = action) { Text(stringResource(it)) } }
            },
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
        )
    }

    private data class Choice(@param:StringRes val label: Int, val value: String)

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val APPEARANCE = "appearance"
        const val AVF = "avf"
        const val NATIVE = "native"
        const val ROOTLESS = "rootless"
        const val TERMINAL_PREFERENCES = "terminal-ui"
        const val RUNTIME_PREFERENCES = "runtime-settings"
        const val KEY_FONT_SIZE = "font-size-sp"
        const val KEY_FONT_FAMILY = "font-family"
        const val KEY_PALETTE = "palette"
        const val KEY_FIXED_WIDTH = "fixed-width"
        const val KEY_SCROLLBACK = "scrollback-rows"
        const val KEY_AVF_MEMORY = "avf-memory-mib"
        const val KEY_AVF_CPU = "avf-cpu-count"
        const val KEY_AVF_PROTECTED = "avf-protected"
        const val KEY_AVF_NETWORK = "avf-network"
        const val KEY_WAKE_POLICY = "wake-policy"
        const val KEY_NATIVE_SHELL = "native-shell"
        const val KEY_NATIVE_TERM = "native-term"
        const val KEY_NATIVE_LOGIN = "native-login-shell"
        private const val DEFAULT_NATIVE_SHELL = "/system/bin/sh"
        private const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        private const val DEFAULT_WAKE_POLICY = "BATTERY_OPTIMIZED"
        private const val ROOTLESS_SETUP_OPERATION = "ROOTLESS_SETUP"
    }
}
