@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

internal enum class UsbSettingsAction { APPROVE, DENY, REVOKE, REMOVE_STORAGE }
internal data class UsbSettingsCommand(val action: UsbSettingsAction, val id: String)

@Composable
internal fun UsbSettingsScreen(
    state: UsbSettingsSnapshot?, connected: Boolean, busy: Boolean, error: String,
    onBack: () -> Unit, onCommand: (UsbSettingsCommand) -> Unit,
    onStorage: () -> Unit, onHostInputs: () -> Unit, onDismissError: () -> Unit,
) {
    var confirm by remember { mutableStateOf<UsbSettingsCommand?>(null) }
    val request = state?.requests?.firstOrNull { it.requestId == confirm?.id }
    LaunchedEffect(state, connected) {
        if (!connected || (confirm?.action == UsbSettingsAction.APPROVE && request == null)) confirm = null
    }
    SettingsPageScaffold(stringResource(R.string.usb_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text(stringResource(R.string.usb_screen_intro), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (error.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onDismissError, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.usb_dismiss_error)) }
                    }
                }
            }
            if (state == null) item {
                UsbSection(R.string.usb_section_devices) {
                    ListItem(modifier = Modifier.settingsItemSurface(), colors = usbListColors(), leadingContent = { LoadingIndicator(Modifier.size(32.dp)) },
                        supportingContent = { Text(stringResource(R.string.usb_connecting_hint)) },
                        verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.usb_connecting)) }
                }
            }
            if (state != null) {
                if (state.requests.isNotEmpty()) item {
                    UsbSection(R.string.usb_section_requests, state.requests.size) {
                        state.requests.forEach { access ->
                            AccessRow(access, state, pending = true)
                            if (access.waiting) Text(stringResource(R.string.usb_waiting_permission),
                                Modifier.padding(horizontal = 16.dp), color = TerminalStatusBlue, style = MaterialTheme.typography.bodyMedium)
                            FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { confirm = UsbSettingsCommand(UsbSettingsAction.APPROVE, access.requestId) },
                                    enabled = connected && !busy && !access.waiting, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.usb_approve)) }
                                TextButton(onClick = { onCommand(UsbSettingsCommand(UsbSettingsAction.DENY, access.requestId)) },
                                    enabled = connected && !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.usb_deny)) }
                            }
                        }
                    }
                }
                item {
                    UsbSection(R.string.usb_section_devices, state.devices.size) {
                        if (!state.supported) UsbEmpty(R.string.usb_unsupported, R.string.usb_host_hint)
                        else if (state.devices.isEmpty()) UsbEmpty(R.string.usb_no_devices, R.string.usb_cable_hint)
                        state.devices.forEach { device ->
                            key(device.id) { DeviceRow(device, state.grants.count { it.device == device.id && it.active }) }
                        }
                    }
                }
                if (state.requests.isEmpty()) item {
                    UsbSection(R.string.usb_section_requests) { UsbEmpty(R.string.usb_no_requests_title, R.string.usb_no_requests_hint) }
                }
                item {
                    UsbSection(R.string.usb_section_grants, state.grants.size) {
                        if (state.grants.isEmpty()) UsbEmpty(R.string.usb_no_grants, R.string.usb_grants_hint)
                        state.grants.forEach { access ->
                            AccessRow(access, state, pending = false)
                            TextButton(onClick = { confirm = UsbSettingsCommand(UsbSettingsAction.REVOKE, access.session) },
                                enabled = connected && !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(stringResource(R.string.usb_revoke), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                UsbSection(R.string.usb_section_storage, state?.storage?.size) {
                    ListItem(onClick = onStorage, enabled = connected && !busy, leadingContent = { UsbIcon(R.drawable.ic_material_symbol_add_24) },
                        trailingContent = { UsbIcon(R.drawable.ic_material_symbol_chevron_right_24) }, shapes = terminalListItemShapes(),
                        colors = usbListColors(), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.usb_add_storage)) }
                    state?.storage?.forEach { root ->
                        ListItem(modifier = Modifier.settingsItemSurface(), colors = usbListColors(), supportingContent = {
                            Text(stringResource(when {
                                !root.available -> R.string.usb_storage_unavailable
                                root.writable -> R.string.usb_storage_writable
                                else -> R.string.usb_storage_readonly
                            }), color = if (root.available) TerminalStatusBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                        }, verticalAlignment = Alignment.CenterVertically) { Text(root.name.ifEmpty { stringResource(R.string.usb_storage_folder) }) }
                        TextButton(onClick = { confirm = UsbSettingsCommand(UsbSettingsAction.REMOVE_STORAGE, root.id) },
                            enabled = connected && !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.usb_remove_storage))
                        }
                    }
                }
                Text(stringResource(R.string.usb_storage_help), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                UsbSection(R.string.usb_section_tools) {
                    ListItem(onClick = onHostInputs, supportingContent = { Text(stringResource(R.string.usb_host_inputs_hint)) },
                        trailingContent = { UsbIcon(R.drawable.ic_material_symbol_chevron_right_24) },
                        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(), colors = usbListColors()) {
                        Text(stringResource(R.string.host_tool_inputs_title))
                    }
                }
            }
            item {
                var help by rememberSaveable { mutableStateOf(false) }
                var diagnostics by rememberSaveable { mutableStateOf(false) }
                UsbSection(R.string.usb_section_help) {
                    Column {
                        ExpandRow(R.string.usb_usage_title, help, { help = !help })
                        SettingsExpansionContent(help) {
                            Text(stringResource(R.string.usb_help), Modifier.padding(top = SettingsItemSpacing).padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column {
                        ExpandRow(R.string.usb_diagnostics, diagnostics, { diagnostics = !diagnostics }, enabled = state != null)
                        SettingsExpansionContent(diagnostics && state != null) {
                            Column(Modifier.padding(top = SettingsItemSpacing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state?.diagnostics?.forEach { (key, count) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(stringResource(when (key) {
                                            "rawConnections" -> R.string.usb_diag_raw
                                            "serialConnections" -> R.string.usb_diag_serial
                                            "iphoneOperations" -> R.string.usb_diag_iphone
                                            "fileConnections" -> R.string.usb_diag_files
                                            else -> R.string.usb_section_requests
                                        }), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { action ->
        val approval = action.action == UsbSettingsAction.APPROVE
        val revoke = action.action == UsbSettingsAction.REVOKE
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (approval) R.string.usb_approve else if (revoke) R.string.usb_revoke_title else R.string.usb_remove_storage)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (approval && request != null) {
                        item { Text(accessTitle(request)) }
                        item { Text(stringResource(R.string.usb_session_label, runtimeLabel(request.runtime), request.session)) }
                        item { Text(stringResource(R.string.usb_target_label, targetLabel(request, state))) }
                        if (!request.driver.startsWith("storage-")) item { Text(stringResource(R.string.usb_device_id, request.device)) }
                        if (request.driver.isNotEmpty() && request.driver !in setOf("configuration", "storage-read", "storage-write"))
                            item { Text(stringResource(R.string.usb_driver_port, request.driver, request.port)) }
                        if (request.scope.isNotEmpty()) item { SelectionContainer { Text(request.scope) } }
                        item { Text(stringResource(accessConfirmation(request))) }
                        if (request.tool.isNotEmpty()) item { Text(stringResource(if (request.tool == "isideload") R.string.host_tool_signing_scope else R.string.host_tool_probe_scope)) }
                    } else item { Text(stringResource(if (revoke) R.string.usb_revoke_confirmation else R.string.usb_remove_storage_confirmation)) }
                }
            },
            confirmButton = { Button(onClick = { onCommand(action); confirm = null },
                enabled = connected && !busy && (!approval || (request != null && !request.waiting)), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (approval) R.string.usb_approve else R.string.usb_confirm_remove))
            } },
            dismissButton = { TextButton(onClick = { confirm = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun DeviceRow(device: UsbDeviceUi, active: Int) {
    var expanded by rememberSaveable(device.id) { mutableStateOf(false) }
    val expansion = stringResource(if (expanded) R.string.usb_expanded else R.string.usb_collapsed)
    Column {
        ListItem(onClick = { expanded = !expanded }, shapes = terminalListItemShapes(), colors = usbListColors(),
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansion },
            supportingContent = {
                Text(device.hardwareId + " · " + stringResource(if (active > 0) R.string.usb_connection_active else R.string.usb_device_connected),
                    color = if (active > 0) TerminalStatusBlue else MaterialTheme.colorScheme.onSurfaceVariant)
            }, trailingContent = { SettingsExpansionIcon(expanded) }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (device.iphone) R.string.usb_device_iphone else if (device.cdc || device.serialDrivers.isNotEmpty()) R.string.usb_device_serial else R.string.usb_device_generic))
        }
        SettingsExpansionContent(expanded) {
            SelectionContainer(Modifier.padding(top = SettingsItemSpacing)) {
                Column(Modifier.settingsItemSurface().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.usb_device_id, device.id), style = MaterialTheme.typography.bodySmall)
                    if (device.serialDrivers.isNotEmpty()) Text(stringResource(R.string.usb_serial_drivers, device.serialDrivers.joinToString()), style = MaterialTheme.typography.bodySmall)
                    device.interfaces.forEach { intf ->
                        Text(stringResource(R.string.usb_interface_details, intf.configuration, intf.id, intf.alternate) + "\n" +
                            stringResource(when {
                                intf.deviceClass == 255 && intf.subclass == 66 && intf.protocol == 1 -> R.string.usb_protocol_adb
                                intf.deviceClass == 6 && intf.subclass == 1 && intf.protocol == 1 -> R.string.usb_protocol_mtp
                                intf.deviceClass == 3 -> R.string.usb_protocol_hid
                                intf.deviceClass == 8 -> R.string.usb_protocol_storage
                                device.iphone && intf.deviceClass == 255 && intf.subclass == 254 && intf.protocol == 2 -> R.string.usb_protocol_iphone
                                else -> R.string.usb_protocol_raw
                            }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessRow(access: UsbAccessUi, state: UsbSettingsSnapshot, pending: Boolean) {
    ListItem(modifier = Modifier.settingsItemSurface(), colors = usbListColors(), supportingContent = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.usb_session_label, runtimeLabel(access.runtime), access.session))
            Text(stringResource(R.string.usb_target_label, targetLabel(access, state)))
            if (!access.driver.startsWith("storage-")) Text(stringResource(R.string.usb_device_id, access.device))
            if (access.scope.isNotEmpty()) SelectionContainer { Text(access.scope) }
            if (access.driver.isNotEmpty() && access.driver !in setOf("configuration", "storage-read", "storage-write"))
                Text(stringResource(R.string.usb_driver_port, access.driver, access.port))
            if (!pending) Text(stringResource(if (access.active) R.string.usb_connection_active else R.string.usb_grant_idle),
                color = if (access.active) TerminalStatusBlue else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, verticalAlignment = Alignment.CenterVertically) { Text(accessTitle(access)) }
}

@Composable
private fun targetLabel(access: UsbAccessUi, state: UsbSettingsSnapshot?): String {
    if (access.driver.startsWith("storage-")) return state?.storage?.firstOrNull { it.id == access.device }?.name ?: access.device
    val name = state?.devices?.firstOrNull { it.id == access.device }?.hardwareId ?: "%04x:%04x".format(access.vendor, access.product)
    return name + " · " + when {
        access.interfaceId < 0 -> stringResource(R.string.usb_device_exclusive) +
            if (access.configuration > 0) " · " + stringResource(R.string.usb_interface_details, access.configuration, access.interfaceId, access.alternate) else ""
        access.configuration > 0 -> stringResource(R.string.usb_interface_details, access.configuration, access.interfaceId, access.alternate)
        else -> stringResource(R.string.usb_interface_label, access.interfaceId)
    }
}
private fun runtimeLabel(runtime: String): String = when (runtime) {
    "NATIVE" -> "Native"
    "ROOTLESS" -> "Rootless Debian"
    "ROOTLESS_UBUNTU" -> "Rootless Ubuntu"
    "ROOTLESS_ARCH" -> "Rootless Arch Linux"
    else -> runtime
}
@Composable
private fun accessTitle(access: UsbAccessUi): String = when {
    access.tool.isNotEmpty() -> stringResource(R.string.usb_tool_title, access.tool)
    else -> stringResource(when {
        access.driver == "storage-read" -> R.string.usb_storage_read_grant
        access.driver == "storage-write" -> R.string.usb_storage_write_grant
        access.driver == "configuration" -> R.string.usb_configuration_grant
        access.iphone == "pair" -> R.string.usb_iphone_pair
        access.iphone == "info" -> R.string.usb_iphone_info
        access.iphone == "unpair" -> R.string.usb_iphone_unpair
        access.iphone == "afc-list" -> R.string.usb_iphone_list
        access.iphone == "afc-get" -> R.string.usb_iphone_get
        access.iphone == "afc-put" -> R.string.usb_iphone_put
        access.driver.isNotEmpty() -> R.string.usb_serial_driver
        access.serial -> R.string.usb_cdc
        else -> R.string.usb_raw
    })
}
@StringRes
private fun accessConfirmation(access: UsbAccessUi) = when {
    access.driver == "configuration" -> R.string.usb_configuration_confirmation
    access.tool.isNotEmpty() -> R.string.usb_tool_confirmation
    access.driver.startsWith("storage-") -> R.string.usb_storage_confirmation
    access.iphone.isNotEmpty() -> R.string.usb_iphone_confirmation
    else -> R.string.usb_confirmation
}
@Composable
private fun UsbSection(@StringRes title: Int, count: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(stringResource(title) + (count?.let { " · $it" } ?: ""), Modifier.padding(start = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        SettingsGroup(content = content)
    }
}
@Composable
private fun UsbEmpty(@StringRes title: Int, @StringRes hint: Int) {
    ListItem(modifier = Modifier.settingsItemSurface(), colors = usbListColors(), supportingContent = { Text(stringResource(hint)) },
        verticalAlignment = Alignment.CenterVertically) { Text(stringResource(title)) }
}
@Composable
private fun usbListColors() = terminalSettingsListItemColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
@Composable
private fun UsbIcon(id: Int) { Icon(painterResource(id), null, Modifier.size(24.dp)) }
@Composable
private fun ExpandRow(@StringRes title: Int, expanded: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val expansion = stringResource(if (expanded) R.string.usb_expanded else R.string.usb_collapsed)
    ListItem(onClick = onClick, enabled = enabled, trailingContent = { SettingsExpansionIcon(expanded) },
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansion },
        shapes = terminalListItemShapes(), colors = usbListColors(), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(title)) }
}
