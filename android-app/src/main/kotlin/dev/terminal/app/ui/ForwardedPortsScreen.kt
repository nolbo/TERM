@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ProfileSection
import dev.terminal.app.R

@Composable
internal fun ForwardedPortsScreen(state: ForwardedPortsState, onBack: () -> Unit,
    onRefresh: () -> Unit, onCopyAddress: (String) -> Unit) {
    SettingsPageScaffold(stringResource(R.string.forwarded_ports_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text(stringResource(R.string.forwarded_ports_description), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                ProfileSection(R.string.forwarded_ports_connections) {
                    PortStatusRow(state)
                    ListItem(onClick = onRefresh, enabled = state != ForwardedPortsState.Connecting,
                        leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), null) },
                        verticalAlignment = Alignment.CenterVertically,
                        shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh)) }
                }
            }
            if (state is ForwardedPortsState.Ready) {
                items(state.ports) { port -> PortConnectionGroup(port, onCopyAddress) }
            }
            item {
                Text(stringResource(R.string.forwarded_ports_local_only), Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PortStatusRow(state: ForwardedPortsState) {
    val title = when (state) {
        ForwardedPortsState.Connecting -> R.string.forwarded_ports_loading
        ForwardedPortsState.Unavailable -> R.string.forwarded_ports_unavailable
        is ForwardedPortsState.Ready -> if (state.ports.isEmpty()) R.string.forwarded_ports_empty else R.string.forwarded_ports_active
    }
    ListItem(
        supportingContent = when (state) {
            ForwardedPortsState.Connecting -> { { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) } }
            ForwardedPortsState.Unavailable -> { { Text(stringResource(R.string.forwarded_ports_unavailable_hint)) } }
            is ForwardedPortsState.Ready -> if (state.ports.isEmpty()) {
                { Text(stringResource(R.string.forwarded_ports_empty_hint)) }
            } else null
        },
        trailingContent = if (state is ForwardedPortsState.Ready) {
            { Text(stringResource(R.string.forwarded_ports_count, state.ports.size), style = MaterialTheme.typography.labelLarge) }
        } else null,
        verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
        modifier = Modifier.fillMaxWidth().settingsItemSurface(),
    ) {
        Text(stringResource(title), color = if (state == ForwardedPortsState.Unavailable)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PortConnectionGroup(port: ForwardedPortConnection, onCopyAddress: (String) -> Unit) {
    val tcpPort = stringResource(R.string.forwarded_ports_tcp_port, port.guestPort)
    val address = port.androidAddress
    SettingsGroup {
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_memory_24), null) },
            supportingContent = port.label?.let { { Text(tcpPort) } },
            verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
            modifier = Modifier.fillMaxWidth().settingsItemSurface(),
        ) { Text(port.label ?: tcpPort) }
        ListItem(
            supportingContent = { SelectionContainer {
                Text(address, fontFamily = LocalTerminalMonospaceFontFamily.current)
            } },
            trailingContent = {
                FloatingToolbarAction(stringResource(R.string.forwarded_ports_copy_address, address),
                    R.drawable.ic_material_symbol_content_copy_24, enabled = true, onClick = { onCopyAddress(address) })
            },
            verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
            modifier = Modifier.fillMaxWidth().settingsItemSurface(),
        ) { Text(stringResource(R.string.forwarded_ports_android_address)) }
        ListItem(
            trailingContent = { SelectionContainer { Text(port.guestPort.toString(), style = MaterialTheme.typography.labelLarge) } },
            verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
            modifier = Modifier.fillMaxWidth().settingsItemSurface(),
        ) { Text(stringResource(R.string.forwarded_ports_guest_port)) }
    }
}
