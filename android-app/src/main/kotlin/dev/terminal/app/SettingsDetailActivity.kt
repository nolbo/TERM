@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import dev.terminal.app.ui.RootlessSettingsState
import dev.terminal.app.ui.RootlessSettingsStatus
import dev.terminal.app.ui.RuntimeEnvironmentIcon
import dev.terminal.app.ui.RootlessProgressIndicator
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.TerminalHapticDensity
import dev.terminal.app.ui.TerminalHapticPreferences
import dev.terminal.app.ui.TerminalStatusBlue
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.rootlessRuntimeIds
import dev.terminal.app.ui.parseRootlessSettingsState
import dev.terminal.app.ui.SettingsGroup
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.app.ui.terminalListItemShapes
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
    private val taskNotificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.session_task_permission_required, Toast.LENGTH_LONG).show()
    }
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var activityStarted = false
    private val rootlessStates = mutableStateMapOf<String, RootlessSettingsState>()
    private var pendingRootlessRemoval by mutableStateOf<String?>(null)
    private var wakePolicy by mutableStateOf(DEFAULT_WAKE_POLICY)
    private var customFonts by mutableStateOf(emptyList<CustomTerminalFont>())
    private var pendingCustomFontStyle = CustomTerminalFontStyle.REGULAR
    private var sharedStorageEnabled by mutableStateOf(false)
    private var paletteSelection by mutableStateOf("DROID_DARK")

    private val customFontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
        customFonts = (customFonts + CustomTerminalFont(uri.toString(), name, pendingCustomFontStyle))
            .distinctBy { it.uri to it.style }
        saveCustomTerminalFonts(terminalPreferences, customFonts)
    }

    private val listener = object : IRuntimeStateListener.Stub() {
        override fun onRuntimeStateChanged(state: String, detail: String) = runOnUiThread { refreshServiceState() }
        override fun onSessionFocusRequested(sessionId: String) = Unit
        override fun onSessionsChanged(revision: Long, sessionsJson: String) = Unit
        override fun onTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) = Unit
        override fun onSessionExited(sessionId: String, exitCode: Int) = Unit
        override fun onSessionTerminationFailed(sessionId: String, message: String) = Unit
        override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) = runOnUiThread {
            if (runtimeType !in rootlessRuntimeIds) return@runOnUiThread
            refreshServiceState(runtimeType)
        }
        override fun onOperationError(operation: String, message: String) = runOnUiThread {
            if (operation.startsWith("ROOTLESS_MIGRATE:")) {
                val runtimeType = operation.substringAfter(':')
                refreshServiceState(runtimeType)
                rootlessStates[runtimeType]?.let {
                    rootlessStates[runtimeType] = it.copy(status = RootlessSettingsStatus.ERROR, detail = message)
                }
                return@runOnUiThread
            }
            if (operation.startsWith("ROOTLESS_REMOVE:")) {
                refreshServiceState(operation.substringAfter(':'))
                Toast.makeText(this@SettingsDetailActivity, message, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val runtimeType = operation.removePrefix("$ROOTLESS_SETUP_OPERATION:")
            if (operation.startsWith("$ROOTLESS_SETUP_OPERATION:") && runtimeType in rootlessRuntimeIds) {
                refreshServiceState(runtimeType)
                rootlessStates[runtimeType] = rootlessStates.getValue(runtimeType).copy(status = RootlessSettingsStatus.ERROR, detail = message)
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
            setRootlessStates(RootlessSettingsStatus.ERROR, getString(R.string.runtime_disconnected))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Old task entries can still point to the removed tool's settings screen.
        if (category == "toolbar-clipboard") intent.putExtra(EXTRA_CATEGORY, TOOLBAR)
        if (category == AVF && !SessionProfiles(this).hasAvfPrivileges()) {
            finish()
            return
        }
        setRootlessStates(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_status_connecting))
        wakePolicy = runtimePreferences.getString(KEY_WAKE_POLICY, DEFAULT_WAKE_POLICY) ?: DEFAULT_WAKE_POLICY
        customFonts = customTerminalFonts(terminalPreferences)
        compose {
            when (category) {
                TOOLBAR -> ToolbarSettings(terminalPreferences, { finish() }) { screen ->
                    startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra(EXTRA_CATEGORY, screen))
                }
                "toolbar-memory", "toolbar-commands" ->
                    dev.terminal.app.ui.ToolbarToolSettings(category, terminalPreferences, onBack = { finish() },
                        initialCommandId = intent.getStringExtra("toolbar-command-id"))
                HOTKEYS -> HotkeySettings(terminalPreferences) { finish() }
                else -> DetailScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Never cache a separate boolean: Native processes share the app's OS grant.
        sharedStorageEnabled = Environment.isExternalStorageManager()
        paletteSelection = terminalPreferences.getString(KEY_PALETTE, "DROID_DARK") ?: "DROID_DARK"
    }

    private fun openSharedStorageSettings() {
        val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null))
        runCatching { startActivity(appSettings) }.recoverCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.onFailure {
            Toast.makeText(this, R.string.shared_storage_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (category == AVF && !SessionProfiles(this).hasAvfPrivileges()) {
            finish()
            return
        }
        activityStarted = true
        bindingRequested = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bindingRequested) {
            setRootlessStates(RootlessSettingsStatus.ERROR, getString(R.string.runtime_disconnected))
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

    private fun setRootlessStates(status: RootlessSettingsStatus, detail: String) {
        rootlessRuntimeIds.forEach { rootlessStates[it] = RootlessSettingsState(status, detail) }
    }

    private fun refreshServiceState(runtimeType: String? = null) {
        val targets = runtimeType?.let(::listOf) ?: rootlessRuntimeIds.toList()
        val service = runtime
        if (service == null) {
            targets.forEach {
                rootlessStates[it] = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_status_connecting))
            }
            return
        }
        runCatching {
            val snapshot = service.runtimeStatusJson
            targets.forEach { rootlessStates[it] = parseRootlessSettingsState(snapshot, it) }
        }.onFailure { error ->
            targets.forEach { rootlessStates[it] = RootlessSettingsState(RootlessSettingsStatus.ERROR, error.message.orEmpty()) }
        }
    }

    private fun handleRootlessAction(runtimeType: String) {
        when (rootlessStates[runtimeType]?.status) {
            RootlessSettingsStatus.SETUP_REQUIRED, RootlessSettingsStatus.ENVIRONMENT_REQUIRED, RootlessSettingsStatus.ERROR -> prepareRootless(runtimeType)
            RootlessSettingsStatus.READY -> pendingRootlessRemoval = runtimeType
            else -> refreshServiceState(runtimeType)
        }
    }

    private fun removeRootless(runtimeType: String, removeHome: Boolean) {
        val service = runtime ?: return
        rootlessStates[runtimeType] = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_removing))
        runCatching { service.removeRootless(runtimeType, removeHome) }.onFailure {
            refreshServiceState(runtimeType)
            Toast.makeText(this, it.message.orEmpty(), Toast.LENGTH_LONG).show()
        }
    }

    private fun migrateRootless(runtimeType: String) {
        val service = runtime ?: return
        rootlessStates[runtimeType] = rootlessStates.getValue(runtimeType).copy(
            status = RootlessSettingsStatus.CHECKING, detail = getString(R.string.rootless_migrate_running))
        runCatching { service.migrateRootlessToNative(runtimeType) }.onFailure {
            rootlessStates[runtimeType] = rootlessStates.getValue(runtimeType).copy(
                status = RootlessSettingsStatus.ERROR, detail = it.message.orEmpty())
        }
    }

    private fun prepareRootless(runtimeType: String) {
        val service = runtime ?: run {
            rootlessStates[runtimeType] = RootlessSettingsState(RootlessSettingsStatus.ERROR, getString(R.string.runtime_disconnected))
            return
        }
        rootlessStates[runtimeType] = RootlessSettingsState(RootlessSettingsStatus.CHECKING, getString(R.string.rootless_install_detail), rootlessStates[runtimeType]?.installed == true)
        runCatching { service.prepareRootless(runtimeType) }.onFailure {
            rootlessStates[runtimeType] = RootlessSettingsState(RootlessSettingsStatus.ERROR, it.message.orEmpty())
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
            service.setSessionTaskNotifications(runtimePreferences.getBoolean(dev.terminal.runtime.notifications.SessionNotificationSettings.KEY_ENABLED, false))
            service.setKittyRemoteControl(runtimePreferences.getBoolean(KEY_KITTY_REMOTE, false))
            service.setKittyGraphicsCompatibility(runtimePreferences.getBoolean(KEY_KITTY_GRAPHICS, false))
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
                LANGUAGE -> R.string.settings_language_title
                APPEARANCE -> R.string.settings_appearance_title
                AVF -> R.string.settings_avf_title
                ROOTLESS -> R.string.runtime_rootless_title
                TERMINAL -> R.string.settings_terminal_title
                else -> R.string.settings_native_title
            },
        )
        val description = stringResource(
            when (category) {
                LANGUAGE -> R.string.settings_language_description
                APPEARANCE -> R.string.settings_appearance_description
                AVF -> R.string.settings_avf_description
                ROOTLESS -> R.string.settings_rootless_description
                TERMINAL -> R.string.settings_terminal_description
                else -> R.string.settings_native_description
            },
        )
        pendingRootlessRemoval?.let { runtimeType ->
            var removeHome by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { pendingRootlessRemoval = null },
                title = { Text(stringResource(R.string.rootless_remove_title, stringResource(runtimeTitleResource(runtimeType)))) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.rootless_remove_description))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = removeHome,
                                role = Role.Checkbox,
                                onValueChange = { removeHome = it },
                            ),
                        ) {
                            Checkbox(checked = removeHome, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.rootless_remove_home))
                        }
                        if (removeHome) Text(
                            stringResource(R.string.rootless_remove_home_warning),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pendingRootlessRemoval = null; removeRootless(runtimeType, removeHome) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.rootless_remove), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRootlessRemoval = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        SettingsPageScaffold(title, { finish() }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = rememberLazyListState(initialFirstVisibleItemIndex =
                    intent.getIntExtra(EXTRA_SECTION_INDEX, 0).coerceAtLeast(0)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text(
                        description,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                when (category) {
                    LANGUAGE -> languageItems()
                    APPEARANCE -> appearanceItems()
                    AVF -> avfItems()
                    ROOTLESS -> rootlessItems()
                    TERMINAL -> terminalItems()
                    else -> nativeItems()
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.languageItems() {
        item {
            SettingsSection(R.string.settings_language_title) {
                val selected = AppLanguage.selected()
                ChoiceSetting(
                    R.string.settings_language,
                    R.string.settings_language_support,
                    listOf(
                        Choice(R.string.language_korean, AppLanguage.KOREAN.languageTag),
                        Choice(R.string.language_english, AppLanguage.ENGLISH.languageTag),
                    ),
                    selected.languageTag,
                ) { value ->
                    AppLanguage.entries.first { it.languageTag == value }.apply()
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.appearanceItems() {
        item {
            SettingsSection(R.string.section_terminal_layout) {
                IntegerSetting(
                    TerminalAppearance.KEY_PADDING,
                    R.string.terminal_padding,
                    R.string.terminal_padding_support,
                    R.string.unit_dp,
                    TerminalAppearance.paddingDp(terminalPreferences),
                    TerminalAppearance.PADDING_RANGE,
                ) {
                    terminalPreferences.edit().putInt(TerminalAppearance.KEY_PADDING, it).apply()
                }
            }
        }
        item {
            SettingsSection(R.string.section_text) {
                IntegerSetting(
                    KEY_FONT_SIZE,
                    R.string.font_size,
                    R.string.font_size_support,
                    R.string.unit_sp,
                    terminalPreferences.getInt(KEY_FONT_SIZE, TerminalAppearance.DEFAULT_FONT_SIZE_SP),
                    SessionFontSizes.MIN_FONT_SIZE_SP..SessionFontSizes.MAX_FONT_SIZE_SP,
                ) {
                    terminalPreferences.edit().putInt(KEY_FONT_SIZE, it).apply()
                }
                ChoiceSetting(
                    R.string.font_family,
                    R.string.font_family_support,
                    listOf(Choice(R.string.font_monospace, "MONOSPACE"), Choice(R.string.font_sans_monospace, "SANS_MONOSPACE")),
                    terminalPreferences.getString(KEY_FONT_FAMILY, "MONOSPACE") ?: "MONOSPACE",
                ) { terminalPreferences.edit().putString(KEY_FONT_FAMILY, it).apply() }
                BooleanSetting(R.string.fixed_width, R.string.fixed_width_support, terminalPreferences.getBoolean(KEY_FIXED_WIDTH, true)) {
                    terminalPreferences.edit().putBoolean(KEY_FIXED_WIDTH, it).apply()
                }
                BooleanSetting(
                    R.string.natural_wide_glyphs,
                    R.string.natural_wide_glyphs_support,
                    terminalPreferences.getBoolean(KEY_NATURAL_WIDE_GLYPHS, false),
                ) { terminalPreferences.edit().putBoolean(KEY_NATURAL_WIDE_GLYPHS, it).apply() }
            }
        }
        item {
            var selectedStyle by rememberSaveable { mutableStateOf(CustomTerminalFontStyle.REGULAR) }
            val visibleFonts = customFonts.withIndex().filter { it.value.style == selectedStyle }
            SettingsSection(
                R.string.section_custom_fonts,
                footer = {
                    if (visibleFonts.isEmpty()) InfoSetting(R.string.no_custom_fonts, R.string.no_custom_fonts_support)
                },
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                    CustomTerminalFontStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = selectedStyle == style,
                            onClick = { selectedStyle = style },
                            shape = SegmentedButtonDefaults.itemShape(index, CustomTerminalFontStyle.entries.size),
                        ) {
                            Text(stringResource(if (style == CustomTerminalFontStyle.ITALIC) R.string.custom_font_style_italic else R.string.custom_font_style_regular))
                        }
                    }
                }
                ActionSetting(
                    if (selectedStyle == CustomTerminalFontStyle.ITALIC) R.string.add_italic_font else R.string.add_custom_font,
                    if (selectedStyle == CustomTerminalFontStyle.ITALIC) R.string.add_italic_font_support else R.string.add_custom_font_support,
                ) { openCustomFontPicker(selectedStyle) }
                if (visibleFonts.isNotEmpty()) {
                    visibleFonts.forEachIndexed { visibleIndex, (index, font) ->
                        CustomFontSetting(
                            font = font,
                            priority = visibleIndex + 1,
                            canMoveUp = previousFontIndex(index, font.style) != -1,
                            canMoveDown = nextFontIndex(index, font.style) != -1,
                            onMoveUp = { moveCustomFont(index, previousFontIndex(index, font.style)) },
                            onMoveDown = { moveCustomFont(index, nextFontIndex(index, font.style)) },
                            onRemove = { removeCustomFont(font) },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(R.string.section_palette) {
                ChoiceSetting(
                    R.string.color_palette,
                    R.string.color_palette_support,
                    listOf(
                        Choice(R.string.palette_neutral_dark, "DROID_DARK"),
                        Choice(R.string.palette_solarized_dark, "SOLARIZED_DARK"),
                        Choice(R.string.palette_amoled, "AMOLED"),
                        Choice(R.string.palette_custom, TerminalAppearance.CUSTOM_PALETTE),
                    ),
                    paletteSelection,
                ) { paletteSelection = it; terminalPreferences.edit().putString(KEY_PALETTE, it).apply() }
                ActionSetting(R.string.customize_palette, R.string.customize_palette_support) {
                    startActivity(Intent(this@SettingsDetailActivity, PaletteEditorActivity::class.java))
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.avfItems() {
        item {
            SettingsSection(R.string.section_resources) {
                IntegerSetting(KEY_AVF_MEMORY, R.string.memory, R.string.memory_support, R.string.unit_mib, runtimePreferences.getInt(KEY_AVF_MEMORY, 2048), 512..32768) {
                    saveRuntimeInt(KEY_AVF_MEMORY, it)
                }
                IntegerSetting(KEY_AVF_CPU, R.string.virtual_cpu, R.string.virtual_cpu_support, R.string.unit_count, runtimePreferences.getInt(KEY_AVF_CPU, 2), 1..16) {
                    saveRuntimeInt(KEY_AVF_CPU, it)
                }
                BooleanSetting(R.string.protected_vm, R.string.protected_vm_support, runtimePreferences.getBoolean(KEY_AVF_PROTECTED, false)) {
                    saveRuntimeBoolean(KEY_AVF_PROTECTED, it)
                }
                BooleanSetting(R.string.guest_network, R.string.guest_network_support, runtimePreferences.getBoolean(KEY_AVF_NETWORK, true)) {
                    saveRuntimeBoolean(KEY_AVF_NETWORK, it)
                }
            }
        }
        item {
            SettingsSection(R.string.section_execution) {
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
                ActionSetting(R.string.debian_image, R.string.debian_image_support) {
                    startActivity(
                        Intent(this@SettingsDetailActivity, SetupActivity::class.java)
                            .putExtra(SetupActivity.EXTRA_SHOW_SETUP, true),
                    )
                }
                ActionSetting(R.string.diagnostics_title, R.string.diagnostics_support) {
                    startActivity(Intent(this@SettingsDetailActivity, DiagnosticsActivity::class.java))
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.nativeItems() {
        item {
            SettingsSection(R.string.section_shell) {
                TextSetting(KEY_NATIVE_SHELL, R.string.default_shell, R.string.default_shell_support, savedNativeShell()) {
                    runtimePreferences.edit().putString(KEY_NATIVE_SHELL, it).apply()
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.terminalItems() {
        item {
            SettingsSection(R.string.usb_title) {
                ActionSetting(R.string.usb_title, R.string.usb_summary) {
                    startActivity(Intent(this@SettingsDetailActivity, UsbDevicesActivity::class.java))
                }
            }
        }
        item {
            SettingsSection(R.string.section_terminal_customization) {
                ActionSetting(R.string.toolbar_settings, R.string.toolbar_settings_summary) {
                    startActivity(Intent(this@SettingsDetailActivity, SettingsDetailActivity::class.java)
                        .putExtra(EXTRA_CATEGORY, TOOLBAR))
                }
                ActionSetting(R.string.settings_appearance_title, R.string.settings_appearance_summary) {
                    startActivity(Intent(this@SettingsDetailActivity, SettingsDetailActivity::class.java)
                        .putExtra(EXTRA_CATEGORY, APPEARANCE))
                }
                BooleanSetting(
                    R.string.follow_tui_background,
                    R.string.follow_tui_background_support,
                    terminalPreferences.getBoolean(TerminalAppearance.KEY_FOLLOW_TUI_BACKGROUND, false),
                ) { terminalPreferences.edit().putBoolean(TerminalAppearance.KEY_FOLLOW_TUI_BACKGROUND, it).apply() }
            }
        }
        item {
            SettingsSection(R.string.section_shell) {
                ChoiceSetting(
                    R.string.terminal_type,
                    R.string.terminal_type_support,
                    listOf(Choice(R.string.term_xterm_256, "xterm-256color"), Choice(R.string.term_xterm, "xterm"), Choice(R.string.term_screen_256, "screen-256color")),
                    runtimePreferences.getString(KEY_NATIVE_TERM, "xterm-256color") ?: "xterm-256color",
                ) {
                    runtimePreferences.edit().putString(KEY_NATIVE_TERM, it).apply()
                    syncRuntimeSettings()
                }
                BooleanSetting(R.string.login_shell, R.string.login_shell_support, runtimePreferences.getBoolean(KEY_NATIVE_LOGIN, true)) {
                    saveRuntimeBoolean(KEY_NATIVE_LOGIN, it)
                }
                BooleanSetting(R.string.terminal_open_links, R.string.terminal_open_links_support,
                    terminalPreferences.getBoolean(KEY_OPEN_LINKS, false)) {
                    terminalPreferences.edit().putBoolean(KEY_OPEN_LINKS, it).apply()
                }
                BooleanSetting(R.string.session_task_notifications, R.string.session_task_notifications_support,
                    runtimePreferences.getBoolean(dev.terminal.runtime.notifications.SessionNotificationSettings.KEY_ENABLED, false)) { enabled ->
                    saveRuntimeBoolean(dev.terminal.runtime.notifications.SessionNotificationSettings.KEY_ENABLED, enabled)
                    if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        taskNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                ActionSetting(R.string.session_task_system_settings, R.string.session_task_permission_required) {
                    startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                }
                BooleanSetting(R.string.kitty_remote_control, R.string.kitty_remote_control_support,
                    runtimePreferences.getBoolean(KEY_KITTY_REMOTE, false)) {
                    saveRuntimeBoolean(KEY_KITTY_REMOTE, it)
                }
                BooleanSetting(R.string.kitty_graphics_compatibility, R.string.kitty_graphics_compatibility_support,
                    runtimePreferences.getBoolean(KEY_KITTY_GRAPHICS, false)) {
                    saveRuntimeBoolean(KEY_KITTY_GRAPHICS, it)
                }
            }
        }
        item {
            SettingsSection(R.string.section_history) {
                BooleanSetting(
                    R.string.smooth_scrolling,
                    R.string.smooth_scrolling_support,
                    terminalPreferences.getBoolean(KEY_SMOOTH_SCROLLING, false),
                ) {
                    terminalPreferences.edit().putBoolean(KEY_SMOOTH_SCROLLING, it).apply()
                }
                IntegerSetting(KEY_SCROLLBACK, R.string.scrollback, R.string.scrollback_support, R.string.unit_rows, terminalPreferences.getInt(KEY_SCROLLBACK, 10_000), 1_000..100_000) {
                    terminalPreferences.edit().putInt(KEY_SCROLLBACK, it).apply()
                }
            }
        }
        item {
            SettingsSection(R.string.section_haptics) {
                BooleanSetting(
                    R.string.haptic_feedback,
                    R.string.haptic_feedback_support,
                    terminalPreferences.getBoolean(TerminalHapticPreferences.KEY_ENABLED, true),
                ) {
                    terminalPreferences.edit().putBoolean(TerminalHapticPreferences.KEY_ENABLED, it).apply()
                }
                ChoiceSetting(
                    R.string.haptic_density,
                    R.string.haptic_density_support,
                    listOf(
                        Choice(R.string.haptic_density_low, TerminalHapticDensity.LOW.name),
                        Choice(R.string.haptic_density_standard, TerminalHapticDensity.STANDARD.name),
                        Choice(R.string.haptic_density_high, TerminalHapticDensity.HIGH.name),
                    ),
                    terminalPreferences.getString(TerminalHapticPreferences.KEY_DENSITY, TerminalHapticDensity.STANDARD.name)
                        ?: TerminalHapticDensity.STANDARD.name,
                ) {
                    terminalPreferences.edit().putString(TerminalHapticPreferences.KEY_DENSITY, it).apply()
                }
                BooleanSetting(
                    R.string.haptic_scroll,
                    R.string.haptic_scroll_support,
                    terminalPreferences.getBoolean(TerminalHapticPreferences.KEY_SCROLL, false),
                ) {
                    terminalPreferences.edit().putBoolean(TerminalHapticPreferences.KEY_SCROLL, it).apply()
                }
            }
        }
        sharedStorageItems()
        item {
            SettingsSection(R.string.android_permissions) {
                ActionSetting(R.string.android_permissions, R.string.android_permissions_support) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.rootlessItems() {
        item {
            SettingsSection(R.string.section_status) {
                rootlessRuntimeIds.forEachIndexed { index, runtimeType ->
                    RootlessStatusSetting(runtimeType, rootlessStates.getValue(runtimeType)) {
                        handleRootlessAction(runtimeType)
                    }
                }
            }
        }
        item {
            SettingsSection(R.string.section_environment, hasOptions = false) {
                InfoSetting(R.string.shared_workspace, R.string.shared_workspace_support)
                InfoSetting(R.string.rootless_limitations, R.string.rootless_limitations_support)
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.sharedStorageItems() {
        item {
            SettingsSection(
                R.string.section_shared_storage,
                footer = { InfoSetting(R.string.shared_storage_usage, R.string.shared_storage_usage_support) },
            ) {
                ActionSetting(
                    R.string.shared_storage_access,
                    if (sharedStorageEnabled) R.string.shared_storage_enabled else R.string.shared_storage_disabled,
                    action = ::openSharedStorageSettings,
                )
            }
        }
    }

    private fun updateCustomFonts(fonts: List<CustomTerminalFont>) {
        customFonts = fonts
        saveCustomTerminalFonts(terminalPreferences, fonts)
    }

    private fun openCustomFontPicker(style: CustomTerminalFontStyle) {
        pendingCustomFontStyle = style
        customFontPicker.launch(arrayOf("font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-opentype"))
    }

    private fun previousFontIndex(index: Int, style: CustomTerminalFontStyle): Int =
        (index - 1 downTo 0).firstOrNull { customFonts[it].style == style } ?: -1

    private fun nextFontIndex(index: Int, style: CustomTerminalFontStyle): Int =
        (index + 1..customFonts.lastIndex).firstOrNull { customFonts[it].style == style } ?: -1

    private fun moveCustomFont(index: Int, destination: Int) {
        if (destination != -1) updateCustomFonts(moveCustomTerminalFont(customFonts, index, destination))
    }

    private fun removeCustomFont(font: CustomTerminalFont) {
        updateCustomFonts(customFonts.filterNot { it.uri == font.uri && it.style == font.style })
        if (customFonts.any { it.uri == font.uri }) return
        runCatching {
            contentResolver.releasePersistableUriPermission(Uri.parse(font.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Composable
    private fun SettingsSection(
        @StringRes title: Int,
        hasOptions: Boolean = true,
        footer: @Composable () -> Unit = {},
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(title),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            if (hasOptions) SettingsGroup(content = content) else Column(content = content)
            footer()
        }
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
                confirmButton = { Button(onClick = save, shapes = ButtonDefaults.shapes(), enabled = parsed != null) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { dialogVisible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
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
                confirmButton = { Button(onClick = save, shapes = ButtonDefaults.shapes(), enabled = validated != null) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { dialogVisible = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun BooleanSetting(@StringRes title: Int, @StringRes supporting: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        var checked by rememberSaveable { mutableStateOf(initial) }
        val toggle = { checked = !checked; onChange(checked) }
        ListItem(
            onClick = toggle,
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(),
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().semantics { role = Role.Switch },
        ) { Text(stringResource(title)) }
    }

    @Composable
    private fun ChoiceSetting(@StringRes title: Int, @StringRes supporting: Int, choices: List<Choice>, initial: String, onChange: (String) -> Unit) {
        var selected by rememberSaveable(initial) { mutableStateOf(initial) }
        dev.terminal.app.ui.SettingsChoiceRow(
            title = stringResource(title), supporting = stringResource(supporting),
            choices = choices.map { dev.terminal.app.ui.SettingsChoice(stringResource(it.label), it.value) },
            selected = selected,
        ) { selected = it; onChange(it) }
    }

    @Composable
    private fun ActionSetting(@StringRes title: Int, @StringRes supporting: Int?, supportingText: String? = null, action: () -> Unit) {
        ListItem(
            onClick = action,
            supportingContent = { Text(supportingText ?: supporting?.let { stringResource(it) }.orEmpty()) },
            trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
            verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(),
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(title)) }
    }

    @Composable
    private fun InfoSetting(@StringRes title: Int, @StringRes supporting: Int) {
        Column(
            Modifier.fillMaxWidth().settingsItemSurface().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(supporting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun CustomFontSetting(
        font: CustomTerminalFont,
        priority: Int,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onRemove: () -> Unit,
    ) {
        ListItem(
            supportingContent = {
                val style = if (font.style == CustomTerminalFontStyle.ITALIC) R.string.custom_font_style_italic else R.string.custom_font_style_regular
                Text(stringResource(R.string.custom_font_priority, stringResource(style), priority))
            },
            trailingContent = {
                Row {
                    IconButton(onClick = onMoveUp, shapes = IconButtonDefaults.shapes(), enabled = canMoveUp) {
                        Icon(painterResource(R.drawable.ic_material_symbol_arrow_upward_24), stringResource(R.string.move_font_up))
                    }
                    IconButton(onClick = onMoveDown, shapes = IconButtonDefaults.shapes(), enabled = canMoveDown) {
                        Icon(painterResource(R.drawable.ic_material_symbol_arrow_downward_24), stringResource(R.string.move_font_down))
                    }
                    IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_material_symbol_delete_24), stringResource(R.string.remove_custom_font, font.name))
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(),
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(font.name, maxLines = 1) }
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
            onClick = onClick,
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = trailing,
            verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(),
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth().semantics { this.role = role },
        ) { Text(stringResource(title)) }
    }

    @Composable
    private fun preferenceListItemColors() = terminalSettingsListItemColors()

    @Composable
    private fun RootlessStatusSetting(runtimeType: String, state: RootlessSettingsState, action: () -> Unit) {
        val title = when (state.status) {
            RootlessSettingsStatus.CHECKING -> R.string.rootless_checking
            RootlessSettingsStatus.SETUP_REQUIRED -> R.string.rootless_setup_required
            RootlessSettingsStatus.ENVIRONMENT_REQUIRED -> R.string.rootless_environment_required
            RootlessSettingsStatus.READY -> R.string.rootless_ready
            RootlessSettingsStatus.ERROR -> R.string.rootless_error
        }
        val actionLabel = when (state.status) {
            RootlessSettingsStatus.CHECKING -> null
            RootlessSettingsStatus.ENVIRONMENT_REQUIRED -> R.string.rootless_complete_environment
            RootlessSettingsStatus.READY -> R.string.rootless_remove
            RootlessSettingsStatus.SETUP_REQUIRED -> R.string.install
            RootlessSettingsStatus.ERROR -> R.string.retry
        }
        val statusColor = if (state.status == RootlessSettingsStatus.ERROR) {
            MaterialTheme.colorScheme.error
        } else {
            TerminalStatusBlue
        }
        ListItem(
            supportingContent = {
                Column {
                    Text(if (state.status == RootlessSettingsStatus.CHECKING) state.progress?.label ?: stringResource(title) else stringResource(title), color = statusColor)
                    Text(state.detail.ifBlank { stringResource(R.string.rootless_status_connecting) })
                    if (state.canMigrateToNative) Text(stringResource(R.string.rootless_migrate_description))
                    if (state.status == RootlessSettingsStatus.CHECKING) state.progress?.let {
                        RootlessProgressIndicator(it, running = true, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            leadingContent = {
                if (state.status == RootlessSettingsStatus.CHECKING && state.progress == null) {
                    LoadingIndicator(Modifier.size(24.dp), color = TerminalStatusBlue)
                } else {
                    RuntimeEnvironmentIcon(runtimeType, state.status == RootlessSettingsStatus.ENVIRONMENT_REQUIRED)
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    if (state.canMigrateToNative && state.status != RootlessSettingsStatus.CHECKING) {
                        TextButton(onClick = { migrateRootless(runtimeType) }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.rootless_migrate))
                        }
                    }
                    actionLabel?.let { TextButton(onClick = action, shapes = ButtonDefaults.shapes()) { Text(stringResource(it)) } }
                    if (state.installed && state.status != RootlessSettingsStatus.READY && state.status != RootlessSettingsStatus.CHECKING) {
                        TextButton(onClick = { pendingRootlessRemoval = runtimeType }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.rootless_remove))
                        }
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(),
            colors = preferenceListItemColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(runtimeTitleResource(runtimeType))) }
    }

    private data class Choice(@param:StringRes val label: Int, val value: String)

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_SECTION_INDEX = "section-index"
        const val HOTKEYS = "hotkeys"
        const val TOOLBAR = "toolbar"
        const val LANGUAGE = "language"
        const val APPEARANCE = "appearance"
        const val AVF = "avf"
        const val NATIVE = "native"
        const val TERMINAL = "terminal"
        const val ROOTLESS = "rootless"
        const val TERMINAL_PREFERENCES = "terminal-ui"
        const val RUNTIME_PREFERENCES = "runtime-settings"
        const val KEY_FONT_SIZE = "font-size-sp"
        const val KEY_OPEN_LINKS = "open-web-links"
        const val KEY_FONT_FAMILY = "font-family"
        const val KEY_PALETTE = "palette"
        const val KEY_FIXED_WIDTH = "fixed-width"
        const val KEY_NATURAL_WIDE_GLYPHS = "natural-wide-glyphs"
        const val KEY_SCROLLBACK = "scrollback-rows"
        const val KEY_SMOOTH_SCROLLING = "smooth-scrolling"
        const val KEY_AVF_MEMORY = "avf-memory-mib"
        const val KEY_AVF_CPU = "avf-cpu-count"
        const val KEY_AVF_PROTECTED = "avf-protected"
        const val KEY_AVF_NETWORK = "avf-network"
        const val KEY_WAKE_POLICY = "wake-policy"
        const val KEY_NATIVE_SHELL = "native-shell"
        const val KEY_NATIVE_TERM = "native-term"
        const val KEY_KITTY_REMOTE = "kitty-remote-control"
        const val KEY_KITTY_GRAPHICS = "kitty-graphics-compatibility"
        const val KEY_NATIVE_LOGIN = "native-login-shell"
        private const val DEFAULT_NATIVE_SHELL = "/system/bin/sh"
        private const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        private const val DEFAULT_WAKE_POLICY = "BATTERY_OPTIMIZED"
        private const val ROOTLESS_SETUP_OPERATION = "ROOTLESS_SETUP"
    }
}
