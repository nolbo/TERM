@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.runtime.session.EnvironmentRuntime

/** Same permission controls for an existing Environment and an unsaved creation draft. */
@Composable internal fun EnvironmentStorageAccessSettings(runtime: EnvironmentRuntime, allowed: Boolean,
    osGranted: Boolean, editable: Boolean, onChange: (Boolean) -> Unit, onOpenSettings: () -> Unit) {
    val native = runtime == EnvironmentRuntime.NATIVE
    // Native access follows the real app-wide grant, so changing it requires Android Settings.
    val checked = if (native) osGranted else allowed
    val enabled = native || editable
    ListItem(
        onClick = { if (native) onOpenSettings() else onChange(!allowed) }, enabled = enabled,
        supportingContent = { Text(stringResource(when {
            native -> if (osGranted) R.string.environment_storage_native_enabled else R.string.environment_storage_native_disabled
            runtime == EnvironmentRuntime.AVF -> R.string.environment_storage_avf_support
            else -> R.string.environment_storage_rootless_support
        })) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        verticalAlignment = Alignment.CenterVertically,
        shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
    ) { Text(stringResource(R.string.environment_storage_access)) }
    EnvironmentActionSetting(
        title = R.string.environment_storage_android_permission,
        icon = null, enabled = true, navigation = true,
        supporting = if (osGranted) R.string.environment_storage_os_enabled else R.string.environment_storage_os_disabled,
        onClick = onOpenSettings,
    )
}

internal fun openInternalStorageSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null)))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }.onFailure { Toast.makeText(context, R.string.shared_storage_settings_unavailable, Toast.LENGTH_LONG).show() }
}
