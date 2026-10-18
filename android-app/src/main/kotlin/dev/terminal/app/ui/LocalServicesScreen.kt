@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.forwarding.*

@Composable
internal fun LocalServicesScreen(snapshot: LocalServicesSnapshot?, loading: Boolean, unavailable: Boolean,
    saving: Boolean, actionError: String?, onBack: () -> Unit, onRefresh: () -> Unit,
    onForward: (String, Int, String, () -> Unit) -> Unit, onCopy: (String) -> Unit,
    onBrowse: (LocalService) -> Unit, onDismissError: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val services = snapshot?.services.orEmpty()
    val selected = snapshot?.services?.firstOrNull { it.id == selectedId }
    SettingsPageScaffold(stringResource(R.string.local_services_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Text(stringResource(R.string.local_services_description), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                SettingsGroup {
                    ListItem(onClick = onRefresh, enabled = !loading,
                        leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), null) },
                        supportingContent = if (loading) { { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) } } else null,
                        shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (loading) R.string.local_services_loading else R.string.refresh))
                    }
                }
                if (unavailable) {
                    Text(stringResource(R.string.local_services_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                snapshot?.storageError?.let {
                    Text(errorLabel(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                snapshot?.let { current ->
                    val connections = current.links.values
                    val connected = connections.count { it == "CONNECTED" }
                    val connecting = connections.count { it == "CONNECTING" }
                    val disconnected = connections.count { it == "DISCONNECTED" }
                    val summary = if (connected + connecting + disconnected == 0)
                        stringResource(R.string.local_services_no_avf_link)
                    else stringResource(R.string.local_services_avf_links, connected, connecting, disconnected)
                    Text(summary, Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
            }
            if (snapshot != null && services.isEmpty()) item {
                SettingsGroup {
                    ListItem(supportingContent = { Text(stringResource(R.string.local_services_empty_hint)) },
                        colors = terminalSettingsListItemColors(), modifier = Modifier.settingsItemSurface(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.local_services_empty))
                    }
                }
            }
            itemsIndexed(services, key = { _, service -> service.id }) { index, service ->
                val corners = MaterialTheme.shapes.extraLarge
                val shape = corners.copy(topStart = if (index == 0) corners.topStart else CornerSize(4.dp),
                    topEnd = if (index == 0) corners.topEnd else CornerSize(4.dp),
                    bottomStart = if (index == services.lastIndex) corners.bottomStart else CornerSize(4.dp),
                    bottomEnd = if (index == services.lastIndex) corners.bottomEnd else CornerSize(4.dp))
                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    ListItem(onClick = { selectedId = service.id },
                        leadingContent = { Icon(painterResource(statusIcon(service)), null,
                            tint = if (service.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${environmentLabel(service, snapshot)} · TCP ${service.port}" +
                                    if (service.applicationProtocol != ApplicationProtocol.UNKNOWN) " · ${service.applicationProtocol.name}" else "")
                                Text(serviceStatus(service), color = if (service.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                service.androidAddress?.let { Text(it, fontFamily = LocalTerminalMonospaceFontFamily.current) }
                            }
                        }, shapes = terminalListItemShapes(),
                        colors = terminalSettingsListItemColors(MaterialTheme.colorScheme.surfaceContainerLow),
                        verticalAlignment = Alignment.CenterVertically) { Text(service.name ?: "TCP ${service.port}") }
                }
            }
            item {
                Text(stringResource(R.string.local_services_limitations), Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (selected != null) AlertDialog(onDismissRequest = { selectedId = null },
        title = { Text(selected.name ?: "TCP ${selected.port}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(R.string.local_services_environment, environmentLabel(selected, snapshot))
                InfoRow(R.string.local_services_status, serviceStatus(selected))
                InfoRow(R.string.local_services_source_address, selected.sourceAddress?.let { address ->
                    if (':' in address) "[$address]:${selected.port}" else "$address:${selected.port}"
                } ?: stringResource(R.string.local_services_unknown), monospace = true)
                InfoRow(R.string.local_services_transport, "TCP")
                InfoRow(R.string.local_services_application_protocol, protocolLabel(selected.applicationProtocol))
                InfoRow(R.string.local_services_source, stringResource(when (selected.source) {
                    ServiceSource.AVF_AGENT -> R.string.local_services_agent_source
                    ServiceSource.LEGACY_AVF_AGENT -> R.string.local_services_legacy_source
                }))
                InfoRow(R.string.local_services_android_address, selected.androidAddress ?: stringResource(R.string.local_services_unconfirmed), true)
                InfoRow(R.string.local_services_mapping,
                    "${selected.port} → ${selected.hostPort ?: stringResource(R.string.local_services_unknown)}")
                selected.attemptedHostPort?.let { InfoRow(R.string.local_services_attempted_port, it.toString()) }
                InfoRow(R.string.local_services_identity, selected.id, true)
                InfoRow(R.string.local_services_environment_id, selected.environmentId ?: stringResource(R.string.local_services_unknown))
                InfoRow(R.string.local_services_session, selected.sessionId ?: stringResource(R.string.local_services_unknown))
                Text(stringResource(R.string.local_services_forward_hint), style = MaterialTheme.typography.bodySmall)
                selected.androidAddress?.let { address ->
                    TextButton(onClick = { onCopy(address) }) { Text(stringResource(R.string.local_services_copy_address)) }
                    TextButton(onClick = { onCopy(requireNotNull(selected.hostPort).toString()) }) { Text(stringResource(R.string.local_services_copy_port)) }
                }
                if (selected.browserUrl != null) TextButton(onClick = { onBrowse(selected) }) { Text(stringResource(R.string.local_services_open_browser)) }
                if (selected.source == ServiceSource.AVF_AGENT && selected.observation == ServiceObservation.LISTENING) {
                    TextButton(onClick = { onDismissError(); editorId = selected.id; selectedId = null }, enabled = !saving) { Text(stringResource(R.string.local_services_edit_mapping)) }
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.close)) } })
    val currentEditorId = editorId
    if (currentEditorId != null && snapshot != null) key(currentEditorId) {
        val existing = snapshot.services.firstOrNull { it.id == currentEditorId }
        if (existing?.source == ServiceSource.AVF_AGENT && existing.observation == ServiceObservation.LISTENING) {
            ServiceEditor(existing, saving, actionError,
                { if (!saving) editorId = null }, onDismissError) { port, protocol ->
                onForward(currentEditorId, port, protocol) { editorId = null }
            }
        }
    }
}

@Composable private fun ServiceEditor(service: LocalService, saving: Boolean,
    error: String?, onDismiss: () -> Unit, clearError: () -> Unit, onSave: (Int, String) -> Unit) {
    var port by rememberSaveable { mutableStateOf(service.hostPort?.toString().orEmpty()) }
    var protocol by rememberSaveable { mutableStateOf(service.applicationProtocol.name) }
    val numericPort = port.takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()
    val validPort = numericPort != null && numericPort in 1024..65_535
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.local_services_edit_mapping)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it; clearError() },
                    label = { Text(stringResource(R.string.local_services_host_port)) },
                    supportingText = { Text(stringResource(R.string.local_services_host_port_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = port.isNotEmpty() && !validPort, singleLine = true, enabled = !saving)
                Text(stringResource(R.string.local_services_application_protocol), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApplicationProtocol.entries.forEach { value -> FilterChip(selected = protocol == value.name,
                        onClick = { protocol = value.name; clearError() }, enabled = !saving, label = { Text(protocolLabel(value)) }) }
                }
                Text(stringResource(R.string.local_services_forward_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(errorLabel(it), color = MaterialTheme.colorScheme.error) }
                if (saving) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = !saving && validPort, onClick = {
            onSave(requireNotNull(numericPort), protocol)
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun InfoRow(label: Int, value: String, monospace: Boolean = false) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, fontFamily = if (monospace) LocalTerminalMonospaceFontFamily.current else null) }
    }
}
@Composable private fun environmentLabel(service: LocalService, snapshot: LocalServicesSnapshot?): String {
    val name = snapshot?.environments?.firstOrNull { it.id == service.environmentId }?.name
        ?: stringResource(R.string.local_services_environment_unavailable)
    return "$name · AVF Linux"
}
@Composable private fun protocolLabel(protocol: ApplicationProtocol) = if (protocol == ApplicationProtocol.UNKNOWN)
    stringResource(R.string.local_services_unknown) else protocol.name
@Composable private fun serviceStatus(service: LocalService): String {
    val observation = stringResource(when (service.observation) {
        ServiceObservation.LISTENING -> R.string.local_services_discovered
        ServiceObservation.UPDATING -> R.string.local_services_updating
        ServiceObservation.DISCONNECTED -> R.string.local_services_disconnected
        ServiceObservation.CLOSED -> R.string.local_services_closed
    })
    val forwarding = when (service.forwarding) {
        ForwardingState.NONE -> ""
        ForwardingState.ACTIVE -> stringResource(R.string.local_services_forward_active)
        ForwardingState.CONFLICT -> stringResource(R.string.local_services_conflict)
        ForwardingState.FAILED -> stringResource(R.string.local_services_forward_failed)
        ForwardingState.UNAVAILABLE -> stringResource(R.string.local_services_proxy_unavailable)
    }
    return listOf(observation, forwarding, service.error?.let { errorLabel(it) }.orEmpty()).filter(String::isNotEmpty).distinct().joinToString(" · ")
}
private fun statusIcon(service: LocalService) = when {
    service.error != null -> R.drawable.ic_app_info_24
    service.forwarding == ForwardingState.ACTIVE -> R.drawable.ic_material_symbol_check_circle_24
    else -> R.drawable.ic_material_symbol_lan_24
}
@Composable private fun errorLabel(error: String): String = stringResource(when (error) {
    "PORT_CONFLICT" -> R.string.local_services_conflict
    "PROXY_UNAVAILABLE" -> R.string.local_services_proxy_unavailable
    "GUEST_CONNECT_FAILED" -> R.string.local_services_guest_failed
    "STORAGE_CORRUPT" -> R.string.local_services_storage_corrupt
    "STORAGE_FAILED" -> R.string.local_services_storage_failed
    "SERVICE_LIMIT" -> R.string.local_services_service_limit
    "SERVICE_UNAVAILABLE", "ENVIRONMENT_UNAVAILABLE", "RUNTIME_UNAVAILABLE" -> R.string.local_services_unavailable
    "INVALID_PORT" -> R.string.local_services_host_port_range
    else -> R.string.local_services_forward_failed
})
