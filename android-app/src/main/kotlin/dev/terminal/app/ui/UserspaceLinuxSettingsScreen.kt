@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.app.UserspaceLinuxSettingsActivity
import dev.terminal.runtime.rootless.RootlessDnsPolicy
import dev.terminal.runtime.rootless.RootlessEngineInfo

@Composable
internal fun UserspaceLinuxSettingsScreen(screen: String, policy: RootlessDnsPolicy?, engine: RootlessEngineInfo?,
    unavailable: Boolean, saving: Boolean, saveFailed: Boolean, sectionIndex: Int,
    onBack: () -> Unit, onOpen: (String) -> Unit, onSave: (RootlessDnsPolicy) -> Unit) {
    val title = when (screen) {
        UserspaceLinuxSettingsActivity.DNS -> R.string.userspace_dns
        UserspaceLinuxSettingsActivity.INFO -> R.string.userspace_runtime_info
        UserspaceLinuxSettingsActivity.LIMITATIONS -> R.string.rootless_limitations
        else -> R.string.settings_userspace_title
    }
    val unknown = stringResource(if (unavailable) R.string.userspace_info_unavailable else R.string.userspace_checking)
    fun status(value: Boolean?, yes: Int, no: Int) = when (value) {
        true -> yes
        false -> no
        null -> null
    }
    @Composable fun state(value: Boolean?, yes: Int, no: Int): String = status(value, yes, no)?.let { stringResource(it) } ?: unknown
    SettingsPageScaffold(stringResource(title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            state = rememberLazyListState(initialFirstVisibleItemIndex = sectionIndex.coerceAtLeast(0)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Guidance(when (screen) {
                    UserspaceLinuxSettingsActivity.DNS -> R.string.userspace_dns_support
                    UserspaceLinuxSettingsActivity.INFO -> R.string.userspace_runtime_info_support
                    UserspaceLinuxSettingsActivity.LIMITATIONS -> R.string.rootless_limitations_support
                    else -> R.string.settings_rootless_description
                })
            }
            when (screen) {
                UserspaceLinuxSettingsActivity.DNS -> item {
                    if (policy == null) Text(unknown, Modifier.padding(horizontal = 4.dp))
                    else DnsEditor(policy, saving, saveFailed, onBack, onSave)
                }
                UserspaceLinuxSettingsActivity.INFO -> item {
                    EngineSection(R.string.userspace_runtime) {
                        EngineValue(R.string.userspace_proot, if (engine == null) unknown
                            else engine.prootVersion ?: stringResource(R.string.userspace_info_unavailable))
                        EngineValue(R.string.userspace_loader, state(engine?.loaderAvailable, R.string.userspace_normal, R.string.userspace_unavailable))
                        EngineValue(R.string.userspace_acceleration_profile, state(engine?.acceleration, R.string.userspace_active, R.string.userspace_inactive))
                        EngineValue(R.string.userspace_faststat, state(engine?.faststat, R.string.userspace_active, R.string.userspace_inactive))
                        EngineValue(R.string.userspace_abi, engine?.let {
                            if (it.arm64) "arm64-v8a" else stringResource(R.string.userspace_unsupported)
                        } ?: unknown)
                        EngineValue(R.string.userspace_storage_links, stringResource(R.string.userspace_environment_automatic))
                    }
                }
                UserspaceLinuxSettingsActivity.LIMITATIONS -> {
                    listOf(R.string.userspace_limit_root, R.string.userspace_limit_namespaces, R.string.userspace_limit_cgroups,
                        R.string.userspace_limit_mounts, R.string.userspace_limit_systemd,
                        R.string.userspace_limit_overhead, R.string.userspace_limit_kernel).forEach { text ->
                        item { Guidance(text) }
                    }
                }
                else -> {
                    item {
                        EngineSection(R.string.userspace_network) {
                            EngineLink(R.string.userspace_dns, policy?.let { stringResource(
                                if (it.mode == RootlessDnsPolicy.ANDROID) R.string.userspace_dns_android else R.string.userspace_dns_custom)
                            } ?: unknown) { onOpen(UserspaceLinuxSettingsActivity.DNS) }
                        }
                    }
                    item {
                        EngineSection(R.string.userspace_runtime) {
                            EngineValue(R.string.userspace_proot, state(engine?.prootAvailable, R.string.userspace_available, R.string.userspace_unavailable))
                            EngineValue(R.string.userspace_architecture, state(engine?.arm64, R.string.userspace_arm64, R.string.userspace_unsupported))
                            EngineValue(R.string.userspace_acceleration, state(engine?.acceleration, R.string.userspace_active, R.string.userspace_inactive))
                        }
                    }
                    item {
                        EngineSection(R.string.userspace_information) {
                            EngineLink(R.string.userspace_runtime_info) { onOpen(UserspaceLinuxSettingsActivity.INFO) }
                            EngineLink(R.string.rootless_limitations) { onOpen(UserspaceLinuxSettingsActivity.LIMITATIONS) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(title), Modifier.padding(start = 16.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        SettingsGroup(content = content)
    }
}

@Composable
private fun EngineValue(@StringRes title: Int, value: String) {
    ListItem(trailingContent = { SelectionContainer {
        Text(value, Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.labelLarge)
    } }, verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
        modifier = Modifier.fillMaxWidth().settingsItemSurface()) { Text(stringResource(title)) }
}

@Composable
private fun EngineLink(@StringRes title: Int, summary: String? = null, onClick: () -> Unit) {
    ListItem(onClick = onClick, supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
}

@Composable
private fun Guidance(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun DnsEditor(saved: RootlessDnsPolicy, saving: Boolean, saveFailed: Boolean,
    onCancel: () -> Unit, onSave: (RootlessDnsPolicy) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(saved.mode) }
    var servers by rememberSaveable { mutableStateOf(saved.servers.joinToString("\n")) }
    val draft = runCatching { RootlessDnsPolicy.parse(mode, servers) }.getOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.selectableGroup()) {
            listOf(RootlessDnsPolicy.ANDROID to R.string.userspace_dns_android,
                RootlessDnsPolicy.CUSTOM to R.string.userspace_dns_custom).forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(mode == value,
                    enabled = !saving, role = Role.RadioButton, onClick = { mode = value }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == value, onClick = null, enabled = !saving)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label))
                }
            }
        }
        if (mode == RootlessDnsPolicy.CUSTOM) {
            OutlinedTextField(servers, { servers = it }, Modifier.fillMaxWidth(), enabled = !saving,
                label = { Text(stringResource(R.string.userspace_dns_servers)) }, minLines = 3, maxLines = 5,
                isError = draft == null && servers.isNotBlank(), supportingText = {
                    Text(stringResource(if (draft == null && servers.isNotBlank()) R.string.userspace_dns_invalid else R.string.userspace_dns_input_help))
                })
        }
        if (saveFailed) Text(stringResource(R.string.userspace_dns_save_failed), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onCancel, enabled = !saving, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            Button({ draft?.let(onSave) }, enabled = draft != null && !saving, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
