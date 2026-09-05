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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.AppScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONObject

class SettingsDetailActivity : TerminalActivity() {
    private val terminalPreferences by lazy { getSharedPreferences(TERMINAL_PREFERENCES, MODE_PRIVATE) }
    private val runtimePreferences by lazy { getSharedPreferences(RUNTIME_PREFERENCES, MODE_PRIVATE) }
    private val category get() = intent.getStringExtra(EXTRA_CATEGORY) ?: APPEARANCE
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var activityStarted = false
    private var rootlessAvailable by mutableStateOf(false)
    private var rootlessStatus by mutableStateOf("")
    private var wakePolicy by mutableStateOf(DEFAULT_WAKE_POLICY)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!activityStarted) {
                runCatching { unbindService(this) }
                bindingRequested = false
                return
            }
            runtime = IRuntimeService.Stub.asInterface(service)
            bound = true
            refreshServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootlessStatus = getString(R.string.rootless_status_connecting)
        wakePolicy = runtimePreferences.getString(KEY_WAKE_POLICY, DEFAULT_WAKE_POLICY) ?: DEFAULT_WAKE_POLICY
        compose { DetailScreen() }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        bindingRequested = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onStop() {
        activityStarted = false
        if (bindingRequested) runCatching { unbindService(connection) }
        bindingRequested = false
        bound = false
        runtime = null
        super.onStop()
    }

    private fun refreshServiceState() {
        val service = runtime ?: return
        runCatching {
            wakePolicy = service.wakePolicy
            val rootless = JSONObject(service.runtimeStatusJson).optJSONObject("ROOTLESS")
            rootlessAvailable = rootless?.optBoolean("available") == true
            rootlessStatus = rootless?.optString("detail").orEmpty().ifBlank { getString(R.string.rootless_status_connecting) }
        }
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
            IntegerSetting(R.string.font_size, R.string.font_size_support, R.string.unit_sp, terminalPreferences.getInt(KEY_FONT_SIZE, 16), 8..72) {
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
            IntegerSetting(R.string.memory, R.string.memory_support, R.string.unit_mib, runtimePreferences.getInt(KEY_AVF_MEMORY, 2048), 512..32768) {
                runtimePreferences.edit().putInt(KEY_AVF_MEMORY, it).apply()
            }
        }
        item {
            IntegerSetting(R.string.virtual_cpu, R.string.virtual_cpu_support, R.string.unit_count, runtimePreferences.getInt(KEY_AVF_CPU, 2), 1..16) {
                runtimePreferences.edit().putInt(KEY_AVF_CPU, it).apply()
            }
        }
        item {
            BooleanSetting(R.string.protected_vm, R.string.protected_vm_support, runtimePreferences.getBoolean(KEY_AVF_PROTECTED, false)) {
                runtimePreferences.edit().putBoolean(KEY_AVF_PROTECTED, it).apply()
            }
        }
        item {
            BooleanSetting(R.string.guest_network, R.string.guest_network_support, runtimePreferences.getBoolean(KEY_AVF_NETWORK, true)) {
                runtimePreferences.edit().putBoolean(KEY_AVF_NETWORK, it).apply()
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
        item { ActionSetting(R.string.debian_image, R.string.debian_image_support) { startActivity(Intent(this@SettingsDetailActivity, SetupActivity::class.java)) } }
        item { ActionSetting(R.string.diagnostics_title, R.string.diagnostics_support) { startActivity(Intent(this@SettingsDetailActivity, DiagnosticsActivity::class.java)) } }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.nativeItems() {
        item { SectionTitle(R.string.section_shell) }
        item {
            TextSetting(R.string.default_shell, R.string.default_shell_support, runtimePreferences.getString(KEY_NATIVE_SHELL, "/system/bin/sh") ?: "/system/bin/sh") {
                runtimePreferences.edit().putString(KEY_NATIVE_SHELL, it).apply()
            }
        }
        item {
            ChoiceSetting(
                R.string.terminal_type,
                R.string.terminal_type_support,
                listOf(Choice(R.string.term_xterm_256, "xterm-256color"), Choice(R.string.term_xterm, "xterm"), Choice(R.string.term_screen_256, "screen-256color")),
                runtimePreferences.getString(KEY_NATIVE_TERM, "xterm-256color") ?: "xterm-256color",
            ) { runtimePreferences.edit().putString(KEY_NATIVE_TERM, it).apply() }
        }
        item {
            BooleanSetting(R.string.login_shell, R.string.login_shell_support, runtimePreferences.getBoolean(KEY_NATIVE_LOGIN, true)) {
                runtimePreferences.edit().putBoolean(KEY_NATIVE_LOGIN, it).apply()
            }
        }
        item { SectionTitle(R.string.section_history) }
        item {
            IntegerSetting(R.string.scrollback, R.string.scrollback_support, R.string.unit_rows, terminalPreferences.getInt(KEY_SCROLLBACK, 10_000), 1_000..100_000) {
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
        item {
            ActionSetting(if (rootlessAvailable) R.string.rootless_available else R.string.rootless_setup_required, null, rootlessStatus, ::refreshServiceState)
        }
        item { SectionTitle(R.string.section_environment) }
        item { InfoSetting(R.string.shared_workspace, R.string.shared_workspace_support) }
        item { InfoSetting(R.string.rootless_limitations, R.string.rootless_limitations_support) }
    }

    @Composable
    private fun SectionTitle(@StringRes title: Int) {
        Text(stringResource(title), Modifier.padding(top = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }

    @Composable
    private fun IntegerSetting(@StringRes title: Int, @StringRes supporting: Int, @StringRes suffix: Int, initial: Int, range: IntRange, onValid: (Int) -> Unit) {
        var value by remember { mutableStateOf(initial.toString()) }
        val parsed = value.toIntOrNull()
        val error = parsed == null || parsed !in range
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                it.toIntOrNull()?.takeIf(range::contains)?.let(onValid)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(title)) },
            supportingText = { Text(if (error) stringResource(R.string.range_support, range.first, range.last) else stringResource(supporting)) },
            trailingIcon = { Text(stringResource(suffix), Modifier.padding(end = 12.dp)) },
            isError = error,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }

    @Composable
    private fun TextSetting(@StringRes title: Int, @StringRes supporting: Int, initial: String, onValid: (String) -> Unit) {
        var value by remember { mutableStateOf(initial) }
        val valid = value.startsWith("/") && value.length <= 256
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; if (it.startsWith("/") && it.length <= 256) onValid(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(title)) },
            supportingText = { Text(if (valid) stringResource(supporting) else stringResource(R.string.absolute_path_error)) },
            isError = !valid,
            singleLine = true,
        )
    }

    @Composable
    private fun BooleanSetting(@StringRes title: Int, @StringRes supporting: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        var checked by remember { mutableStateOf(initial) }
        val toggle = { checked = !checked; onChange(checked) }
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Switch, onClick = toggle),
        )
    }

    @Composable
    private fun ChoiceSetting(@StringRes title: Int, @StringRes supporting: Int, choices: List<Choice>, initial: String, onChange: (String) -> Unit) {
        var selected by remember(initial) { mutableStateOf(initial) }
        var dialogVisible by remember { mutableStateOf(false) }
        val selectedLabel = choices.firstOrNull { it.value == selected }?.label ?: choices.first().label
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            trailingContent = { Text(stringResource(selectedLabel), style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { dialogVisible = true },
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
                                    selected = choice.value
                                    onChange(choice.value)
                                    dialogVisible = false
                                }.heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == choice.value, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(choice.label))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialogVisible = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun ActionSetting(@StringRes title: Int, @StringRes supporting: Int?, supportingText: String? = null, action: () -> Unit) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(supportingText ?: supporting?.let { stringResource(it) }.orEmpty()) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action),
        )
    }

    @Composable
    private fun InfoSetting(@StringRes title: Int, @StringRes supporting: Int) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(supporting)) },
            modifier = Modifier.fillMaxWidth(),
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
        private const val DEFAULT_WAKE_POLICY = "BATTERY_OPTIMIZED"
    }
}
