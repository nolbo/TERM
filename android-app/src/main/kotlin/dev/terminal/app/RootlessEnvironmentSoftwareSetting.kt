@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.RootlessProgressIndicator
import dev.terminal.app.ui.parseRootlessProgress
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import org.json.JSONObject

/** The status and actions belong to this environment, independently of terminal sessions. */
@Composable internal fun RootlessEnvironmentSoftwareSetting(status: JSONObject?, enabled: Boolean,
    onInstall: () -> Unit, onRemove: () -> Unit) {
    val busy = status?.optBoolean("busy") == true
    val state = status?.optString("state").orEmpty()
    EnvironmentSection(R.string.environment_distribution_installation) {
        ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
            supportingContent = { Text(stringResource(when {
                status == null -> R.string.rootless_checking
                status.optBoolean("installed") -> R.string.avf_image_installed
                else -> R.string.avf_image_not_installed
            })) }) { Text(stringResource(R.string.environment_software_status)) }
        if (status != null) {
            ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
                supportingContent = { Text(status.optString("detail")) }) {
                Text(stringResource(when (state) {
                    "READY" -> R.string.rootless_ready
                    "CHECKING" -> R.string.rootless_checking
                    "ENVIRONMENT_REQUIRED" -> R.string.rootless_environment_required
                    "ERROR" -> R.string.rootless_install_failed
                    else -> R.string.avf_image_not_installed
                }))
            }
            val progress = parseRootlessProgress(status)
            if (progress != null && (busy || state == "ERROR")) {
                Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progress.label, style = MaterialTheme.typography.titleSmall)
                    RootlessProgressIndicator(progress, busy)
                }
            } else if (busy) {
                Column(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp)) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        EnvironmentActionSetting(
            title = when {
                state == "ERROR" -> R.string.retry
                status?.optBoolean("installed") == true -> R.string.rootless_complete_environment
                else -> R.string.environment_distribution_install
            },
            icon = if (state == "ERROR" || status?.optBoolean("installed") == true)
                R.drawable.ic_material_symbol_refresh_24 else R.drawable.ic_material_symbol_download_24,
            enabled = enabled && status?.optBoolean("canInstall") == true, onClick = onInstall)
        EnvironmentActionSetting(R.string.environment_distribution_remove, R.drawable.ic_material_symbol_delete_24,
            enabled = enabled && status?.optBoolean("canRemove") == true,
            supporting = R.string.environment_software_protected, destructive = true, onClick = onRemove)
    }
}
