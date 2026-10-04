@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.terminal.app.ui.AvfImageInstallCard
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.image.BundledDebianImageCatalog
import dev.terminal.image.DebianImageVerifier
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun hasAvfEnvironmentImage(context: Context, storageId: String): Boolean =
    DebianImageVerifier.hasInstalledImage(SessionStorage.root(context.filesDir, SessionRuntime.AVF, storageId).toPath())

/** Displays this environment's files and persisted install progress, independently of VM readiness. */
@Composable internal fun AvfEnvironmentImageSetting(
    storageId: String,
    snapshot: AvfImageInstallSnapshot?,
    revision: Int,
    savedStorage: Boolean,
    requesting: Boolean,
    onInstall: () -> Unit,
    canRemove: Boolean = false,
    hasData: Boolean = false,
    onRemove: () -> Unit = {},
) {
    val context = LocalContext.current
    var installed by remember(storageId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(storageId, snapshot, revision) {
        installed = withContext(Dispatchers.IO) { hasAvfEnvironmentImage(context, storageId) }
    }
    EnvironmentSection(R.string.debian_image) {
        ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
            supportingContent = { Text(stringResource(when (installed) {
                true -> R.string.avf_image_installed
                false -> R.string.avf_image_not_installed
                null -> R.string.avf_image_checking
            })) }) { Text(stringResource(R.string.avf_image_installation_status)) }
        val progress = snapshot?.takeIf { it.active || installed == false &&
            it.state in setOf(AvfImageInstallState.FAILED, AvfImageInstallState.CANCELLED) }
        if (progress != null) AvfImageInstallCard(progress)
        if (installed == false && progress?.active != true) {
            val available = BundledDebianImageCatalog.find(BundledDebianImageCatalog.DEFAULT_RELEASE_ID) != null
            EnvironmentActionSetting(R.string.avf_image_install, R.drawable.ic_material_symbol_download_24,
                enabled = savedStorage && available && !requesting,
                supporting = when {
                    !savedStorage -> R.string.avf_image_save_first
                    !available -> R.string.avf_image_catalog_missing
                    else -> null
                }, onClick = onInstall)
        }
        if (installed == true || hasData) {
            EnvironmentActionSetting(R.string.environment_debian_remove, R.drawable.ic_material_symbol_delete_24,
                enabled = canRemove && !requesting && progress?.active != true,
                supporting = R.string.environment_software_protected, destructive = true, onClick = onRemove)
        }
    }
}
