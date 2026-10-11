@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import dev.terminal.app.R
import dev.terminal.app.UserspaceLinuxSettingsActivity
import dev.terminal.runtime.rootless.RootlessDnsPolicy

@Composable
internal fun UserspaceLinuxSettingsScreen(screen: String, policy: RootlessDnsPolicy?,
    unavailable: Boolean, saving: Boolean, saveFailed: Boolean, sectionIndex: Int,
    onBack: () -> Unit, onOpen: (String) -> Unit, onSave: (RootlessDnsPolicy) -> Unit) {
    val dns = screen == UserspaceLinuxSettingsActivity.DNS
    val unknown = stringResource(if (unavailable) R.string.userspace_info_unavailable else R.string.userspace_checking)
    SettingsPageScaffold(stringResource(if (dns) R.string.userspace_dns else R.string.settings_userspace_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            state = rememberLazyListState(initialFirstVisibleItemIndex = sectionIndex.coerceIn(0, 1)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Guidance(if (dns) R.string.userspace_dns_support else R.string.settings_rootless_description) }
            item {
                if (dns) {
                    if (policy == null) Text(unknown, Modifier.padding(horizontal = 4.dp))
                    else DnsEditor(policy, saving, saveFailed, onBack, onSave)
                } else EngineSection(R.string.userspace_network) {
                    EngineLink(R.string.userspace_dns, policy?.let { stringResource(
                        if (it.mode == RootlessDnsPolicy.ANDROID) R.string.userspace_dns_android else R.string.userspace_dns_custom)
                    } ?: unknown) { onOpen(UserspaceLinuxSettingsActivity.DNS) }
                }
            }
        }
    }
}

@Composable
private fun EngineSection(@StringRes title: Int, groupModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(title), Modifier.padding(start = 16.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        SettingsGroup(groupModifier, content = content)
    }
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
    var servers by rememberSaveable { mutableStateOf(saved.servers.toTypedArray()) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var serverInput by rememberSaveable { mutableStateOf("") }
    val draft = runCatching { RootlessDnsPolicy.parse(mode, servers.joinToString("\n")) }.getOrNull()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val layoutDirection = LocalLayoutDirection.current
    val itemPadding = ListItemDefaults.ContentPadding
    // A 48dp button needs 12dp less end padding to align with a 24dp trailing icon.
    val serverPadding = PaddingValues(
        start = itemPadding.calculateStartPadding(layoutDirection),
        end = itemPadding.calculateEndPadding(layoutDirection) - 12.dp,
        top = itemPadding.calculateTopPadding(), bottom = itemPadding.calculateBottomPadding(),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EngineSection(R.string.userspace_dns_mode, Modifier.selectableGroup()) {
            listOf(RootlessDnsPolicy.ANDROID to R.string.userspace_dns_android,
                RootlessDnsPolicy.CUSTOM to R.string.userspace_dns_custom).forEach { (value, label) ->
                ListItem(onClick = { mode = value }, enabled = !saving,
                    trailingContent = { RadioButton(mode == value, onClick = null, enabled = !saving) },
                    verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                    colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth().semantics {
                        role = Role.RadioButton
                        selected = mode == value
                    }) { Text(stringResource(label)) }
            }
        }
        if (mode == RootlessDnsPolicy.CUSTOM) {
            EngineSection(R.string.userspace_dns_servers) {
                AnimatedContent(
                    targetState = servers.toList(), modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        (fadeIn(effectsSpec) togetherWith fadeOut(effectsSpec)).using(
                            SizeTransform { _, _ -> sizeSpec })
                    }, label = "dnsServers",
                ) { displayedServers ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsItemSpacing)) {
                        displayedServers.forEach { server -> key(server) {
                            ListItem(trailingContent = {
                                FloatingToolbarAction(stringResource(R.string.userspace_dns_remove_server, server),
                                    R.drawable.ic_material_symbol_remove_24, enabled = !saving && server in servers,
                                    onClick = { servers = servers.filter { it != server }.toTypedArray() },
                                    iconSize = 20.dp)
                            }, verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
                                contentPadding = serverPadding,
                                modifier = Modifier.fillMaxWidth().settingsItemSurface()) {
                                Text(server)
                            }
                        } }
                        ListItem(onClick = { serverInput = ""; adding = true },
                            enabled = !saving && servers.size < RootlessDnsPolicy.MAX_SERVERS,
                            leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_add_24), null) },
                            supportingContent = { Text(stringResource(if (servers.size >= RootlessDnsPolicy.MAX_SERVERS)
                                R.string.userspace_dns_limit else R.string.userspace_dns_input_help)) },
                            verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                            colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.userspace_dns_add_server))
                        }
                    }
                }
            }
        }
        if (saveFailed) Text(stringResource(R.string.userspace_dns_save_failed), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onCancel, enabled = !saving, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            Button({ draft?.let(onSave) }, enabled = draft != null && !saving, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.save))
            }
        }
    }
    if (adding) DnsServerDialog(serverInput, { serverInput = it }, servers.toList(), saving,
        onDismiss = { adding = false }, onAdd = { server ->
            if (!saving && servers.size < RootlessDnsPolicy.MAX_SERVERS && server !in servers) servers += server
            adding = false
        })
}

@Composable
private fun DnsServerDialog(input: String, onInput: (String) -> Unit, servers: List<String>, saving: Boolean,
    onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val address = input.trim()
    val validAddress = runCatching { RootlessDnsPolicy.parse(RootlessDnsPolicy.CUSTOM, address).servers.single() }
        .getOrNull()?.takeIf { it == address }
    val duplicate = validAddress != null && validAddress in servers
    val canAdd = validAddress != null && !duplicate && !saving && servers.size < RootlessDnsPolicy.MAX_SERVERS
    val add = { if (canAdd) onAdd(checkNotNull(validAddress)) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.userspace_dns_add_server)) },
        text = {
            OutlinedTextField(input, onInput, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.userspace_dns_address)) },
                isError = input.isNotBlank() && (validAddress == null || duplicate),
                supportingText = { Text(stringResource(when {
                    duplicate -> R.string.userspace_dns_duplicate
                    input.isNotBlank() && validAddress == null -> R.string.userspace_dns_invalid
                    else -> R.string.userspace_dns_address_help
                })) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }))
        },
        confirmButton = { Button(onClick = add, enabled = canAdd, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.userspace_dns_add))
        } },
        dismissButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.cancel))
        } })
}
